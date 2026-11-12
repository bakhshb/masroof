package com.baraa.masroof.application.container

import com.baraa.masroof.application.interpretation.InterpretationProvenanceWorkflow
import com.baraa.masroof.application.interpretation.LearningInsightsWorkflow
import com.baraa.masroof.application.interpretation.MessageReviewWorkflow
import com.baraa.masroof.application.patterns.MessagePatternsWorkflow
import com.baraa.masroof.application.patterns.PatternApprovalWorkflow
import com.baraa.masroof.application.patterns.PendingPatternsWorkflow
import com.baraa.masroof.domain.model.Bank

/** Bank message patterns (approval and preview) and the learning workflows that read stored evidence. Built after SMS. */
class PatternsContainer(
    private val storage: StorageContainer,
    private val learning: LearningContainer,
    private val review: ReviewContainer,
    private val sms: SmsContainer,
    private val maintenance: MaintenanceContainer,
) {
    val interpretationProvenanceWorkflow by lazy {
        InterpretationProvenanceWorkflow(review.effectiveParsedEventProvider, storage.reviewRepository,
            feedback = { learning.interpretationService?.feedbackStatus(it) })
    }
    val learningInsightsWorkflow by lazy {
        LearningInsightsWorkflow(
            learning.interpretationService, storage.parsedEventRepository, storage.financialTransactionRepository, storage.reviewRepository)
    }
    val messageReviewWorkflow by lazy {
        MessageReviewWorkflow(storage.reviewRepository, storage.rawSmsRepository, storage.parsedEventRepository)
    }

    val messagePatternsWorkflow by lazy {
        MessagePatternsWorkflow(
            storage.messagePatternStore, storage.databaseAccessGate, bundledBanks = storage.bundledPatternBanks,
            requiredBundledBankIds = setOf(Bank.BANK_ALJAZIRA.id),
            appLogService = storage.appLogService,
            activation = PatternApprovalWorkflow(storage.messagePatternStore, storage.databaseAccessGate,
                storage.accountRegistryRepository, review.ownershipConfirmationService, storage.cardRegistryRepository),
            pendingPreview = PendingPatternsWorkflow(
                storage.messagePatternStore, storage.databaseAccessGate,
                storage.reviewRepository, storage.rawSmsRepository, storage.parsedEventRepository, storage.userCorrectionRepository,
                storage.financialTransactionRepository, sms.bankSmsRegistry, sms.historicalSmsBatchProcessor,
                inTransaction = { work -> storage.withDatabaseTransaction(work) },
                markParseFailed = sms.processStoredSmsUseCase::recordExhaustedDerivedProcessing,
                onChanged = { maintenance.completionSignal.notifyCompleted(); sms.liveSmsIntake.schedulePendingProcessing() }))
    }
}
