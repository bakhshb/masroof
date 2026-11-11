package com.baraa.masroof.instrumentation

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.backup.BackupVerificationOutcome
import com.baraa.masroof.application.backup.DatabaseBackupService
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.baraa.masroof.testsupport.NoOpAppLog

/** Read-only verification on actual Android SQLite, with an isolated synthetic database. */
@RunWith(AndroidJUnit4::class)
class BackupPreviewVerificationTest {
    @Test
    fun authenticatedPreviewNeverClosesOrChangesTheOpenDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fidelity-verification-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, MasroofDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val closed = AtomicBoolean(false)
        val file = File(context.cacheDir, "fidelity-verification.masroof")
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("CREATE TABLE verification_probe (marker TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO verification_probe VALUES ('synthetic-marker')")
            val service = DatabaseBackupService(context, database, { closed.set(true) }, "test", restartProcess = { error("Verification must not restart") }, appLogService = NoOpAppLog)
            assertTrue(service.exportTo(Uri.fromFile(file), "synthetic-test-password".toCharArray()).isSuccess)
            val password = "synthetic-test-password".toCharArray()
            assertEquals(BackupVerificationOutcome.VERIFIED, service.verify(Uri.fromFile(file), password))
            assertTrue(password.all { it == '\u0000' })
            assertFalse(closed.get())
            assertTrue(database.isOpen)
            sqlite.query("SELECT marker FROM verification_probe").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("synthetic-marker", cursor.getString(0))
            }
            assertEquals(BackupVerificationOutcome.AUTHENTICATION_FAILED,
                service.verify(Uri.fromFile(file), "wrong-password".toCharArray()))
            assertFalse(closed.get())
        } finally {
            database.close(); context.deleteDatabase(name); file.delete()
        }
    }
}
