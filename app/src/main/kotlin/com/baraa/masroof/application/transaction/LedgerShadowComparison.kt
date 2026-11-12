package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.ledger.LedgerEvidence
import com.baraa.masroof.ledger.LedgerProjection
import com.baraa.masroof.ledger.LedgerRow
import java.security.MessageDigest

/**
 * Plan 3.2 shadow comparison: the stored ledger (what `TransactionReconciliationService` wrote) against
 * [com.baraa.masroof.ledger.LedgerProjector] on the same [LedgerInputs]. Pure; the owner accepts or rejects each
 * difference (docs/ARCHITECTURE.md, "Shadow comparison").
 */
object LedgerShadowComparison {
    enum class Kind { ONLY_OLD, ONLY_NEW, CHANGED, GROUPING }

    /** Where a difference can be told, the Known differences entry it belongs to. */
    enum class Reason(val knownDifference: String) {
        ID_ONLY("Ids: the old fee-to-financing upgrade keeps the stored id"),
        EXCHANGE_RATE("Exchange rate: only a complete stored pair on the same raw SMS set is carried"),
        LOAN_PRE_PASS("Loan pre-pass: the projector confirms the loan before any installment"),
        INSTALLMENT_FEE("Stored rows: a stored FEE for an installment that cannot become a repayment"),
        DECIDED_WITHOUT_POSTING_COLUMNS("Stored rows: a decided row whose decision lacks its type, time or zone"),
        DECIDED_NOT_STORED("Write guard: a decided posting the old pass did not write"),
        STALE_EVIDENCE("Stored rows: an automatic row whose evidence changed since it was posted"),
        ROW_TYPE_OR_OWNERSHIP("Stored rows: a type edited only on the row, or ownership changed since posting"),
        MISSING_RAW_SMS("Missing raw SMS: a stored row links a raw SMS without evidence"),
        UNEXPLAINED("none"),
    }

    data class RowDifference(
        val kind: Kind,
        val rawSmsIds: List<String>,
        val oldRows: List<LedgerRow>,
        val newRows: List<LedgerRow>,
        val changedFields: List<String>,
        val reason: Reason,
    )

    /**
     * An open review (`REQUIRED`) on one side only, or of another kind. For context, [storedReview] is the stored
     * review's status and [decision] the kind of the SMS's outcome decision (read from the store, not the review).
     */
    data class ReviewDifference(
        val rawSmsId: String,
        val old: ReviewKind?,
        val new: ReviewKind?,
        val storedReview: ReviewStatus?,
        val decision: UserDecisionKind?,
    )

    data class Result(
        val oldRowCount: Int,
        val newRowCount: Int,
        val rows: List<RowDifference>,
        val reviews: List<ReviewDifference>,
        val retryRawSmsIds: List<String>,
    )

    fun compare(inputs: LedgerInputs, projection: LedgerProjection = inputs.project()): Result {
        val evidence = inputs.events.associateBy { it.event.rawSmsId }
        val rows = groups(inputs.storedRows, projection.rows).mapNotNull { (old, new) ->
            val rawSmsIds = (old + new).flatMap { it.rawSmsIds }.distinct().sorted()
            val kind = when {
                new.isEmpty() -> Kind.ONLY_OLD
                old.isEmpty() -> Kind.ONLY_NEW
                old.size == 1 && new.size == 1 && old[0].rawSmsIds == new[0].rawSmsIds -> Kind.CHANGED
                else -> Kind.GROUPING
            }
            val fields = if (kind == Kind.CHANGED) changedFields(old[0].transaction, new[0].transaction) else emptyList()
            if (kind == Kind.CHANGED && fields.isEmpty()) return@mapNotNull null
            RowDifference(kind, rawSmsIds, old, new, fields, reason(kind, fields, rawSmsIds, old, new, inputs, evidence))
        }.sortedBy { it.rawSmsIds.first() }
        val storedReviews = inputs.reviews.associateBy { it.rawSmsId }
        val newReviews = projection.reviewCandidates.associate { it.rawSmsId to it.kind }
        val reviews = evidence.keys.sorted().mapNotNull { rawSmsId ->
            val stored = storedReviews[rawSmsId]
            val old = stored?.kind?.takeIf { stored.status == ReviewStatus.REQUIRED }
            val new = newReviews[rawSmsId]
            if (old == new) return@mapNotNull null
            ReviewDifference(
                rawSmsId, old, new,
                storedReview = stored?.status,
                decision = inputs.decisions[rawSmsId]?.outcome?.kind,
            )
        }
        return Result(inputs.storedRows.size, projection.rows.size, rows, reviews, projection.retryRawSmsIds.sorted())
    }

    /** Old and new rows joined into the groups that share a raw SMS. */
    private fun groups(old: List<LedgerRow>, new: List<LedgerRow>): List<Pair<List<LedgerRow>, List<LedgerRow>>> {
        val all = old.map { true to it } + new.map { false to it }
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int = if (parent[i] == i) i else find(parent[i]).also { parent[i] = it }
        val firstByRawSmsId = mutableMapOf<String, Int>()
        all.forEachIndexed { index, (_, row) ->
            row.rawSmsIds.forEach { rawSmsId ->
                firstByRawSmsId[rawSmsId]?.let { parent[find(index)] = find(it) } ?: firstByRawSmsId.put(rawSmsId, index)
            }
        }
        return all.indices.groupBy(::find).values.map { members ->
            val rows = members.map { all[it] }
            rows.filter { it.first }.map { it.second } to rows.filterNot { it.first }.map { it.second }
        }
    }

    private val FIELDS: List<Pair<String, (FinancialTransaction) -> Any?>> = listOf(
        "id" to { it.id },
        "type" to { it.type },
        "amount" to { "${it.amount.amount.stripTrailingZeros().toPlainString()} ${it.amount.currency}" },
        "occurredAt" to { it.occurredAt },
        "occurredAtZone" to { it.occurredAtZone },
        "source" to { it.sourceContainerId },
        "destination" to { it.destinationContainerId },
        "merchant" to { it.merchant },
        "counterparty" to { it.counterparty },
        "category" to { it.categoryId },
        "parsedEvents" to { it.linkedParsedEventIds.sorted() },
        "exchangeRate" to { it.appliedExchangeRate?.stripTrailingZeros() },
        "exchangeRateSource" to { it.exchangeRateSource },
    )

    private fun changedFields(old: FinancialTransaction, new: FinancialTransaction): List<String> =
        FIELDS.filter { (_, read) -> read(old) != read(new) }.map { it.first }

    private fun reason(
        kind: Kind,
        fields: List<String>,
        rawSmsIds: List<String>,
        old: List<LedgerRow>,
        new: List<LedgerRow>,
        inputs: LedgerInputs,
        evidence: Map<String, LedgerEvidence>,
    ): Reason {
        val legs = rawSmsIds.mapNotNull(evidence::get)
        val outcomes = rawSmsIds.mapNotNull { inputs.decisions[it]?.outcome }
        val oldTypes = old.map { it.transaction.type }
        val newTypes = new.map { it.transaction.type }
        val installment = legs.any { it.event.messageFamily == MessageFamily.FINANCING_INSTALLMENT }
        return when {
            old.any { row -> row.rawSmsIds.any { it !in evidence } } -> Reason.MISSING_RAW_SMS
            kind == Kind.CHANGED && fields == listOf("id") -> Reason.ID_ONLY
            kind == Kind.CHANGED && fields.all { it.startsWith("exchangeRate") } -> Reason.EXCHANGE_RATE
            outcomes.isNotEmpty() -> when {
                old.isNotEmpty() && outcomes.any { it.transactionType == null || it.occurredAt == null || it.occurredAtZone == null } ->
                    Reason.DECIDED_WITHOUT_POSTING_COLUMNS
                kind == Kind.ONLY_NEW -> Reason.DECIDED_NOT_STORED
                else -> Reason.UNEXPLAINED
            }
            installment && FinancialTransactionType.LOAN_REPAYMENT in newTypes &&
                FinancialTransactionType.LOAN_REPAYMENT !in oldTypes -> Reason.LOAN_PRE_PASS
            installment && kind == Kind.ONLY_OLD && oldTypes == listOf(FinancialTransactionType.FEE) -> Reason.INSTALLMENT_FEE
            old.any { !matchesEvidence(it, evidence) } -> Reason.STALE_EVIDENCE
            kind == Kind.CHANGED && fields.any { it in setOf("type", "source", "destination") } -> Reason.ROW_TYPE_OR_OWNERSHIP
            else -> Reason.UNEXPLAINED
        }
    }

    /** True when [row] still reads its evidence as posted: the same parse rows, all eligible, and a lone leg's amount and merchant. */
    private fun matchesEvidence(row: LedgerRow, evidence: Map<String, LedgerEvidence>): Boolean {
        val legs = row.rawSmsIds.mapNotNull(evidence::get)
        val transaction = row.transaction
        if (legs.map { it.event.id }.sorted() != transaction.linkedParsedEventIds.sorted()) return false
        if (legs.any { !TransactionAssembler.isAutomationEligible(it.event, it.automationConfirmed) }) return false
        val single = legs.singleOrNull()?.event ?: return true
        return single.amount == transaction.amount && single.merchant == transaction.merchant
    }

    fun report(inputs: LedgerInputs): String = report(compare(inputs))

    /** The plain-text report: a summary by reason, then one block per difference. Ids masked; no SMS text. */
    fun report(result: Result): String = buildString {
        appendLine("# Ledger shadow comparison (plan 3.2)")
        appendLine("old rows: ${result.oldRowCount}  new rows: ${result.newRowCount}")
        appendLine("row differences: ${result.rows.size}  review differences: ${result.reviews.size}  projection retries: ${result.retryRawSmsIds.size}")
        appendLine()
        appendLine("## Summary by reason")
        val counts = result.rows.groupingBy { it.reason }.eachCount()
        Reason.entries.filter { it in counts }.forEach { appendLine("${it.name}: ${counts.getValue(it)}  (${it.knownDifference})") }
        appendLine()
        appendLine("## Row differences")
        result.rows.forEachIndexed { index, difference ->
            appendLine()
            appendLine("### ${index + 1} ${difference.kind} reason=${difference.reason}")
            appendLine("raw sms: ${difference.rawSmsIds.joinToString(", ", transform = ::mask)}")
            if (difference.changedFields.isNotEmpty()) appendLine("changed: ${difference.changedFields.joinToString(", ")}")
            difference.oldRows.forEach { appendLine("old: ${describe(it)}") }
            difference.newRows.forEach { appendLine("new: ${describe(it)}") }
        }
        appendLine()
        appendLine("## Review differences (open review kind, old -> new)")
        result.reviews.forEach {
            appendLine("${mask(it.rawSmsId)}: ${it.old ?: "none"} -> ${it.new ?: "none"}  stored=${it.storedReview ?: "-"} decision=${it.decision ?: "-"}")
        }
        appendLine()
        appendLine("## Projection retries")
        result.retryRawSmsIds.forEach { appendLine(mask(it)) }
    }

    private fun describe(row: LedgerRow): String {
        val t = row.transaction
        return listOf(
            "id=${mask(t.id)}", "type=${t.type}", "amount=${t.amount.amount.toPlainString()} ${t.amount.currency}",
            "at=${t.occurredAt}", "zone=${t.occurredAtZone ?: "-"}",
            "source=${maskDigits(t.sourceContainerId)}", "destination=${maskDigits(t.destinationContainerId)}",
            "merchant=${maskDigits(t.merchant)}", "counterparty=${maskDigits(t.counterparty)}",
            "category=${t.categoryId ?: "-"}",
            "fx=${t.appliedExchangeRate?.toPlainString() ?: "-"}/${t.exchangeRateSource ?: "-"}",
            "raw=${row.rawSmsIds.joinToString(";", transform = ::mask)}",
            "events=${t.linkedParsedEventIds.sorted().joinToString(";", transform = ::mask)}",
        ).joinToString(" ")
    }

    /** The log mask plus a short digest, so two ids with the same last four stay apart in one report. */
    private fun mask(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return AppLogFormatting.maskId(id) + "#" + digest.take(3).joinToString("") { "%02x".format(it) }
    }

    // A run of five or more digits could be a full account or card number: keep only its last four.
    private val LONG_DIGITS = Regex("\\d{5,}")

    private fun maskDigits(text: String?): String = text?.replace(LONG_DIGITS) { "…" + it.value.takeLast(4) } ?: "-"
}
