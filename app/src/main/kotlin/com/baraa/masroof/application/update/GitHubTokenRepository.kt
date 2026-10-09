package com.baraa.masroof.application.update

interface GitHubTokenRepository {
    fun getToken(): String?

    /**
     * Persists [token]. Returns false when encryption or the preference write
     * does not leave a readable ciphertext. Callers must not report success
     * unless this returns true.
     */
    fun setToken(token: String): Boolean

    fun clearToken()

    fun hasToken(): Boolean
}
