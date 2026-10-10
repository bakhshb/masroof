package com.baraa.masroof.application.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SensitiveFileCleanupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun wipe_overwritesEntireOriginalLengthIncludingPartialLastBlock() {
        val file = temporary.newFile()
        val length = 8192 * 2 + 37
        file.writeBytes(ByteArray(length) { 91 })

        SensitiveFileCleanup.wipe(file)

        assertEquals(length.toLong(), file.length())
        assertArrayEquals(ByteArray(length), file.readBytes())
    }

    @Test
    fun delete_removesNestedSensitiveFilesAndIsIdempotent() {
        val directory = temporary.newFolder()
        val child = File(directory, "nested").also { it.mkdir() }
        File(child, "database-wal").writeText("financial plaintext")
        SensitiveFileCleanup.delete(directory)
        SensitiveFileCleanup.delete(directory)
        assertFalse(directory.exists())
    }
}
