package com.baraa.masroof.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.update.AppUpdateService
import com.baraa.masroof.application.update.GitHubReleaseClient
import com.baraa.masroof.application.update.UpdateAvailability
import com.baraa.masroof.application.update.UpdateChannel
import com.baraa.masroof.application.update.UpdateCheckPreferencesRepository
import com.baraa.masroof.application.update.UpdateCheckResult
import com.baraa.masroof.application.update.UpdateChecker
import java.io.File
import java.util.Base64
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedPrefsGitHubTokenRepositoryTest {
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun freshSave_storesCiphertextOnly() {
        val repository = repository(ReversibleEncryptor())

        assertTrue(repository.setToken("  $TOKEN  "))

        assertEquals(TOKEN, repository.getToken())
        assertTrue(repository.hasToken())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        val stored = prefs.getString(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT, null)
        assertNotEquals(TOKEN, stored)
        assertFalse(stored.isNullOrBlank())
        assertFalse(stored!!.contains(TOKEN))
        assertStoredValuesHide(TOKEN)
    }

    @Test
    fun legacyPlaintext_isMigratedThenDeleted() {
        prefs.edit().putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN, TOKEN).commit()
        val repository = repository(ReversibleEncryptor())

        assertEquals(TOKEN, repository.getToken())

        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        val stored = prefs.getString(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT, null)
        assertNotEquals(null, stored)
        assertNotEquals(TOKEN, stored)
        assertFalse(stored!!.contains(TOKEN))
        assertEquals(TOKEN, repository.getToken())
        assertStoredValuesHide(TOKEN)
    }

    @Test
    fun migration_keepsLegacyPlaintextWhenVerificationFails() {
        prefs.edit().putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN, TOKEN).commit()
        val repository = repository(NonRoundTripEncryptor())

        assertEquals(TOKEN, repository.getToken())

        assertEquals(TOKEN, prefs.getString(SharedPrefsGitHubTokenRepository.KEY_TOKEN, null))
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT))
    }

    @Test
    fun unreadableCiphertext_withLegacyPlaintext_retriesMigration() {
        prefs.edit()
            .putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN, TOKEN)
            .putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT, "not-readable")
            .commit()
        val repository = repository(ReversibleEncryptor())

        assertEquals(TOKEN, repository.getToken())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        val stored = prefs.getString(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT, null)
        assertNotEquals(TOKEN, stored)
        assertFalse(stored!!.contains(TOKEN))
    }

    @Test
    fun clearToken_removesCiphertextAndLegacyKey() {
        val repository = repository(ReversibleEncryptor())
        repository.setToken(TOKEN)
        prefs.edit().putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN, TOKEN).commit()

        repository.clearToken()

        assertNull(repository.getToken())
        assertFalse(repository.hasToken())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun absentOrBlankToken_staysEmpty() {
        val repository = repository(ReversibleEncryptor())

        assertNull(repository.getToken())
        assertFalse(repository.hasToken())
        assertTrue(prefs.all.isEmpty())

        repository.setToken("   ")
        repository.setToken("")

        assertNull(repository.getToken())
        assertFalse(repository.hasToken())
        assertTrue(prefs.all.isEmpty())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT))
    }

    @Test
    fun setToken_dropsUnreadableCiphertextAndDoesNotWritePlaintext() {
        val repository = repository(NonRoundTripEncryptor())

        assertFalse(repository.setToken(TOKEN))

        assertNull(repository.getToken())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT))
    }

    @Test
    fun encryptFailure_doesNotPersistPlaintextOrThrow() {
        val repository = repository(FailingEncryptor())

        assertFalse(repository.setToken(TOKEN))

        assertNull(repository.getToken())
        assertFalse(repository.hasToken())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT))
    }

    @Test
    fun blankLegacyValue_isRemovedWithoutWritingCiphertext() {
        prefs.edit().putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN, "   ").commit()
        val repository = repository(ReversibleEncryptor())

        assertNull(repository.getToken())
        assertFalse(repository.hasToken())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun keyInvalidation_returnsAbsentAndDoesNotThrowIntoUpdateCheck() {
        prefs.edit()
            .putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT, "sealed-payload")
            .commit()
        val logs = AppLogService(context).also { it.clear() }
        val repository = repository(InvalidatedKeyEncryptor())
        val captured = ScriptedGitHub()
        val preferences = updatePreferences()
        val service = updateService(repository, captured.client(), logs, preferences)

        val token = runCatching { repository.getToken() }
        assertTrue(token.isSuccess)
        assertNull(token.getOrNull())
        assertFalse(repository.hasToken())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT))
        assertStoredValuesHide(TOKEN)

        preferences.setUpdateChannel(UpdateChannel.STABLE)
        val stable = runCatching { service.checkForUpdate() }
        assertTrue(stable.isSuccess)
        assertTrue(stable.getOrThrow().isSuccess)
        assertTrue(stable.getOrThrow().getOrThrow() is UpdateCheckResult.UpdateAvailable)

        preferences.setUpdateChannel(UpdateChannel.NIGHTLY)
        val nightly = runCatching { service.checkForUpdate() }
        assertTrue(nightly.isSuccess)
        assertTrue(nightly.getOrThrow().isSuccess)
        assertTrue(nightly.getOrThrow().getOrThrow() is UpdateCheckResult.UpdateAvailable)

        assertTrue(captured.authorizationHeaders.isNotEmpty())
        captured.authorizationHeaders.forEach { header ->
            assertNull(header)
        }
        assertLogsHide(logs, TOKEN)
    }

    @Test
    fun decryptThrow_returnsAbsentAndDoesNotThrowIntoUpdateCheck() {
        prefs.edit()
            .putString(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT, "sealed-payload")
            .commit()
        val repository = repository(ThrowingDecryptEncryptor())
        val captured = ScriptedGitHub()
        val service = updateService(repository, captured.client(), AppLogService(context))

        val checked = runCatching { service.checkForUpdate() }

        assertTrue(checked.isSuccess)
        assertTrue(checked.getOrThrow().isSuccess)
        assertNull(repository.getToken())
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN_CIPHERTEXT))
        captured.authorizationHeaders.forEach { header -> assertNull(header) }
        assertTrue(UpdateChecker(installedVersionCode = 1).evaluate(manifest()) is UpdateAvailability.Available)
    }

    @Test
    fun stableAndNightlyChecks_useStoredToken_thenClearOmitsIt() {
        val logs = AppLogService(context).also { it.clear() }
        val repository = repository(ReversibleEncryptor())
        val captured = ScriptedGitHub()
        val preferences = updatePreferences()
        val service = updateService(repository, captured.client(), logs, preferences)

        service.saveToken(TOKEN)
        assertFalse(prefs.contains(SharedPrefsGitHubTokenRepository.KEY_TOKEN))
        assertStoredValuesHide(TOKEN)

        preferences.setUpdateChannel(UpdateChannel.STABLE)
        assertTrue(service.checkForUpdate().getOrThrow() is UpdateCheckResult.UpdateAvailable)
        preferences.setUpdateChannel(UpdateChannel.NIGHTLY)
        assertTrue(service.checkForUpdate().getOrThrow() is UpdateCheckResult.UpdateAvailable)

        val authorized = captured.authorizationHeaders.toList()
        assertTrue(authorized.isNotEmpty())
        authorized.forEach { header ->
            assertEquals("token $TOKEN", header)
        }

        captured.authorizationHeaders.clear()
        service.clearToken()
        preferences.setUpdateChannel(UpdateChannel.STABLE)
        assertTrue(service.checkForUpdate().getOrThrow() is UpdateCheckResult.UpdateAvailable)
        preferences.setUpdateChannel(UpdateChannel.NIGHTLY)
        assertTrue(service.checkForUpdate().getOrThrow() is UpdateCheckResult.UpdateAvailable)
        assertTrue(captured.authorizationHeaders.isNotEmpty())
        captured.authorizationHeaders.forEach { header -> assertNull(header) }
        assertFalse(repository.hasToken())
        assertTrue(prefs.all.isEmpty())
        assertLogsHide(logs, TOKEN)
    }

    private fun repository(encryptor: GitHubTokenEncryptor) =
        SharedPrefsGitHubTokenRepository(prefs, encryptor)

    private fun updatePreferences(): UpdateCheckPreferencesRepository =
        UpdateCheckPreferencesRepository(
            context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE),
        )

    private fun updateService(
        repository: SharedPrefsGitHubTokenRepository,
        httpClient: OkHttpClient,
        logs: AppLogService,
        preferences: UpdateCheckPreferencesRepository = updatePreferences(),
    ): AppUpdateService =
        AppUpdateService(
            context = context,
            tokenRepository = repository,
            releaseClient = GitHubReleaseClient(httpClient = httpClient, owner = "o", repo = "r"),
            updateChecker = UpdateChecker(installedVersionCode = 1),
            preferencesRepository = preferences,
            appLogService = logs,
        )

    private fun assertStoredValuesHide(token: String) {
        prefs.all.forEach { (key, value) ->
            assertFalse(key.contains(token))
            assertFalse(value?.toString()?.contains(token) == true)
        }
    }

    private fun assertLogsHide(logs: AppLogService, token: String) {
        val messages = logs.readAll().joinToString("\n") { it.message }
        assertFalse(messages.contains(token))
        val logFile = File(context.filesDir, AppLogService.LOG_FILE_NAME)
        if (logFile.exists()) {
            assertFalse(logFile.readText().contains(token))
        }
    }

    private class ReversibleEncryptor : GitHubTokenEncryptor {
        override fun encrypt(plaintext: String): String {
            val encoded = Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))
            return "v1:$encoded"
        }

        override fun decrypt(ciphertext: String): String? {
            if (!ciphertext.startsWith("v1:")) return null
            return try {
                String(Base64.getDecoder().decode(ciphertext.removePrefix("v1:")), Charsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                null
            }
        }
    }

    private class NonRoundTripEncryptor : GitHubTokenEncryptor {
        override fun encrypt(plaintext: String): String = "ciphertext-not-the-token"

        override fun decrypt(ciphertext: String): String? = "unrelated-value"
    }

    private class InvalidatedKeyEncryptor : GitHubTokenEncryptor {
        override fun encrypt(plaintext: String): String = "ciphertext-not-the-token"

        override fun decrypt(ciphertext: String): String? = null
    }

    private class FailingEncryptor : GitHubTokenEncryptor {
        override fun encrypt(plaintext: String): String {
            throw GitHubTokenEncryptorException("GitHub token encryption failed")
        }

        override fun decrypt(ciphertext: String): String? = null
    }

    private class ThrowingDecryptEncryptor : GitHubTokenEncryptor {
        override fun encrypt(plaintext: String): String = "ciphertext-not-the-token"

        override fun decrypt(ciphertext: String): String? {
            throw IllegalStateException("keystore key invalidated")
        }
    }

    private class ScriptedGitHub : Interceptor {
        val authorizationHeaders = mutableListOf<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            authorizationHeaders += request.header("Authorization")
            val url = request.url.toString()
            val body = when {
                url.contains("/releases/assets/") -> VERSION_JSON
                url.contains("/releases/latest") -> LATEST_JSON
                url.contains("/releases") -> RELEASES_JSON
                else -> error("unexpected GitHub url")
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(JSON))
                .build()
        }

        fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
    }

    companion object {
        private const val PREFS: String = "github_token_prefs_test"
        private const val UPDATE_PREFS: String = "update_check_prefs_token_test"
        private const val TOKEN: String = "ghp_abc123xyz"
        private val JSON = "application/json".toMediaType()
        private val VERSION_JSON =
            """{"versionCode":2,"versionName":"0.9.0","apkFileName":"masroof.apk","sha256":"abc"}"""
        private val LATEST_JSON =
            """{"tag_name":"v0.9.0","assets":[{"name":"version.json","url":"https://api.github.com/repos/o/r/releases/assets/1"}]}"""
        private val RELEASES_JSON = "[$LATEST_JSON]"

        private fun manifest() = com.baraa.masroof.application.update.UpdateManifest(
            versionCode = 2,
            versionName = "0.9.0",
            apkFileName = "masroof.apk",
            sha256 = "abc",
        )
    }
}
