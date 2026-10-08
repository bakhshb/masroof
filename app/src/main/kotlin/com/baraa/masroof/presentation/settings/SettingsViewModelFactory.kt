package com.baraa.masroof.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.onboarding.HistoricalSmsRescanService
import com.baraa.masroof.application.update.InstallPermissionHelper
class SettingsViewModelFactory(
    private val container: AppContainer,
    private val appVersion: String,
    private val permissionStateProvider: () -> Boolean,
    private val onRequestInstallPermission: () -> Unit = {},
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
        return SettingsViewModel(
            settingsRegistryWorkflow = container.settingsRegistryWorkflow,
            settingsCommitmentsWorkflow = container.settingsCommitmentsWorkflow,
            appLocaleRepository = container.appLocaleRepository,
            themePreferencesRepository = container.themePreferencesRepository,
            databaseBackupService = container.databaseBackupService,
            refreshReviewQueue = { container.refreshReviewQueue() },
            reconcileOwnershipChange = { change ->
                container.reviewWorkflowService.reconcileOwnershipChange(change)
            },
            reparseStoredEvents = { container.reparseAllStoredEvents().refreshedCount },
            importSmsFromInbox = { HistoricalSmsRescanService(container).rescan() },
            permissionStateProvider = permissionStateProvider,
            appVersion = appVersion,
            appUpdateService = container.appUpdateService,
            updateCheckCoordinator = container.updateCheckCoordinator,
            appLogService = container.appLogService,
            apkInstaller = container.apkInstaller,
            canInstallPackages = {
                InstallPermissionHelper.canInstallPackages(container.applicationContext)
            },
            onRequestInstallPermission = onRequestInstallPermission,
        ) as T
    }
}
