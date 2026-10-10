package com.baraa.masroof.application.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsThemePreferencesRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.OwnershipStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Export must publish a sidecar-free snapshot that survives WAL checkpoints racing the copy.
 * SDK 28 matches the existing backup tests. Robolectric's SQLite supports VACUUM INTO.
 * The quiesced fallback is also covered here by disabling that path, and on a real
 * API 26–28 device by [com.baraa.masroof.instrumentation.PreVacuumBackupSnapshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsistentBackupSnapshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val card = CardReference(Bank.BANK_ALJAZIRA, "4242")
    private val passphrase = "snapshot-passphrase".toCharArray()

    @Before
    fun resetAppStorage() {
        context.deleteDatabase(MasroofDatabase.NAME)
        listOf(
            SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
            SharedPrefsAppLocaleRepository.PREFS_NAME,
            SharedPrefsThemePreferencesRepository.PREFS_NAME,
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(MasroofDatabase.NAME)
    }

    @Test(timeout = 120_000)
    fun export_withConcurrentWalWriters_restoresConsistentSnapshot() {
        runBlocking { assertConcurrentExport(useOnlineBackup = true) }
    }

    @Test(timeout = 120_000)
    fun export_withConcurrentWalWriters_quiescedSnapshotIsConsistent() {
        runBlocking { assertConcurrentExport(useOnlineBackup = false) }
    }

    private suspend fun assertConcurrentExport(useOnlineBackup: Boolean) {
            val live = openWalDatabase()
            val writers = mutableListOf<Thread>()
            val stop = AtomicBoolean(false)
            try {
                val sqliteVersion = prepareLiveDatabase(live)
                val preSeed = seedBatches(liveDbPath(), 1L until (PRESEED_BATCHES + 1L))
                assertConsistent(
                    batches = readBatches(liveDbPath(), sqliteVersion),
                    mustInclude = preSeed,
                    mustBeSubsetOf = preSeed,
                    label = "live before export sqlite=$sqliteVersion",
                )
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.OWNED)
                val preferences = captureLivePreferences()

                val committed = ConcurrentHashMap.newKeySet<Long>()
                val writerIds = AtomicLong(WRITER_ID_START)
                val writerFailure = AtomicReference<Throwable>(null)
                val writerStarted = CountDownLatch(WRITER_COUNT)
                repeat(WRITER_COUNT) { index ->
                    val thread = Thread {
                        val connection = openRaw(liveDbPath())
                        try {
                            connection.rawQuery("PRAGMA busy_timeout = 20000", null).use { it.moveToFirst() }
                            connection.execSQL("PRAGMA foreign_keys = ON")
                            while (!stop.get() && writerFailure.get() == null) {
                                val id = writerIds.incrementAndGet()
                                try {
                                    insertBatchTransaction(connection, id)
                                    committed.add(id)
                                    writerStarted.countDown()
                                } catch (error: SQLiteException) {
                                    if (!isDatabaseLocked(error)) throw error
                                    Thread.sleep(20)
                                }
                                Thread.sleep(2)
                            }
                        } catch (error: Throwable) {
                            writerFailure.set(error)
                            writerStarted.countDown()
                        } finally {
                            connection.close()
                        }
                    }
                    thread.name = "snapshot-writer-$index"
                    thread.isDaemon = true
                    writers += thread
                    thread.start()
                }
                assertTrue(
                    "writers did not commit while sqlite=$sqliteVersion",
                    writerStarted.await(20, TimeUnit.SECONDS),
                )
                writerFailure.get()?.let { throw it }

                val zip = File(context.cacheDir, "consistent-${System.nanoTime()}.masroof")
                val service = backupService(live, useOnlineBackup)
                val exported = service.exportTo(Uri.fromFile(zip), passphrase.copyOf())
                stop.set(true)
                writers.forEach { thread ->
                    thread.join(20_000)
                    assertFalse("writer did not stop", thread.isAlive)
                }
                writerFailure.get()?.let { throw it }

                assertTrue(exported.exceptionOrNull()?.toString() ?: "export failed", exported.isSuccess)
                assertTrue(live.isOpen)
                assertLivePreferences(preferences)

                val plainZip = decryptEnvelope(zip)
                val snapshot = unzipDatabase(plainZip)
                try {
                    val snapshotBatches = readBatches(snapshot, sqliteVersion)
                    assertConsistent(
                        batches = snapshotBatches,
                        mustInclude = preSeed,
                        mustBeSubsetOf = preSeed + committed,
                        label = "snapshot sqlite=$sqliteVersion",
                    )
                    assertCardOwned(snapshot)
                    assertEquals(MasroofDatabase.IDENTITY_HASH, readIdentityHash(snapshot))
                    assertEquals(MasroofDatabase.VERSION, readUserVersion(snapshot))
                    assertFalse(readJournalMode(snapshot).equals("wal", ignoreCase = true))
                    assertEquals(
                        setOf(
                            BackupPackageFormat.MANIFEST_ENTRY,
                            BackupPackageFormat.DATABASE_ENTRY,
                            BackupPackageFormat.PREFERENCES_ENTRY,
                        ),
                        zipEntryNames(plainZip),
                    )

                    val afterExport = seedBatches(liveDbPath(), POST_EXPORT_ID..POST_EXPORT_ID).single()
                    assertFalse(snapshotBatches.containsKey(afterExport))
                    val restartRequested = AtomicBoolean(false)
                    val importer = DatabaseBackupService(
                        appContext = context,
                        database = live,
                        closeDatabase = { if (live.isOpen) live.close() },
                        appVersionName = "test",
                        clockEpochMillis = { 1_700_000_000_000L },
                        restartProcess = { restartRequested.set(true) },
                        onlineBackupEnabled = useOnlineBackup,
                    )
                    val outcome = importer.importFrom(Uri.fromFile(zip), passphrase.copyOf())
                    assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
                    assertTrue(restartRequested.get())
                    assertFalse(live.isOpen)
                    val reopened = openWalDatabase()
                    try {
                        assertEquals(
                            OwnershipStatus.OWNED,
                            RoomCardRegistryRepository.from(reopened).get(card)!!.ownership,
                        )
                        val importedBatches = readBatches(liveDbPath(), sqliteVersion)
                        assertConsistent(
                            batches = importedBatches,
                            mustInclude = preSeed,
                            mustBeSubsetOf = preSeed + committed,
                            label = "reopened room sqlite=$sqliteVersion online=$useOnlineBackup",
                        )
                        assertFalse(importedBatches.containsKey(afterExport))
                        assertLivePreferences(preferences)
                    } finally {
                        if (reopened.isOpen) reopened.close()
                    }
                } finally {
                    snapshot.parentFile?.deleteRecursively()
                    plainZip.delete()
                    zip.delete()
                }
            } finally {
                stop.set(true)
                writers.forEach { it.join(5_000) }
                if (live.isOpen) live.close()
            }
    }

    @Test(timeout = 60_000)
    fun exportFailure_leavesLiveDatabaseAndPreferencesIntact() {
        runBlocking {
            val live = openWalDatabase()
            try {
                val sqliteVersion = prepareLiveDatabase(live)
                val seeded = seedBatches(liveDbPath(), 1L..1L)
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.OWNED)
                val preferences = captureLivePreferences()
                var clockCalls = 0
                val exported = DatabaseBackupService(
                    appContext = context,
                    database = live,
                    closeDatabase = { error("export must not close the live database") },
                    appVersionName = "test",
                    clockEpochMillis = {
                        clockCalls += 1
                        check(clockCalls == 1) { "forced export failure after snapshot" }
                        1_700_000_000_000L
                    },
                    restartProcess = { error("export must not restart the process") },
                ).exportTo(
                    Uri.fromFile(File(context.cacheDir, "failed-export.masroof")),
                    passphrase.copyOf(),
                )

                assertTrue(exported.exceptionOrNull()?.toString() ?: "export succeeded", exported.isFailure)
                assertTrue(live.isOpen)
                assertLivePreferences(preferences)
                val liveBatches = readBatches(liveDbPath(), sqliteVersion)
                assertConsistent(
                    batches = liveBatches,
                    mustInclude = seeded,
                    mustBeSubsetOf = seeded,
                    label = "live after failed export sqlite=$sqliteVersion",
                )
                assertEquals(OwnershipStatus.OWNED, RoomCardRegistryRepository.from(live).get(card)!!.ownership)
                val followUp = seedBatches(liveDbPath(), 2L..2L)
                val afterWrite = readBatches(liveDbPath(), sqliteVersion)
                assertConsistent(
                    batches = afterWrite,
                    mustInclude = seeded + followUp,
                    mustBeSubsetOf = seeded + followUp,
                    label = "live writable after failed export sqlite=$sqliteVersion",
                )
            } finally {
                if (live.isOpen) live.close()
            }
        }
    }

    private fun openWalDatabase(): MasroofDatabase =
        Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

    private fun prepareLiveDatabase(live: MasroofDatabase): String {
        val db = live.openHelper.writableDatabase
        val journal = db.query("PRAGMA journal_mode").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("wal", journal.lowercase())
        db.query("PRAGMA wal_autocheckpoint = 1").use { it.moveToFirst() }
        db.execSQL(
            """
            CREATE TABLE snapshot_batch (
                batch_id INTEGER NOT NULL PRIMARY KEY,
                row_count INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE snapshot_probe (
                batch_id INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY (batch_id, seq),
                FOREIGN KEY (batch_id) REFERENCES snapshot_batch (batch_id)
            )
            """.trimIndent(),
        )
        return db.query("SELECT sqlite_version()").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
    }

    private fun backupService(live: MasroofDatabase, useOnlineBackup: Boolean = true) = DatabaseBackupService(
        appContext = context,
        database = live,
        closeDatabase = { error("export must not close the live database") },
        appVersionName = "test",
        clockEpochMillis = { 1_700_000_000_000L },
        restartProcess = { error("export must not restart the process") },
        onlineBackupEnabled = useOnlineBackup,
        kdfIterations = TEST_KDF_ITERATIONS,
    )

    private fun seedBatches(databasePath: String, ids: LongRange): Set<Long> {
        val connection = openRaw(databasePath)
        try {
            connection.rawQuery("PRAGMA busy_timeout = 20000", null).use { it.moveToFirst() }
            connection.execSQL("PRAGMA foreign_keys = ON")
            ids.forEach { id -> insertBatchTransaction(connection, id) }
        } finally {
            connection.close()
        }
        return ids.toSet()
    }

    private fun insertBatchTransaction(connection: SQLiteDatabase, id: Long) {
        connection.beginTransaction()
        try {
            connection.execSQL(
                "INSERT INTO snapshot_batch (batch_id, row_count) VALUES (?, ?)",
                arrayOf(id, ROWS_PER_BATCH),
            )
            for (seq in 0 until ROWS_PER_BATCH) {
                connection.execSQL(
                    "INSERT INTO snapshot_probe (batch_id, seq, payload) VALUES (?, ?, ?)",
                    arrayOf(id, seq, payload(id, seq)),
                )
            }
            connection.setTransactionSuccessful()
        } finally {
            connection.endTransaction()
        }
    }

    private fun readBatches(databasePath: File, sqliteVersion: String): Map<Long, ProbeBatch> =
        readBatches(databasePath.path, sqliteVersion)

    private fun readBatches(databasePath: String, sqliteVersion: String): Map<Long, ProbeBatch> {
        val rowCounts = mutableMapOf<Long, Int>()
        val sequences = mutableMapOf<Long, MutableSet<Int>>()
        val payloadsOk = mutableMapOf<Long, Boolean>()
        val payloadErrors = mutableMapOf<Long, String>()
        openReadOnly(databasePath).use { db ->
            assertIntegrity(db, sqliteVersion)
            db.rawQuery("SELECT batch_id, row_count FROM snapshot_batch", null).use { cursor ->
                while (cursor.moveToNext()) {
                    rowCounts[cursor.getLong(0)] = cursor.getInt(1)
                }
            }
            db.rawQuery(
                "SELECT batch_id, seq, length(payload), substr(payload, 1, 24) FROM snapshot_probe",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val seq = cursor.getInt(1)
                    val length = cursor.getInt(2)
                    val prefix = cursor.getString(3)
                    sequences.getOrPut(id) { mutableSetOf() }.add(seq)
                    val expectedPayload = payload(id, seq)
                    val ok = length == expectedPayload.length && prefix == expectedPayload.take(24)
                    if (!ok) {
                        payloadErrors.putIfAbsent(
                            id,
                            "id=$id seq=$seq len=$length expectedLen=${expectedPayload.length} " +
                                "prefix=$prefix expectedPrefix=${expectedPayload.take(24)}",
                        )
                    }
                    payloadsOk[id] = (payloadsOk[id] ?: true) && ok
                }
            }
        }
        return rowCounts.keys.associateWith { id ->
            ProbeBatch(
                expectedRows = rowCounts.getValue(id),
                sequences = sequences[id].orEmpty(),
                payloadsOk = payloadsOk[id] ?: (rowCounts.getValue(id) == 0),
                detail = payloadErrors[id],
            )
        }
    }

    private fun assertConsistent(
        batches: Map<Long, ProbeBatch>,
        mustInclude: Set<Long>,
        mustBeSubsetOf: Set<Long>,
        label: String,
    ) {
        val missing = mustInclude - batches.keys
        assertTrue("$label missing batches $missing", missing.isEmpty())
        val unexpected = batches.keys - mustBeSubsetOf
        assertTrue("$label contains uncommitted batches $unexpected", unexpected.isEmpty())
        for ((id, batch) in batches) {
            assertEquals("$label batch $id row count", batch.expectedRows, batch.sequences.size)
            assertEquals(
                "$label batch $id sequences",
                (0 until batch.expectedRows).toSet(),
                batch.sequences,
            )
            assertTrue("$label batch $id payload torn ${batch.detail}", batch.payloadsOk)
        }
    }

    private fun assertIntegrity(db: SQLiteDatabase, sqliteVersion: String) {
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.moveToNext()) rows += cursor.getString(0)
            assertEquals("integrity_check sqlite=$sqliteVersion", listOf("ok"), rows)
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.moveToNext()) {
                rows += (0 until cursor.columnCount).joinToString(":") { index ->
                    cursor.getString(index) ?: ""
                }
            }
            assertTrue("foreign_key_check sqlite=$sqliteVersion $rows", rows.isEmpty())
        }
    }

    private fun assertCardOwned(databasePath: File) {
        openReadOnly(databasePath.path).use { db ->
            val last4 = checkNotNull(card.last4)
            db.rawQuery(
                "SELECT ownershipStatus FROM card_registry WHERE bankId = ? AND last4 = ?",
                arrayOf(card.bank.id, last4),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(OwnershipStatus.OWNED.name, cursor.getString(0))
            }
        }
    }

    private fun readIdentityHash(databasePath: File): String? =
        openReadOnly(databasePath.path).use { db ->
            db.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

    private fun readUserVersion(databasePath: File): Int =
        openReadOnly(databasePath.path).use { db -> db.version }

    private fun readJournalMode(databasePath: File): String =
        openReadOnly(databasePath.path).use { db ->
            db.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    private fun decryptEnvelope(envelope: File): File {
        val zip = File(context.cacheDir, "snapshot-plain-${System.nanoTime()}.zip")
        envelope.inputStream().use { input ->
            zip.outputStream().use { output ->
                BackupEnvelope.decrypt(input, output, passphrase.copyOf())
            }
        }
        return zip
    }

    private fun unzipDatabase(zip: File): File {
        val dir = File(context.cacheDir, "snapshot-unzip-${System.nanoTime()}")
        check(dir.mkdirs())
        ZipInputStream(zip.inputStream()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == BackupPackageFormat.DATABASE_ENTRY) {
                    File(dir, BackupPackageFormat.DATABASE_ENTRY).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
        val database = File(dir, BackupPackageFormat.DATABASE_ENTRY)
        assertTrue(database.isFile && database.length() > 0L)
        return database
    }

    private fun zipEntryNames(zip: File): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(zip.inputStream()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                names += entry.name.substringAfterLast('/')
                input.closeEntry()
                entry = input.nextEntry
            }
        }
        return names
    }

    private fun captureLivePreferences(): LivePreferences {
        val onboarding = context.getSharedPreferences(
            SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        onboarding.edit()
            .putBoolean("onboarding_started", true)
            .putBoolean("onboarding_completed", true)
            .putBoolean("historical_import_completed", false)
            .putLong("historical_import_start_epoch_millis", 42L)
            .commit()
        context.getSharedPreferences(SharedPrefsAppLocaleRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPrefsAppLocaleRepository.KEY_LANGUAGE_TAG, AppLocale.TAG_EN)
            .commit()
        context.getSharedPreferences(SharedPrefsThemePreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPrefsThemePreferencesRepository.KEY_THEME_MODE, ThemeMode.DARK.name)
            .commit()
        return LivePreferences(
            onboardingStarted = true,
            onboardingCompleted = true,
            historicalImportCompleted = false,
            historicalImportStartEpochMillis = 42L,
            languageTag = AppLocale.TAG_EN,
            themeMode = ThemeMode.DARK.name,
        )
    }

    private fun assertLivePreferences(expected: LivePreferences) {
        val onboarding = context.getSharedPreferences(
            SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val locale = context.getSharedPreferences(
            SharedPrefsAppLocaleRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val theme = context.getSharedPreferences(
            SharedPrefsThemePreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        assertEquals(expected.onboardingStarted, onboarding.getBoolean("onboarding_started", false))
        assertEquals(expected.onboardingCompleted, onboarding.getBoolean("onboarding_completed", false))
        assertEquals(
            expected.historicalImportCompleted,
            onboarding.getBoolean("historical_import_completed", true),
        )
        assertEquals(
            expected.historicalImportStartEpochMillis,
            onboarding.getLong("historical_import_start_epoch_millis", 0L),
        )
        assertEquals(
            expected.languageTag,
            locale.getString(SharedPrefsAppLocaleRepository.KEY_LANGUAGE_TAG, null),
        )
        assertEquals(
            expected.themeMode,
            theme.getString(SharedPrefsThemePreferencesRepository.KEY_THEME_MODE, null),
        )
    }

    private fun liveDbPath(): String = context.getDatabasePath(MasroofDatabase.NAME).path

    private fun openRaw(path: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun openReadOnly(path: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    private fun payload(id: Long, seq: Int): String = "$id:$seq:" + "p".repeat(PAYLOAD_CHARS)

    private fun isDatabaseLocked(error: SQLiteException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("locked", ignoreCase = true) || message.contains("busy", ignoreCase = true)
    }

    private data class ProbeBatch(
        val expectedRows: Int,
        val sequences: Set<Int>,
        val payloadsOk: Boolean,
        val detail: String?,
    )

    private data class LivePreferences(
        val onboardingStarted: Boolean,
        val onboardingCompleted: Boolean,
        val historicalImportCompleted: Boolean,
        val historicalImportStartEpochMillis: Long,
        val languageTag: String,
        val themeMode: String,
    )

    private companion object {
        const val PRESEED_BATCHES = 12
        const val ROWS_PER_BATCH = 3
        const val PAYLOAD_CHARS = 4_096
        const val WRITER_COUNT = 2
        const val WRITER_ID_START = 10_000L
        const val POST_EXPORT_ID = 9_000_000L
        const val TEST_KDF_ITERATIONS = 4_096
    }
}
