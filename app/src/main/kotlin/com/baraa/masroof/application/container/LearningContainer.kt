package com.baraa.masroof.application.container

import com.baraa.masroof.application.interpretation.BankInterpretationService

/**
 * The assisted-interpretation service. It is absent when a test supplies its own bank adapter.
 * Built before review and SMS, which read it lazily.
 */
class LearningContainer(
    storage: StorageContainer,
    enabled: Boolean,
    onFeedbackChanged: () -> Unit,
) {
    val interpretationService: BankInterpretationService? = if (enabled) {
        BankInterpretationService(
            storage.appContext, storage.rawSmsRepository, storage.parsedEventRepository,
            storage.userCorrectionRepository, storage.reviewRepository, storage.userDecisionRepository,
            storage.financialTransactionRepository,
            storage.applicationScope, storage.appLogService,
            patterns = storage.messagePatternStore::load,
            onFeedbackChanged = onFeedbackChanged,
        )
    } else {
        null
    }
}
