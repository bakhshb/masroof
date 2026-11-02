package com.baraa.masroof.presentation.settings

import android.os.Looper
import com.baraa.masroof.application.update.AppUpdateService
import com.baraa.masroof.application.update.UpdateManifest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Coming back to the app must not open the system installer unless the user asked to install. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsInstallOnResumeTest {
    private val manifest = UpdateManifest(99, "9.9.0", "masroof.apk", "abc")
    private val state = MutableStateFlow(SettingsUiState(updateState = AppUpdateUiState.ReadyToInstall(manifest)))
    private var canInstall = true
    private var permissionRequests = 0

    private fun actions(service: AppUpdateService = SettingsViewModelTestFixtures.appUpdateService()) = SettingsUpdateActions(
        scope = TestScope(UnconfinedTestDispatcher()),
        settingsState = state,
        appUpdateService = service,
        updateCheckCoordinator = SettingsViewModelTestFixtures.updateCheckCoordinator(),
        appLogService = SettingsViewModelTestFixtures.appLogService(),
        apkInstaller = SettingsViewModelTestFixtures.apkInstaller(),
        canInstallPackages = { canInstall },
        onRequestInstallPermission = { permissionRequests++ },
    )

    @Test
    fun resumeWithADownloadedUpdate_doesNotStartTheInstaller() {
        val service = SettingsViewModelTestFixtures.appUpdateService()
        service.updateApkFile(manifest).apply { parentFile?.mkdirs(); writeBytes("fake-apk".toByteArray()) }

        actions(service).retryInstallAfterPermissionGranted()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AppUpdateUiState.ReadyToInstall(manifest), state.value.updateState)
    }

    @Test
    fun resumeAfterTheUserAskedAndGrantedPermission_continuesTheInstallOnce() {
        val service = SettingsViewModelTestFixtures.appUpdateService()
        service.updateApkFile(manifest).apply { parentFile?.mkdirs(); writeBytes("fake-apk".toByteArray()) }
        val actions = actions(service)
        canInstall = false
        actions.installPendingUpdate()
        assertEquals(1, permissionRequests)
        assertEquals(AppUpdateUiState.ReadyToInstall(manifest), state.value.updateState)

        canInstall = true
        actions.retryInstallAfterPermissionGranted()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppUpdateUiState.Idle, state.value.updateState)

        state.value = state.value.copy(updateState = AppUpdateUiState.ReadyToInstall(manifest))
        actions.retryInstallAfterPermissionGranted()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppUpdateUiState.ReadyToInstall(manifest), state.value.updateState)
    }
}
