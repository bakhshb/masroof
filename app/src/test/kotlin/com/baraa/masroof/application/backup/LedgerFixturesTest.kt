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
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
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
 * both synthetic. They stay at v18 forever; later migration tests open them. To rebuild them from the
 * snapshot corpus run with `MASROOF_UPDATE_LEDGER_FIXTURES=1` (off by default) and review the binary diff.
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
        if (System.getenv(UPDATE_ENV) == "1") regenerate()
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

    private suspend fun assertRows(db: MasroofDatabase) {
        val transactions = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao()).listAll()
        assertTrue(transactions.size >= 10)
        assertEquals(setOf("BANK_ALJAZIRA", "BANK_SNB", "BANK_SAB", "BANK_ALINMA", "BANK_BARQ"),
            transactions.mapNotNull { (it.sourceContainerId ?: it.destinationContainerId)?.split(":")?.getOrNull(1) }.toSet())
        assertEquals(LedgerSnapshotCorpus.BANK_IDS.toSet(),
            RoomParsedEventRepository(db.parsedEventDao()).listAll().map { it.event.bank.id }.toSet())
    }

    private fun regenerate() = runBlocking {
        context.deleteDatabase(GENERATED_DB)
        LedgerSnapshotCorpus.newAllBanksWorld(context, GENERATED_DB).use { world ->
            LedgerSnapshotCorpus.populateAllBanks(world)
            LedgerSnapshotCorpus.populateLegacy(world)
            world.db.openHelper.writableDatabase.apply {
                query("PRAGMA journal_mode=DELETE").close()
                execSQL("VACUUM")
            }
        }
        val dir = fixtureDir().also { it.mkdirs() }
        val db = File(dir, DB_FILE)
        context.getDatabasePath(GENERATED_DB).copyTo(db, overwrite = true)
        context.deleteDatabase(GENERATED_DB)
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
    }

    private fun committedSchemaHash(): String {
        val json = schemaFile().readText()
        return Regex("\"identityHash\":\\s*\"(\\w+)\"").find(json)!!.groupValues[1]
    }

    private fun schemaFile() = File(root(), "schemas/com.baraa.masroof.data.room.MasroofDatabase/18.json")

    private fun fixture(name: String) = File(fixtureDir(), name).also { assertTrue("missing fixture $it", it.isFile) }

    private fun fixtureDir() = File(root(), "src/test/resources/testdata/ledger_fixtures/v18")

    private fun root() = File(if (File("src/test").isDirectory) "." else "app")

    private companion object {
        const val UPDATE_ENV = "MASROOF_UPDATE_LEDGER_FIXTURES"
        const val DB_FILE = "masroof.db"
        const val ARCHIVE_FILE = "masroof-v18-backup.zip"
        const val GENERATED_DB = "fixture-generator.db"
        const val EXPORTED_AT = 1_700_000_000_000L
    }
}
