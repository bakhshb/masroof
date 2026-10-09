package com.baraa.masroof.instrumentation

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.DatabaseBackupService
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The pre-3.27 snapshot path has to run on a device SQLite that does not implement
 * `VACUUM INTO`. API 26–28 ship that SQLite. Robolectric's newer engine is not a substitute.
 */
@RunWith(AndroidJUnit4::class)
class PreVacuumBackupSnapshotTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card = CardReference(Bank.BANK_ALJAZIRA, "4242")

    @Before
    fun resetDatabase() {
        context.deleteDatabase(MasroofDatabase.NAME)
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(MasroofDatabase.NAME)
    }

    @Test(timeout = 180_000)
    fun concurrentExport_onPre327Sqlite_importsAndReopensRoom() {
        val live = openDatabase()
        val stop = AtomicBoolean(false)
        var writer: Thread? = null
        try {
            val sqliteVersion = sqliteVersion(live)
            assumeTrue(
                "Pre-3.27 snapshot proof runs on API 26-28. This device is API ${Build.VERSION.SDK_INT} sqlite $sqliteVersion.",
                Build.VERSION.SDK_INT in 26..28,
            )
            assertTrue(
                "API ${Build.VERSION.SDK_INT} sqlite $sqliteVersion must be older than 3.27",
                sqliteBefore327(sqliteVersion),
            )
            live.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE snapshot_batch (
                    batch_id INTEGER NOT NULL PRIMARY KEY,
                    marker TEXT NOT NULL
                )
                """.trimIndent(),
            )
            insertBatch(liveDbPath(), 1L, "preseed")
            runBlocking {
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.OWNED)
            }

            val writerFailure = AtomicReference<Throwable>(null)
            val writerStarted = CountDownLatch(1)
            writer = Thread {
                val connection = SQLiteDatabase.openDatabase(
                    liveDbPath(),
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
                try {
                    connection.rawQuery("PRAGMA busy_timeout = 20000", null).use { it.moveToFirst() }
                    var id = 100L
                    while (!stop.get() && writerFailure.get() == null) {
                        try {
                            insertBatch(connection, id, "writer")
                            id += 1
                            writerStarted.countDown()
                        } catch (error: SQLiteException) {
                            val message = error.message.orEmpty()
                            if (!message.contains("locked", ignoreCase = true) &&
                                !message.contains("busy", ignoreCase = true)
                            ) {
                                throw error
                            }
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
            }.also {
                it.name = "pre-vacuum-writer"
                it.isDaemon = true
                it.start()
            }
            assertTrue(writerStarted.await(20, TimeUnit.SECONDS))
            writerFailure.get()?.let { throw it }

            val zip = File(context.cacheDir, "pre-vacuum-${System.nanoTime()}.masroof")
            val exported = runBlocking {
                DatabaseBackupService(
                    appContext = context,
                    database = live,
                    closeDatabase = { error("export must not close the live database") },
                    appVersionName = "test",
                    clockEpochMillis = { 1_700_000_000_000L },
                    restartProcess = { error("export must not restart the process") },
                ).exportTo(Uri.fromFile(zip))
            }
            stop.set(true)
            writer.join(20_000)
            assertFalse(writer.isAlive)
            writerFailure.get()?.let { throw it }
            assertTrue(exported.exceptionOrNull()?.toString() ?: "export failed", exported.isSuccess)

            insertBatch(liveDbPath(), 9_000_000L, "after-export")
            val restartRequested = AtomicBoolean(false)
            val outcome = runBlocking {
                DatabaseBackupService(
                    appContext = context,
                    database = live,
                    closeDatabase = { if (live.isOpen) live.close() },
                    appVersionName = "test",
                    clockEpochMillis = { 1_700_000_000_000L },
                    restartProcess = { restartRequested.set(true) },
                ).importFrom(Uri.fromFile(zip))
            }
            assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
            assertTrue(restartRequested.get())

            val reopened = openDatabase()
            try {
                val ownership = runBlocking {
                    RoomCardRegistryRepository.from(reopened).get(card)!!.ownership
                }
                assertEquals(OwnershipStatus.OWNED, ownership)
                val markers = markers(liveDbPath())
                assertTrue(markers.contains(1L to "preseed"))
                assertFalse(markers.contains(9_000_000L to "after-export"))
                markers.filter { it.second == "writer" }.forEach { (id, _) ->
                    assertTrue(id >= 100L)
                }
            } finally {
                if (reopened.isOpen) reopened.close()
                zip.delete()
            }
        } finally {
            stop.set(true)
            writer?.join(5_000)
            if (live.isOpen) live.close()
        }
    }

    private fun openDatabase(): MasroofDatabase =
        Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

    private fun liveDbPath(): String = context.getDatabasePath(MasroofDatabase.NAME).path

    private fun sqliteVersion(database: MasroofDatabase): String =
        database.openHelper.writableDatabase.query("SELECT sqlite_version()").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun sqliteBefore327(version: String): Boolean {
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major < 3 || (major == 3 && minor < 27)
    }

    private fun insertBatch(databasePath: String, id: Long, marker: String) {
        val connection = SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            insertBatch(connection, id, marker)
        } finally {
            connection.close()
        }
    }

    private fun insertBatch(connection: SQLiteDatabase, id: Long, marker: String) {
        connection.execSQL(
            "INSERT OR REPLACE INTO snapshot_batch(batch_id, marker) VALUES (?, ?)",
            arrayOf(id, marker),
        )
    }

    private fun markers(databasePath: String): Set<Pair<Long, String>> {
        val connection = SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READONLY)
        return connection.use { db ->
            db.rawQuery("SELECT batch_id, marker FROM snapshot_batch", null).use { cursor ->
                val rows = mutableSetOf<Pair<Long, String>>()
                while (cursor.moveToNext()) {
                    rows += cursor.getLong(0) to cursor.getString(1)
                }
                rows
            }
        }
    }
}
