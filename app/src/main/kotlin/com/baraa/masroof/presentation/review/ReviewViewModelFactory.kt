package com.baraa.masroof.presentation.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.review.ReviewDetailLoader

class ReviewViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReviewViewModel::class.java))
        val loader = ReviewDetailLoader(
            reviewWorkflowService = container.reviewWorkflowService,
            rawSmsRepository = container.rawSmsRepository,
            effectiveParsedEventProvider = container.effectiveParsedEventProvider,
        )
        return ReviewViewModel(
            reviewWorkflowService = container.reviewWorkflowService,
            detailLoader = loader,
            reviewOwnershipWorkflow = container.reviewOwnershipWorkflow,
            transactionRestoreService = container.transactionRestoreService,
            refreshReviewQueue = { container.refreshReviewQueue() },
            reparseStoredSms = { rawSmsId ->
                val raw = container.rawSmsRepository.getById(rawSmsId)
                if (raw != null) {
                    container.processRawSmsUseCase.reparseStored(raw)
                    container.refreshReviewQueue()
                }
            },
            appLocaleRepository = container.appLocaleRepository,
            maintenanceCompletions = container.maintenanceCompletionSignal.completions,
        ) as T
    }
}
