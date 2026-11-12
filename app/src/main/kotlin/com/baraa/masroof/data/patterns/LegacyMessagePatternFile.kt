package com.baraa.masroof.data.patterns

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Where the pattern document lived before schema v22. [MessagePatternStore.importLegacyFile] copies it into the
 * database once and deletes it; a committed restore deletes one left from before it. Nothing writes it any more.
 */
object LegacyMessagePatternFile {
    fun of(context: Context): File = File(context.filesDir, "message-patterns/definitions.json")

    /** Bounded, strict UTF-8 read: a corrupt file is never executed or silently altered. Null when absent. */
    fun read(file: File): String? {
        if (!file.exists()) return null
        require(file.isFile && file.length() <= MessagePatternConfigurationCodec.MAX_BYTES) { "Invalid pattern configuration file" }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.readBytes())).toString()
    }

    fun delete(file: File) {
        if (file.exists() && (!file.isFile || !file.delete())) throw IOException("Cannot clear pattern configuration")
    }
}
