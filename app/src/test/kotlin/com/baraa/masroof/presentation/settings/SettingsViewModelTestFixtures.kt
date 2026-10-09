package com.baraa.masroof.presentation.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.update.ApkInstaller
import com.baraa.masroof.application.update.AppUpdateService
import com.baraa.masroof.application.update.GitHubReleaseClient
import com.baraa.masroof.application.update.GitHubTokenRepository
import com.baraa.masroof.application.update.PendingUpdateStore
import com.baraa.masroof.application.update.UpdateCheckCoordinator
import com.baraa.masroof.application.update.UpdateCheckPreferencesRepository
import com.baraa.masroof.application.update.UpdateChecker
import okhttp3.OkHttpClient

internal object SettingsViewModelTestFixtures {
    const val APP_VERSION: String = "0.2.1"

    fun appLogService(): AppLogService =
        AppLogService(ApplicationProvider.getApplicationContext())

    fun appUpdateService(
        token: String? = null,
        appLogService: AppLogService = appLogService(),
        persistToken: Boolean = true,
    ): AppUpdateService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tokenRepository =
            object : GitHubTokenRepository {
                private var storedToken: String? = token

                override fun getToken(): String? = storedToken

                override fun setToken(value: String): Boolean {
                    if (!persistToken) return false
                    storedToken = value
                    return true
                }

                override fun clearToken() {
                    storedToken = null
                }

                override fun hasToken(): Boolean = !storedToken.isNullOrBlank()
            }
        return AppUpdateService(
            context = context,
            tokenRepository = tokenRepository,
            releaseClient = GitHubReleaseClient(OkHttpClient(), "bakhshb", "Masroof"),
            updateChecker = UpdateChecker(installedVersionCode = 4),
            preferencesRepository = UpdateCheckPreferencesRepository(
                context.getSharedPreferences(
                    UpdateCheckPreferencesRepository.PREFS_NAME,
                    Context.MODE_PRIVATE,
                ),
            ),
            appLogService = appLogService,
        )
    }

    fun updateCheckCoordinator(
        appUpdateService: AppUpdateService = appUpdateService(),
        appLogService: AppLogService = appLogService(),
        pendingUpdateStore: PendingUpdateStore? = null,
    ): UpdateCheckCoordinator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return UpdateCheckCoordinator(
            appUpdateService = appUpdateService,
            pendingUpdateStore = pendingUpdateStore ?: PendingUpdateStore(
                context.getSharedPreferences(PendingUpdateStore.PREFS_NAME, Context.MODE_PRIVATE),
            ),
            preferencesRepository = UpdateCheckPreferencesRepository(
                context.getSharedPreferences(
                    UpdateCheckPreferencesRepository.PREFS_NAME,
                    Context.MODE_PRIVATE,
                ),
            ),
            appLogService = appLogService,
            minIntervalMs = 0L,
        )
    }

    fun apkInstaller(): ApkInstaller =
        ApkInstaller(ApplicationProvider.getApplicationContext())
}
