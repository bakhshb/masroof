package com.baraa.masroof.lab.beta

import com.baraa.masroof.lab.beta.application.BetaManualAssetWorkflow
import com.baraa.masroof.lab.beta.application.ManualAssetDraft
import com.baraa.masroof.lab.beta.application.BetaStartState
import com.baraa.masroof.lab.beta.application.BetaBankSelectionWorkflow
import com.baraa.masroof.lab.beta.data.RoomBetaBankSelectionStore
import com.baraa.masroof.lab.finance.value.BankId
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.data.BetaAssetAlias
import com.baraa.masroof.lab.beta.data.BetaAssetRegistry
import com.baraa.masroof.lab.finance.asset.OwnedAsset
import com.baraa.masroof.lab.beta.data.BetaDatabase
import com.baraa.masroof.lab.beta.data.BetaShellMarkerEntity
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking

/**
 * Beta composition root with an isolated explicit asset registry.
 *
 * Opens [BetaEnvironment.DATABASE_NAME] only. It does not import the production
 * composition root and it does not expose production repositories.
 */
class BetaContainer(
    context: Context,
    private val evidenceInterpreter: com.baraa.masroof.lab.beta.application.evidence.EvidenceComparer =
        com.baraa.masroof.lab.beta.application.evidence.LabEvidenceComparer(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private var closed = false
    private val recovery = BetaBackupRecovery(appContext).also { it.recover() }
    private var database = openDatabase()
    private val preferences = appContext.getSharedPreferences(
        BetaEnvironment.PREFS_NAME,
        Context.MODE_PRIVATE,
    ).also { BetaEnvironment.requireBetaPreferenceName(BetaEnvironment.PREFS_NAME) }

    /** Read only: prior Beta rows/preferences are history, never a new user's initial accounts. */
    fun startState(): BetaStartState = lock.withLock {
        check(!closed)
        val sqlite = database.openHelper.writableDatabase
        val stored = preferences.all[START_KEY]
        if (stored != null) {
            check(stored == START_VERSION) { "Unsupported Beta entry state" }
            return@withLock BetaStartState.READY
        }
        // Open/validate Room even when only preferences exist. No destructive fallback or reset.
        val hasRows = sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table')").use { tables ->
            var found = false
            while (!found && tables.moveToNext()) {
                val table = tables.getString(0).replace("\"", "\"\"")
                found = sqlite.query("SELECT 1 FROM \"$table\" LIMIT 1").use { it.moveToFirst() }
            }
            found
        }
        if (hasRows || preferences.all.isNotEmpty()) BetaStartState.EXISTING
        else BetaStartState.NEW
    }

    /** Explicit user action; retains every existing row and unrelated preference. */
    fun acknowledgeStart() = lock.withLock {
        check(!closed)
        startState() // Reject unknown future state or unreadable database rather than replacing it.
        check(preferences.edit().putString(START_KEY, START_VERSION).putString("beta_storage_generation",preferences.getString("beta_storage_generation",null) ?: java.util.UUID.randomUUID().toString()).commit()) { "Could not save Beta entry state" }
    }

    fun onboardingStep(): com.baraa.masroof.lab.beta.application.BetaOnboardingStep = lock.withLock {
        check(!closed)
        val stored = preferences.getString("beta_onboarding_v1", null)
        if (stored == null) com.baraa.masroof.lab.beta.application.BetaOnboardingStep.COMPLETE
        else com.baraa.masroof.lab.beta.application.BetaOnboardingStep.valueOf(stored)
    }

    /** One explicit welcome action commits acknowledgement and initial progress together. */
    fun beginOnboarding() = lock.withLock {
        check(!closed)
        check(startState() == BetaStartState.NEW)
        runBlocking { val workflow=BetaBankSelectionWorkflow(RoomBetaBankSelectionStore(database))
            com.baraa.masroof.lab.beta.application.BetaBankCatalog.current.supported.forEach { workflow.enable(it.id) }
            com.baraa.masroof.lab.beta.data.RoomBetaDashboardConfiguration(database).save(0,
                com.baraa.masroof.lab.beta.application.BetaDashboardConfigurationDraft(com.baraa.masroof.lab.beta.application.BetaSalaryCycle(27))) }
        check(preferences.edit().putString(START_KEY, START_VERSION).putString("beta_onboarding_v1", "PERMISSION").putString("beta_storage_generation",preferences.getString("beta_storage_generation",null) ?: java.util.UUID.randomUUID().toString()).commit())
    }

    /** Explicit setup recovery preserves messages, labels, assets, ledger and backups. */
    fun resumeOnboarding() = lock.withLock {
        check(!closed)
        check(startState() == BetaStartState.READY)
        if (onboardingStep() != com.baraa.masroof.lab.beta.application.BetaOnboardingStep.COMPLETE) return@withLock
        val step = if (bankChoices().any { it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED }) "IMPORT" else "PERMISSION"
        check(preferences.edit().putString("beta_onboarding_v1", step).commit())
    }

    fun advanceOnboarding(expected: com.baraa.masroof.lab.beta.application.BetaOnboardingStep) = lock.withLock {
        check(!closed)
        val steps = com.baraa.masroof.lab.beta.application.BetaOnboardingStep.entries
        require(expected != com.baraa.masroof.lab.beta.application.BetaOnboardingStep.COMPLETE)
        if (expected == com.baraa.masroof.lab.beta.application.BetaOnboardingStep.ASSETS)
            check(runBlocking { com.baraa.masroof.lab.beta.application.BetaOwnershipWorkflow(database).snapshot().let { it.unresolvedCount == 0 } }) {
                "Resolve discovered account and card ownership first"
            }
        val next = steps[expected.ordinal + 1]
        val current = onboardingStep()
        if (current == next) return@withLock // Idempotent retry of this exact transition.
        check(current == expected) { "Onboarding changed; reopen" }
        if (expected == com.baraa.masroof.lab.beta.application.BetaOnboardingStep.BANK)
            check(bankChoices().any { it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED })
        check(preferences.edit().putString("beta_onboarding_v1", next.name).commit())
    }

    /** Explicit fresh Beta choice, never called by entry/relaunch. */
    fun startFresh() = lock.withLock {
        check(!closed)
        check(startState() != BetaStartState.READY) { "Entry already acknowledged" }
        reset()
    }

    fun bankChoices() = lock.withLock {
        check(!closed)
        runBlocking { BetaBankSelectionWorkflow(RoomBetaBankSelectionStore(database)).choices() }
    }

    /** Explicit caller-supplied batch through the independent engine; no inbox access or live scheduling. */
    fun importEvidence(request: com.baraa.masroof.lab.beta.application.evidence.EvidenceRequest,
        messages: List<com.baraa.masroof.lab.beta.application.evidence.EvidenceMessage>) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { evidenceWorkflow().importBatch(request, messages) }
    }

    fun reprocessEvidence(id: String) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { evidenceWorkflow().reprocess(id) }
    }

    fun retryEvidence(limit: Int = 100) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { evidenceWorkflow().retryPending(limit) }
    }

    private fun invalidateAutomaticProcessing() {
        check(preferences.edit().remove("beta_auto_complete_v1").remove("beta_auto_cursor_v1").remove("beta_auto_cursor_version_v1").commit())
    }

    /** Versioned replay of immutable lab evidence, checkpointed before automatic adoption.
     * User decisions/corrections remain separate; historical comparison archives stay inert. */
    private fun refreshStoredInterpretations(version: String) {
        if(preferences.getString("beta_reinterpret_complete",null)==version) return
        var cursor=if(preferences.getString("beta_reinterpret_version",null)==version)
            preferences.getString("beta_reinterpret_cursor","").orEmpty() else ""
        while(true) {
            val ids=runBlocking {database.evidenceDao().idsAfter(cursor,100)}
            if(ids.isEmpty()) break
            for(id in ids) runBlocking {
                val outcome=database.evidenceDao().outcome(id) ?: return@runBlocking
                if(outcome.baselineJson!="{}") return@runBlocking
                val archive=runCatching {org.json.JSONObject(outcome.candidateJson)}.getOrNull() ?: return@runBlocking
                if(archive.optString("authority")!="BANK_ENGINE" ||
                    com.baraa.masroof.lab.beta.application.evidence.InterpretationArchive.isCurrent(archive)) return@runBlocking
                val result=evidenceWorkflow().reprocess(id)
                check(result.retryStage==null) {"Lab interpretation upgrade remains retryable"}
            }
            cursor=ids.last()
            check(preferences.edit().putString("beta_reinterpret_version",version).putString("beta_reinterpret_cursor",cursor).commit())
        }
        check(preferences.edit().putString("beta_reinterpret_complete",version).remove("beta_reinterpret_cursor").commit())
    }

    /** Durable, bounded write-side work. Dashboard reads never run this sweep. */
    fun processAutomaticEvidence() = lock.withLock {
        check(!closed)
        if (startState() != BetaStartState.READY) return@withLock
        if (onboardingStep() in setOf(com.baraa.masroof.lab.beta.application.BetaOnboardingStep.BANK,
                com.baraa.masroof.lab.beta.application.BetaOnboardingStep.PERMISSION,
                com.baraa.masroof.lab.beta.application.BetaOnboardingStep.IMPORT,
                com.baraa.masroof.lab.beta.application.BetaOnboardingStep.ASSETS)) return@withLock
        val version = "v4-owned-loan-discovery|" + com.baraa.masroof.lab.bank.interpretation.RuleInterpreter(
            com.baraa.masroof.lab.beta.application.interpretation.BetaInterpretationService.currentSchema()).version
        if (preferences.getString("beta_auto_complete_v1", null) == version) return@withLock
        refreshStoredInterpretations(version)
        runBlocking { com.baraa.masroof.lab.beta.application.BetaOwnershipWorkflow(database).let {it.materializeConfirmedReferences();it.linkProvenDebitAccounts()} }
        var cursor = if (preferences.getString("beta_auto_cursor_version_v1", null) == version) preferences.getString("beta_auto_cursor_v1", "").orEmpty() else ""
        while (true) {
            val next = runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).automaticPage(cursor) }
            if (next == null) {
                check(preferences.edit().putString("beta_auto_complete_v1", version).remove("beta_auto_cursor_v1").remove("beta_auto_cursor_version_v1").commit())
                break
            }
            check(preferences.edit().putString("beta_auto_cursor_v1", next).putString("beta_auto_cursor_version_v1", version).commit())
            cursor = next
        }
    }

    fun storageGeneration(): String? = lock.withLock { preferences.getString("beta_storage_generation",null) }

    /** Capture only. The caller schedules processing after this durable transaction. */
    fun captureLive(message: com.baraa.masroof.lab.beta.application.evidence.EvidenceMessage): String? = lock.withLock {
        check(!closed)
        if(startState()!=BetaStartState.READY || storageGeneration()==null) return@withLock null
        runBlocking { database.withTransaction {
            val router=com.baraa.masroof.lab.beta.application.evidence.LabEvidenceRouter()
            val route=router.route(message)
            val selections=RoomBetaBankSelectionStore(database).all()
            val enabled=selections.filter { it.state==com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED }.map { it.bankId }.toSet()
            if(route.kind==com.baraa.masroof.lab.beta.application.evidence.RouteKind.UNRECOGNIZED || enabled.isEmpty() ||
                (route.candidates.isNotEmpty() && route.candidates.none { it in enabled })) return@withTransaction null
            val request=com.baraa.masroof.lab.beta.application.evidence.EvidenceRequest("beta-live-sms-v1",com.baraa.masroof.lab.beta.application.evidence.EvidenceOrigin.LIVE)
            val requested=com.baraa.masroof.lab.beta.application.evidence.ProcessBetaEvidence.identity(request.namespace,message.providerId)
            val store=com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database)
            val id=store.resolveCaptureId(request,message,requested)
            val existing=store.find(id)
            if(existing!=null) {
                check(existing.message.sender==message.sender && existing.message.body==message.body && existing.message.receivedAt==message.receivedAt)
                store.recordProviderAlias(requested,id)
            } else {
                check(store.capture(com.baraa.masroof.lab.beta.application.evidence.CapturedEvidence(id,request,message,java.time.Instant.now(),route,selections))!=com.baraa.masroof.lab.beta.application.evidence.CaptureState.CONFLICT)
            }
            id
        } }
    }

    fun pendingEvidenceIds(afterId: String="",limit: Int=1000): List<String> = lock.withLock {
        check(!closed);require(limit in 1..1000)
        runBlocking {database.evidenceDao().pendingAfter(afterId,limit)}
    }

    fun processLive(id: String,generation: String): Boolean = lock.withLock {
        check(!closed)
        if(storageGeneration()!=generation) return@withLock true
        val raw=runBlocking {database.evidenceDao().raw(id)} ?: return@withLock true
        val summary=runBlocking {database.evidenceDao().retry(id)}
        if(summary!=null) runBlocking {evidenceWorkflow().reprocess(raw.id)}
        if(runBlocking {database.evidenceDao().retry(id)}!=null) return@withLock false
        if(onboardingStep() in setOf(com.baraa.masroof.lab.beta.application.BetaOnboardingStep.REVIEW,com.baraa.masroof.lab.beta.application.BetaOnboardingStep.COMPLETE)) {
            invalidateAutomaticProcessing()
            try { runBlocking {com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).automaticEvidence(id)} }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                runBlocking {database.evidenceDao().retry(com.baraa.masroof.lab.beta.data.BetaEvidenceRetryEntity(
                    id,"RECONCILIATION",Math.addExact(summary?.attempts ?: 0,1),"AUTOMATIC_POST_FAILED",java.time.Instant.now().toString()))}
                return@withLock false
            }
        }
        true
    }

    fun evidenceSummary(id: String) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database).summary(id) }
    }

    /** Explicit bounded write-side maintenance after a Beta schema upgrade. */
    fun refreshDashboardFacts(afterId: String = "", limit: Int = 100) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaDashboardFacts(database).refreshPage(afterId, limit) }
    }

    fun dashboard(query: com.baraa.masroof.lab.finance.dashboard.DashboardQuery,
        periods: com.baraa.masroof.lab.finance.dashboard.producer.DashboardPeriods = com.baraa.masroof.lab.finance.dashboard.producer.DashboardPeriods()) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.finance.dashboard.producer.BetaDashboardProducer(
            com.baraa.masroof.lab.beta.data.RoomBetaDashboardSource(database, periods)).readWithCoverage(query) }
    }

    /** Controls retain the lifecycle lock on every operation, including callers holding this facade across restore. */
    fun dashboardControls(): com.baraa.masroof.lab.beta.application.BetaDashboardControls = object : com.baraa.masroof.lab.beta.application.BetaDashboardControls {
        override suspend fun read() = kotlinx.coroutines.withContext(BetaIo.dispatcher) { lock.withLock {
            check(!closed)
            runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaDashboardConfiguration(database).read() }
        } }
        override suspend fun save(expectedRevision: Long, draft: com.baraa.masroof.lab.beta.application.BetaDashboardConfigurationDraft) = kotlinx.coroutines.withContext(BetaIo.dispatcher) { lock.withLock {
            check(!closed)
            runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaDashboardConfiguration(database).save(expectedRevision, draft) }
        } }
    }

    fun financialReviewPage(afterId: String = "", limit: Int = 25) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).reviewPage(afterId, limit) }
    }

    fun financialApprovalPreview(id: String) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).preview(id) }
    }

    fun approveEvidenceFinancial(request: com.baraa.masroof.lab.beta.application.evidence.BetaFinancialApproval) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).approve(request) }
    }

    fun retryFinancialApproval(id: String, expected: com.baraa.masroof.lab.beta.application.evidence.BetaFinancialApproval? = null) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).retryApproval(id, expected) }
    }

    fun cancelFinancialApproval(request: com.baraa.masroof.lab.beta.application.evidence.BetaFinancialApproval) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).cancelApproval(request) }
    }

    fun financialObservation(key: com.baraa.masroof.lab.finance.posting.PostingKey) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).observation(key) }
    }

    fun reconcileApprovedEvidence(id: String) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).reconcileApprovedEvidence(id) }
    }

    fun enrichEvidenceFx(id: String, expected: com.baraa.masroof.lab.finance.posting.PostingRevision,
        market: com.baraa.masroof.lab.finance.fx.FxMarketRateProvider) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialAdoption(database).enrich(id, expected, market) }
    }

    fun decideEvidence(id: String, expectedRevision: Long,
        decision: com.baraa.masroof.lab.beta.application.evidence.EvidenceReviewDecision) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { evidenceWorkflow().decide(id, expectedRevision, decision) }
    }

    private fun evidenceWorkflow() = com.baraa.masroof.lab.beta.application.evidence.ProcessBetaEvidence(
        com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database), RoomBetaBankSelectionStore(database),
        com.baraa.masroof.lab.beta.application.evidence.LabEvidenceRouter(),
        evidenceInterpreter)

    fun historySession() = lock.withLock {
        check(!closed)
        com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(preferences).load()
    }

    fun startHistory(days: Int, now: Long = System.currentTimeMillis()) = lock.withLock {
        check(!closed)
        require(days > 0)
        val banks = bankChoices().filter { it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED }.map { it.bankId }.toSet()
        require(banks.isNotEmpty())
        val session = com.baraa.masroof.lab.beta.application.history.HistorySession(
            maxOf(0, now - days * com.baraa.masroof.lab.beta.application.history.HistorySession.DAY), now, banks)
        com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(preferences).save(session)
        session
    }

    fun startHistoryFrom(date: java.time.LocalDate, now: Long = System.currentTimeMillis()) = lock.withLock {
        check(!closed)
        val zone=com.baraa.masroof.lab.beta.application.history.BetaImportDatePolicy.zone
        val from=date.atStartOfDay(zone).toInstant().toEpochMilli()
        require(from in 0..now) { "Choose a past import date" }
        val banks=bankChoices().filter { it.state==com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED }.map { it.bankId }.toSet()
        require(banks.isNotEmpty())
        check(preferences.edit().putString("beta_import_from",from.toString()).commit())
        com.baraa.masroof.lab.beta.application.history.HistorySession(from,now,banks).also {
            com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(preferences).save(it)
        }
    }

    fun restartSavedHistory(now: Long = System.currentTimeMillis()) = lock.withLock {
        val from=preferences.getString("beta_import_from",null)?.toLongOrNull()
        val date=from?.let { java.time.Instant.ofEpochMilli(it).atZone(com.baraa.masroof.lab.beta.application.history.BetaImportDatePolicy.zone).toLocalDate() }
            ?: com.baraa.masroof.lab.beta.application.history.BetaImportDatePolicy.date(com.baraa.masroof.lab.beta.application.history.BetaImportDateOption.LAST_27TH,
                java.time.Instant.ofEpochMilli(now).atZone(com.baraa.masroof.lab.beta.application.history.BetaImportDatePolicy.zone).toLocalDate())
        startHistoryFrom(date,now)
    }

    fun stepHistory() = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        val workflow = evidenceWorkflow()
        com.baraa.masroof.lab.beta.application.history.BetaHistoryImport(
            com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(preferences),
            com.baraa.masroof.lab.beta.data.AndroidHistorySource(appContext),
            { request, messages -> runBlocking { workflow.importBatch(request, messages) } }).step()
    }

    /** Safe bounded metadata page; no raw text enters presentation. */
    fun evidencePage(afterId: String = "", limit: Int = 25, needsReviewOnly: Boolean = false) = lock.withLock {
        check(!closed)
        require(limit in 1..100)
        runBlocking {
            val store = com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database)
            (if (needsReviewOnly) database.evidenceDao().reviewIdsAfter(afterId, limit) else database.evidenceDao().idsAfter(afterId, limit)).map { store.summary(it) }
        }
    }

    fun resolveReviewAsNonFinancial(id: String, restore: Boolean = false, type: com.baraa.masroof.lab.finance.posting.FinancialType? = null) = lock.withLock {
        check(!closed)
        runBlocking {com.baraa.masroof.lab.beta.application.evidence.BetaReviewInboxWorkflow(database).resolveNonFinancial(id,restore,type)}
        invalidateAutomaticProcessing()
    }

    fun restoreReviewAsFinancial(request: com.baraa.masroof.lab.beta.application.evidence.BetaFinancialApproval) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking {com.baraa.masroof.lab.beta.application.evidence.BetaReviewInboxWorkflow(database).restoreFinancial(request)}
    }

    fun reviewInboxPage(after: String = "", ignored: Boolean = false) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.application.evidence.BetaReviewInboxWorkflow(database).page(after, ignored) }
    }

    fun reviewDetail(id: String) = lock.withLock {
        check(!closed)
        runBlocking {com.baraa.masroof.lab.beta.application.evidence.BetaReviewResolutionWorkflow(database).detail(id)}
    }

    fun transactionActionDetail(id: String) = lock.withLock {
        check(!closed)
        runBlocking {com.baraa.masroof.lab.beta.application.corrections.BetaTransactionDetailWorkflow(database).detail(com.baraa.masroof.lab.finance.posting.PostingKey(com.baraa.masroof.lab.finance.value.Environment.BETA,id))}
    }
    fun reclassifyTransaction(token: com.baraa.masroof.lab.beta.application.corrections.BetaReviewToken,decisionId: String,type: com.baraa.masroof.lab.finance.posting.FinancialType) = lock.withLock {
        check(!closed)
        runBlocking {com.baraa.masroof.lab.beta.application.corrections.BetaTransactionDetailWorkflow(database).resolve(token,decisionId,type)}
    }

    fun evidenceInboxCounts() = lock.withLock {
        check(!closed)
        runBlocking { database.evidenceDao().inboxCounts() }
    }

    fun interpretationReview(id: String) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.application.evidence.BetaInterpretationReviewWorkflow(database).detail(id) }
    }

    fun saveInterpretationLabel(draft: com.baraa.masroof.lab.beta.application.evidence.BetaLabelDraft) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.application.evidence.BetaInterpretationReviewWorkflow(database).save(draft) }
    }

    fun confirmProposal(proposal: com.baraa.masroof.lab.beta.application.BetaAssetProposal, draft: ManualAssetDraft): OwnedAsset = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        require(draft.id == proposal.id && !draft.editing)
        require(bankChoices().any { it.bankId == proposal.id.bank && it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED })
        runBlocking { database.withTransaction {
            val store = com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database)
            require(proposal.copy(confirmedAssetId = null) in store.discoveryProposals(proposal.evidenceId)) { "Stale proposal" }
            require(store.proposalBinding(proposal).let { it == null || it == draft.id }) { "Already bound to another asset" }
            saveAssetDraft(draft).also { store.recordProposalConfirmation(proposal) }
        } }
    }

    fun linkExistingProposal(proposal: com.baraa.masroof.lab.beta.application.BetaAssetProposal, expectedAsset: OwnedAsset, ownedConfirmed: Boolean): OwnedAsset = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        require(ownedConfirmed)
        require(expectedAsset.id.environment == com.baraa.masroof.lab.finance.value.Environment.BETA && expectedAsset.id.bank == proposal.id.bank)
        runBlocking { database.withTransaction {
            require(database.bankSelectionDao().all().any { it.bankId == proposal.id.bank.value && it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED.name })
            val store = com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database)
            require(proposal.copy(confirmedAssetId = null) in store.discoveryProposals(proposal.evidenceId)) { "Stale proposal" }
            val asset = BetaAssetRegistry(database).all().singleOrNull { it.id == expectedAsset.id }
            require(asset != null && asset == expectedAsset && asset.kind == proposal.kind && asset.last4 == proposal.last4) { "Asset changed or incompatible" }
            store.recordProposalConfirmation(proposal, expectedAsset.id)
            expectedAsset
        } }
    }

    fun ownershipSnapshot() = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.application.BetaOwnershipWorkflow(database).snapshot() }
    }

    fun deferOwnership(candidate: com.baraa.masroof.lab.beta.application.BetaOwnershipCandidate) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { com.baraa.masroof.lab.beta.application.BetaOwnershipWorkflow(database).defer(candidate) }
    }

    fun decideOwnership(candidate: com.baraa.masroof.lab.beta.application.BetaOwnershipCandidate, owned: Boolean,
        selected: com.baraa.masroof.lab.finance.asset.AssetId? = null) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { com.baraa.masroof.lab.beta.application.BetaOwnershipWorkflow(database).decide(candidate, owned, selected) }
    }

    fun discoveryPage(afterId: String = "", limit: Int = 25) = lock.withLock {
        check(!closed)
        require(limit in 1..25)
        val enabled = bankChoices().filter { it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED }.map { it.bankId }.toSet()
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database).discoveryPage(afterId, limit).let {
            it.copy(proposals = it.proposals.filter { proposal -> proposal.id.bank in enabled })
        } }
    }

    fun setBankEnabled(bank: BankId, enabled: Boolean) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking {
            val workflow = BetaBankSelectionWorkflow(RoomBetaBankSelectionStore(database))
            if (enabled) workflow.enable(bank) else workflow.disable(bank)
        }
    }

    fun saveManualAsset(draft: ManualAssetDraft): OwnedAsset = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { database.withTransaction { saveAssetDraft(draft) } }
    }

    private suspend fun saveAssetDraft(draft: ManualAssetDraft): OwnedAsset {
        val workflow = BetaManualAssetWorkflow(BetaAssetRegistry(database))
        val metadata=draft.metadata
        val metadataRow=database.assetDao().get(draft.id.environment.name,draft.id.bank.value,draft.id.localId)
        val oldMetadata=metadataRow?.let { com.baraa.masroof.lab.beta.application.BetaAssetMetadata(it.displayName,it.network,it.accountType,it.loanType,it.metadataRevision) } ?: com.baraa.masroof.lab.beta.application.BetaAssetMetadata()
        if(metadata!=null) {
            require(metadata.displayName==null || (metadata.displayName.isNotBlank() && metadata.displayName.length<=120))
            require(metadata.network==null || metadata.network in setOf("MADA","VISA","MASTERCARD","UNKNOWN"))
            require(metadata.network!="MADA" || draft.kind==com.baraa.masroof.lab.finance.asset.AssetKind.DEBIT_CARD)
            require(metadata.accountType==null || metadata.accountType in setOf("CURRENT","SAVINGS","INVESTMENT","OTHER"))
            require(metadata.loanType==null || metadata.loanType in setOf("PERSONAL","AUTO","MORTGAGE"))
            check(metadata.revision==oldMetadata.revision || metadata.copy(revision=0)==oldMetadata.copy(revision=0)) { "Registry details changed; reopen" }
        }
        if (draft.createCreditFacility) {
            require(draft.kind == com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD &&
                draft.role == com.baraa.masroof.lab.finance.asset.CreditCardRole.PRIMARY && draft.parentId != null && draft.ownedConfirmed)
            val all = BetaAssetRegistry(database).all()
            val parent = all.singleOrNull { it.id == draft.parentId }
            if (parent == null) workflow.save(ManualAssetDraft(requireNotNull(draft.parentId), com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_FACILITY,
                "", ownedConfirmed = true))
            else check(parent.kind == com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_FACILITY && all.singleOrNull { it.id == draft.id }?.let {
                it.kind == draft.kind && it.last4 == draft.last4 && it.parentId == draft.parentId && it.creditCardRole == draft.role
            } == true) { "Facility already exists; reopen" }
        }
        val saved=workflow.save(draft.copy(createCreditFacility = false))
        if(metadata!=null && metadata.copy(revision=0)!=oldMetadata.copy(revision=0)) {
            val dao=database.assetDao();val id=draft.id
            val count=when(saved.kind) {
                com.baraa.masroof.lab.finance.asset.AssetKind.ACCOUNT -> dao.metadataAccount(id.environment.name,id.bank.value,id.localId,metadata.displayName,null,metadata.accountType,null,oldMetadata.revision)
                com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD,com.baraa.masroof.lab.finance.asset.AssetKind.DEBIT_CARD -> dao.metadataCard(id.environment.name,id.bank.value,id.localId,metadata.displayName,metadata.network,null,null,oldMetadata.revision)
                com.baraa.masroof.lab.finance.asset.AssetKind.LOAN -> dao.metadataLoan(id.environment.name,id.bank.value,id.localId,metadata.displayName,null,null,metadata.loanType,oldMetadata.revision)
                else -> dao.metadataFacility(id.environment.name,id.bank.value,id.localId,metadata.displayName,null,null,null,oldMetadata.revision)
            }
            check(count==1) { "Registry details changed; reopen" }
            dao.appendAudit(com.baraa.masroof.lab.beta.data.BetaAuditRecord(
                java.util.UUID.randomUUID().toString(),"REGISTRY_METADATA",id.environment.name,id.localId,
                transition=oldMetadata.revision+1,bankId=id.bank.value,
                payload=org.json.JSONObject().put("displayName",metadata.displayName).put("network",metadata.network).put("accountType",metadata.accountType).put("loanType",metadata.loanType).toString()))
            if(metadata.loanType!=oldMetadata.loanType) database.financialAdoptionDao().bank(id.bank.value).forEach { adoption ->
                if(id in com.baraa.masroof.lab.beta.data.financialRegistryDependencies(com.baraa.masroof.lab.beta.data.BetaLedgerCodec.decodeInput(adoption.approvedInput)))
                    database.financialAdoptionDao().hold(adoption.evidenceId,"REGISTRY_CHANGED")
            }
        }
        return saved
    }

    /** Explicit Beta ownership registration; shares the backup/reset lifetime lock. */
    fun registerAsset(asset: OwnedAsset, aliases: List<BetaAssetAlias> = emptyList()) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { BetaAssetRegistry(database).register(asset, aliases) }
    }

    fun assetMetadata(): Map<com.baraa.masroof.lab.finance.asset.AssetId,com.baraa.masroof.lab.beta.application.BetaAssetMetadata> = lock.withLock {
        check(!closed)
        runBlocking { database.assetDao().all().associate { row ->
            com.baraa.masroof.lab.finance.asset.AssetId(com.baraa.masroof.lab.finance.value.Environment.valueOf(row.environment),BankId(row.bankId),row.localId) to
                com.baraa.masroof.lab.beta.application.BetaAssetMetadata(row.displayName,row.network,row.accountType,row.loanType,row.metadataRevision)
        } }
    }

    fun cardSetupTasks(): List<com.baraa.masroof.lab.beta.application.BetaCardSetupTask> = lock.withLock {
        check(!closed)
        runBlocking {
            val assets=BetaAssetRegistry(database).owned()
            val cards=assets.filter { it.kind in setOf(com.baraa.masroof.lab.finance.asset.AssetKind.DEBIT_CARD,com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD) }
            val tasks=cards.filter { it.parentId==null || (it.kind==com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD && it.creditCardRole==null) }.map {
                com.baraa.masroof.lab.beta.application.BetaCardSetupTask(it.id.bank,it.last4.orEmpty(),it.id) }
            tasks+database.ownershipDao().all().filter { it.kind=="CARD" && it.status=="OWNED" && cards.none { card -> card.id.bank.value==it.bankId && card.last4==it.suffix } }.map {
                com.baraa.masroof.lab.beta.application.BetaCardSetupTask(BankId(it.bankId),it.suffix,null) }
        }
    }

    fun assets(): List<OwnedAsset> = lock.withLock {
        check(!closed)
        runBlocking { BetaAssetRegistry(database).owned() }
    }

    /** Explicit resolved Beta input only. Evidence import/comparison never calls this API. */
    fun postFinancial(input: com.baraa.masroof.lab.finance.classification.FinancialClassificationInput) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialLedger(database).post(input) }
    }

    fun postFinancial(transfer: com.baraa.masroof.lab.finance.matching.MatchedSelfTransfer) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialLedger(database).post(transfer) }
    }

    fun financialPosting(key: com.baraa.masroof.lab.finance.posting.PostingKey) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialLedger(database).read(key) }
    }

    fun financialHistory(key: com.baraa.masroof.lab.finance.posting.PostingKey) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialLedger(database).history(key) }
    }

    fun submitCorrection(observation: com.baraa.masroof.lab.finance.corrections.CorrectionObservation,
        request: com.baraa.masroof.lab.finance.corrections.CorrectionRequest) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaCorrectionLifecycle(database).submit(observation, request) }
    }

    fun rebaseCorrection(observation: com.baraa.masroof.lab.finance.corrections.CorrectionObservation, expectedCorrectionRevision: Long) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaCorrectionLifecycle(database).rebase(observation, expectedCorrectionRevision) }
    }

    fun correction(key: com.baraa.masroof.lab.finance.posting.PostingKey) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaCorrectionLifecycle(database).read(key) }
    }

    fun reviewPage(afterId: String = "", limit: Int = 25) = lock.withLock {
        check(!closed); runBlocking { com.baraa.masroof.lab.beta.application.corrections.BetaReviewWorkflow(database).page(afterId, limit) }
    }
    fun reviewDetail(key: com.baraa.masroof.lab.finance.posting.PostingKey) = lock.withLock {
        check(!closed); runBlocking { com.baraa.masroof.lab.beta.application.corrections.BetaReviewWorkflow(database).detail(key) }
    }
    fun saveReview(token: com.baraa.masroof.lab.beta.application.corrections.BetaReviewToken, decisionId: String,
        decision: com.baraa.masroof.lab.finance.corrections.FinancialDecision, metadata: Map<String, String> = emptyMap()) = lock.withLock {
        check(!closed)
        invalidateAutomaticProcessing()
        runBlocking { com.baraa.masroof.lab.beta.application.corrections.BetaReviewWorkflow(database).save(token, decisionId, decision, metadata) }
    }

    fun correctionHistory(key: com.baraa.masroof.lab.finance.posting.PostingKey) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaCorrectionLifecycle(database).history(key) }
    }

    /** Prior result for audit; never a financial projection input. */
    fun financialAuditPosting(key: com.baraa.masroof.lab.finance.posting.PostingKey) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaFinancialLedger(database).readAudit(key) }
    }

    fun markerCount(): Int = lock.withLock {
        check(!closed)
        runBlocking { database.shellMarkerDao().count() }
    }

    fun putMarker(id: String, note: String) = lock.withLock {
        check(!closed)
        runBlocking {
            database.shellMarkerDao().upsert(BetaShellMarkerEntity(id = id, note = note))
        }
    }

    fun putPreferenceProbe(value: String) = lock.withLock {
        check(!closed)
        preferences.edit().putString(PREFERENCE_PROBE_KEY, value).commit()
    }

    fun preferenceProbe(): String? = lock.withLock {
        check(!closed)
        preferences.getString(PREFERENCE_PROBE_KEY, null)
    }

    fun backup() = lock.withLock {
        check(!closed)
        database.close()
        try {
            recovery.backup()
        } finally {
            database = openDatabase()
        }
    }

    fun restore(): Boolean = lock.withLock {
        check(!closed)
        database.close()
        val restored=try {
            restoreOffline(appContext)
        } finally {
            database = openDatabase()
        }
        if(restored) com.baraa.masroof.lab.beta.application.sms.BetaLiveSms.resume(appContext)
        restored
    }

    /** The caller owns streams and erases the ephemeral password after return. */
    fun exportArchive(output: java.io.OutputStream, password: CharArray) = lock.withLock {
        check(!closed)
        database.close()
        try { recovery.exportArchive(output, password) } finally { database = openDatabase() }
    }

    fun importArchive(input: java.io.InputStream, password: CharArray): Boolean = lock.withLock {
        check(!closed)
        database.close()
        val restored=try { recovery.importArchive(input, password) } finally { database = openDatabase() }
        if(restored) com.baraa.masroof.lab.beta.application.sms.BetaLiveSms.resume(appContext)
        restored
    }

    fun reset() = lock.withLock {
        check(!closed)
        database.close()
        try {
            resetOffline(appContext)
        } finally {
            database = openDatabase()
        }
    }

    fun backupDatabaseFile(): File = recovery.backupFile()

    fun settings(): BetaSettings = lock.withLock {
        check(!closed)
        BetaSettings(preferences.getString("beta_ui_theme", "SYSTEM") ?: "SYSTEM", recovery.hasBackup(), preferences.getString("beta_ui_language", "SYSTEM") ?: "SYSTEM")
    }

    fun setLanguage(language: String) = lock.withLock {
        check(!closed); require(language in setOf("SYSTEM","ar","en"))
        check(preferences.edit().putString("beta_ui_language",language).commit())
    }

    fun setTheme(theme: String) = lock.withLock {
        check(!closed)
        require(theme in setOf("SYSTEM", "LIGHT", "DARK"))
        check(preferences.edit().putString("beta_ui_theme", theme).commit())
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            database.close()
        }
    }

    private fun openDatabase(): BetaDatabase {
        val name = BetaDatabase.NAME
        BetaEnvironment.requireBetaDatabaseName(name)
        val path = appContext.getDatabasePath(name)
        check(path.name == name) { "Resolved beta database path is ${path.name}" }
        if (!path.exists()) {
            val sidecars = listOf("-wal", "-shm", "-journal").map { File(path.path + it) }
            // Acknowledgement follows a validated Room open. Losing its database is not first launch.
            val acknowledged = appContext.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
                .all[START_KEY] == START_VERSION
            check(sidecars.none { it.exists() } && !acknowledged) { "Existing Beta storage needs recovery" }
        } else {
            // SQLite can initialize a zero-byte file as an empty database. Retain it for recovery instead.
            check(path.isFile && path.length() >= 100) { "Existing Beta database needs recovery" }
            // Android's default corruption handler can delete files. Validate read-only with a
            // non-destructive handler before handing an existing Beta database to Room.
            android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY, android.database.DatabaseErrorHandler { }).use { existing ->
                existing.rawQuery("PRAGMA quick_check", null).use { result ->
                    check(result.moveToFirst() && result.getString(0) == "ok" && !result.moveToNext()) {
                        "Existing Beta database needs recovery"
                    }
                }
            }
        }
        return Room.databaseBuilder(appContext, BetaDatabase::class.java, name).build()
    }

    private fun copyLiveDatabaseToBackup() {
        val live = appContext.getDatabasePath(BetaEnvironment.DATABASE_NAME)
        val backup = backupDatabase(appContext)
        BetaEnvironment.requireBetaDatabaseName(live.name)
        BetaEnvironment.requireBackupDatabaseName(backup.name)
        check(live.absolutePath != backup.absolutePath)
        backup.parentFile?.mkdirs()
        live.copyTo(backup, overwrite = true)
    }

    private fun exportPreferences() {
        val destination = backupPreferences(appContext)
        BetaEnvironment.requireBackupPreferenceName(destination.name)
        val lines = preferences.all.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) ->
                check(value is String) { "Beta preferences store strings only" }
                "$key=$value"
            }
        destination.writeText(lines)
    }

    companion object {
        internal const val START_KEY = "beta_entry_state"
        private const val START_VERSION = "acknowledged-v1"
        const val PREFERENCE_PROBE_KEY: String = "beta_preference_probe"

        fun restoreOffline(context: Context): Boolean {
            return BetaBackupRecovery(context.applicationContext).restore()
        }

        fun resetOffline(context: Context) {
            val appContext = context.applicationContext
            BetaBackupRecovery(appContext).clear()
            BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.DATABASE_NAME)
            BetaEnvironment.requireBetaPreferenceName(BetaEnvironment.PREFS_NAME)
            val live = appContext.getDatabasePath(BetaEnvironment.DATABASE_NAME)
            val databaseFiles = listOf(live) + listOf("-wal", "-shm", "-journal").map { File(live.path + it) }
            val hadDatabaseFiles = databaseFiles.any { it.exists() }
            val deleted = appContext.deleteDatabase(BetaEnvironment.DATABASE_NAME)
            check(!hadDatabaseFiles || deleted) { "Could not clear Beta database" }
            check(databaseFiles.none { it.exists() }) { "Could not clear Beta database sidecars" }
            check(appContext.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()) { "Could not clear Beta preferences" }
            listOf(backupDatabase(appContext), backupPreferences(appContext)).forEach { file ->
                check(!file.exists() || file.delete()) { "Could not clear legacy Beta snapshot" }
            }
        }

        private fun importPreferences(context: Context, file: File) {
            BetaEnvironment.requireBackupPreferenceName(file.name)
            val preferences = context.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = preferences.edit().clear()
            file.readLines().forEach { line ->
                if (line.isEmpty()) return@forEach
                editor.putString(line.substringBefore('='), line.substringAfter('='))
            }
            editor.commit()
        }

        private fun backupDatabase(context: Context): File =
            File(context.filesDir, BetaEnvironment.BACKUP_DATABASE_FILE_NAME)

        private fun backupPreferences(context: Context): File =
            File(context.filesDir, BetaEnvironment.BACKUP_PREFS_FILE_NAME)
    }
}

data class BetaSettings(val theme: String, val backupAvailable: Boolean, val language: String = "SYSTEM")
