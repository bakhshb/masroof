package com.baraa.masroof.application.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.maintenance.TransferIntegrityRepairCoordinator
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.OwnershipStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupImportMigrationTest {
    @Before
    fun clearMaintenancePreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun importV5Backup_resetsParseFactsAndTransferIntegrityMarkers() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, MasroofDatabase.VERSION)
            .putInt(
                MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION,
                TransferIntegrityRepairCoordinator.CURRENT_VERSION,
            )
            .commit()

        val v5DbFile = createV5DatabaseFile(context)
        val backupZip = createBackupZip(v5DbFile)
        val restartRequested = AtomicBoolean(false)

        val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

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
        )

        val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

        assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
        assertTrue(restartRequested.get())

        backupZip.delete()
        v5DbFile.delete()
        }
    }

    @Test
    fun importBackup_failedMaintenanceMarkerReset_keepsMarkersAndDoesNotRestart() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, MasroofDatabase.VERSION)
                .putInt(
                    MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION,
                    TransferIntegrityRepairCoordinator.CURRENT_VERSION,
                )
                .commit()

            val v5DbFile = createV5DatabaseFile(context)
            val backupZip = createBackupZip(v5DbFile)
            val restartRequested = AtomicBoolean(false)
            val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
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
            )

            val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

            assertEquals(BackupImportOutcome.Failed, outcome)
            assertFalse(restartRequested.get())
            assertEquals(
                MasroofDatabase.VERSION,
                prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0),
            )
            assertEquals(
                TransferIntegrityRepairCoordinator.CURRENT_VERSION,
                prefs.getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0),
            )
            backupZip.delete()
            v5DbFile.delete()
        }
    }

    @Test
    fun importV5Backup_migratesToCurrentVersionOnNextOpen() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(MasroofDatabase.NAME)

        val v5DbFile = createV5DatabaseFile(context)
        val backupZip = createBackupZip(v5DbFile)
        val restartRequested = AtomicBoolean(false)

        val liveDatabase = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val backupService = DatabaseBackupService(
            appContext = context,
            database = liveDatabase,
            closeDatabase = { liveDatabase.close() },
            appVersionName = "test",
            clockEpochMillis = { 1_700_000_000_000L },
            restartProcess = { restartRequested.set(true) },
        )

        val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

        assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
        assertTrue(restartRequested.get())

        val migrated = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
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
            v5DbFile.delete()
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
                .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            RoomCardRegistryRepository.from(liveDatabase).setOwnership(
                CardReference(Bank.BANK_ALJAZIRA, "1111"),
                OwnershipStatus.OWNED,
            )

            val v5DbFile = createV5DatabaseFile(context)
            val backupZip = createBackupZip(v5DbFile)
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
            )

            val outcome = backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)

            assertEquals(BackupImportOutcome.Failed, outcome)
            assertFalse(restartRequested.get())
            assertFalse(
                context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
                    .getBoolean("onboarding_completed", false),
            )

            val restored = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
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
                v5DbFile.delete()
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
                    .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
                RoomCardRegistryRepository.from(liveDatabase).setOwnership(
                    CardReference(Bank.BANK_ALJAZIRA, "1111"),
                    OwnershipStatus.OWNED,
                )
                val v5DbFile = createV5DatabaseFile(context)
                val backupZip = createBackupZip(v5DbFile)
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
                )
                try {
                    backupService.importFrom(Uri.fromFile(backupZip), confirmLegacyPlaintext = true)
                    org.junit.Assert.fail("expected termination at $stage")
                } catch (error: DatabaseRestoreRecovery.ProcessTerminated) {
                    assertEquals(stage.name, error.message?.substringAfterLast(' '))
                }
                DatabaseRestoreRecovery.recover(context)
                val reopened = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
                    .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
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
                    v5DbFile.delete()
                }
            }
        }
    }

    private fun createV5DatabaseFile(context: Context): File {
        val schema5 = File("schemas/com.baraa.masroof.data.room.MasroofDatabase/5.json")
        assertTrue(schema5.isFile)
        val dbName = "v5-export.db"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            applyExportedSchema(db, schema5, expectedVersion = 5)
                            db.execSQL(
                                """
                                INSERT INTO card_registry
                                (bankId, last4, ownershipStatus, firstSeenRawSmsId, lastSeenRawSmsId)
                                VALUES ('BANK_ALJAZIRA', '7271', 'OWNED', 'sms-1', 'sms-1')
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        openHelper.writableDatabase.close()
        openHelper.close()

        val exported = File(context.cacheDir, "v5-backup.db")
        context.getDatabasePath(dbName).copyTo(exported, overwrite = true)
        context.deleteDatabase(dbName)
        return exported
    }

    private fun createBackupZip(v5DbFile: File): File {
        val zipFile = File.createTempFile("masroof-v5-backup", ".masroof")
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "test",
            roomVersion = MasroofDatabase.LEGACY_VERSION_5,
            identityHash = MasroofDatabase.LEGACY_IDENTITY_HASH_5,
            exportedAtEpochMillis = 1_700_000_000_000L,
        )
        val preferences = BackupPreferencesSnapshot(
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
            v5DbFile.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupPackageFormat.PREFERENCES_ENTRY))
            zip.write(BackupPackageCodec.encodePreferences(preferences).toByteArray())
            zip.closeEntry()
        }
        return zipFile
    }

    private fun applyExportedSchema(
        db: SupportSQLiteDatabase,
        schemaFile: File,
        expectedVersion: Int,
    ) {
        val root = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(expectedVersion, database.getValue("version").jsonPrimitive.content.toInt())
        for (entityEl in database.getValue("entities").jsonArray) {
            val entity = entityEl.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(
                entity.getValue("createSql").jsonPrimitive.content
                    .replace("\${TABLE_NAME}", tableName),
            )
            for (indexEl in entity["indices"]?.jsonArray.orEmpty()) {
                db.execSQL(
                    indexEl.jsonObject.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName),
                )
            }
        }
        for (setupEl in database["setupQueries"]?.jsonArray.orEmpty()) {
            db.execSQL(setupEl.jsonPrimitive.content)
        }
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
