package com.baraa.masroof.application.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.OwnershipStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.baraa.masroof.testsupport.NoOpAppLog
import com.baraa.masroof.testsupport.storedPatterns

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupImportTest {
    @Test fun olderManifestAndDisguisedOlderDatabaseAreRejectedBeforeTouchingLiveData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_completed", false).commit()
        val live = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
        val patterns = MessagePatternStore(live)
        val original = patternArchive("ORIGINAL")
        patterns.restoreRaw(original)
        val card = CardReference(Bank.BANK_ALJAZIRA, "1111")
        try {
            RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.OWNED)
            for (disguised in listOf(false, true)) {
                val imported = createCurrentDatabaseFile(context)
                if (disguised) android.database.sqlite.SQLiteDatabase.openDatabase(imported.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.version = 17 }
                val zip = createBackupZip(imported, roomVersion = if (disguised) MasroofDatabase.VERSION else 17)
                var closed = false
                try {
                    val service = DatabaseBackupService(context, live, { closed = true; live.close() }, "test", restartProcess = {}, appLogService = NoOpAppLog)
                    assertEquals(BackupImportOutcome.InvalidPackage, service.importFrom(Uri.fromFile(zip), confirmLegacyPlaintext = true))
                    assertFalse(closed)
                    assertEquals(original, patterns.captureRaw())
                    assertFalse(prefs.getBoolean("onboarding_completed", true))
                    assertEquals(OwnershipStatus.OWNED, RoomCardRegistryRepository.from(live).get(card)!!.ownership)
                } finally { imported.delete(); zip.delete() }
            }
        } finally { live.close() }
    }

    @Before
    fun clearMaintenancePreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MessagePatternBackup.restore(context, null)
        context.deleteDatabase(MasroofDatabase.NAME)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(
            context.getDatabasePath(MasroofDatabase.NAME),
        )
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun unsupportedPatternsAreRejectedBeforeClosingOrChangingLiveData() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val live = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .allowMainThreadQueries().build()
            val patterns = MessagePatternStore(live)
            val original = patternArchive("ORIGINAL")
            patterns.restoreRaw(original)
            val imported = createCurrentDatabaseFile(context)
            val zip = createBackupZip(imported, "{}")
            val closed = AtomicBoolean(false)
            try {
                val card = CardReference(Bank.BANK_ALJAZIRA, "1111")
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.OWNED)
                val service = DatabaseBackupService(context, live, { closed.set(true); live.close() }, "test",
                    restartProcess = {},
                    appLogService = NoOpAppLog,)
                assertEquals(BackupImportOutcome.InvalidPackage,
                    service.importFrom(Uri.fromFile(zip), confirmLegacyPlaintext = true))
                assertFalse(closed.get())
                assertEquals(original, patterns.captureRaw())
                assertEquals(OwnershipStatus.OWNED, RoomCardRegistryRepository.from(live).get(card)!!.ownership)
            } finally {
                if (live.isOpen) live.close()
                imported.delete(); zip.delete()
            }
        }
    }

    /** A v22 archive's own pattern row must decode; a broken one fails the import and the live patterns stay. */
    @Test
    fun currentArchiveWithAnInvalidPatternRowFailsAndKeepsTheLivePatterns() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val live = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .allowMainThreadQueries().build()
            MessagePatternStore(live).restoreRaw(patternArchive("ORIGINAL"))
            val imported = createCurrentDatabaseFile(context)
            android.database.sqlite.SQLiteDatabase.openDatabase(imported.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                .use { it.execSQL("INSERT INTO pattern_configuration VALUES (1, 1, '{broken')") }
            val zip = createBackupZip(imported)
            try {
                val outcome = DatabaseBackupService(context, live, { live.close() }, "test", restartProcess = {}, appLogService = NoOpAppLog)
                    .importFrom(Uri.fromFile(zip), confirmLegacyPlaintext = true)
                assertEquals(BackupImportOutcome.Failed, outcome)
                assertEquals(patternArchive("ORIGINAL"), storedPatterns(context.getDatabasePath(MasroofDatabase.NAME)))
            } finally {
                if (live.isOpen) live.close()
                imported.delete(); zip.delete()
            }
        }
    }

    @Test
    fun importCurrentBackup_resetsRetiredMaintenanceMarkers() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, MasroofDatabase.VERSION)
            .putInt(
                MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION,
                RETIRED_REPAIR_VERSION,
            )
            .commit()

        val importedDbFile = createCurrentDatabaseFile(context)
        val backupZip = createBackupZip(importedDbFile)
        val restartRequested = AtomicBoolean(false)

        val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        MessagePatternStore(liveDatabase).restoreRaw(patternArchive("STC"))

        val backupService = DatabaseBackupService(
            appContext = context,
            database = liveDatabase,
            closeDatabase = { liveDatabase.close() },
            appVersionName = "test",
            clockEpochMillis = { 1_700_000_000_000L },
            restartProcess = {
                val maintenance = context.getSharedPreferences(
                    MaintenancePreferences.PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
                assertEquals(0, maintenance.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0))
                assertEquals(
                    0,
                    maintenance.getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0),
                )
                restartRequested.set(true)
            },
            appLogService = NoOpAppLog,
        )

        val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

        assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
        assertTrue(restartRequested.get())
        // The imported database has no pattern row, so the original document is gone and bundled seeding runs next start.
        assertNull(storedPatterns(context.getDatabasePath(MasroofDatabase.NAME)))

        backupZip.delete()
        importedDbFile.delete()
        }
    }

    @Test
    fun importBackup_failedMaintenanceMarkerReset_keepsMarkersAndRequestsFreshProcess() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, MasroofDatabase.VERSION)
                .putInt(
                    MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION,
                    RETIRED_REPAIR_VERSION,
                )
                .commit()

            val importedDbFile = createCurrentDatabaseFile(context)
            val backupZip = createBackupZip(importedDbFile)
            val restartRequested = AtomicBoolean(false)
            val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .allowMainThreadQueries()
                .build()
            val backupService = DatabaseBackupService(
                appContext = context,
                database = liveDatabase,
                closeDatabase = { liveDatabase.close() },
                appVersionName = "test",
                clockEpochMillis = { 1_700_000_000_000L },
                restartProcess = { restartRequested.set(true) },
                maintenancePreferences = CommitFailsPreferences(prefs),
                appLogService = NoOpAppLog,
            )

            val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

            assertEquals(BackupImportOutcome.Failed, outcome)
            assertTrue(restartRequested.get())
            assertEquals(
                MasroofDatabase.VERSION,
                prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0),
            )
            assertEquals(
                RETIRED_REPAIR_VERSION,
                prefs.getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0),
            )
            backupZip.delete()
            importedDbFile.delete()
        }
    }

    @Test
    fun importCurrentBackup_reopensWithOwnershipIntact() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(MasroofDatabase.NAME)

        val importedDbFile = createCurrentDatabaseFile(context)
        val backupZip = createBackupZip(importedDbFile)
        val restartRequested = AtomicBoolean(false)

        val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .allowMainThreadQueries()
            .build()

        val backupService = DatabaseBackupService(
            appContext = context,
            database = liveDatabase,
            closeDatabase = { liveDatabase.close() },
            appVersionName = "test",
            clockEpochMillis = { 1_700_000_000_000L },
            restartProcess = { restartRequested.set(true) },
            appLogService = NoOpAppLog,
        )

        val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

        assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
        assertTrue(restartRequested.get())

        val migrated = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(MasroofDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            val cardRepo = RoomCardRegistryRepository.from(migrated)
            val loaded = cardRepo.get(CardReference(Bank.BANK_ALJAZIRA, "7271"))!!
            assertEquals(OwnershipStatus.OWNED, loaded.ownership)
        } finally {
            migrated.close()
            context.deleteDatabase(MasroofDatabase.NAME)
            backupZip.delete()
            importedDbFile.delete()
        }
        }
    }

    @Test
    fun import_failedReplace_keepsTheLiveDatabase() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("onboarding_completed", false)
                .commit()

            val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .allowMainThreadQueries()
                .build()
            RoomCardRegistryRepository.from(liveDatabase).setOwnership(
                CardReference(Bank.BANK_ALJAZIRA, "1111"),
                OwnershipStatus.OWNED,
            )

            val importedDbFile = createCurrentDatabaseFile(context)
            val backupZip = createBackupZip(importedDbFile)
            val restartRequested = AtomicBoolean(false)
            val validated = DatabaseRestoreRecovery.incomingFile(
                context.getDatabasePath(MasroofDatabase.NAME),
            )

            val backupService = DatabaseBackupService(
                appContext = context,
                database = liveDatabase,
                closeDatabase = { liveDatabase.close() },
                appVersionName = "test",
                clockEpochMillis = { 1_700_000_000_000L },
                restartProcess = { restartRequested.set(true) },
                beforeValidatedInstall = { check(validated.delete()) },
                appLogService = NoOpAppLog,
            )

            val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

            assertEquals(BackupImportOutcome.Failed, outcome)
            assertTrue(restartRequested.get())
            assertFalse(
                context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                    .getBoolean("onboarding_completed", false),
            )

            val restored = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .allowMainThreadQueries()
                .build()
            try {
                val cards = RoomCardRegistryRepository.from(restored)
                assertEquals(
                    OwnershipStatus.OWNED,
                    cards.get(CardReference(Bank.BANK_ALJAZIRA, "1111"))!!.ownership,
                )
                assertNull(cards.get(CardReference(Bank.BANK_ALJAZIRA, "7271")))
            } finally {
                restored.close()
                context.deleteDatabase(MasroofDatabase.NAME)
                backupZip.delete()
                importedDbFile.delete()
            }
        }
    }

    @Test
    fun import_invalidAfterCommit_restoresOriginalAndReportsFailure() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(MasroofDatabase.NAME)
            DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
            context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("onboarding_completed", false)
                .commit()
            val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .allowMainThreadQueries()
                .build()
            RoomCardRegistryRepository.from(liveDatabase).setOwnership(
                CardReference(Bank.BANK_ALJAZIRA, "1111"),
                OwnershipStatus.OWNED,
            )
            val importedDbFile = createCurrentDatabaseFile(context)
            val backupZip = createBackupZip(importedDbFile)
            val restartRequested = AtomicBoolean(false)
            val livePath = context.getDatabasePath(MasroofDatabase.NAME).path
            val backupService = DatabaseBackupService(
                appContext = context,
                database = liveDatabase,
                closeDatabase = { liveDatabase.close() },
                appVersionName = "test",
                clockEpochMillis = { 1_700_000_000_000L },
                restartProcess = { restartRequested.set(true) },
                afterRestoreStage = { reached ->
                    if (reached == DatabaseRestoreRecovery.Stage.COMMITTED) {
                        File(livePath).writeText("imported-broken")
                        listOf("-wal", "-shm", "-journal").forEach { File(livePath + it).delete() }
                    }
                },
                appLogService = NoOpAppLog,
            )

            val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

            assertEquals(BackupImportOutcome.Failed, outcome)
            assertTrue(restartRequested.get())
            assertFalse(
                context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                    .getBoolean("onboarding_completed", false),
            )
            assertFalse(File(livePath + ".restore-journal").exists())
            val restored = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .allowMainThreadQueries()
                .build()
            try {
                val cards = RoomCardRegistryRepository.from(restored)
                assertEquals(
                    OwnershipStatus.OWNED,
                    cards.get(CardReference(Bank.BANK_ALJAZIRA, "1111"))!!.ownership,
                )
                assertNull(cards.get(CardReference(Bank.BANK_ALJAZIRA, "7271")))
            } finally {
                restored.close()
                context.deleteDatabase(MasroofDatabase.NAME)
                DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
                backupZip.delete()
                importedDbFile.delete()
            }
        }
    }

    @Test
    fun import_terminatedAfterEveryStage_restartsOntoOriginalOrCommittedDatabase() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val stages = listOf(
                DatabaseRestoreRecovery.Stage.PREPARED to "original",
                DatabaseRestoreRecovery.Stage.OLD_PARKED to "original",
                DatabaseRestoreRecovery.Stage.NEW_INSTALLED to "imported",
                DatabaseRestoreRecovery.Stage.COMMITTED to "imported",
            )
            for ((stage, expectedOwner) in stages) {
                context.deleteDatabase(MasroofDatabase.NAME)
                DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
                context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("onboarding_completed", false)
                    .commit()
                val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                    .allowMainThreadQueries()
                    .build()
                RoomCardRegistryRepository.from(liveDatabase).setOwnership(
                    CardReference(Bank.BANK_ALJAZIRA, "1111"),
                    OwnershipStatus.OWNED,
                )
                MessagePatternStore(liveDatabase).restoreRaw(patternArchive("ORIGINAL"))
                // A v22 archive's own row wins; its preferences field is ignored.
                val importedDbFile = createCurrentDatabaseFile(context, patternArchive("IMPORTED"))
                val backupZip = createBackupZip(importedDbFile, patternArchive("IGNORED"))
                val backupService = DatabaseBackupService(
                    appContext = context,
                    database = liveDatabase,
                    closeDatabase = { liveDatabase.close() },
                    appVersionName = "test",
                    clockEpochMillis = { 1_700_000_000_000L },
                    restartProcess = { },
                    afterRestoreStage = { reached ->
                        if (reached == stage) throw DatabaseRestoreRecovery.ProcessTerminated(reached)
                    },
                    appLogService = NoOpAppLog,
                )
                try {
                    backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)
                    org.junit.Assert.fail("expected termination at $stage")
                } catch (error: DatabaseRestoreRecovery.ProcessTerminated) {
                    assertEquals(stage.name, error.message?.substringAfterLast(' '))
                }
                DatabaseRestoreRecovery.recover(context)
                assertEquals(patternArchive(if (expectedOwner == "original") "ORIGINAL" else "IMPORTED"),
                    storedPatterns(context.getDatabasePath(MasroofDatabase.NAME)))
                assertNull(MessagePatternBackup.captureOriginal(context))
                val reopened = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                    .allowMainThreadQueries()
                    .build()
                try {
                    val cards = RoomCardRegistryRepository.from(reopened)
                    if (expectedOwner == "original") {
                        assertEquals(
                            OwnershipStatus.OWNED,
                            cards.get(CardReference(Bank.BANK_ALJAZIRA, "1111"))!!.ownership,
                        )
                        assertNull(cards.get(CardReference(Bank.BANK_ALJAZIRA, "7271")))
                        assertFalse(
                            context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                                .getBoolean("onboarding_completed", false),
                        )
                    } else {
                        assertNull(cards.get(CardReference(Bank.BANK_ALJAZIRA, "1111")))
                        assertEquals(
                            OwnershipStatus.OWNED,
                            cards.get(CardReference(Bank.BANK_ALJAZIRA, "7271"))!!.ownership,
                        )
                        assertTrue(
                            context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                                .getBoolean("onboarding_completed", false),
                        )
                    }
                    val livePath = context.getDatabasePath(MasroofDatabase.NAME).path
                    assertFalse(File(livePath + ".restore-journal").exists())
                    assertFalse(File(livePath + ".rollback").exists())
                } finally {
                    reopened.close()
                    context.deleteDatabase(MasroofDatabase.NAME)
                    DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
                    backupZip.delete()
                    importedDbFile.delete()
                }
            }
        }
    }

    private suspend fun createCurrentDatabaseFile(context: Context, patterns: String? = null): File {
        val name = "current-export.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, MasroofDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            RoomCardRegistryRepository.from(db).setOwnership(CardReference(Bank.BANK_ALJAZIRA, "7271"), OwnershipStatus.OWNED)
            patterns?.let(MessagePatternStore(db)::restoreRaw)
        } finally { db.close() }
        val exported = File(context.cacheDir, "current-backup.db")
        context.getDatabasePath(name).copyTo(exported, overwrite = true)
        context.deleteDatabase(name)
        return exported
    }

    private fun createBackupZip(importedDbFile: File, messagePatterns: String? = null, roomVersion: Int = MasroofDatabase.VERSION): File {
        val zipFile = File.createTempFile("masroof-current-backup", ".masroof")
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "test",
            roomVersion = roomVersion,
            identityHash = MasroofDatabase.IDENTITY_HASH,
            exportedAtEpochMillis = 1_700_000_000_000L,
        )
        val preferences = BackupPreferencesSnapshot(
            messagePatterns = messagePatterns,
            onboardingStarted = true,
            onboardingCompleted = true,
            historicalImportStartEpochMillis = null,
            historicalImportCompleted = true,
            languageTag = AppLocale.DEFAULT_TAG,
            themeMode = ThemeMode.DEFAULT.name,
        )

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupPackageFormat.MANIFEST_ENTRY))
            zip.write(BackupPackageCodec.encodeManifest(manifest).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupPackageFormat.DATABASE_ENTRY))
            importedDbFile.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupPackageFormat.PREFERENCES_ENTRY))
            zip.write(BackupPackageCodec.encodePreferences(preferences).toByteArray())
            zip.closeEntry()
        }
        return zipFile
    }

    private class CommitFailsPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = CommitFailsEditor(delegate.edit())
    }

    private class CommitFailsEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by delegate {
        override fun remove(key: String): SharedPreferences.Editor {
            delegate.remove(key)
            return this
        }

        override fun commit(): Boolean = false
    }
}

/** The value the retired transfer-integrity marker held once its repair ran (the repair was deleted in plan 3.3c). */
private const val RETIRED_REPAIR_VERSION = 1
