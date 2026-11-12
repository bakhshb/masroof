package com.baraa.masroof.presentation.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baraa.masroof.application.container.BackupContainer
import com.baraa.masroof.application.container.DashboardContainer
import com.baraa.masroof.application.container.PatternsContainer
import com.baraa.masroof.application.container.ReviewContainer
import com.baraa.masroof.application.container.SmsContainer
import com.baraa.masroof.application.container.StorageContainer
import com.baraa.masroof.application.container.UpdateContainer
import com.baraa.masroof.application.update.InstallPermissionHelper
fun settingsViewModelFactory(
    storage: StorageContainer,
    backup: BackupContainer,
    dashboard: DashboardContainer,
    patterns: PatternsContainer,
    review: ReviewContainer,
    sms: SmsContainer,
    update: UpdateContainer,
    appVersion: String,
    permissionStateProvider: () -> Boolean,
    onRequestInstallPermission: () -> Unit = {},
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        SettingsViewModel(
            messagePatternsWorkflow = patterns.messagePatternsWorkflow,
            bankSmsImportWorkflow = sms.bankSmsImportWorkflow,
            settingsRegistryWorkflow = dashboard.settingsRegistryWorkflow,
            settingsCommitmentsWorkflow = dashboard.settingsCommitmentsWorkflow,
            appLocaleRepository = storage.appLocaleRepository,
            themePreferencesRepository = storage.themePreferencesRepository,
            databaseBackupService = backup.databaseBackupService,
            reconcileOwnershipChange = { change ->
                review.reviewWorkflowService.reconcileOwnershipChange(change)
            },
            reparseStoredEvents = { sms.reparseAllStoredEvents() },
            permissionStateProvider = permissionStateProvider,
            appVersion = appVersion,
            appUpdateService = update.appUpdateService,
            updateCheckCoordinator = update.updateCheckCoordinator,
            appLogService = storage.appLogService,
            apkInstaller = update.apkInstaller,
            canInstallPackages = {
                InstallPermissionHelper.canInstallPackages(storage.appContext)
            },
            onRequestInstallPermission = onRequestInstallPermission,
            historicalMerchantRateCorrectionWorkflow = dashboard.historicalMerchantRateCorrectionWorkflow,
        )
    }
}
