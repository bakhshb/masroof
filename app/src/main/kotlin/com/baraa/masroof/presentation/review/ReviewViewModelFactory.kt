package com.baraa.masroof.presentation.review

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baraa.masroof.application.container.MaintenanceContainer
import com.baraa.masroof.application.container.PatternsContainer
import com.baraa.masroof.application.container.ReviewContainer
import com.baraa.masroof.application.container.SmsContainer
import com.baraa.masroof.application.container.StorageContainer
import com.baraa.masroof.application.review.ReviewDetailLoader

fun reviewViewModelFactory(
    storage: StorageContainer,
    maintenance: MaintenanceContainer,
    review: ReviewContainer,
    sms: SmsContainer,
    patterns: PatternsContainer,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val loader = ReviewDetailLoader(
            reviewWorkflowService = review.reviewWorkflowService,
            rawSmsRepository = storage.rawSmsRepository,
            effectiveParsedEventProvider = review.effectiveParsedEventProvider,
            provenance = patterns.interpretationProvenanceWorkflow::load,
        )
        ReviewViewModel(
            messagePatternsWorkflow = patterns.messagePatternsWorkflow,
            reviewWorkflowService = review.reviewWorkflowService,
            detailLoader = loader,
            reviewOwnershipWorkflow = review.reviewOwnershipWorkflow,
            transactionRestoreService = review.transactionRestoreService,
            reparseStoredSms = sms.explicitBankSelectionWorkflow::reparse,
            selectableBanks = sms.explicitBankSelectionWorkflow.selectableBanks(),
            selectBankAndReparse = { rawSmsId, bankId ->
                sms.explicitBankSelectionWorkflow.selectAndReparse(rawSmsId, bankId)
            },
            appLocaleRepository = storage.appLocaleRepository,
            maintenanceCompletions = maintenance.completionSignal.completions,
            appLogService = storage.appLogService,
        )
    }
}
