package com.baraa.masroof.testsupport

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId

/** Room-backed review workflow and the reconciliation it uses, wired like production. */
internal class RoomReviewWiring(
    db: MasroofDatabase,
    clock: InstantClock,
    raw: RawSmsRepository = RoomRawSmsRepository(db.rawSmsDao()),
    parsed: ParsedEventRepository = RoomParsedEventRepository(db.parsedEventDao()),
    ledger: FinancialTransactionRepository =
        RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao()),
    reviews: ReviewRepository = RoomReviewRepository(db.reviewItemDao()),
    corrections: UserCorrectionRepository = RoomUserCorrectionRepository(db.userCorrectionDao()),
    loans: LoanRegistryRepository = RoomLoanRegistryRepository.from(db),
    /** The review store the queue updater writes; tests inject failures here. */
    reviewQueueReviews: ReviewRepository = reviews,
    reconcileWithReviews: Boolean = true,
    reconcileWithConfirmation: Boolean = false,
    zoneId: ZoneId = ZoneId.systemDefault(),
    processingRecovery: ProcessingRecovery? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val accounts = RoomAccountRegistryRepository.from(db)
    private val cards = RoomCardRegistryRepository.from(db)
    val confirmation = OwnershipConfirmationService(accounts, cards, loans)
    val resolver = OwnershipResolver(accounts, cards, loans)
    val effective = EffectiveParsedEventProvider(parsed, corrections)
    val reconciliation = TransactionReconciliationService(
        parsedEventRepository = parsed,
        rawSmsRepository = raw,
        financialTransactionRepository = ledger,
        ownershipResolver = resolver,
        ownershipConfirmationService = confirmation.takeIf { reconcileWithConfirmation },
        effectiveParsedEventProvider = effective,
        reviewRepository = reviews.takeIf { reconcileWithReviews },
        zoneId = zoneId,
    )
    val updater = ReviewQueueUpdater(reviewQueueReviews, ledger, clock)
    val workflow = ReviewWorkflowService(
        reviewRepository = reviews,
        userCorrectionRepository = corrections,
        financialTransactionRepository = ledger,
        rawSmsRepository = raw,
        ownershipResolver = resolver,
        ownershipConfirmationService = confirmation,
        effectiveParsedEventProvider = effective,
        parsedEventRepository = parsed,
        reconciliationService = reconciliation,
        reviewQueueUpdater = updater,
        manualReviewResolutionRepository = RoomManualReviewResolutionRepository(db, ledger),
        clock = clock,
        zoneId = zoneId,
        processingRecovery = processingRecovery,
        ioDispatcher = ioDispatcher,
    )
}
