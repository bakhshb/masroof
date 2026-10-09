package com.baraa.masroof.data.preferences

/**
 * Encrypts the GitHub update token before it is stored.
 *
 * Production uses [AndroidKeystoreGitHubTokenEncryptor]. Unit tests inject a
 * double because Robolectric has no hardware-backed Android Keystore.
 * Implementations must not log the token or put it in exception messages.
 */
interface GitHubTokenEncryptor {
    /**
     * Returns ciphertext that must not equal [plaintext].
     *
     * @throws GitHubTokenEncryptorException when the key cannot encrypt.
     */
    fun encrypt(plaintext: String): String

    /**
     * Returns the token, or null when the key was invalidated or the payload
     * cannot be decrypted. Must not throw.
     */
    fun decrypt(ciphertext: String): String?
}

class GitHubTokenEncryptorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
