package com.baraa.masroof.application.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogLevel
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveValidatorTest {
    @Suppress("DEPRECATION", "removal")
    @Test
    fun partialDatabaseIsZeroedBeforeUnlink_onLimitAndCrcFailures() {
        val cases = listOf(
            ZipFixtures.zipBytes(BackupPackageFormat.DATABASE_ENTRY to ByteArray(20_000) { 77 }) to
                BackupArchiveLimits(3, 8, 100_000),
            ZipFixtures.storedZipBytes(BackupPackageFormat.DATABASE_ENTRY, ByteArray(20_000) { 77 }).also {
                val payloadOffset = 30 + BackupPackageFormat.DATABASE_ENTRY.toByteArray().size
                it[payloadOffset] = 78
            } to BackupArchiveLimits(3, 100_000, 100_000),
        )
        cases.forEach { (archive, limits) ->
            withStaging { staging ->
                val target = File(staging, BackupPackageFormat.DATABASE_ENTRY)
                val old = System.getSecurityManager()
                var checked = false
                System.setSecurityManager(object : SecurityManager() {
                    override fun checkPermission(permission: java.security.Permission) = Unit
                    override fun checkDelete(file: String) {
                        if (file == target.path && target.exists()) {
                            val bytes = target.readBytes()
                            assertTrue("the rejection wrote a sensitive prefix", bytes.isNotEmpty())
                            assertTrue("every byte must be zero before unlink", bytes.all { it.toInt() == 0 })
                            checked = true
                        }
                    }
                })
                try {
                    assertThrows(BackupArchiveException::class.java) {
                        BackupArchiveValidator(limits).extractInto(archive.inputStream(), staging)
                    }
                    assertTrue(checked)
                    assertFalse(target.exists())
                } finally {
                    System.setSecurityManager(old)
                }
            }
        }
    }

    @Test
    fun productionLimits_areThreeEntriesAnd256Mebibytes() {
        val mebibyte = 1024L * 1024L
        assertEquals(3, BackupArchiveLimits.MAX_ENTRIES)
        assertEquals(256L * mebibyte, BackupArchiveLimits.MAX_UNCOMPRESSED_BYTES_PER_ENTRY)
        assertEquals(256L * mebibyte, BackupArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES)
        assertEquals(BackupArchiveLimits.MAX_ENTRIES, BackupArchiveLimits.PRODUCTION.maxEntries)
        assertEquals(
            BackupArchiveLimits.MAX_UNCOMPRESSED_BYTES_PER_ENTRY,
            BackupArchiveLimits.PRODUCTION.maxUncompressedBytesPerEntry,
        )
        assertEquals(
            BackupArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES,
            BackupArchiveLimits.PRODUCTION.maxTotalUncompressedBytes,
        )
    }

    @Test
    fun exactCanonicalEntries_roundTripInAnyOrder() {
        val manifest = """{"formatVersion":1}""".toByteArray()
        val database = byteArrayOf(1, 2, 3, 4, 5)
        val preferences = """{"languageTag":"ar"}""".toByteArray()
        val bytes = ZipFixtures.zipBytes(
            BackupPackageFormat.PREFERENCES_ENTRY to preferences,
            BackupPackageFormat.DATABASE_ENTRY to database,
            BackupPackageFormat.MANIFEST_ENTRY to manifest,
        )
        withStaging { staging ->
            BackupArchiveValidator(ZipFixtures.limits(perEntry = 100, total = 300))
                .extractInto(bytes.inputStream(), staging)
            assertArrayEquals(manifest, File(staging, BackupPackageFormat.MANIFEST_ENTRY).readBytes())
            assertArrayEquals(database, File(staging, BackupPackageFormat.DATABASE_ENTRY).readBytes())
            assertArrayEquals(preferences, File(staging, BackupPackageFormat.PREFERENCES_ENTRY).readBytes())
            assertEquals(3, staging.list()?.size)
        }
    }

    @Test
    fun highlyCompressedEntry_stopsAtInjectedCapAndDeletesPartialOutput() {
        val cap = 64L
        val uncompressed = 1024 * 1024
        val bytes = ZipFixtures.compressedZeros(BackupPackageFormat.DATABASE_ENTRY, uncompressed)
        assertTrue("zip bomb should be highly compressed", bytes.size < uncompressed / 20)
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = cap, total = cap))
                    .extractInto(bytes.inputStream(), staging)
            }
            assertEquals(BackupArchiveRejection.ENTRY_TOO_LARGE, error.category)
            assertEquals(BackupArchiveRejection.ENTRY_TOO_LARGE.logMessage(), error.message)
            assertTrue(error.uncompressedBytesAccepted in 1..cap)
            assertFalse(File(staging, BackupPackageFormat.DATABASE_ENTRY).exists())
            assertTrue(largestFileBytes(staging) <= cap)
        }
    }

    @Test
    fun knownUncompressedSize_overCap_rejectsBeforeWriting() {
        val cap = 64
        val payload = ByteArray(cap + 40) { 7 }
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = cap.toLong(), total = cap.toLong()))
                    .extractInto(
                        ZipFixtures.storedZipBytes(BackupPackageFormat.DATABASE_ENTRY, payload).inputStream(),
                        staging,
                    )
            }
            assertEquals(BackupArchiveRejection.ENTRY_TOO_LARGE, error.category)
            assertEquals(0L, error.uncompressedBytesAccepted)
            assertTrue(staging.list().isNullOrEmpty())
        }
    }

    @Test
    fun knownUncompressedSize_equalToCap_isWritten() {
        val cap = 64
        val payload = ByteArray(cap) { 3 }
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = cap.toLong(), total = cap.toLong()))
                    .extractInto(
                        ZipFixtures.storedZipBytes(BackupPackageFormat.DATABASE_ENTRY, payload).inputStream(),
                        staging,
                    )
            }
            assertEquals(BackupArchiveRejection.MISSING_ENTRY, error.category)
            assertArrayEquals(payload, File(staging, BackupPackageFormat.DATABASE_ENTRY).readBytes())
        }
    }

    @Test
    fun totalUncompressedCap_stopsAndDeletesThePartialEntry() {
        val manifest = ByteArray(50) { 'm'.code.toByte() }
        val preferences = ByteArray(50) { 'p'.code.toByte() }
        val bytes = ZipFixtures.zipBytes(
            BackupPackageFormat.MANIFEST_ENTRY to manifest,
            BackupPackageFormat.PREFERENCES_ENTRY to preferences,
            BackupPackageFormat.DATABASE_ENTRY to ByteArray(10),
        )
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = 100, total = 80))
                    .extractInto(bytes.inputStream(), staging)
            }
            assertEquals(BackupArchiveRejection.ARCHIVE_TOO_LARGE, error.category)
            assertEquals(80L, error.uncompressedBytesAccepted)
            assertArrayEquals(manifest, File(staging, BackupPackageFormat.MANIFEST_ENTRY).readBytes())
            assertFalse(File(staging, BackupPackageFormat.PREFERENCES_ENTRY).exists())
            assertFalse(File(staging, BackupPackageFormat.DATABASE_ENTRY).exists())
            assertTrue(largestFileBytes(staging) <= 80L)
        }
    }

    @Test
    fun duplicateCanonicalName_isRejectedWithoutClobberingTheFirstEntry() {
        val first = "alpha-manifest".toByteArray()
        val second = "BETA-CLOBBER".toByteArray()
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = 100, total = 200))
                    .extractInto(
                        ZipFixtures.duplicateStoredEntries(
                            BackupPackageFormat.MANIFEST_ENTRY,
                            first,
                            second,
                        ).inputStream(),
                        staging,
                    )
            }
            assertEquals(BackupArchiveRejection.DUPLICATE_ENTRY, error.category)
            assertArrayEquals(first, File(staging, BackupPackageFormat.MANIFEST_ENTRY).readBytes())
        }
    }

    @Test
    fun fourthEntry_isRejectedBeforeItIsExtracted() {
        val manifest = ByteArray(20) { 1 }
        val database = ByteArray(30) { 2 }
        val preferences = ByteArray(40) { 3 }
        val bytes = ZipFixtures.zipBytes(
            BackupPackageFormat.MANIFEST_ENTRY to manifest,
            BackupPackageFormat.DATABASE_ENTRY to database,
            BackupPackageFormat.PREFERENCES_ENTRY to preferences,
            "extra.txt" to ByteArray(100_000) { 4 },
        )
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = 200_000, total = 400_000))
                    .extractInto(bytes.inputStream(), staging)
            }
            assertEquals(BackupArchiveRejection.TOO_MANY_ENTRIES, error.category)
            assertEquals(90L, error.uncompressedBytesAccepted)
            assertFalse(File(staging, "extra.txt").exists())
            assertArrayEquals(manifest, File(staging, BackupPackageFormat.MANIFEST_ENTRY).readBytes())
            assertArrayEquals(database, File(staging, BackupPackageFormat.DATABASE_ENTRY).readBytes())
            assertArrayEquals(preferences, File(staging, BackupPackageFormat.PREFERENCES_ENTRY).readBytes())
        }
    }

    @Test
    fun missingEntry_isRejected() {
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = 100, total = 200))
                    .extractInto(
                        ZipFixtures.zipBytes(
                            BackupPackageFormat.MANIFEST_ENTRY to "{}".toByteArray(),
                            BackupPackageFormat.PREFERENCES_ENTRY to "{}".toByteArray(),
                        ).inputStream(),
                        staging,
                    )
            }
            assertEquals(BackupArchiveRejection.MISSING_ENTRY, error.category)
        }
    }

    @Test
    fun unexpectedEntryName_isRejectedBeforeWriting() {
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits())
                    .extractInto(
                        ZipFixtures.zipBytes("notes.txt" to "secret-payload".toByteArray()).inputStream(),
                        staging,
                    )
            }
            assertEquals(BackupArchiveRejection.REJECTED_NAME, error.category)
            assertEquals(BackupArchiveRejection.REJECTED_NAME.logMessage(), error.message)
            assertFalse(error.message.orEmpty().contains("secret-payload"))
            assertFalse(error.message.orEmpty().contains("notes.txt"))
            assertEquals(0L, error.uncompressedBytesAccepted)
            assertTrue(staging.list().isNullOrEmpty())
        }
    }

    @Test
    fun pathAliases_areRejectedAndDoNotEscapeStaging() {
        val aliases = listOf(
            "../masroof.db",
            "foo/manifest.json",
            "foo/../manifest.json",
            "./manifest.json",
            "/manifest.json",
            "manifest.json/",
            "subdir/",
            "..\\masroof.db",
            "foo\\manifest.json",
            "manifest.json\\",
            "manifest.json\u0000",
        )
        aliases.forEach { entryName ->
            withRoot { root, staging ->
                val error = assertThrows(BackupArchiveException::class.java) {
                    BackupArchiveValidator(ZipFixtures.limits())
                        .extractInto(
                            ZipFixtures.zipBytes(entryName to byteArrayOf(1, 2, 3, 4)).inputStream(),
                            staging,
                        )
                }
                val expected = if (entryName.endsWith("/")) {
                    BackupArchiveRejection.DIRECTORY_ENTRY
                } else {
                    BackupArchiveRejection.REJECTED_NAME
                }
                assertEquals(entryName, expected, error.category)
                assertEquals(entryName, expected.logMessage(), error.message)
                assertFalse(entryName, error.message.orEmpty().contains(entryName))
                assertTrue(entryName, staging.list().isNullOrEmpty())
                assertFalse(entryName, File(root, "masroof.db").exists())
                assertFalse(entryName, File(staging, "manifest.json").exists())
                assertFalse(entryName, File(staging, "masroof.db").exists())
                assertFalse(entryName, File(staging, "foo").exists())
                assertFalse(entryName, File("/manifest.json").exists())
            }
        }
    }

    @Test
    fun absolutePath_isRejectedAndDoesNotCreateTheTarget() {
        withRoot { root, staging ->
            val absolute = File(root, "absolute-escape.bin")
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits())
                    .extractInto(
                        ZipFixtures.zipBytes(absolute.absolutePath to byteArrayOf(9, 9, 9)).inputStream(),
                        staging,
                    )
            }
            assertEquals(BackupArchiveRejection.REJECTED_NAME, error.category)
            assertFalse(error.message.orEmpty().contains("absolute-escape"))
            assertFalse(absolute.exists())
            assertTrue(staging.list().isNullOrEmpty())
        }
    }

    @Test
    fun truncatedZip_isMalformedAndLeavesNoPartialFile() {
        val full = ZipFixtures.compressedZeros(BackupPackageFormat.DATABASE_ENTRY, 100_000)
        val truncated = full.copyOf(80)
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits(perEntry = 50_000, total = 50_000))
                    .extractInto(truncated.inputStream(), staging)
            }
            assertEquals(BackupArchiveRejection.MALFORMED, error.category)
            assertFalse(File(staging, BackupPackageFormat.DATABASE_ENTRY).exists())
            assertTrue(largestFileBytes(staging) == 0L)
        }
    }

    @Test
    fun garbageBytes_areRejectedAsAnInvalidArchive() {
        withStaging { staging ->
            val error = assertThrows(BackupArchiveException::class.java) {
                BackupArchiveValidator(ZipFixtures.limits())
                    .extractInto("this is not a zip".byteInputStream(), staging)
            }
            assertEquals(BackupArchiveRejection.MISSING_ENTRY, error.category)
            assertTrue(staging.list().isNullOrEmpty())
        }
    }

    private fun withStaging(block: (File) -> Unit) {
        val staging = File(System.getProperty("java.io.tmpdir"), "m4-archive-${System.nanoTime()}")
        check(staging.mkdirs())
        try {
            block(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun withRoot(block: (File, File) -> Unit) {
        val root = File(System.getProperty("java.io.tmpdir"), "m4-root-${System.nanoTime()}")
        val staging = File(root, "staging")
        check(staging.mkdirs())
        try {
            block(root, staging)
        } finally {
            root.deleteRecursively()
            File("/manifest.json").delete()
        }
    }

    private fun largestFileBytes(root: File): Long =
        root.walkTopDown().filter { it.isFile }.maxOfOrNull { it.length() } ?: 0L
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BoundedZipImportTest {
    @Test
    fun unsafeArchives_doNotCloseOrModifyTheLiveDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(MasroofDatabase.NAME)
        val liveFile = context.getDatabasePath(MasroofDatabase.NAME)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(liveFile)
        val live = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            live.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                cursor.moveToFirst()
            }
            val before = fingerprint(liveFile)
            val beforeWal = lengthOrAbsent(File(liveFile.path + "-wal"))
            val escape = File(context.cacheDir, "m4-secret-escape.db")
            val absolute = File(context.cacheDir, "m4-absolute-escape.bin")
            escape.delete()
            absolute.delete()

            suspend fun reject(
                bytes: ByteArray,
                limits: BackupArchiveLimits,
                category: BackupArchiveRejection,
                forbidden: List<File> = emptyList(),
            ) {
                val zip = File.createTempFile("m4-import", ".masroof")
                zip.writeBytes(bytes)
                val closed = AtomicBoolean(false)
                val recoveryStarted = AtomicBoolean(false)
                val logs = AppLogService(context).also { it.clear() }
                val outcome = DatabaseBackupService(
                    appContext = context,
                    database = live,
                    closeDatabase = {
                        closed.set(true)
                        live.close()
                    },
                    appVersionName = "test",
                    appLogService = logs,
                    clockEpochMillis = { CLOCK },
                    restartProcess = { error("rejected archive must not restart") },
                    beforeValidatedInstall = { recoveryStarted.set(true) },
                    archiveLimits = limits,
                ).importFrom(Uri.fromFile(zip))
                zip.delete()

                assertEquals(category.name, BackupImportOutcome.InvalidPackage, outcome)
                assertFalse(category.name, closed.get())
                assertFalse(category.name, recoveryStarted.get())
                assertTrue(category.name, live.isOpen)
                assertEquals(category.name, before, fingerprint(liveFile))
                assertEquals(category.name, beforeWal, lengthOrAbsent(File(liveFile.path + "-wal")))
                assertFalse(
                    category.name,
                    File(context.cacheDir, "masroof-backup-import-$CLOCK").exists(),
                )
                assertNoRestoreArtifacts(liveFile, category.name)
                val logged = logs.readAll().single()
                assertEquals(AppLogCategories.BACKUP, logged.category)
                assertEquals(AppLogLevel.ERROR, logged.level)
                assertEquals(category.logMessage(), logged.message)
                assertFalse(logged.message.contains("/"))
                assertFalse(logged.message.contains("\\"))
                assertFalse(logged.message.contains("m4-secret"))
                assertFalse(logged.message.contains("absolute-escape"))
                forbidden.forEach { file -> assertFalse(file.path, file.exists()) }
            }

            val bomb = ZipFixtures.compressedZeros(BackupPackageFormat.DATABASE_ENTRY, 1024 * 1024)
            assertTrue(bomb.size < 64 * 1024)
            reject(bomb, ZipFixtures.limits(perEntry = 64, total = 64), BackupArchiveRejection.ENTRY_TOO_LARGE)

            reject(
                ZipFixtures.storedZipBytes(
                    BackupPackageFormat.DATABASE_ENTRY,
                    ByteArray(200) { 4 },
                ),
                ZipFixtures.limits(perEntry = 64, total = 64),
                BackupArchiveRejection.ENTRY_TOO_LARGE,
            )

            reject(
                ZipFixtures.duplicateStoredEntries(
                    BackupPackageFormat.MANIFEST_ENTRY,
                    "a".toByteArray(),
                    "b".toByteArray(),
                ),
                ZipFixtures.limits(perEntry = 1024, total = 4096),
                BackupArchiveRejection.DUPLICATE_ENTRY,
            )

            reject(
                ZipFixtures.zipBytes("../m4-secret-escape.db" to byteArrayOf(1)),
                ZipFixtures.limits(),
                BackupArchiveRejection.REJECTED_NAME,
                forbidden = listOf(escape),
            )
            reject(
                ZipFixtures.zipBytes(absolute.absolutePath to byteArrayOf(2)),
                ZipFixtures.limits(),
                BackupArchiveRejection.REJECTED_NAME,
                forbidden = listOf(absolute),
            )
            reject(
                ZipFixtures.zipBytes("foo/manifest.json" to byteArrayOf(3)),
                ZipFixtures.limits(),
                BackupArchiveRejection.REJECTED_NAME,
                forbidden = listOf(File(context.cacheDir, "foo")),
            )
            reject(
                ZipFixtures.zipBytes("foo/../manifest.json" to byteArrayOf(3)),
                ZipFixtures.limits(),
                BackupArchiveRejection.REJECTED_NAME,
            )

            reject(
                "this is not a zip".toByteArray(),
                ZipFixtures.limits(),
                BackupArchiveRejection.MISSING_ENTRY,
            )
            val truncated = ZipFixtures.compressedZeros(BackupPackageFormat.DATABASE_ENTRY, 100_000).copyOf(80)
            reject(truncated, ZipFixtures.limits(perEntry = 50_000, total = 50_000), BackupArchiveRejection.MALFORMED)

            reject(
                ZipFixtures.zipBytes(
                    BackupPackageFormat.MANIFEST_ENTRY to "{}".toByteArray(),
                    BackupPackageFormat.DATABASE_ENTRY to byteArrayOf(1),
                    BackupPackageFormat.PREFERENCES_ENTRY to "{}".toByteArray(),
                    "extra.txt" to ByteArray(50_000) { 5 },
                ),
                ZipFixtures.limits(perEntry = 10_000, total = 40_000),
                BackupArchiveRejection.TOO_MANY_ENTRIES,
            )
            reject(
                ZipFixtures.zipBytes(
                    BackupPackageFormat.MANIFEST_ENTRY to "{}".toByteArray(),
                    BackupPackageFormat.PREFERENCES_ENTRY to "{}".toByteArray(),
                ),
                ZipFixtures.limits(perEntry = 1024, total = 4096),
                BackupArchiveRejection.MISSING_ENTRY,
            )
        } finally {
            if (live.isOpen) live.close()
            context.deleteDatabase(MasroofDatabase.NAME)
            DatabaseRestoreRecovery.deleteRestoreArtifacts(context.getDatabasePath(MasroofDatabase.NAME))
            File(context.cacheDir, "m4-secret-escape.db").delete()
            File(context.cacheDir, "m4-absolute-escape.bin").delete()
        }
    }

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) } + ":" + file.length()
    }

    private fun lengthOrAbsent(file: File): Long = if (file.exists()) file.length() else -1L

    private fun assertNoRestoreArtifacts(live: File, label: String) {
        val paths = listOf(
            live.path + ".rollback",
            live.path + ".rollback.preserved",
            live.path + ".restore-journal",
            live.path + ".restore-journal.tmp",
            live.path + ".prefs-original",
            live.path + ".prefs-incoming",
            DatabaseRestoreRecovery.incomingFile(live).path,
        )
        paths.forEach { path -> assertFalse(label + " " + path, File(path).exists()) }
    }

    private companion object {
        const val CLOCK: Long = 1_700_000_111_000L
    }
}

private object ZipFixtures {
    fun limits(
        entries: Int = BackupArchiveLimits.MAX_ENTRIES,
        perEntry: Long = 1024,
        total: Long = 4096,
    ): BackupArchiveLimits = BackupArchiveLimits(
        maxEntries = entries,
        maxUncompressedBytesPerEntry = perEntry,
        maxTotalUncompressedBytes = total,
    )

    fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                if (data.isNotEmpty()) zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun storedZipBytes(name: String, data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val crc = CRC32()
            crc.update(data)
            val entry = ZipEntry(name)
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            entry.crc = crc.value
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    /**
     * ZipOutputStream refuses duplicate names. Import still has to reject an archive
     * that repeats a canonical local header, so the bytes are written directly.
     */
    fun duplicateStoredEntries(name: String, first: ByteArray, second: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(storedLocalHeader(name, first))
        output.write(storedLocalHeader(name, second))
        return output.toByteArray()
    }

    private fun storedLocalHeader(name: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val output = ByteArrayOutputStream()
        fun write16(value: Int) {
            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
        }
        fun write32(value: Long) {
            val bits = value.toInt()
            output.write(bits and 0xff)
            output.write((bits shr 8) and 0xff)
            output.write((bits shr 16) and 0xff)
            output.write((bits shr 24) and 0xff)
        }
        output.write(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        write16(20)
        write16(0)
        write16(0)
        write16(0)
        write16(0)
        write32(crc)
        write32(data.size.toLong())
        write32(data.size.toLong())
        write16(nameBytes.size)
        write16(0)
        output.write(nameBytes)
        output.write(data)
        return output.toByteArray()
    }

    fun compressedZeros(entryName: String, uncompressedBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            val chunk = ByteArray(8192)
            var remaining = uncompressedBytes
            while (remaining > 0) {
                val count = minOf(chunk.size, remaining)
                zip.write(chunk, 0, count)
                remaining -= count
            }
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
