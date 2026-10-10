package com.baraa.masroof.application.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsThemePreferencesRepository
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DatabaseBackupService(
    private val appContext: Context,
    private val database: MasroofDatabase,
    private val closeDatabase: () -> Unit,
    private val appVersionName: String,
    private val appLogService: AppLogService? = null,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val restartProcess: () -> Unit = { defaultRestartProcess(appContext) },
    private val beforeValidatedInstall: () -> Unit = {},
    private val afterRestoreStage: (DatabaseRestoreRecovery.Stage) -> Unit = {},
    private val maintenancePreferences: SharedPreferences? = null,
    /**
     * Production export tries `VACUUM INTO` when the device SQLite supports it.
     * Tests set this to false to exercise the minSdk 26 quiesce path on a newer SQLite.
     */
    private val onlineBackupEnabled: Boolean = true,
    /**
     * Uncompressed ZIP caps from M4, applied before the live database is closed.
     * Tests inject a smaller [BackupArchiveLimits] to prove a zip bomb stops.
     */
    private val archiveLimits: BackupArchiveLimits = BackupArchiveLimits.PRODUCTION,
    /** Production uses [BackupEnvelope.DEFAULT_ITERATIONS]. Tests inject a lower count. */
    private val kdfIterations: Int = BackupEnvelope.DEFAULT_ITERATIONS,
) : DatabaseBackupGateway {
    init {
        require(kdfIterations in 1..BackupEnvelope.MAX_ITERATIONS) {
            "KDF iteration count is outside the supported range"
        }
        sweepAbandonedStaging()
    }
    override suspend fun exportTo(destination: Uri, passphrase: CharArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            val secret = passphrase.copyOf()
            passphrase.fill('\u0000')
            try {
                if (secret.isEmpty()) {
                    logExportFailure(BackupFailureCategory.PASSPHRASE_REQUIRED)
                    return@withContext Result.failure(
                        BackupFailureException(BackupFailureCategory.PASSPHRASE_REQUIRED),
                    )
                }
                runCatching {
                    val staging = createStagingDir("export")
                    try {
                        val dbCopy = File(staging, BackupPackageFormat.DATABASE_ENTRY)
                        writeConsistentSnapshot(dbCopy)
                        verifyIsolatedSnapshot(dbCopy)

                        val exportedAt = clockEpochMillis()
                        val manifest = BackupManifest(
                            formatVersion = BackupPackageFormat.FORMAT_VERSION,
                            appVersionName = appVersionName,
                            roomVersion = MasroofDatabase.VERSION,
                            identityHash = MasroofDatabase.IDENTITY_HASH,
                            exportedAtEpochMillis = exportedAt,
                        )
                        File(staging, BackupPackageFormat.MANIFEST_ENTRY)
                            .writeText(BackupPackageCodec.encodeManifest(manifest))
                        File(staging, BackupPackageFormat.PREFERENCES_ENTRY)
                            .writeText(BackupPackageCodec.encodePreferences(capturePreferences()))

                        writeEncryptedPackage(staging, destination, secret)
                    } finally {
                        SensitiveFileCleanup.delete(staging)
                    }
                }.onSuccess {
                    appLogService?.info(AppLogCategories.BACKUP, "Database export succeeded")
                }.onFailure { error ->
                    logExportFailure(
                        (error as? BackupFailureException)?.category ?: BackupFailureCategory.EXPORT_FAILED,
                    )
                }
            } finally {
                secret.fill('\u0000')
            }
        }

    override suspend fun inspect(source: Uri): BackupPackageKind = withContext(Dispatchers.IO) {
        runCatching {
            val raw = appContext.contentResolver.openInputStream(source) ?: return@withContext BackupPackageKind.UNRECOGNIZED
            raw.use { stream ->
                val peek = ByteArray(BackupEnvelope.HEADER_SIZE)
                val read = readPrefix(stream, peek)
                when {
                    BackupPackageFormat.looksLikeZip(peek, read) -> BackupPackageKind.LEGACY_PLAINTEXT
                    BackupEnvelope.looksLikeEnvelope(peek, read) -> BackupPackageKind.ENCRYPTED
                    else -> BackupPackageKind.UNRECOGNIZED
                }
            }
        }.getOrDefault(BackupPackageKind.UNRECOGNIZED)
    }

    override suspend fun importFrom(
        source: Uri,
        passphrase: CharArray,
        confirmLegacyPlaintext: Boolean,
    ): BackupImportOutcome = withContext(Dispatchers.IO) {
        val secret = passphrase.copyOf()
        passphrase.fill('\u0000')
        var staging: File? = null
        try {
            val raw = appContext.contentResolver.openInputStream(source)
                ?: return@withContext loggedImport(
                    BackupImportOutcome.Failed,
                    BackupFailureCategory.IMPORT_FAILED,
                )
            var legacyPlaintext = false
            BufferedInputStream(raw).use { buffered ->
                buffered.mark(BackupEnvelope.PEEK_MARK)
                val peek = ByteArray(BackupEnvelope.HEADER_SIZE)
                val read = readPrefix(buffered, peek)
                buffered.reset()
                when {
                    BackupEnvelope.looksLikeEnvelope(peek, read) -> {
                        val directory = createStagingDir("import")
                        staging = directory
                        val plainZip = createOwnerOnlyFile(directory, "decrypted-package.zip")
                        try {
                            FileOutputStream(plainZip).use { plain ->
                                BackupEnvelope.decrypt(
                                    input = buffered,
                                    output = plain,
                                    passphrase = secret,
                                    maxPlaintextBytes = archiveLimits.maxTotalUncompressedBytes,
                                )
                            }
                        } catch (error: BackupFailureException) {
                            wipeFile(plainZip)
                            plainZip.delete()
                            throw error
                        }
                        try {
                            FileInputStream(plainZip).use { plain ->
                                extractValidated(plain, directory)
                            }
                        } finally {
                            wipeFile(plainZip)
                            plainZip.delete()
                        }
                    }
                    else -> {
                        // Plaintext ZIPs and unrecognized bytes share M4's validator.
                        // A ZIP that passes validation still needs explicit legacy
                        // confirmation before the live database is closed.
                        legacyPlaintext = BackupPackageFormat.looksLikeZip(peek, read)
                        val directory = createStagingDir("import")
                        staging = directory
                        extractValidated(buffered, directory)
                    }
                }
            }
            if (legacyPlaintext && !confirmLegacyPlaintext) {
                return@withContext BackupImportOutcome.LegacyConfirmationRequired
            }
            val packageDir = staging ?: return@withContext loggedImport(
                BackupImportOutcome.InvalidPackage,
                BackupFailureCategory.INVALID_PACKAGE,
            )
            val manifestFile = File(packageDir, BackupPackageFormat.MANIFEST_ENTRY)
            val dbFile = File(packageDir, BackupPackageFormat.DATABASE_ENTRY)
            val prefsFile = File(packageDir, BackupPackageFormat.PREFERENCES_ENTRY)
            if (!manifestFile.exists() || !dbFile.exists() || !prefsFile.exists()) {
                return@withContext BackupImportOutcome.InvalidPackage
            }

            val manifest = runCatching {
                BackupPackageCodec.decodeManifest(manifestFile.readText())
            }.getOrElse { return@withContext BackupImportOutcome.InvalidPackage }

            val importableVersions = MasroofDatabase.IMPORTABLE_BACKUP_VERSIONS
            if (!BackupPackageCodec.validateManifestForImport(
                    manifest = manifest,
                    targetRoomVersion = MasroofDatabase.VERSION,
                    targetIdentityHash = MasroofDatabase.IDENTITY_HASH,
                    importableVersions = importableVersions,
                )
            ) {
                return@withContext BackupImportOutcome.InvalidPackage
            }

            val expectedDbIdentityHash = BackupPackageCodec.expectedIdentityHashForImport(
                manifest = manifest,
                targetRoomVersion = MasroofDatabase.VERSION,
                targetIdentityHash = MasroofDatabase.IDENTITY_HASH,
                importableVersions = importableVersions,
            ) ?: return@withContext BackupImportOutcome.InvalidPackage

            val identityFromDb = readIdentityHash(dbFile)
            if (identityFromDb != expectedDbIdentityHash) {
                return@withContext BackupImportOutcome.InvalidPackage
            }

            val preferences = runCatching {
                BackupPackageCodec.decodePreferences(prefsFile.readText())
            }.getOrElse { return@withContext BackupImportOutcome.InvalidPackage }

            checkpointWal()
            closeDatabase()

            val liveDb = appContext.getDatabasePath(MasroofDatabase.NAME)
            val databasesDir = liveDb.parentFile ?: error("Database directory missing")
            databasesDir.mkdirs()
            DatabaseRestoreRecovery.recover(appContext)
            val incoming = DatabaseRestoreRecovery.incomingFile(liveDb)
            discardDatabaseFiles(incoming)
            try {
                dbFile.copyTo(incoming, overwrite = false)
                openMigrateAndValidate(incoming)
                val preservedRollback = DatabaseRestoreRecovery.hasPreexistingRollback(liveDb)
                DatabaseRestoreRecovery.writeSnapshots(
                    live = liveDb,
                    original = DatabaseRestoreRecovery.capturePreferences(appContext),
                    incoming = DatabaseRestoreRecovery.incomingPreferences(preferences),
                )
                DatabaseRestoreRecovery.writeJournal(
                    liveDb,
                    DatabaseRestoreRecovery.Stage.PREPARED,
                    preservedRollback,
                )
                if (preservedRollback) {
                    DatabaseRestoreRecovery.preserveExistingRollback(liveDb)
                }
                afterRestoreStage(DatabaseRestoreRecovery.Stage.PREPARED)
                beforeValidatedInstall()
                if (!incoming.isFile) {
                    error("Validated import file missing")
                }
                DatabaseRestoreRecovery.parkOriginal(liveDb)
                DatabaseRestoreRecovery.writeJournal(
                    liveDb,
                    DatabaseRestoreRecovery.Stage.OLD_PARKED,
                    preservedRollback,
                )
                afterRestoreStage(DatabaseRestoreRecovery.Stage.OLD_PARKED)
                if (!incoming.renameTo(liveDb)) {
                    error("Cannot replace the live database")
                }
                deleteSidecarFiles(incoming)
                DatabaseRestoreRecovery.writeJournal(
                    liveDb,
                    DatabaseRestoreRecovery.Stage.NEW_INSTALLED,
                    preservedRollback,
                )
                afterRestoreStage(DatabaseRestoreRecovery.Stage.NEW_INSTALLED)
                restorePreferences(preferences)
                resetMaintenanceMarkers()
                DatabaseRestoreRecovery.writeJournal(
                    liveDb,
                    DatabaseRestoreRecovery.Stage.COMMITTED,
                    preservedRollback,
                )
                afterRestoreStage(DatabaseRestoreRecovery.Stage.COMMITTED)
                DatabaseRestoreRecovery.cleanupCommitted(appContext, liveDb)
                SensitiveFileCleanup.delete(packageDir)
                secret.fill('\u0000')
                restartProcess()
                appLogService?.info(AppLogCategories.BACKUP, "Database import succeeded; restart required")
                BackupImportOutcome.SuccessNeedsRestart
            } catch (error: Exception) {
                if (incoming.exists()) discardDatabaseFiles(incoming)
                DatabaseRestoreRecovery.failImport(appContext, liveDb)
                throw error
            }
        } catch (error: BackupArchiveException) {
            appLogService?.error(AppLogCategories.BACKUP, error.category.logMessage())
            BackupImportOutcome.InvalidPackage
        } catch (error: BackupFailureException) {
            logImportFailure(error.category)
            importOutcome(error.category)
        } catch (error: Exception) {
            logImportFailure(BackupFailureCategory.IMPORT_FAILED)
            BackupImportOutcome.Failed
        } finally {
            secret.fill('\u0000')
            staging?.let(SensitiveFileCleanup::delete)
        }
    }

    /**
     * Point-in-time copy used by export. `VACUUM INTO` (SQLite's online backup API) is used
     * when this device's SQLite is new enough to support it. minSdk 26's SQLite has no online
     * backup entry point, so that path quiesces writers, truncates the WAL, and copies the
     * main file while the exclusive lock is held. The live database is not closed or replaced.
     */
    private fun writeConsistentSnapshot(destination: File) {
        val source = database.openHelper.writableDatabase
        val sourcePath = source.path ?: appContext.getDatabasePath(MasroofDatabase.NAME).path
        check(File(sourcePath).isFile) { "Database file missing" }
        destination.parentFile?.mkdirs()
        SensitiveFileCleanup.delete(destination)
        deleteSidecarFiles(destination)
        val wroteOnline = onlineBackupEnabled && writeOnlineBackupIfSupported(sourcePath, destination)
        if (!wroteOnline) {
            writeQuiescedSnapshot(sourcePath, destination)
        }
        makeSnapshotSidecarFree(destination)
        check(destination.isFile && destination.length() > 0L) { "Snapshot file was not written" }
        check(!File(destination.path + "-wal").let { it.exists() && it.length() > 0L }) {
            "Snapshot still depends on a WAL sidecar"
        }
    }

    private fun writeOnlineBackupIfSupported(sourcePath: String, destination: File): Boolean {
        val raw = openSnapshotConnection(sourcePath)
        try {
            if (!supportsOnlineBackup(raw)) return false
            readSnapshotString(raw, "PRAGMA busy_timeout = $SNAPSHOT_BUSY_TIMEOUT_MS")
            try {
                executeVacuumInto(raw, destination)
            } catch (error: android.database.SQLException) {
                SensitiveFileCleanup.delete(destination)
                deleteSidecarFiles(destination)
                if (isOnlineBackupUnsupported(error)) return false
                throw error
            } catch (error: IllegalStateException) {
                SensitiveFileCleanup.delete(destination)
                deleteSidecarFiles(destination)
                if (isOnlineBackupUnsupported(error)) return false
                throw error
            }
            check(destination.isFile && destination.length() > 0L) {
                "Online backup produced no snapshot file"
            }
            return true
        } finally {
            raw.close()
        }
    }

    /**
     * Checkpoint first, then take the write lock and copy only if the WAL is still empty.
     * A writer that lands between those steps is detected and the attempt is repeated.
     * The copy itself runs while writers are blocked, so an automatic checkpoint cannot
     * tear the pages being read.
     */
    private fun writeQuiescedSnapshot(sourcePath: String, destination: File) {
        val raw = openSnapshotConnection(sourcePath)
        try {
            readSnapshotString(raw, "PRAGMA busy_timeout = $SNAPSHOT_BUSY_TIMEOUT_MS")
            var last = "no attempt"
            repeat(CHECKPOINT_ATTEMPTS) {
                if (!checkpointFully(raw)) {
                    last = "checkpoint incomplete"
                    Thread.sleep(CHECKPOINT_RETRY_MS)
                    return@repeat
                }
                if (!beginExclusiveTransaction(raw)) {
                    last = "writers still active"
                    Thread.sleep(CHECKPOINT_RETRY_MS)
                    return@repeat
                }
                try {
                    val wal = File("$sourcePath-wal")
                    val walBytes = if (wal.exists()) wal.length() else 0L
                    if (walBytes > 0L) {
                        last = "wal bytes=$walBytes after checkpoint"
                        return@repeat
                    }
                    File(sourcePath).copyTo(destination, overwrite = true)
                    return
                } finally {
                    raw.endTransaction()
                }
            }
            error("Could not copy a quiescent snapshot ($last)")
        } finally {
            raw.close()
        }
    }

    private fun beginExclusiveTransaction(raw: SQLiteDatabase): Boolean {
        return try {
            raw.beginTransaction()
            true
        } catch (error: android.database.sqlite.SQLiteException) {
            val message = error.message.orEmpty()
            if (!message.contains("locked", ignoreCase = true) &&
                !message.contains("busy", ignoreCase = true)
            ) {
                throw error
            }
            false
        }
    }

    private fun checkpointFully(raw: SQLiteDatabase): Boolean {
        return try {
            raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (!cursor.moveToFirst()) return false
                val busy = cursor.getInt(0)
                val log = cursor.getInt(1)
                val checkpointed = cursor.getInt(2)
                busy == 0 && log == checkpointed
            }
        } catch (error: android.database.sqlite.SQLiteException) {
            val message = error.message.orEmpty()
            if (!message.contains("locked", ignoreCase = true) &&
                !message.contains("busy", ignoreCase = true)
            ) {
                throw error
            }
            false
        }
    }

    private fun makeSnapshotSidecarFree(destination: File) {
        val raw = openSnapshotConnection(destination.path)
        try {
            val mode = readSnapshotString(raw, "PRAGMA journal_mode = DELETE")
            check(
                mode.equals("delete", ignoreCase = true) ||
                    mode.equals("truncate", ignoreCase = true) ||
                    mode.equals("persist", ignoreCase = true),
            ) { "Snapshot journal mode is $mode" }
        } finally {
            raw.close()
        }
        deleteSidecarFiles(destination)
    }

    private fun executeVacuumInto(raw: SQLiteDatabase, destination: File) {
        val sql = "VACUUM INTO ${sqlStringLiteral(destination.absolutePath)}"
        try {
            raw.execSQL(sql)
        } catch (error: IllegalStateException) {
            if (!error.message.orEmpty().contains("rawQuery", ignoreCase = true)) throw error
            raw.rawQuery(sql, null).use { cursor -> cursor.moveToFirst() }
        }
    }

    private fun supportsOnlineBackup(raw: SQLiteDatabase): Boolean {
        val version = readSnapshotString(raw, "SELECT sqlite_version()")
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major > 3 || (major == 3 && minor >= ONLINE_BACKUP_MIN_MINOR)
    }

    private fun isOnlineBackupUnsupported(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("syntax", ignoreCase = true) ||
            message.contains("not authorized", ignoreCase = true) ||
            message.contains("no such", ignoreCase = true)
    }

    private fun openSnapshotConnection(path: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun readSnapshotString(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst()) { "SQLite returned no row" }
            cursor.getString(0)
        }

    private fun sqlStringLiteral(value: String): String = "'${value.replace("'", "''")}'"

    /**
     * Checks the isolated snapshot only. A failure here leaves the live database and
     * preferences untouched; staging is deleted and export returns a failed result.
     */
    private fun verifyIsolatedSnapshot(dbFile: File) {
        check(dbFile.isFile && dbFile.length() > 0L) { "Snapshot file missing" }
        SQLiteDatabase.openDatabase(
            dbFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { snapshot ->
            snapshot.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Snapshot failed integrity_check"
                }
            }
            snapshot.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                check(!cursor.moveToFirst()) { "Snapshot failed foreign_key_check" }
            }
        }
    }

    private fun checkpointWal() {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            cursor.moveToFirst()
        }
    }

    private fun capturePreferences(): BackupPreferencesSnapshot {
        val onboarding = appContext.getSharedPreferences(
            SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val locale = appContext.getSharedPreferences(
            SharedPrefsAppLocaleRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val theme = appContext.getSharedPreferences(
            SharedPrefsThemePreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val importStart = if (onboarding.contains(KEY_IMPORT_START_EPOCH_MILLIS)) {
            onboarding.getLong(KEY_IMPORT_START_EPOCH_MILLIS, 0L)
        } else {
            null
        }
        return BackupPreferencesSnapshot(
            onboardingStarted = onboarding.getBoolean(KEY_ONBOARDING_STARTED, false),
            onboardingCompleted = onboarding.getBoolean(KEY_ONBOARDING_COMPLETED, false),
            historicalImportStartEpochMillis = importStart,
            historicalImportCompleted = onboarding.getBoolean(KEY_IMPORT_COMPLETED, false),
            languageTag = locale.getString(
                SharedPrefsAppLocaleRepository.KEY_LANGUAGE_TAG,
                AppLocale.DEFAULT_TAG,
            ) ?: AppLocale.DEFAULT_TAG,
            themeMode = theme.getString(
                SharedPrefsThemePreferencesRepository.KEY_THEME_MODE,
                ThemeMode.DEFAULT.name,
            ) ?: ThemeMode.DEFAULT.name,
        )
    }

    private fun restorePreferences(snapshot: BackupPreferencesSnapshot) {
        val onboarding = appContext.getSharedPreferences(
            SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val locale = appContext.getSharedPreferences(
            SharedPrefsAppLocaleRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val theme = appContext.getSharedPreferences(
            SharedPrefsThemePreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        onboarding.edit().apply {
            putBoolean(KEY_ONBOARDING_STARTED, snapshot.onboardingStarted)
            putBoolean(KEY_ONBOARDING_COMPLETED, snapshot.onboardingCompleted)
            putBoolean(KEY_IMPORT_COMPLETED, snapshot.historicalImportCompleted)
            val start = snapshot.historicalImportStartEpochMillis
            if (start == null) {
                remove(KEY_IMPORT_START_EPOCH_MILLIS)
            } else {
                putLong(KEY_IMPORT_START_EPOCH_MILLIS, start)
            }
        }.commit()

        locale.edit().putString(
            SharedPrefsAppLocaleRepository.KEY_LANGUAGE_TAG,
            when (snapshot.languageTag) {
                AppLocale.TAG_EN -> AppLocale.TAG_EN
                else -> AppLocale.TAG_AR
            },
        ).commit()

        theme.edit().putString(
            SharedPrefsThemePreferencesRepository.KEY_THEME_MODE,
            ThemeMode.fromStorage(snapshot.themeMode).name,
        ).commit()
    }

    /**
     * The restored database may predate the current parse-fact or transfer-integrity
     * rules. Clearing these markers makes the next launch re-run both repairs.
     */
    private fun resetMaintenanceMarkers() {
        val committed = maintenancePrefs().edit()
            .remove(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION)
            .remove(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION)
            .commit()
        check(committed) { "Cannot reset maintenance markers after backup restore" }
    }

    private fun maintenancePrefs(): SharedPreferences =
        maintenancePreferences
            ?: appContext.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun writeEncryptedPackage(staging: File, destination: Uri, passphrase: CharArray) {
        val output = appContext.contentResolver.openOutputStream(destination)
            ?: throw BackupFailureException(BackupFailureCategory.EXPORT_FAILED)
        output.use { raw ->
            BackupEnvelope.encrypt(
                output = raw,
                passphrase = passphrase,
                iterations = kdfIterations,
            ) { encrypted ->
                ZipOutputStream(encrypted).use { zip ->
                    listOf(
                        BackupPackageFormat.MANIFEST_ENTRY,
                        BackupPackageFormat.DATABASE_ENTRY,
                        BackupPackageFormat.PREFERENCES_ENTRY,
                    ).forEach { name ->
                        val file = File(staging, name)
                        zip.putNextEntry(ZipEntry(name))
                        FileInputStream(file).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun extractValidated(input: java.io.InputStream, staging: File) {
        try {
            BackupArchiveValidator(archiveLimits).extractInto(input, staging)
        } catch (error: BackupArchiveException) {
            throw error
        } catch (ignored: Exception) {
            throw BackupArchiveException(BackupArchiveRejection.MALFORMED)
        }
    }

    private fun wipeFile(file: File) = SensitiveFileCleanup.wipe(file)

    private fun createOwnerOnlyFile(directory: File, name: String): File {
        val file = File(directory, name)
        SensitiveFileCleanup.delete(file)
        check(file.createNewFile()) { "Cannot create staging file" }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        return file
    }

    private fun readPrefix(input: java.io.InputStream, dest: ByteArray): Int {
        var offset = 0
        while (offset < dest.size) {
            val read = input.read(dest, offset, dest.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun loggedImport(
        outcome: BackupImportOutcome,
        category: BackupFailureCategory,
    ): BackupImportOutcome {
        logImportFailure(category)
        return outcome
    }

    private fun importOutcome(category: BackupFailureCategory): BackupImportOutcome = when (category) {
        BackupFailureCategory.AUTHENTICATION_FAILED,
        BackupFailureCategory.PASSPHRASE_REQUIRED,
        -> BackupImportOutcome.AuthenticationFailed
        BackupFailureCategory.ARCHIVE_REJECTED,
        BackupFailureCategory.ENVELOPE_TOO_LARGE,
        BackupFailureCategory.INVALID_ENVELOPE,
        BackupFailureCategory.INVALID_PACKAGE,
        -> BackupImportOutcome.InvalidPackage
        BackupFailureCategory.EXPORT_FAILED,
        BackupFailureCategory.IMPORT_FAILED,
        -> BackupImportOutcome.Failed
    }

    private fun logExportFailure(category: BackupFailureCategory) {
        appLogService?.error(
            AppLogCategories.BACKUP,
            "Database export failed: ${category.logToken}",
        )
    }

    private fun logImportFailure(category: BackupFailureCategory) {
        appLogService?.error(
            AppLogCategories.BACKUP,
            "Database import failed: ${category.logToken}",
        )
    }

    private fun sweepAbandonedStaging() {
        appContext.cacheDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || !dir.name.startsWith("masroof-backup-")) return@forEach
            SensitiveFileCleanup.delete(dir)
        }
    }

    private fun createStagingDir(label: String): File {
        sweepAbandonedStaging()
        val dir = File(appContext.cacheDir, "masroof-backup-$label-${clockEpochMillis()}")
        SensitiveFileCleanup.delete(dir)
        check(dir.mkdirs()) { "Cannot create staging directory" }
        return dir
    }

    private fun deleteSidecarFiles(dbFile: File) {
        SensitiveFileCleanup.delete(File(dbFile.path + "-wal"))
        SensitiveFileCleanup.delete(File(dbFile.path + "-shm"))
        SensitiveFileCleanup.delete(File(dbFile.path + "-journal"))
    }

    /**
     * Migrates a copy of the backup and checks it before the live file is replaced.
     * The live database stays in place until [installValidatedDatabase].
     */
    private fun openMigrateAndValidate(dbFile: File) {
        val opened = Room.databaseBuilder(appContext, MasroofDatabase::class.java, dbFile.name)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val db = opened.openHelper.writableDatabase
            check(db.version == MasroofDatabase.VERSION) {
                "Imported database did not migrate to ${MasroofDatabase.VERSION}"
            }
            db.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Imported database failed integrity_check"
                }
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                check(!cursor.moveToFirst()) { "Imported database failed foreign_key_check" }
            }
            db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                cursor.moveToFirst()
            }
        } finally {
            opened.close()
        }
        deleteSidecarFiles(dbFile)
    }

    private fun discardDatabaseFiles(dbFile: File) {
        deleteSidecarFiles(dbFile)
        SensitiveFileCleanup.delete(dbFile)
    }

    private fun readIdentityHash(dbFile: File): String? {
        return runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery(
                    "SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1",
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
        }.getOrNull()
    }

    companion object {
        internal fun defaultRestartProcess(appContext: Context) {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?: return
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            appContext.startActivity(launchIntent)
            Runtime.getRuntime().exit(0)
        }

        private const val ONLINE_BACKUP_MIN_MINOR: Int = 27
        private const val SNAPSHOT_BUSY_TIMEOUT_MS: Int = 10_000
        private const val CHECKPOINT_ATTEMPTS: Int = 40
        private const val CHECKPOINT_RETRY_MS: Long = 25L

        // Mirror SharedPrefsOnboardingPreferencesRepository private keys for backup I/O.
        private const val KEY_ONBOARDING_STARTED = "onboarding_started"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_IMPORT_START_EPOCH_MILLIS = "historical_import_start_epoch_millis"
        private const val KEY_IMPORT_COMPLETED = "historical_import_completed"
    }
}
