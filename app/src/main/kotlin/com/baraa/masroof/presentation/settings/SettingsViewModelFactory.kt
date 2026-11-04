package com.baraa.masroof.presentation.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.update.InstallPermissionHelper
fun settingsViewModelFactory(
    container: AppContainer,
    appVersion: String,
    permissionStateProvider: () -> Boolean,
    onRequestInstallPermission: () -> Unit = {},
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        SettingsViewModel(
            messagePatternsWorkflow = container.messagePatternsWorkflow,
            bankSmsImportWorkflow = container.bankSmsImportWorkflow,
            settingsRegistryWorkflow = container.settingsRegistryWorkflow,
            settingsCommitmentsWorkflow = container.settingsCommitmentsWorkflow,
            appLocaleRepository = container.appLocaleRepository,
            themePreferencesRepository = container.themePreferencesRepository,
            databaseBackupService = container.databaseBackupService,
            reconcileOwnershipChange = { change ->
                container.reviewWorkflowService.reconcileOwnershipChange(change)
            },
            reparseStoredEvents = { container.reparseAllStoredEvents() },
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
            historicalMerchantRateCorrectionWorkflow = container.historicalMerchantRateCorrectionWorkflow,
        )
    }
}
