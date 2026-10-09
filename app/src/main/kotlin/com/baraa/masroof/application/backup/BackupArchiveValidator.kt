package com.baraa.masroof.application.backup

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Hard caps for backup ZIP import. Production values reject a fourth entry and
 * any uncompressed payload above 256 MiB per entry or across the archive.
 */
data class BackupArchiveLimits(
    val maxEntries: Int,
    val maxUncompressedBytesPerEntry: Long,
    val maxTotalUncompressedBytes: Long,
) {
    init {
        require(maxEntries >= 0) { "maxEntries must be non-negative" }
        require(maxUncompressedBytesPerEntry >= 0L) {
            "maxUncompressedBytesPerEntry must be non-negative"
        }
        require(maxTotalUncompressedBytes >= 0L) {
            "maxTotalUncompressedBytes must be non-negative"
        }
    }

    companion object {
        const val MAX_ENTRIES: Int = 3
        private const val MEBIBYTE: Long = 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES_PER_ENTRY: Long = 256L * MEBIBYTE
        const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 256L * MEBIBYTE

        val PRODUCTION: BackupArchiveLimits = BackupArchiveLimits(
            maxEntries = MAX_ENTRIES,
            maxUncompressedBytesPerEntry = MAX_UNCOMPRESSED_BYTES_PER_ENTRY,
            maxTotalUncompressedBytes = MAX_TOTAL_UNCOMPRESSED_BYTES,
        )
    }
}

/**
 * Stable archive-rejection categories. Log text is the category token only.
 */
enum class BackupArchiveRejection(val logToken: String) {
    MALFORMED("malformed_archive"),
    TOO_MANY_ENTRIES("too_many_entries"),
    REJECTED_NAME("rejected_entry_name"),
    DUPLICATE_ENTRY("duplicate_entry"),
    DIRECTORY_ENTRY("directory_entry"),
    ENTRY_TOO_LARGE("entry_too_large"),
    ARCHIVE_TOO_LARGE("archive_too_large"),
    MISSING_ENTRY("missing_entry"),
    ;

    fun logMessage(): String = "$LOG_PREFIX$logToken"

    private companion object {
        const val LOG_PREFIX: String = "Database import rejected: "
    }
}

/**
 * Thrown when a backup ZIP must not be applied.
 * [message] is the stable log line and never includes entry bytes.
 */
class BackupArchiveException(
    val category: BackupArchiveRejection,
    val uncompressedBytesAccepted: Long = 0L,
) : Exception(category.logMessage())

/**
 * Extracts a backup ZIP into a staging directory using an exact name allow-list.
 *
 * Names are matched byte-for-byte against [BackupPackageFormat]. Paths are not
 * flattened. The fourth entry is rejected before its payload is read. Uncompressed
 * bytes are counted as they are written, and a rejected entry's partial file is deleted.
 */
class BackupArchiveValidator(
    private val limits: BackupArchiveLimits = BackupArchiveLimits.PRODUCTION,
) {
    fun extractInto(input: InputStream, staging: File) {
        require(staging.isDirectory) { "Staging directory is missing" }
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var scanned = 0
            var totalWritten = 0L
            val acceptedNames = HashSet<String>(CANONICAL_ENTRY_NAMES.size)
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (ignored: Exception) {
                    throw BackupArchiveException(BackupArchiveRejection.MALFORMED, totalWritten)
                } ?: break
                scanned += 1
                if (scanned > limits.maxEntries) {
                    throw BackupArchiveException(BackupArchiveRejection.TOO_MANY_ENTRIES, totalWritten)
                }
                val name = entry.name ?: throw BackupArchiveException(
                    BackupArchiveRejection.REJECTED_NAME,
                    totalWritten,
                )
                if (entry.isDirectory || name.endsWith("/")) {
                    throw BackupArchiveException(BackupArchiveRejection.DIRECTORY_ENTRY, totalWritten)
                }
                if (name !in CANONICAL_ENTRY_NAMES) {
                    throw BackupArchiveException(BackupArchiveRejection.REJECTED_NAME, totalWritten)
                }
                if (!acceptedNames.add(name)) {
                    throw BackupArchiveException(BackupArchiveRejection.DUPLICATE_ENTRY, totalWritten)
                }
                val declared = entry.size
                if (declared > limits.maxUncompressedBytesPerEntry) {
                    throw BackupArchiveException(BackupArchiveRejection.ENTRY_TOO_LARGE, totalWritten)
                }
                if (declared >= 0L && limits.maxTotalUncompressedBytes - declared < totalWritten) {
                    throw BackupArchiveException(BackupArchiveRejection.ARCHIVE_TOO_LARGE, totalWritten)
                }
                totalWritten += extractEntry(zip, name, staging, totalWritten)
            }
            if (acceptedNames != CANONICAL_ENTRY_NAMES) {
                throw BackupArchiveException(BackupArchiveRejection.MISSING_ENTRY, totalWritten)
            }
        }
    }

    private fun extractEntry(
        zip: ZipInputStream,
        entryName: String,
        staging: File,
        alreadyWrittenTotal: Long,
    ): Long {
        val destination = stagedFile(staging, entryName)
        try {
            val entryWritten = FileOutputStream(destination).use { output ->
                writeBounded(zip, output, alreadyWrittenTotal)
            }
            zip.closeEntry()
            return entryWritten
        } catch (error: BackupArchiveException) {
            destination.delete()
            throw error
        } catch (ignored: Exception) {
            destination.delete()
            throw BackupArchiveException(BackupArchiveRejection.MALFORMED, alreadyWrittenTotal)
        }
    }

    private fun stagedFile(staging: File, entryName: String): File {
        val destination = File(staging, entryName)
        val parent = destination.canonicalFile.parentFile?.canonicalPath
        if (parent != staging.canonicalPath) {
            throw BackupArchiveException(BackupArchiveRejection.REJECTED_NAME)
        }
        return destination
    }

    /**
     * Writes at most the remaining per-entry and total caps. A further uncompressed
     * byte rejects the archive before that byte is written.
     */
    private fun writeBounded(
        source: InputStream,
        output: OutputStream,
        alreadyWrittenTotal: Long,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var entryWritten = 0L
        var totalWritten = alreadyWrittenTotal
        while (true) {
            val entryRemaining = limits.maxUncompressedBytesPerEntry - entryWritten
            val totalRemaining = limits.maxTotalUncompressedBytes - totalWritten
            val allowed = minOf(entryRemaining, totalRemaining)
            if (allowed <= 0L) {
                val extra = source.read()
                if (extra < 0) return entryWritten
                throw BackupArchiveException(
                    category = if (entryRemaining <= 0L) {
                        BackupArchiveRejection.ENTRY_TOO_LARGE
                    } else {
                        BackupArchiveRejection.ARCHIVE_TOO_LARGE
                    },
                    uncompressedBytesAccepted = totalWritten,
                )
            }
            val chunk = minOf(COPY_BUFFER_BYTES.toLong(), allowed).toInt()
            val read = source.read(buffer, 0, chunk)
            if (read < 0) return entryWritten
            if (read == 0) continue
            output.write(buffer, 0, read)
            entryWritten += read
            totalWritten += read
            check(entryWritten <= limits.maxUncompressedBytesPerEntry)
            check(totalWritten <= limits.maxTotalUncompressedBytes)
        }
    }

    companion object {
        private const val COPY_BUFFER_BYTES: Int = 8 * 1024

        private val CANONICAL_ENTRY_NAMES: Set<String> = setOf(
            BackupPackageFormat.MANIFEST_ENTRY,
            BackupPackageFormat.DATABASE_ENTRY,
            BackupPackageFormat.PREFERENCES_ENTRY,
        )
    }
}
