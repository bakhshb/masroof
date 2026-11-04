package com.baraa.masroof.presentation.notification

import com.baraa.masroof.presentation.dashboard.DashboardUiState
import com.baraa.masroof.presentation.settings.AppUpdateUiState
import com.baraa.masroof.presentation.settings.SettingsUiState

fun notificationCenterExternalState(
    dashboardState: DashboardUiState,
    settingsState: SettingsUiState,
): NotificationCenterExternalState {
    val updateState = settingsState.updateState
    val readyVersion = when (updateState) {
        is AppUpdateUiState.ReadyToInstall -> updateState.manifest.versionName
        is AppUpdateUiState.Downloading -> updateState.manifest.versionName
        else -> null
    }
    val availableVersion = when (updateState) {
        is AppUpdateUiState.Available -> updateState.manifest.versionName
        else -> null
    }

    return NotificationCenterExternalState(
        periodLabel = dashboardState.periodLabel,
        periodKey = dashboardState.period?.startDate?.toString().orEmpty(),
        excludedForeignCurrencyCount = dashboardState.summary?.excludedOtherCurrencyCount ?: 0,
        updateAvailableVersion = availableVersion,
        updateReadyVersion = readyVersion,
    )
}
