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

/**
 * The committed v18 fixtures (plan 0.2): a `masroof.db` and a plaintext v1 backup archive holding it,
 * both synthetic. They stay at v18 forever; later migration tests open them. Only
 * `MASROOF_REBUILD_V18_FIXTURES=1` (off by default, separate from the golden snapshot's variable) rebuilds
 * them, and only while the schema is still v18.
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
        assertEquals(committedSchemaHash(), MasroofDatabase.IDENTITY_HASH)
        val db = Room.databaseBuilder(context, MasroofDatabase::class.java, "fixture-v18.db").allowMainThreadQueries().build()
        try {
            assertEquals(18, db.openHelper.writableDatabase.version)
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
    }

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
        // Equal database content means nothing changed: keep the committed files (the envelope is random per export).
        val target = fixtureDir().also { it.mkdirs() }
        if (!File(target, DB_FILE).let { it.isFile && it.readBytes().contentEquals(db.readBytes()) }) {
            dir.listFiles()!!.forEach { it.copyTo(File(target, it.name), overwrite = true) }
        }
        dir.deleteRecursively()
    }

    private fun committedSchemaHash(): String {
        val json = File(root(), "schemas/com.baraa.masroof.data.room.MasroofDatabase/18.json").readText()
        return Regex("\"identityHash\":\\s*\"(\\w+)\"").find(json)!!.groupValues[1]
    }

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
