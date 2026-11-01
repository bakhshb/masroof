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
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.sms.hash.SmsBodyHasher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ML is a family assistant; the production workflows retain all financial authority.
 * Every bank with bundled role rules gets its own classifier built from that bank's human decisions.
 */
class BankInterpretationService(
    private val context: Context,
    private val raw: RawSmsRepository,
    private val parsed: ParsedEventRepository,
    private val corrections: UserCorrectionRepository,
    private val reviews: ReviewRepository,
    private val transactions: FinancialTransactionRepository,
    private val scope: CoroutineScope,
    private val log: AppLogService,
    private val onFeedbackChanged: () -> Unit = {},
    private val patterns: () -> MessagePatternConfiguration,
) : AutoCloseable {
    private val store = LearningStore(context)
    private val auditJob = SupervisorJob(scope.coroutineContext[Job])
    private val auditScope = CoroutineScope(scope.coroutineContext + auditJob)
    @Volatile private var closed = false
    private val stopSignal = CompletableDeferred<Unit>()
    private val seed = runCatching {store.loadSeed()}.getOrElse { log.warn("ML","Seed unavailable: ${it.javaClass.simpleName}");null }
    // Rebuild assistance from durable human decisions before enabling it; one classifier per assisted bank.
    @Volatile private var classifiers: Map<String, FamilyClassifier>? = null
    @Volatile private var personalizations: Map<String, FamilyPersonalizationResult> = emptyMap()
    /** Statistical personal learning needs an eligible seed, which only the seed bank has; other banks use exact templates. */
    val personalization: FamilyPersonalizationResult? get() = personalizations[SEED_BANK]
    // Templates of pending messages in banks other than the seed bank that learned decisions would now change.
    @Volatile private var assistable: Set<String> = emptySet()
    @Volatile private var seedRead: Set<String> = emptySet()
    @Volatile private var synchronizedDecisions: String? = null
    @Volatile private var pendingFeedback = false
    private data class Attempt(val bank: String, val classifier: FamilyClassifier?, var requested: Boolean = false,
        var outcome: String = "RULES", var prediction: FamilyModel.Prediction? = null)
    private val attempt = ThreadLocal<Attempt>()
    private fun engine() = PatternFactInterpreter.of(patterns())
    private val familyAssist = FamilyAssist { input,title ->
        val trace = attempt.get()
        trace?.requested = true
        val features = FamilyAssistBridge.clean(input.body).features
        trace?.prediction = features?.let { trace.classifier?.model?.predict(it) }
        val result = trace?.let { FamilyAssistBridge.suggest(it.classifier,it.bank,input.body,title) }
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
    private val observer = object : InvalidationTracker.Observer("user_correction","review_item","raw_sms","parsed_event","financial_transaction") {
        override fun onInvalidated(tables: Set<String>) {
            if(tables.any { it == "user_correction" || it == "review_item" }) invalidateFeedback()
            else if(pendingFeedback) changes.trySend(Unit)
        }
    }
    /** One stable version for all banks, so refresh work started for an older set of models is skipped. */
    val modelVersion: String? get() = classifiers?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${it.value.version}" }
    val statisticalFamilyCount: Int get() = classifiers?.values?.sumOf { it.model.enabledFamilies.size } ?: 0
    /** Banks whose bundled role rules can ask for a family; only these learn and receive suggestions. */
    fun assistedBanks(configuration: MessagePatternConfiguration = patterns()): Set<String> =
        configuration.banks.filter { bank -> bank.bankId.matches(BANK_ID) && bank.currentPatterns.any { it.roleProfile != null } }
            .map { it.bankId }.toSet() + SEED_BANK
    val refreshVersion: String get() = "${engine().fingerprint}|${modelVersion ?: "rules-only"}"
    fun interpret(input: MessageInput, snapshot: PatternFactInterpreter = engine(), bankId: String = SEED_BANK): Interpretation {
        val trace = Attempt(bankId, classifiers?.get(bankId))
        val activeModel = trace.classifier?.version
        attempt.set(trace)
        val result = try { snapshot.interpretRouted(input,BankId(bankId),familyAssist) } finally { attempt.remove() }
        val interpretedAt = System.currentTimeMillis()
        // Advisory audit writes never decide whether a transaction may be posted.
        if(!closed) auditScope.launch {
            try {
                val key=SmsBodyHasher.sha256Hex("${input.evidenceId}|${result.version}|${result.familyInference?.modelVersion?:activeModel}")
                store.record(EngineInterpretationRow(key,input.evidenceId,result.bank?.value,result.version.engine,result.version.rules,
                    result.familyInference?.modelVersion?:activeModel,result.familyInference?.origin?.name?:"RULES",result.facts.family.name,result.status.name,
                    result.blockers.joinToString(",") {it.name},SmsBodyHasher.sha256Hex(input.body),input.receivedAt.toEpochMilli(),interpretedAt,
                    trace.requested,trace.outcome,trace.prediction?.score,trace.prediction?.margin,trace.prediction?.coverage,
                    if(trace.requested) FamilyAssistBridge.clean(input.body).features?.template else null))
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
        personalizations = emptyMap()
        synchronizedDecisions = null
        changes.trySend(Unit)
    }

    /**
     * Every manual decision, read in a fixed number of batch queries: this runs inside the
     * financial write transaction for each inferred write, so its cost must not grow per message.
     */
    private suspend fun decisions(): List<Decision> {
        val result = mutableListOf<Decision>()
        corrections.listAll().forEach { correction ->
            result += Decision("correction:${correction.id}", correction.targetRawSmsId,
                correction.correctedType?.let { MessageFamily.valueOf(it.name) },
                correction.createdAt.toEpochMilli(), "USER_MESSAGE_FAMILY",
                reason = if(correction.correctedType == null) "FIELDS_ONLY" else null)
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
        val seed = seed ?: run { schedulePendingRefresh(); return@withLock null }
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
        val eligibleTemplates = mutableMapOf<String, MutableSet<String>>()
        var requested = false
        val probe = engine()
        val probeAssist = FamilyAssist { _, _ -> requested = true; null }
        learned.forEach { label ->
            val message = evidence[label.rawId] ?: return@forEach
            requested = false
            probe.interpretRouted(MessageInput(message.id, message.sender, message.body, message.receivedAt, true), BankId(label.bankId), probeAssist)
            if (requested) eligibleTemplates.getOrPut(label.bankId) { mutableSetOf() } += label.template
        }
        val rebuilt = banks.associateWith { bank ->
            // Statistical families and their evaluations belong to the seed bank; other banks start from exact human templates.
            val bankSeed = if (bank == seed.model.bankId) seed else seed.copy(
                model = seed.model.copy(bankId = bank, enabledFamilies = emptySet()), evaluation = emptyList())
            FamilyPersonalization.rebuild(bankSeed.model, learned.filter { it.bankId == bank }, bankSeed.evaluation,
                blocks.filter { it.bankId == bank }, eligibleTemplates[bank].orEmpty())
        }
        val updated = rebuilt.mapValues { it.value.classifier }
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
        personalizations = rebuilt
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
        val prefs = context.getSharedPreferences("learning_runtime", Context.MODE_PRIVATE)
        val version = refreshVersion
        val required = reviews.listRequired()
        val records = parsed.listByRawSmsIds(required.map { it.rawSmsId })
        val models = classifiers.orEmpty()
        // Rules ask for a family only when they found none; a message already read with this model is never re-read,
        // otherwise a suggestion the rules cannot use would refresh the same message forever.
        val candidates = records.filter { record -> val bank = record.event.bank.id
            bank != SEED_BANK && bank in models && record.event.parseStatus.name != "SUCCESS" &&
                record.event.messageFamily.name == "UNKNOWN" &&
                store.history(record.event.rawSmsId).lastOrNull()?.activeModelVersion != models.getValue(bank).version }
        val bodies = raw.getByIds(candidates.map { it.event.rawSmsId }.toSet()).associateBy { it.id }
        assistable = candidates.filter { record -> bodies[record.event.rawSmsId]?.body?.let { body ->
            FamilyAssistBridge.suggest(models[record.event.bank.id], record.event.bank.id, body,
                com.baraa.masroof.engine.bank.interpretation.FamilyTextPreparation.prepare(body).title)
        } != null }.map { it.event.rawSmsId }.toSet()
        // Seed UNKNOWN messages already read with the current seed model are not read again (same guard as above).
        seedRead = records.filter { it.event.bank.id == SEED_BANK && it.event.messageFamily.name == "UNKNOWN" &&
            store.history(it.event.rawSmsId).lastOrNull()?.activeModelVersion == models[SEED_BANK]?.version }
            .map { it.event.rawSmsId }.toSet()
        val needs = refreshFilter()
        val pending = records.any { needs(it) && mayRefresh(it.event.rawSmsId) }
        if (pending) ModelRefreshWorker.enqueue(context, version)
        check(prefs.edit().putString("scheduled_interpretation", version).commit())
    }
    fun needsRefresh(record: com.baraa.masroof.parsing.repository.ParsedEventRecord): Boolean = refreshFilter()(record)
    /**
     * One pass reads the pattern file once, not once per pending message. The audit history is written before
     * the parse commits, so it only decides whether to schedule work; the worker itself ignores it ([readHistory]
     * false) and a failed, retried run still refreshes the message.
     */
    fun refreshFilter(readHistory: Boolean = true): (com.baraa.masroof.parsing.repository.ParsedEventRecord) -> Boolean {
        val rules = lazy { engine().fingerprint }
        val read = if (readHistory) seedRead else emptySet()
        return { record -> needsRefresh(record, rules, read) }
    }
    private fun needsRefresh(record: com.baraa.masroof.parsing.repository.ParsedEventRecord, rules: Lazy<String>,
        read: Set<String>): Boolean {
        // Successful facts resume derived work.
        if (record.event.parseStatus.name == "SUCCESS") return false
        val bank = record.event.bank.id
        val reasons = record.event.confidence.reasons
        val staleInference = reasons.any { it.startsWith("family_origin:") } && "model:${classifiers?.get(bank)?.version}" !in reasons
        // Other banks refresh only what learning now changes, so an approved pattern is still applied explicitly.
        if (bank != SEED_BANK) return bank in classifiers.orEmpty() && (staleInference || record.event.rawSmsId in assistable)
        val fingerprint = rules.value
        return record.event.messageFamily.name == "UNKNOWN" && record.event.rawSmsId !in read ||
            reasons.none { it == "rules:$fingerprint" || it.startsWith("rules:$fingerprint:") } || staleInference
    }
    /** Do not re-interpret posted evidence or human decisions on engine/model upgrades. */
    suspend fun mayRefresh(rawSmsId: String): Boolean =
        !transactions.isRawSmsLinked(rawSmsId) && corrections.latestForRawSmsId(rawSmsId) == null &&
            reviews.findByRawSmsId(rawSmsId)?.resolutionKind == null

    private data class Decision(val id: String,val rawId: String,val family: MessageFamily?,val at: Long,
        val provenance: String,val type: FinancialTransactionType? = null,val reason: String? = null)
    suspend fun auditHistory(rawId: String) = store.history(rawId)
    fun invalidateForRestore() {
        close()
        store.invalidate()
        context.getSharedPreferences("learning_runtime",Context.MODE_PRIVATE).edit().clear().commit()
    }
    override fun close() {
        closed = true
        stopSignal.complete(Unit)
        classifiers = null
        synchronizedDecisions = null
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
        /** The bank of the bundled statistical seed. */
        const val SEED_BANK="BANK_ALJAZIRA"
        private val BANK_ID = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}
