package com.baraa.masroof.presentation.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baraa.masroof.application.container.BackupContainer
import com.baraa.masroof.application.container.PatternsContainer
import com.baraa.masroof.application.container.ReviewContainer
import com.baraa.masroof.application.container.SmsContainer
import com.baraa.masroof.application.container.StorageContainer
import com.baraa.masroof.application.onboarding.HistoricalImportGateway
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository

fun onboardingViewModelFactory(
    storage: StorageContainer,
    backup: BackupContainer,
    review: ReviewContainer,
    sms: SmsContainer,
    patterns: PatternsContainer,
    onboardingPreferencesRepository: OnboardingPreferencesRepository,
    permissionStateProvider: () -> Boolean,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        OnboardingViewModel(
            onboardingPrefs = onboardingPreferencesRepository,
            historicalImportGateway = HistoricalImportGateway { request ->
                sms.bankSmsImportWorkflow.import(request.bankIds, request.from, request.through, request.onProgress)
            },
            messagePatternsWorkflow = patterns.messagePatternsWorkflow,
            onboardingOwnershipWorkflow = review.onboardingOwnershipWorkflow,
            refreshReviewQueue = { review.reviewWorkflowService.refreshReviewQueue() },
            reconcileOwnershipChange = { change ->
                review.reviewWorkflowService.reconcileOwnershipChange(change)
            },
            databaseBackupService = backup.databaseBackupService,
            permissionStateProvider = permissionStateProvider,
            appLogService = storage.appLogService,
        )
    }
}
