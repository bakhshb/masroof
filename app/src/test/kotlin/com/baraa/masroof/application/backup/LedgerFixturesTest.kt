package com.baraa.masroof.application.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.LedgerSnapshotCorpus
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.repository.RoomCommitmentRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.MigrationOpenPathContract
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.baraa.masroof.testsupport.NoOpAppLog

/**
 * The committed v18 fixtures (plan 0.2): a `masroof.db` plus a plaintext v1 archive and an encrypted
 * envelope holding it, all synthetic. They stay at v18 forever; later migration tests open them. Only
 * `MASROOF_REBUILD_V18_FIXTURES=1` with `--rerun` (off by default, separate from the golden snapshot's variable)
 * rebuilds them, and only while the schema is still v18.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerFixturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))
        MessagePatternStore(context).restoreRaw(null)
        context.deleteDatabase(MasroofDatabase.NAME)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
        if (System.getenv(REBUILD_ENV) == "1" && !rebuilt) {
            rebuilt = true
            rebuild()
        }
    }

    @After
    fun tearDown() = TimeZone.setDefault(originalZone)

    @Test
    fun v18Database_opensWithTheCommittedIdentityHashAndHasRows() = runBlocking {
        val file = context.getDatabasePath("fixture-v18.db").also { it.parentFile?.mkdirs() }
        fixture(DB_FILE).copyTo(file, overwrite = true)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(18, raw.version)
            raw.rawQuery("SELECT identity_hash FROM room_master_table", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(committedSchemaHash(), it.getString(0))
            }
        }
        // The production builder migrates the frozen file to the current schema (plan 2.1: v19, empty user_decision).
        val db = MasroofDatabase.builder(context, MasroofDatabase::class.java, "fixture-v18.db").allowMainThreadQueries().build()
        try {
            assertEquals(MasroofDatabase.VERSION, db.openHelper.writableDatabase.version)
            assertRows(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun v18BackupArchive_importsAndRestoresTheSameRows() = runBlocking {
        val live = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
        val outcome = DatabaseBackupService(
            appContext = context,
            database = live,
            closeDatabase = { live.close() },
            appVersionName = "test",
            clockEpochMillis = { EXPORTED_AT },
            restartProcess = {},
            appLogService = NoOpAppLog,
        ).importFrom(Uri.fromFile(fixture(ARCHIVE_FILE)), confirmLegacyPlaintext = true)
        assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
        val restored = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
        try {
            assertRows(restored)
        } finally {
            restored.close()
        }
    }

    @Test
    fun v18EncryptedBackup_importsThroughTheNormalPathAndRestoresTheSameRows() = runBlocking {
        val live = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
        val importer = DatabaseBackupService(
            appContext = context,
            database = live,
            closeDatabase = { live.close() },
            appVersionName = "test",
            clockEpochMillis = { EXPORTED_AT },
            restartProcess = {},
            appLogService = NoOpAppLog,
        )
        val file = fixture(ENCRYPTED_FILE)
        assertTrue(file.readBytes().let { BackupEnvelope.looksLikeEnvelope(it, it.size) })
        assertEquals(BackupImportOutcome.AuthenticationFailed, importer.importFrom(Uri.fromFile(file), "wrong".toCharArray()))
        assertEquals(BackupImportOutcome.SuccessNeedsRestart, importer.importFrom(Uri.fromFile(file), PASSPHRASE.toCharArray()))
        val restored = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
        try {
            assertRows(restored)
        } finally {
            restored.close()
        }
    }

    private suspend fun assertRows(db: MasroofDatabase) {
        val transactions = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao()).listAll()
        assertTrue(transactions.size >= 10)
        assertEquals(setOf("BANK_ALJAZIRA", "BANK_SNB", "BANK_SAB", "BANK_ALINMA", "BANK_BARQ"),
            transactions.mapNotNull { (it.sourceContainerId ?: it.destinationContainerId)?.split(":")?.getOrNull(1) }.toSet())
        assertEquals(LedgerSnapshotCorpus.BANK_IDS.toSet(),
            RoomParsedEventRepository(db.parsedEventDao()).listAll().map { it.event.bank.id }.toSet())
        assertTrue("fixture needs a persisted FX pair", transactions.any { it.appliedExchangeRate != null && it.exchangeRateSource != null })
        assertEquals(1, RoomCommitmentRepository.from(db).listAll().size)
        assertTrue("fixture needs a resolved review", RoomReviewRepository(db.reviewItemDao()).listAll().any { it.resolutionKind != null })
        assertEquals("no backfill", emptyList<UserDecisionEntity>(), db.userDecisionDao().listAll())
    }

    @Test
    fun currentVersionExport_roundTripsEveryRowIncludingUserDecisions() = runBlocking {
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
        val archive = File(context.cacheDir, "round-trip.masroof")
        val file = context.getDatabasePath(MasroofDatabase.NAME)
        try {
            assertEquals(BackupImportOutcome.SuccessNeedsRestart, service(live).importFrom(Uri.fromFile(fixture(ARCHIVE_FILE)), confirmLegacyPlaintext = true))
            val migrated = MasroofDatabase.builder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
            val rawSmsId = migrated.openHelper.readableDatabase.query("SELECT id FROM raw_sms ORDER BY id LIMIT 1").use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            migrated.userDecisionDao().insert(
                UserDecisionEntity(rawSmsId = rawSmsId, kind = "BANK_SELECTION", decidedAtEpochMillis = EXPORTED_AT, bankId = "BANK_SNB"))
            assertTrue(service(migrated, closes = false).exportTo(Uri.fromFile(archive), PASSPHRASE.toCharArray()).isSuccess)
            migrated.close()
            val expected = MigrationOpenPathContract.readOnly(file, MigrationOpenPathContract::rows)
            assertEquals(1, expected.getValue("user_decision").size)

            context.deleteDatabase(MasroofDatabase.NAME)
            val empty = MasroofDatabase.builder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()
            assertEquals(BackupImportOutcome.SuccessNeedsRestart, service(empty).importFrom(Uri.fromFile(archive), PASSPHRASE.toCharArray()))
            assertEquals(expected, MigrationOpenPathContract.readOnly(file) {
                assertEquals(MasroofDatabase.VERSION, it.version)
                MigrationOpenPathContract.rows(it)
            })
        } finally {
            if (live.isOpen) live.close()
            archive.delete()
        }
    }

    private fun service(live: MasroofDatabase, closes: Boolean = true) = DatabaseBackupService(
        appContext = context,
        database = live,
        closeDatabase = { check(closes) { "export must not close" }; live.close() },
        appVersionName = "test",
        clockEpochMillis = { EXPORTED_AT },
        restartProcess = {},
        kdfIterations = KDF_ITERATIONS,
        appLogService = NoOpAppLog,
    )

    private fun rebuild() = runBlocking {
        check(MasroofDatabase.VERSION == 18) { "the v18 fixtures must never be rebuilt from schema ${MasroofDatabase.VERSION}" }
        val dir = File(context.cacheDir, "v18-rebuild").also { it.deleteRecursively(); it.mkdirs() }
        val db = File(dir, DB_FILE)
        LedgerSnapshotCorpus.newAllBanksWorld(context, MasroofDatabase.NAME).use { world ->
            LedgerSnapshotCorpus.populateAllBanks(world)
            LedgerSnapshotCorpus.populateExtras(world)
            LedgerSnapshotCorpus.populateLegacy(world)
            world.db.openHelper.writableDatabase.apply {
                query("PRAGMA journal_mode=DELETE").close()
                execSQL("VACUUM")
            }
            context.getDatabasePath(MasroofDatabase.NAME).copyTo(db, overwrite = true)
            // The real export path; the envelope salt and nonce are random, so this file changes on every rebuild.
            val exported = DatabaseBackupService(
                appContext = context,
                database = world.db,
                closeDatabase = { error("live database must stay open") },
                appVersionName = "fixture",
                clockEpochMillis = { EXPORTED_AT },
                restartProcess = { error("unexpected restart") },
                kdfIterations = KDF_ITERATIONS,
                appLogService = NoOpAppLog,
            ).exportTo(Uri.fromFile(File(dir, ENCRYPTED_FILE)), PASSPHRASE.toCharArray())
            assertTrue(exported.exceptionOrNull()?.toString(), exported.isSuccess)
        }
        context.deleteDatabase(MasroofDatabase.NAME)
        val manifest = BackupManifest(BackupPackageFormat.FORMAT_VERSION, "fixture", 18, committedSchemaHash(), EXPORTED_AT)
        val preferences = BackupPreferencesSnapshot(
            onboardingStarted = true, onboardingCompleted = true, historicalImportCompleted = true,
            languageTag = AppLocale.DEFAULT_TAG, themeMode = ThemeMode.DEFAULT.name,
        )
        ZipOutputStream(File(dir, ARCHIVE_FILE).outputStream()).use { zip ->
            listOf(
                BackupPackageFormat.MANIFEST_ENTRY to BackupPackageCodec.encodeManifest(manifest).toByteArray(),
                BackupPackageFormat.DATABASE_ENTRY to db.readBytes(),
                BackupPackageFormat.PREFERENCES_ENTRY to BackupPackageCodec.encodePreferences(preferences).toByteArray(),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = EXPORTED_AT })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        // Registry, review and commitment ids are random, so every rebuild rewrites all three files.
        val target = fixtureDir().also { it.mkdirs() }
        dir.listFiles()!!.forEach { it.copyTo(File(target, it.name), overwrite = true) }
        dir.deleteRecursively()
    }

    /** The v18 hash, whatever the current version is; `FinancialSchemaCompatibilityTest` ties the map to 18.json. */
    private fun committedSchemaHash() = MasroofDatabase.IDENTITY_HASHES.getValue(18)

    private fun fixture(name: String) = File(fixtureDir(), name).also { assertTrue("missing fixture $it", it.isFile) }

    private fun fixtureDir() = File(root(), "src/test/resources/testdata/ledger_fixtures/v18")

    private fun root() = File(if (File("src/test").isDirectory) "." else "app")

    private companion object {
        /** Rebuild at most once per JVM run, not once per test. */
        var rebuilt = false

        const val REBUILD_ENV = "MASROOF_REBUILD_V18_FIXTURES"
        const val DB_FILE = "masroof.db"
        const val ARCHIVE_FILE = "masroof-v18-backup.zip"
        const val ENCRYPTED_FILE = "masroof-v18-backup.masroof"
        const val PASSPHRASE = "synthetic-fixture-passphrase-v18"
        const val KDF_ITERATIONS = 4_096
        const val EXPORTED_AT = 1_700_000_000_000L
    }
}
