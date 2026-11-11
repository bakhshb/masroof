package com.baraa.masroof.presentation.dashboard

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
import com.baraa.masroof.application.container.DashboardContainer
import com.baraa.masroof.application.container.MaintenanceContainer
import com.baraa.masroof.application.container.PatternsContainer
import com.baraa.masroof.application.container.ReviewContainer
import com.baraa.masroof.application.container.StorageContainer
import com.baraa.masroof.application.analytics.AnalyticsWorkflow
import java.time.ZoneId

fun dashboardViewModelFactory(
    storage: StorageContainer,
    maintenance: MaintenanceContainer,
    dashboard: DashboardContainer,
    patterns: PatternsContainer,
    review: ReviewContainer,
    analytics: () -> AnalyticsWorkflow,
    permissionStateProvider: () -> Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        DashboardViewModel(
            overviewLoader = dashboard.dashboardService,
            analytics = analytics(),
            dashboardRegistryWorkflow = dashboard.dashboardRegistryWorkflow,
            dashboardPeriodWorkflow = dashboard.dashboardPeriodWorkflow,
            layoutPreferencesRepository = dashboard.dashboardLayoutPreferencesRepository,
            chartDataWorkflow = dashboard.chartDataWorkflow,
            reclassificationService = review.transactionReclassificationService,
            ignoreService = review.transactionIgnoreService,
            dashboardCommitmentsWorkflow = dashboard.dashboardCommitmentsWorkflow,
            smsEvidenceLoader = TransactionSmsEvidenceLoader(
                financialTransactionRepository = storage.financialTransactionRepository,
                rawSmsRepository = storage.rawSmsRepository,
                provenance = patterns.interpretationProvenanceWorkflow::load,
            ),
            permissionStateProvider = permissionStateProvider,
            appContext = storage.appContext,
            appLocaleRepository = storage.appLocaleRepository,
            appLogService = storage.appLogService,
            zoneId = zoneId,
            maintenanceCompletions = maintenance.completionSignal.completions,
        )
    }
}
