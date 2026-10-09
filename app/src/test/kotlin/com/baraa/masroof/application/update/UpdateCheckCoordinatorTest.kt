package com.baraa.masroof.application.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.update.GitHubReleaseClient
import com.baraa.masroof.application.update.GitHubTokenRepository
import com.baraa.masroof.application.update.UpdateChecker
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class UpdateCheckCoordinatorTest {
    private lateinit var context: Context
    private lateinit var appLogService: AppLogService
    private lateinit var pendingUpdateStore: PendingUpdateStore
    private lateinit var preferencesRepository: UpdateCheckPreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appLogService = AppLogService(context).also { it.clear() }
        pendingUpdateStore = PendingUpdateStore(
            context.getSharedPreferences("pending_update_prefs_test", Context.MODE_PRIVATE),
        ).also { it.clear() }
        preferencesRepository = UpdateCheckPreferencesRepository(
            context.getSharedPreferences("update_check_prefs_test", Context.MODE_PRIVATE),
        )
    }

    @Test
    fun shouldCheckNow_usesLastAttemptNotLastSuccess() {
        val now = 1_700_000_000_000L
        preferencesRepository.setLastCheckEpochMs(now - 10_000L)
        preferencesRepository.setLastAttemptEpochMs(now - 10_000L)
        val coordinator = coordinator(minIntervalMs = 60_000L, now = { now })

        assertFalse(coordinator.shouldCheckNow())
    }

    @Test
    fun shouldCheckNow_fallsBackToLastSuccessWhenNoAttemptRecorded() {
        val now = 1_700_000_000_000L
        preferencesRepository.setLastCheckEpochMs(now - 10_000L)
        val coordinator = coordinator(minIntervalMs = 60_000L, now = { now })

        assertFalse(coordinator.shouldCheckNow())
    }

    @Test
    fun checkForUpdate_recordsAttemptOnFailure() {
        val now = 1_700_000_000_000L
        var current = now
        val coordinator = coordinator(
            minIntervalMs = 60_000L,
            now = { current },
            performUpdateCheck = { Result.failure(IOException("offline")) },
        )

        val result = coordinator.checkForUpdate("test")

        assertTrue(result.isFailure)
        assertEquals(now, preferencesRepository.getLastAttemptEpochMs())
        assertEquals(0L, preferencesRepository.getLastCheckEpochMs())
        current += 30_000L
        assertFalse(coordinator.shouldCheckNow())
    }

    @Test
    fun checkForUpdate_recordsSuccessTimestampAndPendingManifest() {
        val manifest = UpdateManifest(
            versionCode = 99,
            versionName = "9.9.0",
            apkFileName = "masroof.apk",
            sha256 = "abc",
            releaseNotes = null,
        )
        val coordinator = coordinator(
            performUpdateCheck = { Result.success(UpdateCheckResult.UpdateAvailable(manifest)) },
        )

        val result = coordinator.checkForUpdate("manual")

        assertTrue(result.isSuccess)
        assertEquals(manifest, pendingUpdateStore.readAvailable())
        assertTrue(preferencesRepository.getLastCheckEpochMs() > 0L)
    }

    @Test
    fun clearPendingUpdate_removesStoredManifest() {
        val manifest = UpdateManifest(
            versionCode = 2,
            versionName = "0.2.0",
            apkFileName = "masroof.apk",
            sha256 = "def",
            releaseNotes = null,
        )
        pendingUpdateStore.saveAvailable(manifest)
        val coordinator = coordinator()

        coordinator.clearPendingUpdate()

        assertNull(pendingUpdateStore.readAvailable())
    }

    @Test
    fun restorePendingUpdate_clearsStaleManifestWhenAlreadyInstalled() {
        val manifest = UpdateManifest(
            versionCode = 4,
            versionName = "0.2.1",
            apkFileName = "masroof.apk",
            sha256 = "def",
            releaseNotes = null,
        )
        pendingUpdateStore.saveAvailable(manifest)
        val coordinator = coordinator()

        assertNull(coordinator.restorePendingUpdate())
        assertNull(pendingUpdateStore.readAvailable())
    }

    @Test
    fun restorePendingUpdate_returnsManifestWhenStillNeeded() {
        val manifest = UpdateManifest(
            versionCode = 99,
            versionName = "9.9.0",
            apkFileName = "masroof.apk",
            sha256 = "abc",
            releaseNotes = null,
        )
        pendingUpdateStore.saveAvailable(manifest)
        val coordinator = coordinator()

        assertEquals(manifest, coordinator.restorePendingUpdate())
    }

    private fun coordinator(
        minIntervalMs: Long = 0L,
        now: () -> Long = { System.currentTimeMillis() },
        performUpdateCheck: () -> Result<UpdateCheckResult> = { Result.success(UpdateCheckResult.UpToDate) },
    ): UpdateCheckCoordinator =
        UpdateCheckCoordinator(
            appUpdateService = appUpdateService(),
            pendingUpdateStore = pendingUpdateStore,
            preferencesRepository = preferencesRepository,
            appLogService = appLogService,
            minIntervalMs = minIntervalMs,
            clock = now,
            performUpdateCheck = performUpdateCheck,
        )

    private fun appUpdateService(): AppUpdateService =
        AppUpdateService(
            context = context,
            tokenRepository = object : GitHubTokenRepository {
                override fun getToken(): String? = null
                override fun setToken(token: String): Boolean = true
                override fun clearToken() = Unit
                override fun hasToken(): Boolean = false
            },
            releaseClient = GitHubReleaseClient(OkHttpClient(), "bakhshb", "Masroof"),
            updateChecker = UpdateChecker(installedVersionCode = 4),
            preferencesRepository = preferencesRepository,
            appLogService = appLogService,
        )
}
