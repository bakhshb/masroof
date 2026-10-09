package com.baraa.masroof.data.preferences

import android.content.SharedPreferences
import com.baraa.masroof.application.update.GitHubTokenRepository

/**
 * Stores the GitHub update token as ciphertext in [PREFS_NAME].
 *
 * Upgrades that still have [KEY_TOKEN] plaintext are migrated once: read the
 * old value, write [KEY_TOKEN_CIPHERTEXT], decrypt it back, and only then
 * delete the plaintext key. A missing or blank token stays absent so public
 * repositories can be checked without credentials.
 *
 * When the keystore key cannot decrypt the stored payload, the ciphertext is
 * removed and [getToken] returns null. Update checks then run in no-token
 * mode instead of throwing, and the user can enter the token again. This
 * preference file is not part of the database backup snapshot.
 */
class SharedPrefsGitHubTokenRepository(
    private val prefs: SharedPreferences,
    private val encryptor: GitHubTokenEncryptor,
) : GitHubTokenRepository {
    private val lock = Any()

    override fun getToken(): String? = synchronized(lock) { readTokenLocked() }

    override fun setToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            clearToken()
            return
        }
        synchronized(lock) {
            val ciphertext = encryptOrNull(trimmed) ?: return
            if (decryptOrNull(ciphertext) != trimmed) return
            val previous = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)
            if (!prefs.edit().putString(KEY_TOKEN_CIPHERTEXT, ciphertext).commit()) return
            val stored = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)
            if (stored != ciphertext || decryptOrNull(stored) != trimmed) {
                val rollback = prefs.edit()
                if (previous == null) {
                    rollback.remove(KEY_TOKEN_CIPHERTEXT)
                } else {
                    rollback.putString(KEY_TOKEN_CIPHERTEXT, previous)
                }
                rollback.commit()
                return
            }
            prefs.edit().remove(KEY_TOKEN).commit()
        }
    }

    override fun clearToken() {
        synchronized(lock) {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_TOKEN_CIPHERTEXT)
                .commit()
        }
    }

    override fun hasToken(): Boolean = getToken() != null

    private fun readTokenLocked(): String? {
        val ciphertext = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)
        if (!ciphertext.isNullOrBlank()) {
            val decrypted = decryptOrNull(ciphertext)
            if (!decrypted.isNullOrBlank()) {
                if (prefs.contains(KEY_TOKEN)) {
                    prefs.edit().remove(KEY_TOKEN).commit()
                }
                return decrypted
            }
            // Unreadable payload. Drop it, but keep leftover plaintext so an
            // interrupted migration can still be retried.
            prefs.edit().remove(KEY_TOKEN_CIPHERTEXT).commit()
        }
        val legacy = prefs.getString(KEY_TOKEN, null)
        if (legacy.isNullOrBlank()) {
            if (prefs.contains(KEY_TOKEN)) {
                prefs.edit().remove(KEY_TOKEN).commit()
            }
            return null
        }
        return migrateLegacyLocked(legacy)
    }

    private fun migrateLegacyLocked(legacy: String): String? {
        val ciphertext = encryptOrNull(legacy) ?: return legacy
        if (!prefs.edit().putString(KEY_TOKEN_CIPHERTEXT, ciphertext).commit()) {
            return legacy
        }
        val stored = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)
        if (stored != ciphertext || decryptOrNull(stored) != legacy) {
            prefs.edit().remove(KEY_TOKEN_CIPHERTEXT).commit()
            return legacy
        }
        prefs.edit().remove(KEY_TOKEN).commit()
        return legacy
    }

    private fun encryptOrNull(plaintext: String): String? {
        val ciphertext = try {
            encryptor.encrypt(plaintext)
        } catch (error: Exception) {
            return null
        }
        if (ciphertext.isBlank() || ciphertext == plaintext) return null
        return ciphertext
    }

    /**
     * Decrypt failures, including a thrown keystore error, stay inside the
     * repository so update checks observe an absent token.
     */
    private fun decryptOrNull(ciphertext: String): String? =
        try {
            encryptor.decrypt(ciphertext)?.takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            null
        }

    companion object {
        const val PREFS_NAME: String = "github_token_prefs"
        /** Legacy plaintext key. Absent after a successful migration. */
        const val KEY_TOKEN: String = "github_token"
        const val KEY_TOKEN_CIPHERTEXT: String = "github_token_ciphertext"
    }
}
