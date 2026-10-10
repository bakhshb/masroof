package com.baraa.masroof.application.backup

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts the three inner backup entries with a hard byte cap.
 *
 * Entry names must match exactly. A directory, a fourth entry, a duplicate,
 * or a path such as `nested/manifest.json` is rejected. Inflated bytes are
 * counted while streaming; a declared size is not trusted. Rejection throws
 * before the caller may replace the live database. Partial files stay in
 * [staging], which the caller deletes.
 */
internal object BackupPackageZip {
    const val MAX_ENTRIES: Int = 3
    const val MAX_UNCOMPRESSED_BYTES_PER_ENTRY: Long = 64L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 64L * 1024 * 1024

    private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val REQUIRED = setOf(
        BackupPackageFormat.MANIFEST_ENTRY,
        BackupPackageFormat.DATABASE_ENTRY,
        BackupPackageFormat.PREFERENCES_ENTRY,
    )

    data class Limits(
        val maxEntries: Int = MAX_ENTRIES,
        val maxUncompressedBytesPerEntry: Long = MAX_UNCOMPRESSED_BYTES_PER_ENTRY,
        val maxTotalUncompressedBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES,
    )

    fun looksLikeZip(prefix: ByteArray, length: Int): Boolean {
        if (length < ZIP_LOCAL_HEADER.size) return false
        return prefix[0] == ZIP_LOCAL_HEADER[0] &&
            prefix[1] == ZIP_LOCAL_HEADER[1] &&
            prefix[2] == ZIP_LOCAL_HEADER[2] &&
            prefix[3] == ZIP_LOCAL_HEADER[3]
    }

    fun extract(input: InputStream, staging: File, limits: Limits = Limits()) {
        val seen = mutableSetOf<String>()
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (seen.size >= limits.maxEntries) {
                        throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
                    }
                    val name = entry.name ?: throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
                    if (entry.isDirectory || name !in REQUIRED || name in seen) {
                        throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
                    }
                    val declared = entry.size
                    if (declared > limits.maxUncompressedBytesPerEntry ||
                        (declared >= 0 && total + declared > limits.maxTotalUncompressedBytes)
                    ) {
                        throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
                    }
                    val outFile = safeEntryFile(staging, name)
                    FileOutputStream(outFile).use { output ->
                        total += copyCapped(
                            input = zip,
                            output = output,
                            maxEntry = limits.maxUncompressedBytesPerEntry,
                            maxRemainingTotal = limits.maxTotalUncompressedBytes - total,
                        )
                    }
                    seen += name
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (error: BackupFailureException) {
            throw error
        } catch (error: Exception) {
            throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
        }
        if (seen != REQUIRED) {
            throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
        }
    }

    private fun safeEntryFile(staging: File, name: String): File {
        val stagingCanon = staging.canonicalFile
        val outFile = File(stagingCanon, name)
        val outCanon = outFile.canonicalFile
        if (outCanon.parentFile != stagingCanon) {
            throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
        }
        return outFile
    }

    private fun copyCapped(
        input: InputStream,
        output: OutputStream,
        maxEntry: Long,
        maxRemainingTotal: Long,
    ): Long {
        val buffer = ByteArray(8192)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return written
            val next = written + read
            if (next > maxEntry || next > maxRemainingTotal) {
                throw BackupFailureException(BackupFailureCategory.ARCHIVE_REJECTED)
            }
            output.write(buffer, 0, read)
            written = next
        }
    }
}
