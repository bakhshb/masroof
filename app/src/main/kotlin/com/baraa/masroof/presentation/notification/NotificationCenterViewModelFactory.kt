package com.baraa.masroof.presentation.notification

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baraa.masroof.application.container.DashboardContainer
import com.baraa.masroof.application.container.StorageContainer

fun notificationCenterViewModelFactory(
    storage: StorageContainer,
    dashboard: DashboardContainer,
    permissionStateProvider: () -> Boolean,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        NotificationCenterViewModel(
            notificationCenterMetricsWorkflow = dashboard.notificationCenterMetricsWorkflow,
            notificationCenterService = dashboard.notificationCenterService,
            permissionStateProvider = permissionStateProvider,
            appLogService = storage.appLogService,
        )
    }
}
