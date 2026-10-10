package com.baraa.masroof.application.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated envelope around the inner backup ZIP.
 *
 * Layout, big-endian, 42-byte header then ciphertext:
 * ```
 *  0..7   magic "MSRFENV1"          (not a ZIP local header)
 *  8..9   envelope version uint16   ([BackupPackageFormat.ENVELOPE_VERSION])
 * 10..13  PBKDF2 iteration count uint32
 * 14..29  random salt, 16 bytes
 * 30..41  random GCM IV, 12 bytes
 * 42..end AES-GCM ciphertext including the 16-byte tag
 * ```
 *
 * The header is GCM additional authenticated data, so a flipped header byte
 * fails authentication together with any flipped ciphertext byte. The key is
 * PBKDF2-HMAC-SHA256, 256 bits. Production exports use [DEFAULT_ITERATIONS].
 * Callers may pass a lower count in tests; import uses the count stored in
 * the header after it authenticates, and rejects counts outside 1..[MAX_ITERATIONS]
 * before key derivation so a hostile header cannot force an unbounded KDF.
 *
 * Platform JCA only. The passphrase and derived key bytes are cleared before return.
 * This type does not log them.
 */
object BackupEnvelope {
    const val DEFAULT_ITERATIONS: Int = 210_000
    const val MAX_ITERATIONS: Int = 1_000_000
    const val HEADER_SIZE: Int = 42
    const val PEEK_MARK: Int = HEADER_SIZE + 16

    private const val SALT_BYTES: Int = 16
    private const val IV_BYTES: Int = 12
    private const val AES_KEY_BITS: Int = 256
    private const val GCM_TAG_BITS: Int = 128
    private val MAGIC: ByteArray = "MSRFENV1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun looksLikeEnvelope(prefix: ByteArray, length: Int): Boolean {
        if (length < MAGIC.size) return false
        return prefix.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }

    fun encrypt(
        output: OutputStream,
        passphrase: CharArray,
        iterations: Int,
        writePlaintext: (OutputStream) -> Unit,
    ) {
        if (passphrase.isEmpty()) {
            throw BackupFailureException(BackupFailureCategory.PASSPHRASE_REQUIRED)
        }
        if (iterations !in 1..MAX_ITERATIONS) {
            throw BackupFailureException(BackupFailureCategory.INVALID_ENVELOPE)
        }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val header = headerBytes(iterations, salt, iv)
        val password = passphrase.copyOf()
        var encoded: ByteArray? = null
        try {
            output.write(header)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            encoded = deriveKey(password, salt, iterations).encoded
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(encoded, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            val encrypted = FinishingCipherOutputStream(output, cipher)
            try {
                writePlaintext(encrypted)
            } finally {
                encrypted.close()
            }
        } finally {
            password.fill('\u0000')
            encoded?.fill(0)
            salt.fill(0)
            iv.fill(0)
            header.fill(0)
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, passphrase: CharArray) {
        if (passphrase.isEmpty()) {
            throw BackupFailureException(BackupFailureCategory.PASSPHRASE_REQUIRED)
        }
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header) || !looksLikeEnvelope(header, header.size)) {
            throw BackupFailureException(BackupFailureCategory.INVALID_ENVELOPE)
        }
        val version = readU16(header, MAGIC.size)
        if (version != BackupPackageFormat.ENVELOPE_VERSION) {
            throw BackupFailureException(BackupFailureCategory.INVALID_ENVELOPE)
        }
        val iterations = readU32(header, MAGIC.size + 2)
        if (iterations !in 1..MAX_ITERATIONS) {
            throw BackupFailureException(BackupFailureCategory.INVALID_ENVELOPE)
        }
        val salt = header.copyOfRange(14, 14 + SALT_BYTES)
        val iv = header.copyOfRange(30, 30 + IV_BYTES)
        val password = passphrase.copyOf()
        var encoded: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            encoded = deriveKey(password, salt, iterations).encoded
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(encoded, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val chunk = cipher.update(buffer, 0, read)
                if (chunk != null && chunk.isNotEmpty()) output.write(chunk)
            }
            val tail = try {
                cipher.doFinal()
            } catch (error: GeneralSecurityException) {
                throw BackupFailureException(BackupFailureCategory.AUTHENTICATION_FAILED)
            }
            if (tail != null && tail.isNotEmpty()) output.write(tail)
        } finally {
            password.fill('\u0000')
            encoded?.fill(0)
            salt.fill(0)
            iv.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): javax.crypto.SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, AES_KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        } finally {
            spec.clearPassword()
        }
    }

    private fun headerBytes(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(header)
        val version = BackupPackageFormat.ENVELOPE_VERSION
        header[8] = (version ushr 8).toByte()
        header[9] = version.toByte()
        header[10] = (iterations ushr 24).toByte()
        header[11] = (iterations ushr 16).toByte()
        header[12] = (iterations ushr 8).toByte()
        header[13] = iterations.toByte()
        salt.copyInto(header, destinationOffset = 14)
        iv.copyInto(header, destinationOffset = 30)
        return header
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        val value = ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
        if (value > Int.MAX_VALUE) return Int.MAX_VALUE
        return value.toInt()
    }

    private fun readFully(input: InputStream, dest: ByteArray): Boolean {
        var offset = 0
        while (offset < dest.size) {
            val read = input.read(dest, offset, dest.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    /**
     * Encrypts written bytes and appends the GCM tag when closed.
     * Does not close [output]; the caller owns that stream.
     */
    private class FinishingCipherOutputStream(
        private val output: OutputStream,
        private val cipher: Cipher,
    ) : OutputStream() {
        private var finished = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished) { "cipher already finished" }
            if (len == 0) return
            val chunk = cipher.update(b, off, len)
            if (chunk != null && chunk.isNotEmpty()) output.write(chunk)
        }

        override fun close() {
            if (finished) return
            finished = true
            val tail = cipher.doFinal()
            if (tail != null && tail.isNotEmpty()) output.write(tail)
        }
    }
}
