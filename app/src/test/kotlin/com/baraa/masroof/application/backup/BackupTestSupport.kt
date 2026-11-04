package com.baraa.masroof.application.backup

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** True when [needle] appears anywhere in [haystack]. */
internal fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
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

/** Writes a plain zip with [entries] in order. */
internal fun writeZip(output: OutputStream, entries: List<Pair<String, ByteArray>>) {
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

/** An encrypted backup envelope around a plain zip of [entries]. */
internal fun writeEncryptedPackage(
    file: File,
    passphrase: CharArray,
    kdfIterations: Int,
    entries: List<Pair<String, ByteArray>>,
): File {
    FileOutputStream(file).use { output ->
        BackupEnvelope.encrypt(output, passphrase, kdfIterations) { plain -> writeZip(plain, entries) }
    }
    return file
}
