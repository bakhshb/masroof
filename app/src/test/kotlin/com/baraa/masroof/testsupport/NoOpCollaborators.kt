package com.baraa.masroof.testsupport

import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.TransactionReclassificationRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.time.InstantClock
import java.time.Instant

/*
 * Collaborators a test does not exercise. Each does what a missing collaborator used to do: nothing,
 * and no write fails. A test that cares passes the real one.
 */

private val noOpClock = InstantClock { Instant.EPOCH }

/** Accepts every review write and stores none. */
internal open class NoOpReviewRepository : EmptyReviewRepository() {
    override suspend fun upsertRequired(rawSmsId: String, kind: ReviewKind, reasons: List<String>, now: Instant) =
        ReviewItem(
            id = rawSmsId, rawSmsId = rawSmsId, kind = kind, status = ReviewStatus.REQUIRED, reasons = reasons,
            createdAt = now, updatedAt = now, resolvedAt = null, resolutionKind = null, resolvedTransactionId = null,
        )
}

internal object NoOpProcessingRetryRepository : ProcessingRetryRepository {
    override suspend fun markRequired(rawSmsId: String, createdAt: Instant, mode: ProcessingRetryMode) = Unit
    override suspend fun markRequired(rawSmsIds: List<String>, createdAt: Instant, mode: ProcessingRetryMode) = Unit
    override suspend fun clear(rawSmsIds: List<String>) = Unit
}

/** Runs the write and keeps no decision row, so every SMS reads as undecided. */
internal object NoOpUserDecisionRepository : UserDecisionRepository {
    override suspend fun <R : Any> recordWith(decision: UserDecision, before: suspend () -> R?, write: suspend () -> R?): R? =
        write()

    override suspend fun effectiveFor(rawSmsIds: Collection<String>) = rawSmsIds.associateWith { EffectiveUserDecisions.NONE }
}

internal fun noOpOwnershipDiscovery() =
    OwnershipDiscoveryService(StubAccountRegistryRepository(), NoOpCardRegistryRepository(), NoOpLoanRegistryRepository)

internal fun noOpOwnershipConfirmation() =
    OwnershipConfirmationService(StubAccountRegistryRepository(), NoOpCardRegistryRepository(), NoOpLoanRegistryRepository)

internal fun noOpReviewQueueUpdater() =
    ReviewQueueUpdater(NoOpReviewRepository(), EmptyFinancialTransactionRepository(), noOpClock)

internal fun noOpIngestionReviewService() = IngestionReviewService(NoOpReviewRepository(), noOpClock)

internal fun noOpProcessingRecovery() =
    ProcessingRecovery(NoOpProcessingRetryRepository, EmptyReviewRepository(), NoOpUserDecisionRepository, noOpIngestionReviewService(), noOpClock,
        NoOpHistoricalBatchRecoveryScheduler)

/** Reads corrections from nowhere, so the projection is the stored event. */
internal fun noOpEffectiveParsedEventProvider(parsedEventRepository: ParsedEventRepository) =
    EffectiveParsedEventProvider(parsedEventRepository, NoOpUserDecisionRepository)

/** Saves the transaction update on [financialTransactionRepository], with no separate decision write. */
internal fun noOpReclassificationRepository(financialTransactionRepository: FinancialTransactionRepository) =
    TransactionReclassificationRepository { transaction, _, _ -> financialTransactionRepository.update(transaction) }

internal fun noOpReconciliation(): TransactionReconciliationService {
    val parsed = EmptyParsedEventRepository()
    return TransactionReconciliationService(
        rawSmsRepository = EmptyRawSmsRepository(),
        financialTransactionRepository = EmptyFinancialTransactionRepository(),
        ownershipResolver = OwnershipResolver(StubAccountRegistryRepository(), NoOpCardRegistryRepository(), NoOpLoanRegistryRepository),
        ownershipConfirmationService = noOpOwnershipConfirmation(),
        effectiveParsedEventProvider = noOpEffectiveParsedEventProvider(parsed),
        reviewRepository = EmptyReviewRepository(),
        userDecisionRepository = NoOpUserDecisionRepository,
    )
}
