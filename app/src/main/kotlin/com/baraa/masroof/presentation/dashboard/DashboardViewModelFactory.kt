package com.baraa.masroof.presentation.dashboard

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
import com.baraa.masroof.application.AppContainer
import java.time.ZoneId

fun dashboardViewModelFactory(
    container: AppContainer,
    permissionStateProvider: () -> Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        DashboardViewModel(
            overviewLoader = container.dashboardService,
            analytics = container.analytics,
            dashboardRegistryWorkflow = container.dashboardRegistryWorkflow,
            dashboardPeriodWorkflow = container.dashboardPeriodWorkflow,
            layoutPreferencesRepository = container.dashboardLayoutPreferencesRepository,
            chartDataWorkflow = container.chartDataWorkflow,
            reclassificationService = container.transactionReclassificationService,
            ignoreService = container.transactionIgnoreService,
            dashboardCommitmentsWorkflow = container.dashboardCommitmentsWorkflow,
            smsEvidenceLoader = TransactionSmsEvidenceLoader(
                financialTransactionRepository = container.financialTransactionRepository,
                rawSmsRepository = container.rawSmsRepository,
                provenance = container.interpretationProvenanceWorkflow::load,
            ),
            permissionStateProvider = permissionStateProvider,
            appContext = container.applicationContext,
            appLocaleRepository = container.appLocaleRepository,
            appLogService = container.appLogService,
            zoneId = zoneId,
            maintenanceCompletions = container.maintenanceCompletionSignal.completions,
        )
    }
}
