package com.baraa.masroof.application.backup

import java.io.File
import java.io.RandomAccessFile

/** Overwrite sensitive temporary bytes and sync before unlinking their files. */
internal object SensitiveFileCleanup {
    fun wipe(file: File) {
        if (!file.isFile) return
        // Opening FileOutputStream first would truncate the file before length is read.
        RandomAccessFile(file, "rw").use { output ->
            var remaining = output.length()
            val zeros = ByteArray(BUFFER_SIZE)
            while (remaining > 0) {
                val count = minOf(zeros.size.toLong(), remaining).toInt()
                output.write(zeros, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
    }

    fun delete(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach(::delete)
        } else {
            wipe(file)
        }
        check(file.delete()) { "Cannot delete sensitive temporary file" }
    }

    private const val BUFFER_SIZE = 8192
}
