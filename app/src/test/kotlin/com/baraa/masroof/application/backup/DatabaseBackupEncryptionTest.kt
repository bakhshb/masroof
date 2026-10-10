package com.baraa.masroof.application.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.OwnershipStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupEncryptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val card = CardReference(Bank.BANK_ALJAZIRA, "7271")
    private val passphrase = "passphrase-DO-NOT-LOG-7f3a".toCharArray()

    @Before
    fun resetStorage() {
        context.deleteDatabase(MasroofDatabase.NAME)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
        context.getSharedPreferences(SharedPrefsOnboardingPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("onboarding_completed", false)
            .commit()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("masroof-backup-") }?.forEach {
            it.deleteRecursively()
        }
    }

    @Test
    fun export_isNotAZipAndDoesNotContainSmsOrSqliteHeader() {
        runBlocking {
            val live = openDatabase()
            try {
                insertProbe(live)
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.OWNED)
                val destination = File(context.cacheDir, "encrypted-export.masroof")
                val logs = AppLogService(context)
                val exported = service(live, logs).exportTo(Uri.fromFile(destination), passphrase.copyOf())
                assertTrue(exported.exceptionOrNull()?.toString(), exported.isSuccess)
                val bytes = destination.readBytes()
                assertTrue(BackupEnvelope.looksLikeEnvelope(bytes, bytes.size))
                assertFalse(bytes.copyOfRange(0, 4).contentEquals(ZIP_LOCAL))
                assertFalse(contains(bytes, SMS_FIXTURE.toByteArray(Charsets.UTF_8)))
                assertFalse(contains(bytes, "SQLite format 3".toByteArray(Charsets.US_ASCII)))
                assertFalse(contains(bytes, passphrase.concatToString().toByteArray(Charsets.UTF_8)))
                assertTrue(logs.readAll().none { it.message.contains(passphrase.concatToString()) })
                assertEquals(TEST_KDF_ITERATIONS, readIterations(bytes))
            } finally {
                if (live.isOpen) live.close()
            }
        }
    }

    @Test
    fun encryptedExport_roundTripsThroughImport() {
        runBlocking {
            val live = openDatabase()
            val destination = File(context.cacheDir, "round-trip.masroof")
            val restartRequested = AtomicBoolean(false)
            try {
                insertProbe(live)
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.OWNED)
                val exported = service(live).exportTo(Uri.fromFile(destination), passphrase.copyOf())
                assertTrue(exported.exceptionOrNull()?.toString(), exported.isSuccess)
                live.openHelper.writableDatabase.execSQL("DELETE FROM sms_probe")
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.EXTERNAL)
                val outcome = DatabaseBackupService(
                    appContext = context,
                    database = live,
                    closeDatabase = { if (live.isOpen) live.close() },
                    appVersionName = "test",
                    clockEpochMillis = { 1_700_000_000_000L },
                    restartProcess = {
                        assertNoBackupStaging()
                        restartRequested.set(true)
                    },
                    kdfIterations = TEST_KDF_ITERATIONS,
                ).importFrom(Uri.fromFile(destination), passphrase.copyOf())
                assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
                assertTrue(restartRequested.get())
            } finally {
                if (live.isOpen) live.close()
            }
            val reopened = openDatabase()
            try {
                assertEquals(
                    OwnershipStatus.OWNED,
                    RoomCardRegistryRepository.from(reopened).get(card)!!.ownership,
                )
                reopened.openHelper.readableDatabase.query("SELECT body FROM sms_probe").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(SMS_FIXTURE, cursor.getString(0))
                }
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun wrongPassphraseAndTamperedByte_doNotCloseOrChangeTheLiveDatabase() {
        runBlocking {
            val live = openDatabase()
            try {
                insertProbe(live)
                val logs = AppLogService(context)
                logs.clear()
                val destination = File(context.cacheDir, "auth-check.masroof")
                assertTrue(
                    service(live, logs).exportTo(Uri.fromFile(destination), passphrase.copyOf()).isSuccess,
                )
                val before = digest(context.getDatabasePath(MasroofDatabase.NAME))
                val closed = AtomicBoolean(false)
                val importer = service(live, logs, closeDatabase = { closed.set(true) })
                val wrong = "wrong-passphrase-DO-NOT-LOG-7f3a"
                val wrongOutcome = importer.importFrom(Uri.fromFile(destination), wrong.toCharArray())
                assertEquals(BackupImportOutcome.AuthenticationFailed, wrongOutcome)
                assertFalse(closed.get())
                assertEquals(before, digest(context.getDatabasePath(MasroofDatabase.NAME)))
                assertFalse(onboardingCompleted())
                assertTrue(live.isOpen)

                val tampered = destination.readBytes()
                val flipAt = BackupEnvelope.HEADER_SIZE
                tampered[flipAt] = (tampered[flipAt].toInt() xor 0x01).toByte()
                destination.writeBytes(tampered)
                val tamperedOutcome = importer.importFrom(Uri.fromFile(destination), passphrase.copyOf())
                assertEquals(BackupImportOutcome.AuthenticationFailed, tamperedOutcome)
                assertFalse(closed.get())
                assertEquals(before, digest(context.getDatabasePath(MasroofDatabase.NAME)))
                assertFalse(onboardingCompleted())
                val logged = logs.readAll().joinToString("\n") { it.message }
                assertFalse(logged.contains(wrong))
                assertFalse(logged.contains(passphrase.concatToString()))
                assertTrue(logged.contains(BackupFailureCategory.AUTHENTICATION_FAILED.logToken))
                assertNoBackupStaging()
            } finally {
                if (live.isOpen) live.close()
            }
        }
    }

    @Test
    fun legacyZip_isRejectedUntilConfirmed_andCancelLeavesTheDatabase() {
        runBlocking {
            val live = openDatabase()
            try {
                RoomCardRegistryRepository.from(live).setOwnership(card, OwnershipStatus.EXTERNAL)
                val zip = legacyZip()
                val before = digest(context.getDatabasePath(MasroofDatabase.NAME))
                val closed = AtomicBoolean(false)
                val importer = service(live, closeDatabase = { closed.set(true) })
                val cancelled = importer.importFrom(Uri.fromFile(zip))
                assertEquals(BackupImportOutcome.LegacyConfirmationRequired, cancelled)
                assertFalse(closed.get())
                assertEquals(before, digest(context.getDatabasePath(MasroofDatabase.NAME)))
                assertFalse(onboardingCompleted())
                assertEquals(
                    OwnershipStatus.EXTERNAL,
                    RoomCardRegistryRepository.from(live).get(card)!!.ownership,
                )

                val restartRequested = AtomicBoolean(false)
                val confirmed = DatabaseBackupService(
                    appContext = context,
                    database = live,
                    closeDatabase = { if (live.isOpen) live.close() },
                    appVersionName = "test",
                    clockEpochMillis = { 1_700_000_000_000L },
                    restartProcess = {
                        assertNoBackupStaging()
                        restartRequested.set(true)
                    },
                ).importFrom(Uri.fromFile(zip), confirmLegacyPlaintext = true)
                assertEquals(BackupImportOutcome.SuccessNeedsRestart, confirmed)
                assertTrue(restartRequested.get())
                assertTrue(onboardingCompleted())
                zip.delete()
            } finally {
                if (live.isOpen) live.close()
            }
            val reopened = openDatabase()
            try {
                assertEquals(
                    OwnershipStatus.OWNED,
                    RoomCardRegistryRepository.from(reopened).get(card)!!.ownership,
                )
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun hostileZip_doesNotModifyTheLiveDatabase() {
        runBlocking {
            val live = openDatabase()
            try {
                insertProbe(live)
                val before = digest(context.getDatabasePath(MasroofDatabase.NAME))
                val closed = AtomicBoolean(false)
                val importer = service(
                    live,
                    closeDatabase = { closed.set(true) },
                    archiveLimits = BackupArchiveLimits(
                        maxEntries = 3,
                        maxUncompressedBytesPerEntry = 32,
                        maxTotalUncompressedBytes = 32,
                    ),
                )
                val cases = listOf(
                    hostileZip(
                        BackupPackageFormat.MANIFEST_ENTRY to byteArrayOf(1),
                        BackupPackageFormat.DATABASE_ENTRY to byteArrayOf(2),
                        BackupPackageFormat.PREFERENCES_ENTRY to byteArrayOf(3),
                        "extra.txt" to byteArrayOf(4),
                    ),
                    hostileZip(
                        "nested/${BackupPackageFormat.MANIFEST_ENTRY}" to byteArrayOf(1, 2, 3),
                        BackupPackageFormat.DATABASE_ENTRY to byteArrayOf(4),
                        BackupPackageFormat.PREFERENCES_ENTRY to byteArrayOf(5),
                    ),
                    hostileZip(BackupPackageFormat.MANIFEST_ENTRY to ByteArray(64) { 7 }),
                )
                cases.forEach { zip ->
                    val outcome = importer.importFrom(Uri.fromFile(zip), confirmLegacyPlaintext = true)
                    assertEquals(BackupImportOutcome.InvalidPackage, outcome)
                    zip.delete()
                }
                val encryptedHostile = File(context.cacheDir, "encrypted-hostile.masroof")
                FileOutputStream(encryptedHostile).use { output ->
                    BackupEnvelope.encrypt(output, passphrase.copyOf(), TEST_KDF_ITERATIONS) { plain ->
                        ZipOutputStream(plain).use { zip ->
                            listOf(
                                BackupPackageFormat.MANIFEST_ENTRY,
                                BackupPackageFormat.DATABASE_ENTRY,
                                BackupPackageFormat.PREFERENCES_ENTRY,
                                "notes.txt",
                            ).forEach { name ->
                                zip.putNextEntry(ZipEntry(name))
                                zip.write(byteArrayOf(1, 2, 3, 4))
                                zip.closeEntry()
                            }
                        }
                    }
                }
                val encryptedOutcome = importer.importFrom(
                    Uri.fromFile(encryptedHostile),
                    passphrase.copyOf(),
                )
                assertEquals(BackupImportOutcome.InvalidPackage, encryptedOutcome)
                assertFalse(closed.get())
                assertEquals(before, digest(context.getDatabasePath(MasroofDatabase.NAME)))
                assertTrue(live.isOpen)
                assertNoBackupStaging()
            } finally {
                if (live.isOpen) live.close()
            }
        }
    }

    @Test
    fun oversizedAuthenticatedEnvelope_stopsWhileDecrypting_andLeavesTheLiveDatabaseOpen() {
        runBlocking {
            val live = openDatabase()
            try {
                insertProbe(live)
                val before = digest(context.getDatabasePath(MasroofDatabase.NAME))
                val closed = AtomicBoolean(false)
                val logs = AppLogService(context).also { it.clear() }
                val importer = service(
                    live,
                    logs = logs,
                    closeDatabase = { closed.set(true) },
                    archiveLimits = BackupArchiveLimits(
                        maxEntries = 3,
                        maxUncompressedBytesPerEntry = 32,
                        maxTotalUncompressedBytes = 32,
                    ),
                )
                val envelope = File(context.cacheDir, "oversized-envelope.masroof")
                FileOutputStream(envelope).use { output ->
                    BackupEnvelope.encrypt(output, passphrase.copyOf(), TEST_KDF_ITERATIONS) { plain ->
                        plain.write(ByteArray(256) { it.toByte() })
                    }
                }
                val abandoned = File(context.cacheDir, "masroof-backup-import-killed")
                abandoned.mkdirs()
                File(abandoned, "decrypted-package.zip").writeBytes(byteArrayOf(1, 2, 3, 4))
                val outcome = importer.importFrom(Uri.fromFile(envelope), passphrase.copyOf())
                assertEquals(BackupImportOutcome.InvalidPackage, outcome)
                assertEquals(
                    "Database import failed: ${BackupFailureCategory.ENVELOPE_TOO_LARGE.logToken}",
                    logs.readAll().single().message,
                )
                assertFalse(closed.get())
                assertTrue(live.isOpen)
                assertEquals(before, digest(context.getDatabasePath(MasroofDatabase.NAME)))
                assertFalse(abandoned.exists())
                assertNoBackupStaging()
                envelope.delete()
            } finally {
                if (live.isOpen) live.close()
            }
        }
    }

    @Test
    fun encryptedCompressionBomb_isRejectedByTheArchiveValidator() {
        runBlocking {
            val live = openDatabase()
            try {
                insertProbe(live)
                val before = digest(context.getDatabasePath(MasroofDatabase.NAME))
                val closed = AtomicBoolean(false)
                val logs = AppLogService(context).also { it.clear() }
                // The decrypted ZIP stays under the streaming cap. The uncompressed
                // entry exceeds M4's per-entry limit, so the archive validator rejects it.
                val importer = service(
                    live,
                    logs = logs,
                    closeDatabase = { closed.set(true) },
                    archiveLimits = BackupArchiveLimits(
                        maxEntries = 3,
                        maxUncompressedBytesPerEntry = 64,
                        maxTotalUncompressedBytes = 64 * 1024,
                    ),
                )
                val envelope = File(context.cacheDir, "encrypted-bomb.masroof")
                FileOutputStream(envelope).use { output ->
                    BackupEnvelope.encrypt(output, passphrase.copyOf(), TEST_KDF_ITERATIONS) { plain ->
                        ZipOutputStream(plain).use { zip ->
                            zip.putNextEntry(ZipEntry(BackupPackageFormat.MANIFEST_ENTRY))
                            zip.write(ByteArray(8 * 1024))
                            zip.closeEntry()
                        }
                    }
                }
                assertTrue(envelope.length() < 64 * 1024)
                val outcome = importer.importFrom(Uri.fromFile(envelope), passphrase.copyOf())
                assertEquals(BackupImportOutcome.InvalidPackage, outcome)
                assertEquals(
                    BackupArchiveRejection.ENTRY_TOO_LARGE.logMessage(),
                    logs.readAll().single().message,
                )
                assertFalse(closed.get())
                assertTrue(live.isOpen)
                assertEquals(before, digest(context.getDatabasePath(MasroofDatabase.NAME)))
                assertNoBackupStaging()
                envelope.delete()
            } finally {
                if (live.isOpen) live.close()
            }
        }
    }

    @Test
    fun restoreFailureAfterIncomingCopy_removesIncomingAndExtractedStaging() = runBlocking {
        val live = openDatabase()
        try {
            insertProbe(live)
            val archive = File(context.cacheDir, "cleanup-failure.masroof")
            assertTrue(service(live).exportTo(Uri.fromFile(archive), passphrase.copyOf()).isSuccess)
            val importer = DatabaseBackupService(
                appContext = context,
                database = live,
                closeDatabase = { live.close() },
                appVersionName = "test",
                restartProcess = { error("failure must not restart") },
                beforeValidatedInstall = { error("injected install failure") },
                kdfIterations = TEST_KDF_ITERATIONS,
            )
            assertEquals(BackupImportOutcome.Failed, importer.importFrom(Uri.fromFile(archive), passphrase.copyOf()))
            assertNoBackupStaging()
            val incoming = DatabaseRestoreRecovery.incomingFile(context.getDatabasePath(MasroofDatabase.NAME))
            assertFalse(incoming.exists())
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                assertFalse(File(incoming.path + suffix).exists())
            }
        } finally {
            if (live.isOpen) live.close()
        }
    }

    private fun service(
        database: MasroofDatabase,
        logs: AppLogService? = null,
        closeDatabase: () -> Unit = { error("live database must stay open") },
        archiveLimits: BackupArchiveLimits = BackupArchiveLimits.PRODUCTION,
    ) = DatabaseBackupService(
        appContext = context,
        database = database,
        closeDatabase = closeDatabase,
        appVersionName = "test",
        appLogService = logs,
        clockEpochMillis = { 1_700_000_000_000L },
        restartProcess = { error("unexpected restart") },
        kdfIterations = TEST_KDF_ITERATIONS,
        archiveLimits = archiveLimits,
    )

    private fun openDatabase(): MasroofDatabase =
        Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun insertProbe(database: MasroofDatabase) {
        val db = database.openHelper.writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_probe (body TEXT NOT NULL)")
        db.execSQL("DELETE FROM sms_probe")
        db.execSQL("INSERT INTO sms_probe (body) VALUES (?)", arrayOf(SMS_FIXTURE))
    }

    private suspend fun legacyZip(): File {
        val sourceName = "legacy-source"
        context.deleteDatabase(sourceName)
        val source = Room.databaseBuilder(context, MasroofDatabase::class.java, sourceName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            RoomCardRegistryRepository.from(source).setOwnership(card, OwnershipStatus.OWNED)
            source.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val dbFile = File(context.cacheDir, "legacy-source.db")
            context.getDatabasePath(sourceName).copyTo(dbFile, overwrite = true)
            val zip = File(context.cacheDir, "legacy-v1.masroof")
            val manifest = BackupManifest(
                formatVersion = BackupPackageFormat.FORMAT_VERSION,
                appVersionName = "test",
                roomVersion = MasroofDatabase.VERSION,
                identityHash = MasroofDatabase.IDENTITY_HASH,
                exportedAtEpochMillis = 1_700_000_000_000L,
            )
            val preferences = BackupPreferencesSnapshot(
                onboardingStarted = true,
                onboardingCompleted = true,
                historicalImportCompleted = true,
                languageTag = AppLocale.DEFAULT_TAG,
                themeMode = ThemeMode.DEFAULT.name,
            )
            ZipOutputStream(FileOutputStream(zip)).use { output ->
                output.putNextEntry(ZipEntry(BackupPackageFormat.MANIFEST_ENTRY))
                output.write(BackupPackageCodec.encodeManifest(manifest).toByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry(BackupPackageFormat.DATABASE_ENTRY))
                dbFile.inputStream().use { it.copyTo(output) }
                output.closeEntry()
                output.putNextEntry(ZipEntry(BackupPackageFormat.PREFERENCES_ENTRY))
                output.write(BackupPackageCodec.encodePreferences(preferences).toByteArray())
                output.closeEntry()
            }
            dbFile.delete()
            return zip
        } finally {
            source.close()
            context.deleteDatabase(sourceName)
        }
    }

    private fun hostileZip(vararg entries: Pair<String, ByteArray>): File {
        val file = File(context.cacheDir, "hostile-${System.nanoTime()}.masroof")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun onboardingCompleted(): Boolean =
        context.getSharedPreferences(SharedPrefsOnboardingPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("onboarding_completed", false)

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readIterations(envelope: ByteArray): Int =
        ((envelope[10].toInt() and 0xff) shl 24) or
            ((envelope[11].toInt() and 0xff) shl 16) or
            ((envelope[12].toInt() and 0xff) shl 8) or
            (envelope[13].toInt() and 0xff)

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun assertNoBackupStaging() {
        val leftovers = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("masroof-backup-") }
        assertTrue(leftovers.joinToString { it.name }, leftovers.isEmpty())
    }

    private companion object {
        const val SMS_FIXTURE = "MASROOF-SMS-FIXTURE-do-not-leak-9f3c2a-رسالة"
        const val TEST_KDF_ITERATIONS = 4_096
        val ZIP_LOCAL: ByteArray = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    }
}
