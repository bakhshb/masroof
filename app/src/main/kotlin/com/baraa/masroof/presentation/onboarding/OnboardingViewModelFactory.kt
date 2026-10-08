package com.baraa.masroof.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.onboarding.HistoricalImportGateway
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.application.onboarding.toHistoricalImportResult

class OnboardingViewModelFactory(
    private val container: AppContainer,
    private val onboardingPreferencesRepository: OnboardingPreferencesRepository,
    private val permissionStateProvider: () -> Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            return OnboardingViewModel(
                onboardingPrefs = onboardingPreferencesRepository,
                historicalImportGateway = HistoricalImportGateway { after ->
                    container.historicalSmsScanner.scan(after).toHistoricalImportResult()
                },
                onboardingOwnershipWorkflow = container.onboardingOwnershipWorkflow,
                discoverFromStoredEvents = { container.discoverFromStoredEvents() },
                refreshReviewQueue = { container.refreshReviewQueue() },
                reconcileOwnershipChange = { change ->
                    container.reviewWorkflowService.reconcileOwnershipChange(change)
                },
                databaseBackupService = container.databaseBackupService,
                permissionStateProvider = permissionStateProvider,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
