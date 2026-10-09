package com.baraa.masroof.data.preferences

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM via the Android Keystore. The key is not user-auth bound so stable
 * and nightly update checks can read the token while the device is locked.
 * A lock-screen or biometric change that invalidates the key surfaces as a
 * decrypt failure; the repository then drops the payload and asks for re-entry.
 */
class AndroidKeystoreGitHubTokenEncryptor(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : GitHubTokenEncryptor {
    private val lock = Any()

    override fun encrypt(plaintext: String): String = synchronized(lock) {
        if (plaintext.isEmpty()) {
            throw GitHubTokenEncryptorException(FAILURE)
        }
        try {
            seal(plaintext, loadOrCreateKey())
        } catch (first: Exception) {
            deleteKey()
            try {
                seal(plaintext, loadOrCreateKey())
            } catch (second: Exception) {
                throw GitHubTokenEncryptorException(FAILURE, second)
            }
        }
    }

    override fun decrypt(ciphertext: String): String? = synchronized(lock) {
        try {
            val payload = decode(ciphertext) ?: return null
            val key = loadKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.iv))
            String(cipher.doFinal(payload.ciphertext), Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            // KeyPermanentlyInvalidatedException, AEAD failure, or a keystore
            // ProviderException. Never throw into update checks.
            null
        }
    }

    private fun seal(plaintext: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        if (iv == null || iv.size != GCM_IV_BYTES) {
            throw GitHubTokenEncryptorException(FAILURE)
        }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = encode(iv, encrypted)
        if (payload == plaintext) {
            throw GitHubTokenEncryptorException(FAILURE)
        }
        return payload
    }

    private fun loadOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun loadKey(): SecretKey? {
        val keyStore = openKeyStore()
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun deleteKey() {
        try {
            val keyStore = openKeyStore()
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (error: Exception) {
            // The next encrypt attempt reports failure without the token.
        }
    }

    private fun openKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private class Payload(val iv: ByteArray, val ciphertext: ByteArray)

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "masroof.github_update_token"
        private const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
        private const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val PAYLOAD_PREFIX: String = "v1."
        private const val GCM_IV_BYTES: Int = 12
        private const val GCM_TAG_BITS: Int = 128
        private const val AES_KEY_BITS: Int = 256
        private const val FAILURE: String = "GitHub token encryption failed"

        private fun encode(iv: ByteArray, ciphertext: ByteArray): String {
            val encoder = Base64.getEncoder()
            return PAYLOAD_PREFIX + encoder.encodeToString(iv) + "." + encoder.encodeToString(ciphertext)
        }

        private fun decode(payload: String): Payload? {
            if (!payload.startsWith(PAYLOAD_PREFIX)) return null
            val rest = payload.substring(PAYLOAD_PREFIX.length)
            val separator = rest.indexOf('.')
            if (separator <= 0 || separator >= rest.lastIndex) return null
            val iv = decodePart(rest.substring(0, separator)) ?: return null
            val ciphertext = decodePart(rest.substring(separator + 1)) ?: return null
            if (iv.size != GCM_IV_BYTES || ciphertext.isEmpty()) return null
            return Payload(iv, ciphertext)
        }

        private fun decodePart(value: String): ByteArray? =
            try {
                Base64.getDecoder().decode(value)
            } catch (error: IllegalArgumentException) {
                null
            }
    }
}
