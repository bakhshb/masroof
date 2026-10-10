package com.baraa.masroof.instrumentation

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.DatabaseBackupService
import com.baraa.masroof.application.backup.DatabaseRestoreRecovery
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real emulator SQLite: stage callbacks are deterministic barriers, never timing sleeps. */
@RunWith(AndroidJUnit4::class)
class RestoreConcurrencyTest {
    @Test(timeout = 120_000)
    fun ungatedOldRoomRepository_reopensLivePathDuringOldParkedStage() = runBlocking {
        checkOnEmulator()
        scenario(sharedGate = false)
    }

    @Test(timeout = 120_000)
    fun sharedGate_preventsOldProcessAccessDuringSwapAndRequiresNewRoomInstance() = runBlocking {
        checkOnEmulator()
        scenario(sharedGate = true)
    }

    private suspend fun scenario(sharedGate: Boolean) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = if (sharedGate) "gated" else "ungated"
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File =
                if (name == MasroofDatabase.NAME) File(super.getDatabasePath(name).parentFile, "restore-$tag.db")
                else super.getDatabasePath(name)
        }
        val path = context.getDatabasePath(MasroofDatabase.NAME)
        path.delete()
        listOf("-wal", "-shm", "-journal").forEach { File(path.path + it).delete() }
        val database = open(context)
        val gate = DatabaseAccessGate()
        val raw = RoomRawSmsRepository(database.rawSmsDao(), if (sharedGate) gate else DatabaseAccessGate())
        val backup = File(context.cacheDir, "restore-$tag.masroof")
        var observedStage = false
        var restarted = false
        try {
            val exporter = DatabaseBackupService(
                context, database, { error("export cannot close Room") }, "test",
                restartProcess = {}, kdfIterations = 1_000, databaseAccessGate = gate,
            )
            assertTrue(exporter.exportTo(Uri.fromFile(backup), "test-phrase".toCharArray()).isSuccess)
            val importer = DatabaseBackupService(
                context, database, { database.close() }, "test",
                restartProcess = { restarted = true }, kdfIterations = 1_000, databaseAccessGate = gate,
                afterRestoreStage = { stage ->
                    if (stage == DatabaseRestoreRecovery.Stage.OLD_PARKED) {
                        observedStage = true
                        assertFalse("live path must be absent before incoming install", path.exists())
                        runBlocking {
                            if (sharedGate) {
                                try {
                                    raw.insertIfAbsent(message())
                                    error("Old process access must be rejected")
                                } catch (_: DatabaseRestartRequiredException) {
                                    assertFalse("rejected access cannot reopen live", path.exists())
                                }
                            } else {
                                assertEquals(RawSmsInsertResult.Inserted, raw.insertIfAbsent(message()))
                                assertTrue("ungated Room recreated live while original was parked", path.exists())
                                database.close()
                                error("stop the unsafe baseline probe before installation")
                            }
                        }
                    }
                },
            )
            val outcome = importer.importFrom(Uri.fromFile(backup), "test-phrase".toCharArray())
            assertTrue(observedStage)
            assertTrue(restarted)
            assertEquals(if (sharedGate) BackupImportOutcome.SuccessNeedsRestart else BackupImportOutcome.Failed, outcome)
            val reopened = open(context)
            try {
                assertEquals(0, reopened.rawSmsDao().count())
                reopened.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            } finally {
                reopened.close()
            }
            assertFalse(File(path.path + ".restore-journal").exists())
        } finally {
            database.close()
            backup.delete()
            path.delete()
            listOf("-wal", "-shm", "-journal").forEach { File(path.path + it).delete() }
        }
    }

    private fun open(context: Context): MasroofDatabase =
        Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS).build()

    private fun message() = RawSms(
        "restore-concurrent-capture", "AlJazira", "test-only-evidence", Instant.parse("2026-08-03T10:00:00Z"),
        null, "test-hash",
    )

    private fun checkOnEmulator() {
        assertFalse(Build.FINGERPRINT.equals("robolectric", ignoreCase = true))
        assertTrue(Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu"))
    }
}
