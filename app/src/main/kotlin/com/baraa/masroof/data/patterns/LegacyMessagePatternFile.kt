package com.baraa.masroof.data.patterns

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the pattern document lived before schema v22. [MessagePatternStore.importLegacyFile] copies it into the
 * database once. Restore snapshots still carry it, so a restore journal written by an older app version finishes
 * with its patterns, which the next open then copies.
 */
object LegacyMessagePatternFile {
    fun of(context: Context): File = File(context.filesDir, "message-patterns/definitions.json")

    /** Bounded, strict UTF-8 read: a corrupt file is never executed or silently altered. Null when absent. */
    fun read(file: File): String? {
        if (!file.exists()) return null
        require(file.isFile && file.length() <= MessagePatternConfigurationCodec.MAX_BYTES) { "Invalid pattern configuration file" }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.readBytes())).toString()
    }

    /** Atomic replace. Validation may be skipped ONLY when rollback restores the trusted original journal snapshot. */
    fun write(file: File, raw: String, validate: Boolean) {
        require(raw.toByteArray(Charsets.UTF_8).size <= MessagePatternConfigurationCodec.MAX_BYTES)
        if (validate) MessagePatternConfigurationCodec.decode(raw)
        val target = file.absoluteFile
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create pattern directory" }
        val temporary = Files.createTempFile(directory.toPath(), ".patterns-", ".new")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(raw.toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun delete(file: File) {
        if (file.exists() && (!file.isFile || !file.delete())) throw IOException("Cannot clear pattern configuration")
    }
}
