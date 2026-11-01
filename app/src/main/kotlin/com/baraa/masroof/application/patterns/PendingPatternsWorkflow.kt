package com.baraa.masroof.application.patterns

import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.ExplicitBankSelection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.sms.HistoricalSmsBatchProcessor
import com.baraa.masroof.application.sms.HistoricalBatchDerivedResult
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.ParseStatus as EngineParseStatus
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId

enum class PendingPatternStatus { MATCHED, NO_MATCH, PATTERN_CONFLICT, BANK_CONFLICT, INCOMPLETE }
data class PendingPatternRow(val reviewId: String, val status: PendingPatternStatus, val matches: List<PatternMatch>)
data class PendingPatternReport(val generation: Long, val bankId: String, val rows: List<PendingPatternRow>, val limited: Boolean,
    val requiredReviewCount: Int = rows.size) {
    fun matchingCount(patternId: String) = rows.count { it.status == PendingPatternStatus.MATCHED && it.matches.single().patternId == patternId }
    /** Patterns that overlap another pattern on at least one pending message. */
    val conflictingPatternIds: Set<String> get() =
        rows.filter { it.status == PendingPatternStatus.PATTERN_CONFLICT }.flatMapTo(hashSetOf()) { row -> row.matches.map { it.patternId } }
}
data class PendingPatternApplyReport(val processed: Int, val posted: Int, val failed: Int?, val incomplete: Boolean, val limited: Boolean,
    val reviewsBefore: Int? = null, val reviewsAfter: Int? = null, val pendingAfter: PendingPatternReport? = null)
data class PatternApprovalExample(val reviewId: String, val body: String, val fields: Map<String, PatternText>)

/** Read-only preview; explicit apply rechecks eligibility and delegates to the existing batch pipeline. */
class PendingPatternsWorkflow(
    private val store: MessagePatternStore,
    private val accessGate: DatabaseAccessGate,
    private val reviews: ReviewRepository,
    private val rawMessages: RawSmsRepository,
    private val parsedEvents: ParsedEventRepository,
    private val corrections: UserCorrectionRepository,
    private val transactions: FinancialTransactionRepository,
    private val registry: BankSmsRegistry,
    private val batches: HistoricalSmsBatchProcessor? = null,
    private val inTransaction: (suspend (suspend () -> SmsIngestionResult) -> SmsIngestionResult)? = null,
    private val markParseFailed: (suspend (String) -> Unit)? = null,
    private val onChanged: suspend () -> Unit = {},
) {
    val canApply: Boolean get() = batches != null && inTransaction != null

    suspend fun approvalExamples(bankId: String, generation: Long, patternId: String): List<PatternApprovalExample> = withContext(Dispatchers.IO) {
        accessGate.withAccess {
            // Patterns may be approved while off; read this one as on so its examples can be found.
            val eligible = preview(bankId, generation, forceEnabledPatternId = patternId).rows.filter {
                it.status == PendingPatternStatus.MATCHED && it.matches.single().patternId == patternId
            }.take(10)
            val reviewRows = eligible.mapNotNull { row -> reviews.getById(row.reviewId)?.let { row to it } }
            val raw = rawMessages.getByIds(reviewRows.map { it.second.rawSmsId }).associateBy { it.id }
            reviewRows.mapNotNull { (row, review) -> raw[review.rawSmsId]?.let {
                PatternApprovalExample(row.reviewId, it.body, row.matches.single().fields)
            } }
        }
    }

    /**
     * Runs to the end once started: leaving the screen must not stop between the reparse, which
     * stores SUCCESS rows, and the batch finish that posts them or records their retry.
     */
    suspend fun apply(bankId: String, generation: Long, reviewId: String? = null, patternId: String? = null): PendingPatternApplyReport = withContext(Dispatchers.IO + NonCancellable) {
        accessGate.withAccess {
            val report = preview(bankId, generation, reviewId)
            val before = if (reviewId == null) report else preview(bankId, generation)
            val current = store.load().banks.single { it.bankId == bankId }
            val approved = current.currentPatterns.filter { it.enabled && it.factContract != null }.map { it.id }.toSet()
            val batch = requireNotNull(batches).startBatch(Bank(bankId))
            val processed = mutableListOf<String>()
            var failed = 0
            for (row in report.rows.filter { it.status == PendingPatternStatus.MATCHED && it.matches.single().patternId in approved &&
                    (patternId == null || it.matches.single().patternId == patternId) }) {
                requireNotNull(inTransaction).invoke {
                    val review = reviews.getById(row.reviewId)
                    if (review == null || review.status != ReviewStatus.REQUIRED || review.resolutionKind != null ||
                        corrections.listForRawSmsId(review.rawSmsId).isNotEmpty() || transactions.isRawSmsLinked(review.rawSmsId)) {
                        return@invoke SmsIngestionResult.Duplicate
                    }
                    val event = parsedEvents.findByRawSmsId(review.rawSmsId)?.event
                    if (event?.parseStatus == ParseStatus.SUCCESS) return@invoke SmsIngestionResult.Duplicate
                    val raw = rawMessages.getById(review.rawSmsId) ?: return@invoke SmsIngestionResult.Duplicate
                    val selected = ExplicitBankSelection.selectedBankId(review.reasons) ?: event?.bank?.takeUnless { it == Bank.UNKNOWN }?.id
                    if (selected != null && selected != bankId) return@invoke SmsIngestionResult.Duplicate
                    if (selected == null) {
                        val route = registry.route(raw.sender, raw.body)
                        if (route !is BankRoutingResult.Matched || route.adapter.bank.id != bankId) return@invoke SmsIngestionResult.Duplicate
                    }
                    batch.reprocess(raw).also {
                        if (it is SmsIngestionResult.Failed) { failed++; markParseFailed?.invoke(raw.id) }
                        else if (it !is SmsIngestionResult.Duplicate && it !is SmsIngestionResult.NotRelevant) processed += raw.id
                    }
                }
            }
            val derived = batch.finish()
            var posted = 0
            for (id in processed) if (transactions.isRawSmsLinked(id)) posted++
            val financialFailed = batch.reconciliationSummary?.failed
            val failureCount = if (derived is HistoricalBatchDerivedResult.Incomplete && (financialFailed ?: 0) == 0) null
                else failed + (financialFailed ?: 0)
            // Posted rows must reach other screens even if the refreshed preview fails.
            val after = try { preview(bankId, generation) } finally { onChanged() }
            PendingPatternApplyReport(processed.size, posted, failureCount,
                failed > 0 || derived is HistoricalBatchDerivedResult.Incomplete, before.limited || after.limited,
                before.requiredReviewCount, after.requiredReviewCount, after)
        }
    }
    suspend fun preview(bankId: String, generation: Long, reviewId: String? = null,
        forceEnabledPatternId: String? = null): PendingPatternReport = withContext(Dispatchers.IO) {
        accessGate.withAccess {
            val stored = store.load()
            check(stored.generation == generation) { "Pattern configuration changed" }
            val configuration = if (forceEnabledPatternId == null) stored else stored.copy(banks = stored.banks.map { bank ->
                val current = bank.currentPatterns.singleOrNull { it.id == forceEnabledPatternId && bank.bankId == bankId }
                if (current == null) bank else bank.copy(patterns = bank.patterns.map { if (it === current) it.copy(enabled = true) else it })
            })
            require(configuration.banks.any { it.bankId == bankId }) { "Unknown bank" }
            val interpreter = PatternFactInterpreter(configuration)
            val required = if (reviewId == null) reviews.listRequired()
                else listOfNotNull(reviews.getById(reviewId)?.takeIf { it.status == ReviewStatus.REQUIRED })
            val rows = mutableListOf<PendingPatternRow>()
            var requiredReviewCount = 0
            // ponytail: bounded interactive preview; add repository pagination if queues exceed 1,000 rows routinely.
            for (batch in required.take(1_000).chunked(200)) {
                val parsed = parsedEvents.listByRawSmsIds(batch.map { it.rawSmsId }).associateBy { it.event.rawSmsId }
                fun storedBankOf(review: ReviewItem) = ExplicitBankSelection.selectedBankId(review.reasons)
                    ?: parsed[review.rawSmsId]?.event?.bank?.takeUnless { it == Bank.UNKNOWN }?.id
                // Another bank's stored review is never a candidate; skip its body and correction reads.
                val relevant = batch.filter { storedBankOf(it).let { stored -> stored == null || stored == bankId } }
                val ids = relevant.map { it.rawSmsId }
                val protected = corrections.listForRawSmsIds(ids).mapTo(hashSetOf()) { it.targetRawSmsId }
                val raw = rawMessages.getByIds(ids).associateBy { it.id }
                for (review in relevant) {
                    val event = parsed[review.rawSmsId]?.event
                    val message = raw[review.rawSmsId] ?: continue
                    val storedBank = storedBankOf(review)
                    val candidates = if (storedBank != null) setOf(storedBank) else {
                        val routed = when (val route = registry.route(message.sender, message.body)) {
                            is BankRoutingResult.Matched -> setOf(route.adapter.bank.id)
                            is BankRoutingResult.Ambiguous -> route.banks.mapTo(hashSetOf()) { it.id }
                            else -> emptySet()
                        }
                        routed + configuration.banks.filter { message.sender in it.senders }.map { it.bankId }
                    }
                    if (bankId !in candidates) continue
                    requiredReviewCount++
                    if (review.resolutionKind != null || review.rawSmsId in protected || transactions.isRawSmsLinked(review.rawSmsId) ||
                        event?.parseStatus == ParseStatus.SUCCESS) continue
                    if (candidates.size != 1) {
                        rows += PendingPatternRow(review.id, PendingPatternStatus.BANK_CONFLICT, emptyList())
                        continue
                    }
                    // One engine pass gives the matched patterns and the decision processing would make.
                    val routed = interpreter.previewRouted(MessageInput(message.id, message.sender, message.body, message.receivedAt,
                        durableSmsReceipt = true), BankId(bankId))
                    val matches = routed.matches.map { (pattern, preview) ->
                        PatternMatch(pattern.id, pattern.revision, pattern.label, preview.fields.displayFields())
                    }
                    // The one unapproved taught match stays a plain match, even beside a bundled role, so approval can use
                    // it as an example. Otherwise follow the interpreter: a taught pattern may complete a bundled role,
                    // and a match that still cannot finish the message would only return to review when processed.
                    val unapproved = routed.matches.filter { it.first.roleProfile == null }.singleOrNull()
                        ?.takeIf { it.first.factContract == null }?.first?.id
                    val interpretation = routed.interpretation.takeUnless { unapproved != null }
                    val chosen = matches.filter { it.patternId == (unapproved ?: interpretation?.patternProvenance?.patternId) }
                    val status = when {
                        matches.isEmpty() -> PendingPatternStatus.NO_MATCH
                        chosen.size == 1 && (interpretation == null || interpretation.status == EngineParseStatus.SUCCESS) ->
                            PendingPatternStatus.MATCHED
                        chosen.size == 1 || matches.size == 1 -> PendingPatternStatus.INCOMPLETE
                        else -> PendingPatternStatus.PATTERN_CONFLICT
                    }
                    rows += PendingPatternRow(review.id, status, if (status == PendingPatternStatus.MATCHED) chosen else matches)
                }
            }
            PendingPatternReport(generation, bankId, rows, required.size > 1_000, requiredReviewCount)
        }
    }
}
