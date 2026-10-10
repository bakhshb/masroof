package com.baraa.masroof.application.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupEnvelopeTest {
    private val passphrase = "envelope-passphrase".toCharArray()
    private val plaintext = (
        "SQLite format 3\u0000" +
            "MASROOF-SMS-FIXTURE-do-not-leak-9f3c2a"
        ).toByteArray(Charsets.UTF_8)

    @Test
    fun encryptDecrypt_roundTripsAndHidesPlaintext() {
        val envelope = encrypt(plaintext, passphrase.copyOf())
        assertFalse(envelope.copyOfRange(0, 4).contentEquals(ZIP_LOCAL))
        assertTrue(BackupEnvelope.looksLikeEnvelope(envelope, envelope.size))
        assertFalse(contains(envelope, "SQLite format 3".toByteArray(Charsets.US_ASCII)))
        assertFalse(contains(envelope, "MASROOF-SMS-FIXTURE-do-not-leak-9f3c2a".toByteArray(Charsets.UTF_8)))

        val restored = ByteArrayOutputStream()
        BackupEnvelope.decrypt(ByteArrayInputStream(envelope), restored, passphrase.copyOf())
        assertTrue(restored.toByteArray().contentEquals(plaintext))
    }

    @Test
    fun wrongPassphrase_failsWithoutEchoingTheSecret() {
        val envelope = encrypt(plaintext, passphrase.copyOf())
        val wrong = "wrong-passphrase-DO-NOT-LOG-7f3a"
        val error = assertThrows(BackupFailureException::class.java) {
            BackupEnvelope.decrypt(
                ByteArrayInputStream(envelope),
                ByteArrayOutputStream(),
                wrong.toCharArray(),
            )
        }
        assertEquals(BackupFailureCategory.AUTHENTICATION_FAILED, error.category)
        assertFalse(error.toString().contains(wrong))
        assertTrue(error.cause == null)
    }

    @Test
    fun flippedCiphertextByte_failsAuthentication() {
        val envelope = encrypt(plaintext, passphrase.copyOf())
        envelope[envelope.lastIndex] = (envelope[envelope.lastIndex].toInt() xor 0x01).toByte()
        val error = assertThrows(BackupFailureException::class.java) {
            BackupEnvelope.decrypt(
                ByteArrayInputStream(envelope),
                ByteArrayOutputStream(),
                passphrase.copyOf(),
            )
        }
        assertEquals(BackupFailureCategory.AUTHENTICATION_FAILED, error.category)
    }

    @Test
    fun emptyPassphrase_failsBeforeWriting() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(BackupFailureException::class.java) {
            BackupEnvelope.encrypt(output, CharArray(0), BackupEnvelope.DEFAULT_ITERATIONS) { }
        }
        assertEquals(BackupFailureCategory.PASSPHRASE_REQUIRED, error.category)
        assertEquals(0, output.size())
    }

    private fun encrypt(bytes: ByteArray, passphrase: CharArray): ByteArray {
        val output = ByteArrayOutputStream()
        BackupEnvelope.encrypt(output, passphrase, 1_024) { stream ->
            stream.write(bytes)
        }
        return output.toByteArray()
    }

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

    private companion object {
        val ZIP_LOCAL: ByteArray = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    }
}
