package com.baraa.masroof.application.interpretation

import android.content.Context
import androidx.room.InvalidationTracker
import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.engine.bank.interpretation.*
import com.baraa.masroof.engine.bank.patterns.*
import com.baraa.masroof.ml.family.*
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.data.learning.*
import com.baraa.masroof.domain.repository.*
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.application.review.ReviewCorrectionClosure
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.sms.hash.SmsBodyHasher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Human-template memory is a family assistant; the production workflows retain all financial authority.
 * Every bank with bundled role rules gets its own template memory built from that bank's human decisions.
 */
class BankInterpretationService(
    private val context: Context,
    private val raw: RawSmsRepository,
    private val parsed: ParsedEventRepository,
    private val reviews: ReviewRepository,
    private val decisions: UserDecisionRepository,
    private val transactions: FinancialTransactionRepository,
    private val scope: CoroutineScope,
    private val log: AppLogService,
    private val onFeedbackChanged: () -> Unit = {},
    private val patterns: () -> MessagePatternConfiguration,
) : AutoCloseable {
    private val store = LearningStore(context) { log.warn("ML", "Learning cache corrupt; deleted and rebuilt from decisions (${it.javaClass.simpleName})") }
    private val auditJob = SupervisorJob(scope.coroutineContext[Job])
    private val auditScope = CoroutineScope(scope.coroutineContext + auditJob)
    @Volatile private var closed = false
    private val stopSignal = CompletableDeferred<Unit>()
    // Rebuild assistance from durable human decisions before enabling it; one classifier per assisted bank.
    @Volatile private var classifiers: Map<String, FamilyClassifier>? = null
    // Pending messages in banks without role rules that learned decisions would now change.
    @Volatile private var assistable: Set<String> = emptySet()
    @Volatile private var ruleRead: Set<String> = emptySet()
    // Messages hidden by a remembered "not financial" that the current decisions no longer hide.
    @Volatile private var revoked: Set<String> = emptySet()
    fun revokedMemories(): Set<String> = revoked
    @Volatile private var synchronizedDecisions: String? = null
    @Volatile private var pendingFeedback = false
    private data class Attempt(val bank: String, val classifier: FamilyClassifier?, var requested: Boolean = false,
        var outcome: String = "RULES", var suggestion: FamilySuggestion? = null, var template: String? = null)
    private val attempt = ThreadLocal<Attempt>()
    private fun engine() = PatternFactInterpreter.of(patterns())
    private val familyAssist = FamilyAssist { input,title ->
        val trace = attempt.get()
        trace?.requested = true
        val shape = FamilyTextPreparation.prepare(input.body)
        val features = FamilyAssistBridge.clean(shape).features
        // The structural view must belong to the title the interpreter is reading.
        val result = trace?.takeIf { shape.title == title }?.let { FamilyAssistBridge.suggest(it.classifier, it.bank, features) }
        trace?.suggestion = result
        trace?.template = features?.template
        trace?.outcome = when {
            trace?.classifier == null -> "FEEDBACK_PENDING"
            features == null -> "CLEANING_REJECTED"
            result == null -> "ABSTAINED"
            else -> "SUGGESTED"
        }
        result
    }
    private val mutex = Mutex()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var tracker: InvalidationTracker? = null
    private val observer = object : InvalidationTracker.Observer("user_decision","review_item","raw_sms","parsed_event","financial_transaction") {
        override fun onInvalidated(tables: Set<String>) {
            if(tables.any { it == "user_decision" || it == "review_item" }) invalidateFeedback()
            else if(pendingFeedback) changes.trySend(Unit)
        }
    }
    /** One stable version for all banks, so refresh work started for an older set of models is skipped. */
    /** Whether any bank's template memory can propose a family right now. */
    val suggesting: Boolean get() = classifiers?.values?.any { it.canSuggest } == true
    val modelVersion: String? get() = classifiers?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${it.value.version}" }
    /**
     * Every configured bank remembers the user's decisions. A remembered "not financial" applies in any bank;
     * a remembered financial family is used where the bank's role rules ask for one.
     */
    fun assistedBanks(configuration: MessagePatternConfiguration = patterns()): Set<String> =
        configuration.banks.filter { bank -> bank.bankId.matches(com.baraa.masroof.ml.family.LEARNABLE_BANK_ID) }.map { it.bankId }.toSet()
    val refreshVersion: String get() = "${engine().fingerprint}|${modelVersion ?: "rules-only"}"
    fun interpret(input: MessageInput, bankId: String, snapshot: PatternFactInterpreter = engine()): Interpretation {
        val trace = Attempt(bankId, classifiers?.get(bankId))
        val activeModel = trace.classifier?.version
        attempt.set(trace)
        val ruled = try {
            snapshot.interpretRouted(input,BankId(bankId),familyAssist).also { ruled ->
                // Banks without role rules never ask; an unidentified message without an amount asks once here.
                if (!trace.requested && FamilyAssistBridge.mayRemember(ruled, input.body))
                    familyAssist.suggest(input, FamilyTextPreparation.prepare(input.body).title)
            }
        } finally { attempt.remove() }
        val result = FamilyAssistBridge.rememberNonFinancial(ruled, input.body, trace.suggestion)
        val interpretedAt = System.currentTimeMillis()
        // Advisory audit writes never decide whether a transaction may be posted.
        if(!closed) auditScope.launch {
            try {
                val key=SmsBodyHasher.sha256Hex("${input.evidenceId}|${result.version}|${result.familyInference?.modelVersion?:activeModel}")
                store.record(EngineInterpretationRow(key,input.evidenceId,result.bank?.value,result.version.engine,result.version.rules,
                    result.familyInference?.modelVersion?:activeModel,result.familyInference?.origin?.name?:"RULES",result.facts.family.name,result.status.name,
                    result.blockers.joinToString(",") {it.name},SmsBodyHasher.sha256Hex(input.body),input.receivedAt.toEpochMilli(),interpretedAt,
                    trace.requested,trace.outcome,null,null,null,
                    trace.template))
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { log.warn("ML","Interpretation audit pending: ${e.javaClass.simpleName}") }
        }
        return result
    }
    @Synchronized fun startLearning(invalidationTracker: InvalidationTracker) {
        if (job!=null || closed) return
        tracker=invalidationTracker;invalidationTracker.addObserver(observer)
        job=scope.launch {
            var retryDelay = 5_000L
            for (ignored in changes) {
                if (closed) break
                try {
                    synchronizeFeedback()
                    if(pendingFeedback && !closed) {
                        withTimeoutOrNull(retryDelay) { stopSignal.await() }
                        retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                        changes.trySend(Unit)
                    } else retryDelay = 5_000L
                }
                catch (e: CancellationException) {throw e}
                catch (e: Exception) {
                    invalidateFeedback()
                    log.warn("ML","Feedback sync pending: ${e.javaClass.simpleName}")
                    if (!closed) withTimeoutOrNull(5_000) { stopSignal.await() }
                }
            }
        }
        changes.trySend(Unit)
    }
    fun invalidateFeedback() {
        classifiers = null
        synchronizedDecisions = null
        revoked = emptySet()
        changes.trySend(Unit)
    }

    /**
     * Every manual decision, read in a fixed number of batch queries: this runs inside the
     * financial write transaction for each inferred write, so its cost must not grow per message.
     */
    private suspend fun decisions(): List<Decision> {
        val result = mutableListOf<Decision>()
        decisions.listCorrections().forEach { (sequence, correction) ->
            result += Decision("correction:$sequence", correction.rawSmsId,
                correction.messageFamily?.let { MessageFamily.valueOf(it.name) },
                correction.decidedAt.toEpochMilli(), "USER_MESSAGE_FAMILY",
                reason = if(correction.messageFamily == null) "FIELDS_ONLY" else null)
        }
        // USER_CORRECTION is settlement metadata for the append-only correction
        // already read above, not a second human decision that can supersede it.
        val resolved = reviews.listAll().filter { it.status == ReviewStatus.RESOLVED && it.resolutionKind != null &&
            it.resolutionKind != ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED &&
            it.resolutionKind != ReviewResolutionKind.USER_CORRECTION && it.resolvedAt != null }
        val types = transactions.getByIds(resolved.mapNotNull { it.resolvedTransactionId }).associate { it.id to it.type }
        val linked = transactions.listLinkedRawSmsIds(resolved.map { it.rawSmsId })
        for (review in resolved) {
            val at = review.resolvedAt?.toEpochMilli() ?: continue
            val type = review.resolvedTransactionId?.let(types::get)
            val financialDecision = review.resolutionKind in setOf(ReviewResolutionKind.USER_FINANCIAL_TYPE,
                ReviewResolutionKind.USER_EXTERNAL_TRANSFER)
            val financialOnly = financialDecision &&
                type == null && review.rawSmsId in linked
            val family = when(review.resolutionKind) {
                ReviewResolutionKind.USER_NON_FINANCIAL -> MessageFamily.NON_FINANCIAL
                ReviewResolutionKind.USER_FINANCIAL_TYPE -> type?.let(FamilyFeedbackPolicy::explicitFamily)?.let { MessageFamily.valueOf(it.name) }
                ReviewResolutionKind.USER_EXTERNAL_TRANSFER -> type?.takeIf { it in setOf(
                    com.baraa.masroof.domain.model.FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                    com.baraa.masroof.domain.model.FinancialTransactionType.EXTERNAL_TRANSFER_OUT) }
                    ?.let(FamilyFeedbackPolicy::explicitFamily)?.let { MessageFamily.valueOf(it.name) }
                else -> null
            }
            val source = if(review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL) "USER_NON_FINANCIAL" else "USER_EVENT_KIND"
            result += Decision("review:${review.id}:$at",review.rawSmsId,family,at,source,type,
                when(review.resolutionKind) {
                    ReviewResolutionKind.USER_FINANCIAL_TYPE -> if(financialOnly) "FINANCIAL_ONLY" else if(type == null) "DECISION_PENDING" else null
                    ReviewResolutionKind.USER_EXTERNAL_TRANSFER -> if(type == null) "DECISION_PENDING" else if(family == null) "CONTEXT_ONLY" else null
                    ReviewResolutionKind.USER_NON_FINANCIAL -> null
                    else -> "CONTEXT_ONLY"
                })
        }
        return result.sortedWith(compareBy<Decision> { it.at }.thenBy { it.id })
    }
    private fun fingerprint(decisions: List<Decision>) = SmsBodyHasher.sha256Hex(decisions.joinToString("|") { it.toString() })

    /** Called inside the financial write transaction, after reading the current manual decisions. */
    suspend fun isInferenceCurrent(record: com.baraa.masroof.parsing.repository.ParsedEventRecord): Boolean {
        if(record.automationConfirmed) return true
        val inferred = record.event.confidence.reasons.any { it.startsWith("family_origin:") }
        if(!inferred) return true
        val model = classifiers?.get(record.event.bank.id) ?: return false
        return "model:${model.version}" in record.event.confidence.reasons &&
            synchronizedDecisions == fingerprint(decisions())
    }

    suspend fun synchronizeFeedback(): String? = mutex.withLock {
        if(closed) return@withLock null
        val banks = assistedBanks()
        val before = decisions()
        val wanted = before.map { it.rawId }.toSet()
        val evidence = raw.getByIds(wanted).associateBy { it.id }
        val events = parsed.listByRawSmsIds(wanted).associateBy { it.event.rawSmsId }
        val semantic = before.filter { it.reason !in setOf("FIELDS_ONLY", "CONTEXT_ONLY") }
            .groupBy { it.rawId }.mapValues { (_, rows) -> rows.maxWith(compareBy<Decision> { it.at }
                .thenBy { if(it.provenance == "USER_MESSAGE_FAMILY") 1 else 0 }.thenBy { it.id }) }
        val labels = mutableListOf<LearningLabelRow>()
        val blocks = mutableListOf<HumanTemplateBlock>()
        val feedback = before.map { decision ->
            val message = evidence[decision.rawId]
            val event = events[decision.rawId]?.event
            val bank = event?.bank?.id
            val cleaned = message?.let { FamilyAssistBridge.clean(it.body) }
            val features = cleaned?.features
            var reason = decision.reason
            var state = LearningFeedbackState.EXCLUDED
            when {
                reason in setOf("FIELDS_ONLY", "CONTEXT_ONLY") -> Unit
                semantic[decision.rawId]?.id != decision.id -> reason = "SUPERSEDED"
                message == null || event == null -> { state = LearningFeedbackState.PENDING; reason = "MISSING_EVIDENCE" }
                bank == null || bank !in banks -> reason = "UNSUPPORTED_BANK"
                features == null -> reason = "CLEANING_${cleaned?.rejection?.name ?: "EMPTY"}"
                reason == "DECISION_PENDING" -> state = LearningFeedbackState.PENDING
                decision.family == MessageFamily.UNKNOWN && event.messageFamily.name != "UNKNOWN" -> {
                    state = LearningFeedbackState.BLOCKED; reason = "CONTRADICTED_FAMILY"
                    blocks += HumanTemplateBlock(bank, decision.rawId, decision.id, features.template, decision.at)
                }
                decision.family != null && decision.family != MessageFamily.UNKNOWN -> {
                    state = LearningFeedbackState.LEARNED
                    labels += LearningLabelRow(decision.id, decision.rawId, bank, FamilyAssistBridge.toModel(decision.family).name,
                        features.template, FamilyModelCodec.termsJson(features.terms).toString(), FamilyInputCleaner.FEATURES_VERSION,
                        decision.at, decision.provenance, message.bodyHash)
                }
                decision.type != null && FamilyFeedbackPolicy.contradicts(
                    com.baraa.masroof.domain.model.MessageFamily.valueOf(event.messageFamily.name), decision.type) -> {
                    state = LearningFeedbackState.BLOCKED; reason = "CONTRADICTED_FAMILY"
                    blocks += HumanTemplateBlock(bank, decision.rawId, decision.id, features.template, decision.at)
                }
                else -> reason = "AMBIGUOUS_FAMILY"
            }
            LearningFeedbackRow(decision.id, decision.rawId, decision.at, decision.provenance, state.name, reason, bank, features?.template)
        }
        val learned = labels.map { row -> HumanFamilyLabel(row.bankId,row.rawId,row.decisionId,SmsFamilyLabel.valueOf(row.family),
            row.template,FamilyModelCodec.terms(kotlinx.serialization.json.Json.parseToJsonElement(row.featuresJson)),row.sourceAt,row.provenance) }
        val labelsByBank = learned.groupBy { it.bankId }
        val blocksByBank = blocks.groupBy { it.bankId }
        val updated = banks.associateWith { bank ->
            FamilyClassifier(bank, labelsByBank[bank].orEmpty(), blocksByBank[bank].orEmpty())
        }
        val conflicted = learned.groupBy { it.bankId to it.template }.filterValues { rows -> rows.map { it.family }.distinct().size > 1 }.keys +
            updated.values.flatMap { classifier -> classifier.latestBlocks.map { it.bankId to it.template } }
        val explained = feedback.map { row -> if(row.state == LearningFeedbackState.LEARNED.name && row.bankId to row.template in conflicted)
            row.copy(state = LearningFeedbackState.BLOCKED.name, reason = "CONFLICTING_TEMPLATE") else row }
        pendingFeedback = explained.any { it.state == LearningFeedbackState.PENDING.name }
        store.saveFeedback(labels,explained)
        // An edit during fitting invalidates the candidate; do not briefly publish stale personal assistance.
        if (closed) return@withLock null
        if(before != decisions()) { invalidateFeedback(); return@withLock null }
        synchronizedDecisions = fingerprint(before)
        classifiers = updated
        schedulePendingRefresh()
        onFeedbackChanged()
        modelVersion
    }
    suspend fun feedbackStatus(rawId: String): LearningFeedbackStatus? {
        if(classifiers == null) return LearningFeedbackStatus(LearningFeedbackState.PENDING, "FEEDBACK_PENDING")
        return store.feedback().lastOrNull { it.rawId == rawId }?.let { LearningFeedbackStatus(LearningFeedbackState.valueOf(it.state),it.reason) }
    }
    suspend fun learningFeedback() = store.feedback()
    suspend fun learningLabels() = store.labels()
    suspend fun interpretations() = store.interpretations()
    private suspend fun schedulePendingRefresh() {
        val configuration = patterns()
        val version = "${PatternFactInterpreter.of(configuration).fingerprint}|${modelVersion ?: "rules-only"}"
        val required = reviews.listRequired()
        val records = parsed.listByRawSmsIds(required.map { it.rawSmsId })
        val models = classifiers.orEmpty()
        // Outside role rules only a remembered "not financial" can change a message, and only one without an amount.
        val roled = ruleBanks(configuration)
        // Rules ask for a family only when they found none; a message already read with this model is never re-read,
        // otherwise a suggestion the rules cannot use would refresh the same message forever.
        val candidates = records.filter { record -> val bank = record.event.bank.id
            bank !in roled && bank in models && record.event.parseStatus.name != "SUCCESS" &&
                record.event.messageFamily.name == "UNKNOWN" &&
                store.history(record.event.rawSmsId).lastOrNull()?.activeModelVersion != models.getValue(bank).version }
        val bodies = raw.getByIds(candidates.map { it.event.rawSmsId }.toSet()).associateBy { it.id }
        assistable = candidates.filter { record -> bodies[record.event.rawSmsId]?.body?.let { body ->
            FamilyAssistBridge.suggest(models[record.event.bank.id], record.event.bank.id, body)?.let {
                it.family == MessageFamily.NON_FINANCIAL && record.event.amount == null && !FamilyAssistBridge.showsMoney(body) }
        } == true }
            .map { it.event.rawSmsId }.toSet()
        // Role-rule UNKNOWN messages already read with their bank's current model are not read again (same guard as above).
        ruleRead = records.filter { it.event.bank.id in roled && it.event.messageFamily.name == "UNKNOWN" &&
            store.history(it.event.rawSmsId).lastOrNull()?.activeModelVersion == models[it.event.bank.id]?.version }
            .map { it.event.rawSmsId }.toSet()
        // A message read with its bank's current decisions is still settled by them; only older reads can be revoked.
        val remembered = parsed.listRememberedNonFinancial().filter { record ->
            "model:${models[record.event.bank.id]?.version}" !in record.event.confidence.reasons }
        revoked = FamilyAssistBridge.revokedMemories(remembered,
            raw.getByIds(remembered.map { it.event.rawSmsId }.toSet()).associate { it.id to it.body }, models)
        val needs = refreshFilter(loaded = configuration)
        val pending = records.any { needs(it) && mayRefresh(it.event.rawSmsId) } || revoked.any { mayRefresh(it) }
        if (pending) ModelRefreshWorker.enqueue(context, version)
    }
    fun needsRefresh(record: com.baraa.masroof.parsing.repository.ParsedEventRecord): Boolean = refreshFilter()(record)
    /**
     * One pass reads the pattern file once, not once per pending message. The audit history is written before
     * the parse commits, so it only decides whether to schedule work; the worker itself ignores it ([readHistory]
     * false) and a failed, retried run still refreshes the message.
     */
    fun refreshFilter(readHistory: Boolean = true, loaded: MessagePatternConfiguration? = null):
        (com.baraa.masroof.parsing.repository.ParsedEventRecord) -> Boolean {
        // One file version decides both the rules fingerprint and which banks have role rules.
        val configuration = lazy { loaded ?: patterns() }
        val rules = lazy { PatternFactInterpreter.of(configuration.value).fingerprint }
        val roled = lazy { ruleBanks(configuration.value) }
        val read = if (readHistory) ruleRead else emptySet()
        return { record -> needsRefresh(record, rules, roled, read) }
    }
    private fun needsRefresh(record: com.baraa.masroof.parsing.repository.ParsedEventRecord, rules: Lazy<String>,
        roled: Lazy<Set<String>>, read: Set<String>): Boolean {
        // Only while it is still hidden: once read again it is an ordinary review message.
        if (record.event.rawSmsId in revoked && record.event.messageFamily.name == "NON_FINANCIAL") return true
        // Successful facts resume derived work.
        if (record.event.parseStatus.name == "SUCCESS") return false
        val bank = record.event.bank.id
        val reasons = record.event.confidence.reasons
        val staleInference = reasons.any { it.startsWith("family_origin:") } && "model:${classifiers?.get(bank)?.version}" !in reasons
        // Banks without role rules refresh only what learning now changes, so a taught pattern is still applied explicitly.
        if (bank !in roled.value) return bank in classifiers.orEmpty() && (staleInference || record.event.rawSmsId in assistable)
        val fingerprint = rules.value
        return record.event.messageFamily.name == "UNKNOWN" && record.event.rawSmsId !in read ||
            reasons.none { it == "rules:$fingerprint" || it.startsWith("rules:$fingerprint:") } || staleInference
    }
    /** Do not re-interpret posted evidence or human decisions on engine/model upgrades; an automatic settlement is not one. */
    suspend fun mayRefresh(rawSmsId: String): Boolean =
        !transactions.isRawSmsLinked(rawSmsId) && decisions.effectiveFor(rawSmsId).let {
            it.corrections.isEmpty() && !ReviewCorrectionClosure.protects(it, reviews.findByRawSmsId(rawSmsId))
        }

    private data class Decision(val id: String,val rawId: String,val family: MessageFamily?,val at: Long,
        val provenance: String,val type: FinancialTransactionType? = null,val reason: String? = null)
    suspend fun auditHistory(rawId: String) = store.history(rawId)
    fun invalidateForRestore() {
        close()
        store.invalidate()
    }
    override fun close() {
        closed = true
        stopSignal.complete(Unit)
        classifiers = null
        synchronizedDecisions = null
        revoked = emptySet()
        tracker?.removeObserver(observer);tracker=null
        changes.close()
        // Do not cancel Room continuations while their connection callback is opening.
        // Stop producers, let admitted writes and synchronization finish, then close Room.
        runBlocking {
            job?.join()
            mutex.withLock { }
            auditJob.complete()
            auditJob.join()
        }
        job=null
        classifiers = null
        store.close()
    }
    companion object {
        /** Banks whose role rules ask for a family and refresh unread UNKNOWN messages when the rules change. */
        private fun ruleBanks(configuration: MessagePatternConfiguration) =
            configuration.banks.filter { bank -> bank.currentPatterns.any { it.roleProfile != null } }.map { it.bankId }.toSet()
    }
}
