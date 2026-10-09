package com.baraa.masroof.application.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocalePreferences
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsThemePreferencesRepository
import com.baraa.masroof.data.room.MasroofDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe replacement of the live Room file.
 *
 * Import writes a durable stage before each destructive rename. Each new stage
 * is fsynced to a temporary journal, then atomically replaces the previous
 * journal. [recover] runs before Room opens the database and finishes on one
 * pair: the original database with its preferences, or a fully committed import
 * with its preferences. A database file that is not valid SQLite, with no valid
 * rollback, fails closed instead of letting Room create an empty database.
 *
 * Preferences and maintenance markers are one commit with the new database.
 * A preexisting rollback is moved aside for the attempt and deleted only after
 * [Stage.COMMITTED].
 */
object DatabaseRestoreRecovery {
    enum class Stage {
        PREPARED,
        OLD_PARKED,
        NEW_INSTALLED,
        ORIGINAL_SELECTED,
        COMMITTED,
    }

    /** Points where a rollback onto the original database can be interrupted. */
    internal enum class OriginalRestoreStep {
        BEFORE_MAIN,
        AFTER_MAIN,
        AFTER_WAL,
        AFTER_SHM,
        AFTER_DATABASE,
        AFTER_PREFERENCES,
    }

    /**
     * Test seam. Runs during rollback onto the original database.
     * Production leaves this null.
     */
    internal var afterOriginalRestoreStep: ((OriginalRestoreStep) -> Unit)? = null

    /** Counts full integrity scans. Normal startup must not increment this. */
    internal var integrityCheckCount: Int = 0

    class ProcessTerminated(stage: Stage) : Error("Database restore terminated after $stage")

    class IncompleteRestoreException(message: String) : IllegalStateException(message)

    /**
     * Test seam. Runs after the next journal is fsynced and before it replaces
     * the previous journal. Production leaves this null.
     */
    internal var afterJournalTempDurable: ((Stage) -> Unit)? = null

    internal data class Journal(
        val stage: Stage,
        val preservedRollback: Boolean,
    )

    internal data class PreferenceSnapshot(
        val onboardingStarted: Boolean,
        val onboardingCompleted: Boolean,
        val historicalImportStartEpochMillis: Long?,
        val historicalImportCompleted: Boolean,
        val languageTag: String,
        val themeMode: String,
        val reparsedSchemaVersion: Int?,
        val transferIntegrityRepairVersion: Int?,
    )

    fun recover(context: Context) {
        val live = liveDatabase(context)
        live.parentFile?.mkdirs()
        when (val read = readJournalState(live)) {
            JournalRead.Absent -> recoverWithoutJournal(live)
            JournalRead.Corrupt -> recoverCorruptJournal(context, live)
            is JournalRead.Ready -> when (read.journal.stage) {
                Stage.PREPARED -> recoverPrepared(context, live, read.journal)
                Stage.OLD_PARKED -> recoverParkedOriginal(context, live, read.journal)
                Stage.NEW_INSTALLED -> recoverNewInstalled(context, live, read.journal)
                Stage.ORIGINAL_SELECTED -> finishOriginalSelection(context, live, read.journal)
                Stage.COMMITTED -> cleanupCommitted(live)
            }
        }
        journalTempFile(live).delete()
    }

    fun liveDatabase(context: Context): File = context.getDatabasePath(MasroofDatabase.NAME)

    fun incomingFile(live: File): File = File(live.parentFile, live.name + INCOMING_SUFFIX)

    internal fun rollbackFile(live: File): File = File(live.path + ROLLBACK_SUFFIX)

    internal fun capturePreferences(context: Context): PreferenceSnapshot {
        val onboarding = prefs(context, SharedPrefsOnboardingPreferencesRepository.PREFS_NAME)
        val locale = prefs(context, SharedPrefsAppLocaleRepository.PREFS_NAME)
        val theme = prefs(context, SharedPrefsThemePreferencesRepository.PREFS_NAME)
        val maintenance = prefs(context, MaintenancePreferences.PREFS_NAME)
        val importStart = if (onboarding.contains(KEY_IMPORT_START_EPOCH_MILLIS)) {
            onboarding.getLong(KEY_IMPORT_START_EPOCH_MILLIS, 0L)
        } else {
            null
        }
        return PreferenceSnapshot(
            onboardingStarted = onboarding.getBoolean(KEY_ONBOARDING_STARTED, false),
            onboardingCompleted = onboarding.getBoolean(KEY_ONBOARDING_COMPLETED, false),
            historicalImportStartEpochMillis = importStart,
            historicalImportCompleted = onboarding.getBoolean(KEY_IMPORT_COMPLETED, false),
            languageTag = locale.getString(AppLocalePreferences.KEY_LANGUAGE_TAG, AppLocale.DEFAULT_TAG)
                ?: AppLocale.DEFAULT_TAG,
            themeMode = theme.getString(SharedPrefsThemePreferencesRepository.KEY_THEME_MODE, ThemeMode.DEFAULT.name)
                ?: ThemeMode.DEFAULT.name,
            reparsedSchemaVersion = optionalInt(maintenance, MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION),
            transferIntegrityRepairVersion = optionalInt(
                maintenance,
                MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION,
            ),
        )
    }

    internal fun incomingPreferences(snapshot: BackupPreferencesSnapshot): PreferenceSnapshot =
        PreferenceSnapshot(
            onboardingStarted = snapshot.onboardingStarted,
            onboardingCompleted = snapshot.onboardingCompleted,
            historicalImportStartEpochMillis = snapshot.historicalImportStartEpochMillis,
            historicalImportCompleted = snapshot.historicalImportCompleted,
            languageTag = snapshot.languageTag,
            themeMode = snapshot.themeMode,
            reparsedSchemaVersion = null,
            transferIntegrityRepairVersion = null,
        )

    internal fun writeSnapshots(live: File, original: PreferenceSnapshot, incoming: PreferenceSnapshot) {
        writeDurable(originalPrefsFile(live), encodePreferences(original))
        writeDurable(incomingPrefsFile(live), encodePreferences(incoming))
    }

    internal fun hasPreexistingRollback(live: File): Boolean {
        val rollback = rollbackFile(live)
        return rollback.exists() || hasSidecar(rollback)
    }

    internal fun preserveExistingRollback(live: File): Boolean {
        val rollback = rollbackFile(live)
        if (!rollback.exists() && !hasSidecar(rollback)) return false
        val preserved = preservedRollbackFile(live)
        check(!preserved.exists() && !hasSidecar(preserved)) {
            "Cannot start restore while a preserved rollback already exists"
        }
        if (rollback.exists() && !rollback.renameTo(preserved)) {
            error("Cannot move the preexisting rollback aside")
        }
        moveSidecars(rollback, preserved)
        return preserved.exists() || hasSidecar(preserved)
    }

    internal fun writeJournal(live: File, stage: Stage, preservedRollback: Boolean) {
        val temp = journalTempFile(live)
        writeDurable(temp, journalText(stage, preservedRollback))
        afterJournalTempDurable?.invoke(stage)
        replaceJournal(temp, journalFile(live))
    }

    internal fun parkOriginal(live: File) {
        val rollback = rollbackFile(live)
        check(!rollback.exists()) { "Rollback path is not free" }
        if (live.exists() && !live.renameTo(rollback)) {
            error("Cannot preserve the current database before restore")
        }
        moveSidecars(live, rollback)
    }

    /**
     * In-process import failure. A committed import is only cleaned up.
     * Every earlier stage puts the original database and preferences back.
     */
    internal fun failImport(context: Context, live: File) {
        when (val read = readJournalState(live)) {
            JournalRead.Absent -> return
            JournalRead.Corrupt -> recoverCorruptJournal(context, live)
            is JournalRead.Ready -> when (read.journal.stage) {
                Stage.PREPARED -> abortPrepared(context, live, read.journal)
                Stage.OLD_PARKED,
                Stage.NEW_INSTALLED,
                -> restoreOriginalFiles(context, live, read.journal)
                Stage.ORIGINAL_SELECTED -> finishOriginalSelection(context, live, read.journal)
                Stage.COMMITTED -> cleanupCommitted(live)
            }
        }
    }

    internal fun cleanupCommitted(live: File) {
        if (!isSqliteOk(live)) {
            val rollback = rollbackFile(live)
            if (isSqliteOk(rollback)) {
                discardDatabase(live)
                restoreRollbackOverLive(live)
            } else {
                throw IncompleteRestoreException(
                    "Committed restore has no valid database",
                )
            }
        }
        discardDatabase(rollbackFile(live))
        discardDatabase(preservedRollbackFile(live))
        discardDatabase(incomingFile(live))
        originalPrefsFile(live).delete()
        incomingPrefsFile(live).delete()
        journalFile(live).delete()
    }

    internal fun deleteRestoreArtifacts(live: File) {
        discardDatabase(rollbackFile(live))
        discardDatabase(preservedRollbackFile(live))
        discardDatabase(incomingFile(live))
        originalPrefsFile(live).delete()
        incomingPrefsFile(live).delete()
        journalFile(live).delete()
        journalTempFile(live).delete()
    }

    private fun recoverWithoutJournal(live: File) {
        if (!hasRestoreEvidence(live)) {
            if (!live.exists() || looksLikeSqlite(live)) return
            throw IncompleteRestoreException(
                "A database file exists but neither copy is a valid SQLite database",
            )
        }
        if (isSqliteOk(live)) {
            reclaimOrphanedPreservedRollback(live)
            return
        }
        val rollback = rollbackFile(live)
        val preserved = preservedRollbackFile(live)
        if (!live.exists() && !rollback.exists() && !preserved.exists() && !hasSidecar(preserved)) {
            return
        }
        if (isSqliteOk(rollback)) {
            discardDatabase(live)
            restoreRollbackOverLive(live)
            return
        }
        reclaimOrphanedPreservedRollback(live)
        if (isSqliteOk(rollbackFile(live))) {
            discardDatabase(live)
            restoreRollbackOverLive(live)
            return
        }
        throw IncompleteRestoreException(
            "A database file exists but neither copy is a valid SQLite database",
        )
    }

    /**
     * A torn journal is not a commit marker. A parked rollback is restored
     * with the original preference snapshot. A live database that was never
     * parked keeps that snapshot and does not receive imported preferences.
     */
    private fun recoverCorruptJournal(context: Context, live: File) {
        val rollbackOk = isSqliteOk(rollbackFile(live))
        val liveOk = isSqliteOk(live)
        when {
            rollbackOk -> restoreOriginalFiles(
                context,
                live,
                Journal(
                    stage = Stage.OLD_PARKED,
                    preservedRollback = preservedRollbackFile(live).exists() ||
                        hasSidecar(preservedRollbackFile(live)),
                ),
            )
            liveOk -> recoverUnparkedLive(context, live)
            else -> throw IncompleteRestoreException(
                "Restore journal is unreadable and neither database is valid",
            )
        }
    }

    /**
     * No parked rollback means the live file was not replaced. It stays the
     * original database and must keep the original preference snapshot.
     * Imported preferences are applied only after NEW_INSTALLED is durable.
     */
    private fun recoverUnparkedLive(context: Context, live: File) {
        reclaimOrphanedPreservedRollback(live)
        val originalPrefs = readPreferences(originalPrefsFile(live))
        if (originalPrefs != null) {
            applyPreferences(context, originalPrefs)
            discardDatabase(incomingFile(live))
            deleteSnapshots(live)
            journalFile(live).delete()
            return
        }
        if (readPreferences(incomingPrefsFile(live)) != null) {
            throw IncompleteRestoreException(
                "Unreadable journal cannot pair the live database with imported preferences",
            )
        }
        journalFile(live).delete()
    }

    private fun reclaimOrphanedPreservedRollback(live: File) {
        restorePreservedRollback(
            live,
            Journal(stage = Stage.PREPARED, preservedRollback = true),
        )
    }

    private fun recoverPrepared(context: Context, live: File, journal: Journal) {
        if (isSqliteOk(live)) {
            discardDatabase(incomingFile(live))
            restorePreservedRollback(live, journal)
            deleteSnapshots(live)
            journalFile(live).delete()
            return
        }
        if (isSqliteOk(rollbackFile(live))) {
            restoreOriginalFiles(context, live, journal)
            return
        }
        throw IncompleteRestoreException("Prepared restore has no valid original database")
    }

    private fun recoverParkedOriginal(context: Context, live: File, journal: Journal) {
        if (!isSqliteOk(rollbackFile(live))) {
            throw IncompleteRestoreException("Parked original database is not valid")
        }
        restoreOriginalFiles(context, live, journal)
    }

    private fun recoverNewInstalled(context: Context, live: File, journal: Journal) {
        val incomingPrefs = readPreferences(incomingPrefsFile(live))
        if (isSqliteOk(live) && incomingPrefs != null) {
            applyPreferences(context, incomingPrefs)
            writeJournal(live, Stage.COMMITTED, journal.preservedRollback)
            cleanupCommitted(live)
            return
        }
        if (isSqliteOk(rollbackFile(live))) {
            restoreOriginalFiles(context, live, journal)
            return
        }
        throw IncompleteRestoreException("Installed restore has no valid database")
    }

    private fun abortPrepared(context: Context, live: File, journal: Journal) {
        if (!isSqliteOk(live) && isSqliteOk(rollbackFile(live))) {
            restoreOriginalFiles(context, live, journal)
            return
        }
        discardDatabase(incomingFile(live))
        restorePreservedRollback(live, journal)
        deleteSnapshots(live)
        journalFile(live).delete()
    }

    internal fun restoreOriginalFilesForTest(context: Context, live: File) {
        val journal = when (val read = readJournalState(live)) {
            is JournalRead.Ready -> read.journal
            else -> Journal(stage = Stage.NEW_INSTALLED, preservedRollback = false)
        }
        restoreOriginalFiles(context, live, journal)
    }

    private fun restoreOriginalFiles(context: Context, live: File, journal: Journal) {
        writeJournal(live, Stage.ORIGINAL_SELECTED, journal.preservedRollback)
        finishOriginalSelection(
            context,
            live,
            journal.copy(stage = Stage.ORIGINAL_SELECTED),
        )
    }

    /**
     * [Stage.ORIGINAL_SELECTED] means the original database won. Repeating this
     * finishes the main file, WAL, SHM, original preferences, and cleanup
     * without applying the imported preference snapshot.
     */
    private fun finishOriginalSelection(context: Context, live: File, journal: Journal) {
        signalOriginalRestore(OriginalRestoreStep.BEFORE_MAIN)
        val rollback = rollbackFile(live)
        if (rollback.exists()) {
            discardDatabase(live)
            moveBundlePart(rollback, live)
        }
        signalOriginalRestore(OriginalRestoreStep.AFTER_MAIN)
        moveBundlePart(File(rollback.path + "-wal"), File(live.path + "-wal"))
        signalOriginalRestore(OriginalRestoreStep.AFTER_WAL)
        moveBundlePart(File(rollback.path + "-shm"), File(live.path + "-shm"))
        signalOriginalRestore(OriginalRestoreStep.AFTER_SHM)
        moveBundlePart(File(rollback.path + "-journal"), File(live.path + "-journal"))
        signalOriginalRestore(OriginalRestoreStep.AFTER_DATABASE)
        readPreferences(originalPrefsFile(live))?.let { applyPreferences(context, it) }
        signalOriginalRestore(OriginalRestoreStep.AFTER_PREFERENCES)
        discardDatabase(incomingFile(live))
        restorePreservedRollback(live, journal)
        deleteSnapshots(live)
        journalFile(live).delete()
    }

    private fun signalOriginalRestore(step: OriginalRestoreStep) {
        afterOriginalRestoreStep?.invoke(step)
    }

    /**
     * A crash can move the preexisting rollback main file and its sidecars
     * separately. Put every preserved part and every part still at the old
     * path back into one rollback bundle. A part that exists in both places
     * is left where it is and recovery fails closed.
     */
    private fun restorePreservedRollback(live: File, journal: Journal) {
        if (!journal.preservedRollback) return
        recombineRollbackBundle(preservedRollbackFile(live), rollbackFile(live))
    }

    private fun recombineRollbackBundle(preserved: File, rollback: File) {
        if (!preserved.exists() && !hasSidecar(preserved)) return
        moveBundlePart(preserved, rollback)
        SIDECARS.forEach { suffix ->
            moveBundlePart(File(preserved.path + suffix), File(rollback.path + suffix))
        }
    }

    private fun restoreRollbackOverLive(live: File) {
        val rollback = rollbackFile(live)
        if (rollback.exists() && !rollback.renameTo(live)) {
            error("Cannot restore the original database")
        }
        moveSidecars(rollback, live)
    }

    private fun moveBundlePart(from: File, to: File) {
        if (!from.exists()) return
        if (to.exists()) {
            throw IncompleteRestoreException(
                "Rollback bundle is split and both copies of ${to.name} exist",
            )
        }
        if (!from.renameTo(to)) {
            error("Cannot recombine the preexisting rollback")
        }
    }

    private fun applyPreferences(context: Context, snapshot: PreferenceSnapshot) {
        val onboarding = prefs(context, SharedPrefsOnboardingPreferencesRepository.PREFS_NAME)
        val locale = prefs(context, SharedPrefsAppLocaleRepository.PREFS_NAME)
        val theme = prefs(context, SharedPrefsThemePreferencesRepository.PREFS_NAME)
        val maintenance = prefs(context, MaintenancePreferences.PREFS_NAME)
        val onboardingEditor = onboarding.edit()
        onboardingEditor.putBoolean(KEY_ONBOARDING_STARTED, snapshot.onboardingStarted)
        onboardingEditor.putBoolean(KEY_ONBOARDING_COMPLETED, snapshot.onboardingCompleted)
        onboardingEditor.putBoolean(KEY_IMPORT_COMPLETED, snapshot.historicalImportCompleted)
        val start = snapshot.historicalImportStartEpochMillis
        if (start == null) {
            onboardingEditor.remove(KEY_IMPORT_START_EPOCH_MILLIS)
        } else {
            onboardingEditor.putLong(KEY_IMPORT_START_EPOCH_MILLIS, start)
        }
        check(onboardingEditor.commit()) { "Cannot restore onboarding preferences" }
        val language = when (snapshot.languageTag) {
            AppLocale.TAG_EN -> AppLocale.TAG_EN
            else -> AppLocale.TAG_AR
        }
        val localeCommitted = locale.edit()
            .putString(AppLocalePreferences.KEY_LANGUAGE_TAG, language)
            .commit()
        check(localeCommitted) { "Cannot restore locale preferences" }
        val themeCommitted = theme.edit()
            .putString(
                SharedPrefsThemePreferencesRepository.KEY_THEME_MODE,
                ThemeMode.fromStorage(snapshot.themeMode).name,
            )
            .commit()
        check(themeCommitted) { "Cannot restore theme preferences" }
        val maintenanceEditor = maintenance.edit()
        putOrRemove(
            maintenanceEditor,
            MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION,
            snapshot.reparsedSchemaVersion,
        )
        putOrRemove(
            maintenanceEditor,
            MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION,
            snapshot.transferIntegrityRepairVersion,
        )
        check(maintenanceEditor.commit()) { "Cannot restore maintenance markers" }
    }

    private fun putOrRemove(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Int?,
    ) {
        if (value == null) editor.remove(key) else editor.putInt(key, value)
    }

    private fun optionalInt(prefs: android.content.SharedPreferences, key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    private fun hasRestoreEvidence(live: File): Boolean {
        val rollback = rollbackFile(live)
        val preserved = preservedRollbackFile(live)
        val incoming = incomingFile(live)
        return rollback.exists() || hasSidecar(rollback) ||
            preserved.exists() || hasSidecar(preserved) ||
            incoming.exists() || hasSidecar(incoming) ||
            originalPrefsFile(live).exists() ||
            incomingPrefsFile(live).exists() ||
            journalTempFile(live).exists()
    }

    /** Header only. Does not open SQLite or scan pages. */
    private fun looksLikeSqlite(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(SQLITE_HEADER.size)
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read < 0) return false
                    offset += read
                }
                header.contentEquals(SQLITE_HEADER)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isSqliteOk(file: File): Boolean {
        if (!looksLikeSqlite(file)) return false
        integrityCheckCount += 1
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readJournalState(live: File): JournalRead {
        val file = journalFile(live)
        if (!file.exists()) return JournalRead.Absent
        val text = try {
            if (!file.isFile) return JournalRead.Corrupt
            file.readText()
        } catch (_: Exception) {
            return JournalRead.Corrupt
        }
        val match = JOURNAL_PATTERN.matchEntire(text) ?: return JournalRead.Corrupt
        val stage = runCatching { Stage.valueOf(match.groupValues[1]) }.getOrNull()
            ?: return JournalRead.Corrupt
        return JournalRead.Ready(
            Journal(
                stage = stage,
                preservedRollback = match.groupValues[2] == "true",
            ),
        )
    }

    private fun journalText(stage: Stage, preservedRollback: Boolean): String =
        "stage=${stage.name}\npreservedRollback=$preservedRollback\n"

    private fun replaceJournal(temp: File, destination: File) {
        try {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            temp.delete()
            throw IllegalStateException("Cannot replace the restore journal atomically", error)
        }
    }

    private fun readPreferences(file: File): PreferenceSnapshot? {
        if (!file.isFile) return null
        val values = file.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        if (values.isEmpty()) return null
        return PreferenceSnapshot(
            onboardingStarted = values["onboardingStarted"] == "true",
            onboardingCompleted = values["onboardingCompleted"] == "true",
            historicalImportStartEpochMillis = values["historicalImportStartEpochMillis"]?.toLongOrNull(),
            historicalImportCompleted = values["historicalImportCompleted"] == "true",
            languageTag = values["languageTag"].orEmpty().ifBlank { AppLocale.DEFAULT_TAG },
            themeMode = values["themeMode"].orEmpty().ifBlank { ThemeMode.DEFAULT.name },
            reparsedSchemaVersion = values["reparsedSchemaVersion"]?.toIntOrNull(),
            transferIntegrityRepairVersion = values["transferIntegrityRepairVersion"]?.toIntOrNull(),
        )
    }

    private fun encodePreferences(snapshot: PreferenceSnapshot): String = buildString {
        appendLine("onboardingStarted=${snapshot.onboardingStarted}")
        appendLine("onboardingCompleted=${snapshot.onboardingCompleted}")
        appendLine("historicalImportStartEpochMillis=${snapshot.historicalImportStartEpochMillis ?: ""}")
        appendLine("historicalImportCompleted=${snapshot.historicalImportCompleted}")
        appendLine("languageTag=${snapshot.languageTag}")
        appendLine("themeMode=${snapshot.themeMode}")
        appendLine("reparsedSchemaVersion=${snapshot.reparsedSchemaVersion ?: ""}")
        appendLine("transferIntegrityRepairVersion=${snapshot.transferIntegrityRepairVersion ?: ""}")
    }

    private fun writeDurable(file: File, text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun deleteSnapshots(live: File) {
        originalPrefsFile(live).delete()
        incomingPrefsFile(live).delete()
    }

    private fun discardDatabase(file: File) {
        deleteSidecars(file)
        if (file.exists()) file.delete()
    }

    private fun deleteSidecars(file: File) {
        SIDECARS.forEach { suffix -> File(file.path + suffix).delete() }
    }

    private fun hasSidecar(file: File): Boolean = SIDECARS.any { File(file.path + it).exists() }

    private fun moveSidecars(from: File, to: File) {
        SIDECARS.forEach { suffix ->
            val source = File(from.path + suffix)
            if (!source.exists()) return@forEach
            val dest = File(to.path + suffix)
            if (dest.exists()) dest.delete()
            check(source.renameTo(dest)) { "Cannot move database sidecar $suffix" }
        }
    }

    private fun prefs(context: Context, name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun journalFile(live: File): File = File(live.path + JOURNAL_SUFFIX)

    private fun journalTempFile(live: File): File = File(live.path + JOURNAL_SUFFIX + ".tmp")

    private fun originalPrefsFile(live: File): File = File(live.path + ORIGINAL_PREFS_SUFFIX)

    private fun incomingPrefsFile(live: File): File = File(live.path + INCOMING_PREFS_SUFFIX)

    private fun preservedRollbackFile(live: File): File = File(live.path + PRESERVED_ROLLBACK_SUFFIX)

    private const val INCOMING_SUFFIX = ".incoming"
    private const val ROLLBACK_SUFFIX = ".rollback"
    private const val PRESERVED_ROLLBACK_SUFFIX = ".rollback.preserved"
    private const val JOURNAL_SUFFIX = ".restore-journal"
    private val JOURNAL_PATTERN = Regex("""stage=([A-Z_]+)\npreservedRollback=(true|false)\n""")

    private sealed interface JournalRead {
        data object Absent : JournalRead
        data object Corrupt : JournalRead
        data class Ready(val journal: Journal) : JournalRead
    }
    private const val ORIGINAL_PREFS_SUFFIX = ".prefs-original"
    private const val INCOMING_PREFS_SUFFIX = ".prefs-incoming"
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    private val SQLITE_HEADER = byteArrayOf(
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    private const val KEY_ONBOARDING_STARTED = "onboarding_started"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_IMPORT_START_EPOCH_MILLIS = "historical_import_start_epoch_millis"
    private const val KEY_IMPORT_COMPLETED = "historical_import_completed"
}
