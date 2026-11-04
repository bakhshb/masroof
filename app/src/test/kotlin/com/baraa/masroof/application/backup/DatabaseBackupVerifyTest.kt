package com.baraa.masroof.application.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Verify and import must agree that a malformed but correctly encrypted package is invalid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupVerifyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val passphrase = "passphrase-DO-NOT-LOG-7f3a".toCharArray()

    @Before
    fun resetStorage() {
        context.deleteDatabase(MasroofDatabase.NAME)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
    }

    @Test
    fun verifyAndImport_agreeThatAMalformedEnvelopeIsAnInvalidPackage() = runBlocking {
        val live = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            live.openHelper.writableDatabase
            val service = DatabaseBackupService(
                appContext = context,
                database = live,
                closeDatabase = { error("live database must stay open") },
                appVersionName = "test",
                clockEpochMillis = { 1_700_000_000_000L },
                restartProcess = { error("unexpected restart") },
            )
            val missingPreferences = encryptedPackage(
                BackupPackageFormat.MANIFEST_ENTRY to byteArrayOf(1),
                BackupPackageFormat.DATABASE_ENTRY to byteArrayOf(2),
            )
            val badManifest = encryptedPackage(
                BackupPackageFormat.MANIFEST_ENTRY to "not json".toByteArray(),
                BackupPackageFormat.DATABASE_ENTRY to byteArrayOf(2),
                BackupPackageFormat.PREFERENCES_ENTRY to byteArrayOf(3),
            )
            for (file in listOf(missingPreferences, badManifest)) {
                assertEquals(BackupVerificationOutcome.INVALID_PACKAGE, service.verify(Uri.fromFile(file), passphrase.copyOf()))
                assertEquals(BackupImportOutcome.InvalidPackage, service.importFrom(Uri.fromFile(file), passphrase.copyOf()))
                file.delete()
            }
            assertTrue(live.isOpen)
        } finally { if (live.isOpen) live.close() }
    }

    private fun encryptedPackage(vararg entries: Pair<String, ByteArray>): File {
        val file = File(context.cacheDir, "envelope-${System.nanoTime()}.masroof")
        FileOutputStream(file).use { output ->
            BackupEnvelope.encrypt(output, passphrase.copyOf(), TEST_KDF_ITERATIONS) { plain ->
                ZipOutputStream(plain).use { zip ->
                    entries.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
        }
        return file
    }

    private companion object {
        const val TEST_KDF_ITERATIONS = 4_096
    }
}
