package com.baraa.masroof.lab.beta

import com.baraa.masroof.lab.beta.application.BetaManualAssetWorkflow
import com.baraa.masroof.lab.beta.application.ManualAssetDraft
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
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private var closed = false
    private var database = openDatabase()
    private val preferences = appContext.getSharedPreferences(
        BetaEnvironment.PREFS_NAME,
        Context.MODE_PRIVATE,
    ).also { BetaEnvironment.requireBetaPreferenceName(BetaEnvironment.PREFS_NAME) }

    fun bankChoices() = lock.withLock {
        check(!closed)
        runBlocking { BetaBankSelectionWorkflow(RoomBetaBankSelectionStore(database)).choices() }
    }

    /** Explicit caller-supplied batch, comparison only; no inbox access or live scheduling. */
    fun importEvidence(request: com.baraa.masroof.lab.beta.application.evidence.EvidenceRequest,
        messages: List<com.baraa.masroof.lab.beta.application.evidence.EvidenceMessage>) = lock.withLock {
        check(!closed)
        runBlocking { evidenceWorkflow().importBatch(request, messages) }
    }

    fun reprocessEvidence(id: String) = lock.withLock {
        check(!closed)
        runBlocking { evidenceWorkflow().reprocess(id) }
    }

    fun retryEvidence(limit: Int = 100) = lock.withLock {
        check(!closed)
        runBlocking { evidenceWorkflow().retryPending(limit) }
    }

    fun evidenceSummary(id: String) = lock.withLock {
        check(!closed)
        runBlocking { com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database).summary(id) }
    }

    fun decideEvidence(id: String, expectedRevision: Long,
        decision: com.baraa.masroof.lab.beta.application.evidence.EvidenceReviewDecision) = lock.withLock {
        check(!closed)
        runBlocking { evidenceWorkflow().decide(id, expectedRevision, decision) }
    }

    private fun evidenceWorkflow() = com.baraa.masroof.lab.beta.application.evidence.ProcessBetaEvidence(
        com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database), RoomBetaBankSelectionStore(database),
        com.baraa.masroof.lab.beta.application.evidence.LabEvidenceRouter(),
        com.baraa.masroof.lab.beta.application.evidence.LabEvidenceComparer())

    fun historySession() = lock.withLock {
        check(!closed)
        com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(preferences).load()
    }

    fun startHistory(days: Int, now: Long = System.currentTimeMillis()) = lock.withLock {
        check(!closed)
        require(days in listOf(7, 30, 90))
        val banks = bankChoices().filter { it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED }.map { it.bankId }.toSet()
        require(banks.isNotEmpty())
        val session = com.baraa.masroof.lab.beta.application.history.HistorySession(
            maxOf(0, now - days * com.baraa.masroof.lab.beta.application.history.HistorySession.DAY), now, banks)
        com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(preferences).save(session)
        session
    }

    fun stepHistory() = lock.withLock {
        check(!closed)
        val workflow = evidenceWorkflow()
        com.baraa.masroof.lab.beta.application.history.BetaHistoryImport(
            com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(preferences),
            com.baraa.masroof.lab.beta.data.AndroidHistorySource(appContext),
            { request, messages -> runBlocking { workflow.importBatch(request, messages) } }).step()
    }

    /** Safe bounded metadata page; no raw text enters presentation. */
    fun evidencePage(afterId: String = "", limit: Int = 25) = lock.withLock {
        check(!closed)
        require(limit in 1..100)
        runBlocking {
            val store = com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database)
            database.evidenceDao().idsAfter(afterId, limit).map { store.summary(it) }
        }
    }

    fun confirmProposal(proposal: com.baraa.masroof.lab.beta.application.BetaAssetProposal, draft: ManualAssetDraft): OwnedAsset = lock.withLock {
        check(!closed)
        require(draft.id == proposal.id && !draft.editing)
        require(bankChoices().any { it.bankId == proposal.id.bank && it.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED })
        runBlocking { database.withTransaction {
            val store = com.baraa.masroof.lab.beta.data.RoomBetaEvidenceStore(database)
            require(proposal.copy(confirmedAssetId = null) in store.discoveryProposals(proposal.evidenceId)) { "Stale proposal" }
            require(store.proposalBinding(proposal).let { it == null || it == draft.id }) { "Already bound to another asset" }
            BetaManualAssetWorkflow(BetaAssetRegistry(database)).save(draft).also { store.recordProposalConfirmation(proposal) }
        } }
    }

    fun linkExistingProposal(proposal: com.baraa.masroof.lab.beta.application.BetaAssetProposal, expectedAsset: OwnedAsset, ownedConfirmed: Boolean): OwnedAsset = lock.withLock {
        check(!closed)
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
        runBlocking {
            val workflow = BetaBankSelectionWorkflow(RoomBetaBankSelectionStore(database))
            if (enabled) workflow.enable(bank) else workflow.disable(bank)
        }
    }

    fun saveManualAsset(draft: ManualAssetDraft): OwnedAsset = lock.withLock {
        check(!closed)
        runBlocking { BetaManualAssetWorkflow(BetaAssetRegistry(database)).save(draft) }
    }

    /** Explicit Beta ownership registration; shares the backup/reset lifetime lock. */
    fun registerAsset(asset: OwnedAsset, aliases: List<BetaAssetAlias> = emptyList()) = lock.withLock {
        check(!closed)
        runBlocking { BetaAssetRegistry(database).register(asset, aliases) }
    }

    fun assets(): List<OwnedAsset> = lock.withLock {
        check(!closed)
        runBlocking { BetaAssetRegistry(database).all() }
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
            copyLiveDatabaseToBackup()
            exportPreferences()
        } finally {
            database = openDatabase()
        }
    }

    fun restore(): Boolean = lock.withLock {
        check(!closed)
        database.close()
        try {
            restoreOffline(appContext)
        } finally {
            database = openDatabase()
        }
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

    fun backupDatabaseFile(): File = backupDatabase(appContext)

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
        const val PREFERENCE_PROBE_KEY: String = "beta_preference_probe"

        fun restoreOffline(context: Context): Boolean {
            val appContext = context.applicationContext
            val backup = backupDatabase(appContext)
            val prefsBackup = backupPreferences(appContext)
            if (!backup.isFile || !prefsBackup.isFile) return false
            BetaEnvironment.requireBackupDatabaseName(backup.name)
            BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.DATABASE_NAME)
            appContext.deleteDatabase(BetaEnvironment.DATABASE_NAME)
            backup.copyTo(appContext.getDatabasePath(BetaEnvironment.DATABASE_NAME), overwrite = true)
            importPreferences(appContext, prefsBackup)
            return true
        }

        fun resetOffline(context: Context) {
            val appContext = context.applicationContext
            BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.DATABASE_NAME)
            BetaEnvironment.requireBetaPreferenceName(BetaEnvironment.PREFS_NAME)
            appContext.deleteDatabase(BetaEnvironment.DATABASE_NAME)
            appContext.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            backupDatabase(appContext).delete()
            backupPreferences(appContext).delete()
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
