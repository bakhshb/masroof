package com.baraa.masroof.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.baraa.masroof.application.onboarding.HistoricalSmsRescanService
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
import com.baraa.masroof.application.AppContainer
import java.time.ZoneId

class DashboardViewModelFactory(
    private val container: AppContainer,
    private val permissionStateProvider: () -> Boolean,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DashboardViewModel::class.java))
        return DashboardViewModel(
            overviewLoader = container.dashboardService,
            dashboardRegistryWorkflow = container.dashboardRegistryWorkflow,
            dashboardPeriodWorkflow = container.dashboardPeriodWorkflow,
            layoutPreferencesRepository = container.dashboardLayoutPreferencesRepository,
            rescanService = { HistoricalSmsRescanService(container).rescan() },
            reclassificationService = container.transactionReclassificationService,
            ignoreService = container.transactionIgnoreService,
            dashboardCommitmentsWorkflow = container.dashboardCommitmentsWorkflow,
            smsEvidenceLoader = TransactionSmsEvidenceLoader(
                financialTransactionRepository = container.financialTransactionRepository,
                rawSmsRepository = container.rawSmsRepository,
            ),
            permissionStateProvider = permissionStateProvider,
            appContext = container.applicationContext,
            appLocaleRepository = container.appLocaleRepository,
            appLogService = container.appLogService,
            zoneId = zoneId,
            maintenanceCompletions = container.maintenanceCompletionSignal.completions,
        ) as T
    }
}
