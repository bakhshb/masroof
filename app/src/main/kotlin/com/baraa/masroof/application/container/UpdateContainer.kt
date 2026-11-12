package com.baraa.masroof.application.container

import android.content.Context
import com.baraa.masroof.BuildConfig
import com.baraa.masroof.application.update.ApkInstaller
import com.baraa.masroof.application.update.AppUpdateService
import com.baraa.masroof.application.update.GitHubReleaseClient
import com.baraa.masroof.application.update.GitHubTokenRepository
import com.baraa.masroof.application.update.PendingUpdateStore
import com.baraa.masroof.application.update.UpdateCheckCoordinator
import com.baraa.masroof.application.update.UpdateCheckPreferencesRepository
import com.baraa.masroof.application.update.UpdateChecker
import com.baraa.masroof.data.preferences.AndroidKeystoreGitHubTokenEncryptor
import com.baraa.masroof.data.preferences.SharedPrefsGitHubTokenRepository
import okhttp3.OkHttpClient

/** App updates: release checks, the pending-update store, the token and the APK installer. */
class UpdateContainer(private val storage: StorageContainer) {
    private val appContext = storage.appContext

    /** Shared with the exchange-rate client in the dashboard container, which derives a fast-fail copy. */
    val httpClient: OkHttpClient = GitHubReleaseClient.defaultHttpClient()

    val githubTokenRepository: GitHubTokenRepository =
        SharedPrefsGitHubTokenRepository(
            prefs = appContext.getSharedPreferences(
                SharedPrefsGitHubTokenRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
            encryptor = AndroidKeystoreGitHubTokenEncryptor(),
        )

    val pendingUpdateStore: PendingUpdateStore by lazy {
        PendingUpdateStore(
            appContext.getSharedPreferences(
                PendingUpdateStore.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )
    }

    private val updateCheckPreferencesRepository: UpdateCheckPreferencesRepository by lazy {
        UpdateCheckPreferencesRepository(
            appContext.getSharedPreferences(
                UpdateCheckPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )
    }

    val appUpdateService: AppUpdateService by lazy {
        AppUpdateService(
            context = appContext,
            tokenRepository = githubTokenRepository,
            releaseClient =
                GitHubReleaseClient(
                    httpClient = httpClient,
                    owner = BuildConfig.GITHUB_OWNER,
                    repo = BuildConfig.GITHUB_REPO,
                ),
            updateChecker = UpdateChecker(installedVersionCode = BuildConfig.VERSION_CODE),
            preferencesRepository = updateCheckPreferencesRepository,
            appLogService = storage.appLogService,
        )
    }

    val updateCheckCoordinator: UpdateCheckCoordinator by lazy {
        UpdateCheckCoordinator(
            appUpdateService = appUpdateService,
            pendingUpdateStore = pendingUpdateStore,
            preferencesRepository = updateCheckPreferencesRepository,
            appLogService = storage.appLogService,
        )
    }

    val apkInstaller: ApkInstaller by lazy {
        ApkInstaller(appContext)
    }
}
