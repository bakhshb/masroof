package com.baraa.masroof.application.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
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
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.ReviewIdFactory
import com.baraa.masroof.domain.ids.UserCorrectionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewWorkflowServiceTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var correctionRepo: RoomUserCorrectionRepository
    private lateinit var loans: RoomLoanRegistryRepository
    private lateinit var confirmation: OwnershipConfirmationService
    private lateinit var workflow: ReviewWorkflowService
    private val now = AtomicReference(Instant.parse("2026-08-11T12:00:00Z"))
    private val clock = InstantClock { now.get() }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        correctionRepo = RoomUserCorrectionRepository(db.userCorrectionDao())
        loans = RoomLoanRegistryRepository.from(db)
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        confirmation = OwnershipConfirmationService(accounts, cards, loans)
        val ownershipResolver = OwnershipResolver(accounts, cards, loans)
        val effective = EffectiveParsedEventProvider(parsedRepo, correctionRepo)
        val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = ownershipResolver,
            effectiveParsedEventProvider = effective,
        )
        val updater = ReviewQueueUpdater(reviewRepo, ftRepo, clock)
        val resolutionStore = RoomManualReviewResolutionRepository(db, ftRepo)
        workflow = ReviewWorkflowService(
            reviewRepository = reviewRepo,
            userCorrectionRepository = correctionRepo,
            financialTransactionRepository = ftRepo,
            rawSmsRepository = rawRepo,
            ownershipResolver = ownershipResolver,
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = effective,
            reconciliationService = reconciliation,
            reviewQueueUpdater = updater,
            manualReviewResolutionRepository = resolutionStore,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun needsReview_createsRequiredReview() = runBlocking {
        persistEvent(
            smsId = "sms-unknown",
            event = event(
                id = "pe-unknown",
                rawSmsId = "sms-unknown",
                family = MessageFamily.UNKNOWN,
                amount = money("80.00"),
            ),
        )
        workflow.refreshReviewQueue()
        val review = reviewRepo.findByRawSmsId("sms-unknown")!!
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertEquals(ReviewKind.NEEDS_REVIEW, review.kind)
        assertTrue(review.reasons.contains("unknown_message_family"))
        assertEquals(ReviewIdFactory.fromRawSmsId("sms-unknown"), review.id)
    }

    @Test
    fun pendingMatch_unownedLocalSide_createsRequiredReview() = runBlocking {
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        workflow.refreshReviewQueue()
        val review = reviewRepo.findByRawSmsId("sms-out")!!
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertEquals(ReviewKind.PENDING_MATCH, review.kind)
        assertEquals(listOf("transfer_pending_match"), review.reasons)
        assertEquals(0, ftRepo.listAll().size)
    }

    @Test
    fun refresh_unmatchedOwnedTransfer_postsExternalAndClearsReview() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        workflow.refreshReviewQueue()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, tx.type)
        assertNotEquals(FinancialTransactionType.EXPENSE, tx.type)
        val review = reviewRepo.findByRawSmsId("sms-out")
        assertTrue(review == null || review.status == ReviewStatus.RESOLVED)
    }

    @Test
    fun ignoredAndAssembled_createNoRequiredReview() = runBlocking {
        persistEvent(
            smsId = "sms-otp",
            event = event(
                id = "pe-otp",
                rawSmsId = "sms-otp",
                family = MessageFamily.OTP,
                amount = null,
            ),
        )
        persistEvent(
            smsId = "sms-fee",
            event = event(
                id = "pe-fee",
                rawSmsId = "sms-fee",
                family = MessageFamily.FEE,
                amount = money("1.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        workflow.refreshReviewQueue()
        assertNull(reviewRepo.findByRawSmsId("sms-otp"))
        assertNull(reviewRepo.listRequired().find { it.rawSmsId == "sms-fee" })
        assertEquals(1, ftRepo.listAll().size)
    }

    @Test
    fun refreshRerun_isIdempotent_oneReviewPerRawSms() = runBlocking {
        persistEvent(
            smsId = "sms-unknown",
            event = event(
                id = "pe-unknown",
                rawSmsId = "sms-unknown",
                family = MessageFamily.UNKNOWN,
                amount = money("80.00"),
            ),
        )
        workflow.refreshReviewQueue()
        val first = reviewRepo.findByRawSmsId("sms-unknown")!!
        val createdAt = first.createdAt
        now.set(Instant.parse("2026-08-11T13:00:00Z"))
        workflow.refreshReviewQueue()
        workflow.refreshReviewQueue()
        assertEquals(1, reviewRepo.listAll().size)
        val again = reviewRepo.findByRawSmsId("sms-unknown")!!
        assertEquals(createdAt, again.createdAt)
        assertEquals(first.id, again.id)
        assertEquals(ReviewStatus.REQUIRED, again.status)
    }

    @Test
    fun unmatchedOwnedOutgoing_postsWithoutWaitingForCounterpart() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        workflow.refreshReviewQueue()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, tx.type)
        val review = reviewRepo.findByRawSmsId("sms-out")
        assertTrue(review == null || review.status == ReviewStatus.RESOLVED)
    }

    @Test
    fun missingAmountCorrection_createsExpenseAndResolves() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-buy",
            event = event(
                id = "pe-buy",
                rawSmsId = "sms-buy",
                family = MessageFamily.PURCHASE,
                amount = null,
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
            ),
        )
        workflow.refreshReviewQueue()
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-buy")!!.status)

        val bodyBefore = rawRepo.getById("sms-buy")!!.body
        val parsedBefore = parsedRepo.findByRawSmsId("sms-buy")!!.event
        val result = workflow.applyCorrection(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-buy"),
            correctedAmount = money("51.99"),
        ) as ReviewWorkflowResult.Success

        assertEquals(ReviewStatus.RESOLVED, result.review.status)
        assertEquals(ReviewResolutionKind.USER_CORRECTION, result.review.resolutionKind)
        assertEquals(FinancialTransactionType.EXPENSE, result.transaction!!.type)
        assertEquals(money("51.99"), result.transaction!!.amount)
        assertEquals(bodyBefore, rawRepo.getById("sms-buy")!!.body)
        assertNull(parsedRepo.findByRawSmsId("sms-buy")!!.event.amount)
        assertEquals(parsedBefore.id, parsedRepo.findByRawSmsId("sms-buy")!!.event.id)
        assertNotNull(correctionRepo.latestForRawSmsId("sms-buy"))
    }

    @Test
    fun correction_survivesParsedEventReplacement_e1ToE2() = runBlocking {
        persistEvent(
            smsId = "sms-r",
            event = event(
                id = "e1",
                rawSmsId = "sms-r",
                family = MessageFamily.PURCHASE,
                amount = null,
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        workflow.refreshReviewQueue()
        workflow.applyCorrection(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-r"),
            correctedAmount = money("51.99"),
        )
        // Replace parser evidence with E2 (same RawSms).
        parsedRepo.save(
            event(
                id = "e2",
                rawSmsId = "sms-r",
                family = MessageFamily.PURCHASE,
                amount = null,
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
            ParsedEventDetails(),
        )
        val effective = EffectiveParsedEventProvider(parsedRepo, correctionRepo)
            .findEffectiveByRawSmsId("sms-r")!!
        assertEquals("e2", effective.event.id)
        assertEquals(money("51.99"), effective.event.amount)
        assertEquals("sms-r", correctionRepo.latestForRawSmsId("sms-r")!!.targetRawSmsId)
    }

    @Test
    fun latestCorrectionWins_deterministically() = runBlocking {
        persistEvent(
            smsId = "sms-c",
            event = event(
                id = "pe-c",
                rawSmsId = "sms-c",
                family = MessageFamily.PURCHASE,
                amount = money("100.00"),
                merchant = "X",
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        correctionRepo.save(
            UserCorrection(
                id = "corr-a",
                targetRawSmsId = "sms-c",
                correctedType = null,
                correctedAmount = money("120.00"),
                correctedMerchant = null,
                correctedCounterparty = null,
                createdAt = Instant.parse("2026-08-11T10:00:00Z"),
            ),
        )
        correctionRepo.save(
            UserCorrection(
                id = "corr-b",
                targetRawSmsId = "sms-c",
                correctedType = null,
                correctedAmount = null,
                correctedMerchant = "Y",
                correctedCounterparty = null,
                createdAt = Instant.parse("2026-08-11T11:00:00Z"),
            ),
        )
        val effective = EffectiveParsedEventProvider(parsedRepo, correctionRepo)
            .findEffectiveByRawSmsId("sms-c")!!.event
        // Fold chronologically: amount from earlier correction, merchant from later.
        assertEquals(money("120.00"), effective.amount)
        assertEquals("Y", effective.merchant)
    }

    @Test
    fun correctionRejected_whenAlreadyFinalized() = runBlocking {
        persistEvent(
            smsId = "sms-fee",
            event = event(
                id = "pe-fee",
                rawSmsId = "sms-fee",
                family = MessageFamily.FEE,
                amount = money("1.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        workflow.refreshReviewQueue()
        // Force a REQUIRED review row even though FT exists (edge), then reject correction.
        reviewRepo.upsertRequired(
            rawSmsId = "sms-fee",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("forced"),
            now = clock.now(),
        )
        val result = workflow.applyCorrection(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-fee"),
            correctedAmount = money("2.00"),
        )
        assertTrue(result is ReviewWorkflowResult.Rejected)
        assertEquals("raw_sms_already_finalized", (result as ReviewWorkflowResult.Rejected).reason)
    }

    @Test
    fun insufficientCorrection_keepsRequired() = runBlocking {
        persistEvent(
            smsId = "sms-unknown",
            event = event(
                id = "pe-unknown",
                rawSmsId = "sms-unknown",
                family = MessageFamily.UNKNOWN,
                amount = money("80.00"),
            ),
        )
        workflow.refreshReviewQueue()
        val result = workflow.applyCorrection(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-unknown"),
            correctedMerchant = "Biller",
        ) as ReviewWorkflowResult.Success
        assertEquals(ReviewStatus.REQUIRED, result.review.status)
        assertEquals(0, ftRepo.listAll().size)
    }

    @Test
    fun resolveTransferAsExternal_out_notExpense() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-out",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = clock.now(),
        )
        val result = workflow.resolveTransferAsExternal(
            ReviewIdFactory.fromRawSmsId("sms-out"),
        ) as ReviewWorkflowResult.Success
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, result.transaction!!.type)
        assertNotEquals(FinancialTransactionType.EXPENSE, result.transaction!!.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            result.transaction!!.sourceContainerId,
        )
        assertNull(result.transaction!!.destinationContainerId)
        assertEquals(ReviewStatus.RESOLVED, result.review.status)
        assertEquals(ReviewResolutionKind.USER_EXTERNAL_TRANSFER, result.review.resolutionKind)
        assertNull(
            RoomAccountRegistryRepository.from(db)
                .get(AccountReference(Bank.UNKNOWN, "6810")),
        )
    }

    @Test
    fun resolveTransferAsExternal_in_notIncome() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-in",
            event = event(
                id = "pe-in",
                rawSmsId = "sms-in",
                family = MessageFamily.TRANSFER_IN,
                amount = money("200.00"),
                source = AccountReference(Bank.UNKNOWN, "9999"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-in",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = clock.now(),
        )
        val result = workflow.resolveTransferAsExternal(
            ReviewIdFactory.fromRawSmsId("sms-in"),
        ) as ReviewWorkflowResult.Success
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN, result.transaction!!.type)
        assertNotEquals(FinancialTransactionType.INCOME, result.transaction!!.type)
        assertNull(result.transaction!!.sourceContainerId)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            result.transaction!!.destinationContainerId,
        )
    }

    @Test
    fun resolveSelfTransferPair_withoutAutoBridge() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank("D360"), "6810"))
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "9999"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        persistEvent(
            smsId = "sms-in",
            event = event(
                id = "pe-in",
                rawSmsId = "sms-in",
                bank = Bank("D360"),
                family = MessageFamily.TRANSFER_IN,
                amount = money("500.00"),
                destination = AccountReference(Bank("D360"), "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-out",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = clock.now(),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-in",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = clock.now(),
        )
        assertEquals(0, ftRepo.listAll().size)
        assertEquals(2, reviewRepo.listRequired().size)

        val result = workflow.resolveSelfTransferPair(
            outgoingReviewId = ReviewIdFactory.fromRawSmsId("sms-out"),
            incomingReviewId = ReviewIdFactory.fromRawSmsId("sms-in"),
        ) as ReviewWorkflowResult.Success
        assertEquals(FinancialTransactionType.SELF_TRANSFER, result.transaction!!.type)
        assertEquals(1, ftRepo.listAll().size)
        assertEquals(2, result.transaction!!.linkedParsedEventIds.size)
        assertTrue(ftRepo.isRawSmsLinked("sms-out"))
        assertTrue(ftRepo.isRawSmsLinked("sms-in"))
        assertEquals(ReviewStatus.RESOLVED, result.review.status)
        assertEquals(ReviewStatus.RESOLVED, result.pairedReview!!.status)
        assertEquals(ReviewResolutionKind.USER_SELF_TRANSFER_PAIR, result.review.resolutionKind)
        assertNull(
            RoomAccountRegistryRepository.from(db)
                .get(AccountReference(Bank.UNKNOWN, "9999")),
        )
    }

    @Test
    fun resolveSelfTransferPair_rejectsWhenAlreadyLinked() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank("D360"), "6810"))
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "9999"),
            ),
        )
        persistEvent(
            smsId = "sms-in",
            event = event(
                id = "pe-in",
                rawSmsId = "sms-in",
                bank = Bank("D360"),
                family = MessageFamily.TRANSFER_IN,
                amount = money("500.00"),
                destination = AccountReference(Bank("D360"), "6810"),
            ),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-out",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = clock.now(),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-in",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = clock.now(),
        )
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-out")!!.status)
        // Consume outgoing RawSms into an FT without resolving the review row.
        ftRepo.save(
            com.baraa.masroof.domain.model.FinancialTransaction(
                id = com.baraa.masroof.domain.ids.TransactionIdFactory.fromRawSmsIds(listOf("sms-out")),
                type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                amount = money("500.00"),
                occurredAt = Instant.parse("2026-08-01T12:00:00Z"),
                sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
                destinationContainerId = null,
                merchant = null,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = listOf("pe-out"),
            ),
            listOf("sms-out"),
        )
        val result = workflow.resolveSelfTransferPair(
            outgoingReviewId = ReviewIdFactory.fromRawSmsId("sms-out"),
            incomingReviewId = ReviewIdFactory.fromRawSmsId("sms-in"),
        )
        assertTrue(result is ReviewWorkflowResult.Rejected)
        assertEquals("raw_sms_already_finalized", (result as ReviewWorkflowResult.Rejected).reason)
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-out")!!.status)
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-in")!!.status)
        assertFalse(ftRepo.isRawSmsLinked("sms-in"))
    }

    @Test
    fun billPayment_userFinancialTypeBillPayment() = runBlocking {
        persistEvent(
            smsId = "sms-bill",
            event = event(
                id = "pe-bill",
                rawSmsId = "sms-bill",
                family = MessageFamily.BILL_PAYMENT,
                amount = money("80.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        workflow.refreshReviewQueue()
        assertEquals(1, ftRepo.listAll().size)
        assertEquals(FinancialTransactionType.BILL_PAYMENT, ftRepo.listAll().single().type)
    }

    @Test
    fun billPayment_withoutSource_autoAssembles() = runBlocking {
        persistEvent(
            smsId = "sms-bill",
            event = event(
                id = "pe-bill",
                rawSmsId = "sms-bill",
                family = MessageFamily.BILL_PAYMENT,
                amount = money("80.00"),
            ),
        )
        workflow.refreshReviewQueue()
        assertNull(reviewRepo.findByRawSmsId("sms-bill"))
        assertEquals(FinancialTransactionType.BILL_PAYMENT, ftRepo.listAll().single().type)
    }

    @Test
    fun resolveAsFinancialType_rejectsSelfTransfer() = runBlocking {
        persistEvent(
            smsId = "sms-unknown",
            event = event(
                id = "pe-unknown",
                rawSmsId = "sms-unknown",
                family = MessageFamily.UNKNOWN,
                amount = money("80.00"),
            ),
        )
        workflow.refreshReviewQueue()
        val result = workflow.resolveAsFinancialType(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-unknown"),
            type = FinancialTransactionType.SELF_TRANSFER,
        )
        assertTrue(result is ReviewWorkflowResult.Rejected)
    }

    @Test
    fun sameRawSms_sameCreatedAt_twoCorrections_bothPersist() = runBlocking {
        persistEvent(
            smsId = "sms-c2",
            event = event(
                id = "pe-c2",
                rawSmsId = "sms-c2",
                family = MessageFamily.PURCHASE,
                amount = money("100.00"),
                merchant = "X",
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        val createdAt = Instant.parse("2026-08-11T10:00:00.000Z")
        val a = UserCorrection(
            id = UserCorrectionIdFactory.create("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            targetRawSmsId = "sms-c2",
            correctedType = null,
            correctedAmount = money("120.00"),
            correctedMerchant = null,
            correctedCounterparty = null,
            createdAt = createdAt,
        )
        val b = UserCorrection(
            id = UserCorrectionIdFactory.create("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            targetRawSmsId = "sms-c2",
            correctedType = null,
            correctedAmount = null,
            correctedMerchant = "Y",
            correctedCounterparty = null,
            createdAt = createdAt,
        )
        correctionRepo.save(a)
        correctionRepo.save(b)
        val listed = correctionRepo.listForRawSmsId("sms-c2")
        assertEquals(2, listed.size)
        assertNotEquals(listed[0].id, listed[1].id)
        assertEquals(listOf(a.id, b.id).sorted(), listed.map { it.id }.sorted())
        // Ascending createdAt then id
        assertEquals(a.id, listed[0].id)
        assertEquals(b.id, listed[1].id)
        val effective = EffectiveParsedEventProvider(parsedRepo, correctionRepo)
            .findEffectiveByRawSmsId("sms-c2")!!.event
        assertEquals(money("120.00"), effective.amount)
        assertEquals("Y", effective.merchant)
    }

    @Test
    fun resolveAsFinancialType_loanRepayment_confirmsLoanOwnership() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "sms-loan")
        persistEvent(
            smsId = "sms-loan",
            event = event(
                id = "pe-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("3036.11"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                counterparty = "تمويل شخصي",
            ),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-loan",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("manual_resolution"),
            now = clock.now(),
        )
        val result = workflow.resolveAsFinancialType(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-loan"),
            type = FinancialTransactionType.LOAN_REPAYMENT,
        )
        assertTrue(result is ReviewWorkflowResult.Success)
        assertEquals(
            OwnershipStatus.OWNED,
            loans.resolve(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)),
        )
        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, ftRepo.listAll().single().type)
    }

    @Test
    fun resolvedReview_upsertRequired_doesNotReopen() = runBlocking {
        persistEvent(
            smsId = "sms-unknown",
            event = event(
                id = "pe-unknown",
                rawSmsId = "sms-unknown",
                family = MessageFamily.UNKNOWN,
                amount = money("80.00"),
            ),
        )
        workflow.refreshReviewQueue()
        val success = workflow.resolveAsFinancialType(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-unknown"),
            type = FinancialTransactionType.EXPENSE,
        ) as ReviewWorkflowResult.Success
        val resolved = success.review
        assertEquals(ReviewStatus.RESOLVED, resolved.status)
        assertEquals(ReviewResolutionKind.USER_FINANCIAL_TYPE, resolved.resolutionKind)
        val resolvedAt = resolved.resolvedAt
        val txId = resolved.resolvedTransactionId

        now.set(Instant.parse("2026-08-12T12:00:00Z"))
        val after = reviewRepo.upsertRequired(
            rawSmsId = "sms-unknown",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("stale_candidate"),
            now = clock.now(),
        )
        assertEquals(ReviewStatus.RESOLVED, after.status)
        assertEquals(ReviewResolutionKind.USER_FINANCIAL_TYPE, after.resolutionKind)
        assertEquals(resolvedAt, after.resolvedAt)
        assertEquals(txId, after.resolvedTransactionId)
        assertEquals(resolved.createdAt, after.createdAt)
        assertEquals(resolved.updatedAt, after.updatedAt)
        assertEquals(resolved.kind, after.kind)
        assertEquals(resolved.reasons, after.reasons)
    }

    @Test
    fun autoResolvedReview_upsertRequired_doesNotReopen() = runBlocking {
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        workflow.refreshReviewQueue()
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-out")!!.status)

        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        workflow.refreshReviewQueue()
        val auto = reviewRepo.findByRawSmsId("sms-out")!!
        assertEquals(ReviewStatus.RESOLVED, auto.status)
        assertEquals(ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED, auto.resolutionKind)
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, ftRepo.listAll().single().type)
        val after = reviewRepo.upsertRequired(
            rawSmsId = "sms-out",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("should_not_apply"),
            now = Instant.parse("2026-08-12T00:00:00Z"),
        )
        assertEquals(ReviewStatus.RESOLVED, after.status)
        assertEquals(ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED, after.resolutionKind)
        assertEquals(auto.resolvedAt, after.resolvedAt)
        assertEquals(auto.resolvedTransactionId, after.resolvedTransactionId)
    }

    @Test
    fun concurrentResolveSameReview_oneTransactionConsistent() = runBlocking {
        persistEvent(
            smsId = "sms-unknown",
            event = event(
                id = "pe-unknown",
                rawSmsId = "sms-unknown",
                family = MessageFamily.UNKNOWN,
                amount = money("80.00"),
            ),
        )
        workflow.refreshReviewQueue()
        val reviewId = ReviewIdFactory.fromRawSmsId("sms-unknown")
        val first = async {
            workflow.resolveAsFinancialType(reviewId, FinancialTransactionType.EXPENSE)
        }
        val second = async {
            workflow.resolveAsFinancialType(reviewId, FinancialTransactionType.EXPENSE)
        }
        val results = listOf(first.await(), second.await())
        assertTrue(results.any { it is ReviewWorkflowResult.Success })
        assertEquals(1, ftRepo.listAll().size)
        assertTrue(ftRepo.isRawSmsLinked("sms-unknown"))
        val review = reviewRepo.findByRawSmsId("sms-unknown")!!
        assertEquals(ReviewStatus.RESOLVED, review.status)
        assertEquals(ftRepo.listAll().single().id, review.resolvedTransactionId)
        // Loser is rejected or idempotent success — never a second FT / stolen link.
        assertFalse(results.any { it is ReviewWorkflowResult.Success && it.transaction?.id != review.resolvedTransactionId })
    }

    @Test
    fun resolveAsNonFinancial_dismissesWithoutTransaction() = runBlocking {
        persistEvent(
            smsId = "sms-otp",
            event = event(
                id = "pe-otp",
                rawSmsId = "sms-otp",
                family = MessageFamily.UNKNOWN,
                amount = null,
            ),
            body = "عملية غير معروفة بمبلغ: 100.00 SAR",
        )
        workflow.refreshReviewQueue()
        val result = workflow.resolveAsNonFinancial(
            ReviewIdFactory.fromRawSmsId("sms-otp"),
        ) as ReviewWorkflowResult.Success
        assertEquals(ReviewStatus.RESOLVED, result.review.status)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, result.review.resolutionKind)
        assertNull(result.transaction)
        assertEquals(0, ftRepo.listAll().size)
    }

    @Test
    fun manualResolution_matchesAutomaticAlJaziraTimeAroundSalaryBoundary() = runBlocking {
        val newYork = java.time.ZoneId.of("America/New_York")
        val riyadh = java.time.ZoneId.of("Asia/Riyadh")
        val local = java.time.LocalDateTime.of(2026, 8, 26, 22, 30)
        val receivedAt = Instant.parse("2026-08-27T02:00:00Z")
        val expected = local.atZone(riyadh).toInstant()
        assertTrue(expected.isBefore(receivedAt))

        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val resolver = OwnershipResolver(accounts, cards, loans)
        val effective = EffectiveParsedEventProvider(parsedRepo, correctionRepo)
        val auto = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = resolver,
            effectiveParsedEventProvider = effective,
            zoneId = newYork,
        )
        val manual = ReviewWorkflowService(
            reviewRepository = reviewRepo,
            userCorrectionRepository = correctionRepo,
            financialTransactionRepository = ftRepo,
            rawSmsRepository = rawRepo,
            ownershipResolver = resolver,
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = effective,
            reconciliationService = auto,
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, clock),
            manualReviewResolutionRepository = RoomManualReviewResolutionRepository(db, ftRepo),
            clock = clock,
            zoneId = newYork,
        )

        persistEvent(
            smsId = "sms-auto-purchase",
            at = receivedAt,
            details = ParsedEventDetails(occurredAtLocal = local),
            event = event(
                id = "pe-auto-purchase",
                rawSmsId = "sms-auto-purchase",
                family = MessageFamily.PURCHASE,
                amount = money("12.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
                merchant = "Shop",
            ),
        )
        persistEvent(
            smsId = "sms-auto-transfer",
            at = receivedAt,
            details = ParsedEventDetails(occurredAtLocal = local),
            event = event(
                id = "pe-auto-transfer",
                rawSmsId = "sms-auto-transfer",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("40.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        auto.reconcileStoredEvents()
        val autoPurchase = ftRepo.findByRawSmsId("sms-auto-purchase")!!
        val autoTransfer = ftRepo.findByRawSmsId("sms-auto-transfer")!!
        assertEquals(expected, autoPurchase.occurredAt)
        assertEquals("Asia/Riyadh", autoPurchase.occurredAtZone)
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, autoTransfer.type)
        assertEquals(expected, autoTransfer.occurredAt)
        assertEquals("Asia/Riyadh", autoTransfer.occurredAtZone)

        persistEvent(
            smsId = "sms-manual-purchase",
            at = receivedAt,
            details = ParsedEventDetails(occurredAtLocal = local),
            event = event(
                id = "pe-manual-purchase",
                rawSmsId = "sms-manual-purchase",
                family = MessageFamily.PURCHASE,
                amount = money("12.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
                merchant = "Shop",
            ),
        )
        persistEvent(
            smsId = "sms-manual-transfer",
            at = receivedAt,
            details = ParsedEventDetails(occurredAtLocal = local),
            event = event(
                id = "pe-manual-transfer",
                rawSmsId = "sms-manual-transfer",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("40.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-manual-purchase",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("needs_review"),
            now = clock.now(),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-manual-transfer",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = clock.now(),
        )

        val purchase = manual.resolveAsFinancialType(
            ReviewIdFactory.fromRawSmsId("sms-manual-purchase"),
            FinancialTransactionType.EXPENSE,
        ) as ReviewWorkflowResult.Success
        val transfer = manual.resolveTransferAsExternal(
            ReviewIdFactory.fromRawSmsId("sms-manual-transfer"),
        ) as ReviewWorkflowResult.Success

        assertEquals(autoPurchase.occurredAt, purchase.transaction!!.occurredAt)
        assertEquals(autoPurchase.occurredAtZone, purchase.transaction!!.occurredAtZone)
        assertEquals(expected, purchase.transaction!!.occurredAt)
        assertNotEquals(receivedAt, purchase.transaction!!.occurredAt)
        assertEquals(autoTransfer.occurredAt, transfer.transaction!!.occurredAt)
        assertEquals(autoTransfer.occurredAtZone, transfer.transaction!!.occurredAtZone)
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, transfer.transaction!!.type)
        assertNotEquals(receivedAt, transfer.transaction!!.occurredAt)
    }

    @Test
    fun refreshReviewQueue_autoIgnoresInformationalUnknown() = runBlocking {
        persistEvent(
            smsId = "sms-info",
            event = event(
                id = "pe-info",
                rawSmsId = "sms-info",
                family = MessageFamily.UNKNOWN,
                amount = null,
            ),
            body = "اسم المستفيد : TEST\nحالة: غير نشط",
        )
        workflow.refreshReviewQueue()
        val review = workflow.listRequiredReviews().single { it.rawSmsId == "sms-info" }
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertTrue(review.reasons.contains("unknown_message_family"))
        assertEquals(0, ftRepo.listAll().size)
    }

    private suspend fun persistEvent(
        smsId: String,
        event: ParsedEvent,
        details: ParsedEventDetails = ParsedEventDetails(),
        at: Instant = Instant.parse("2026-08-01T12:00:00Z"),
        body: String = "body-$smsId",
    ) {
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "AlJazira",
                body = body,
                receivedAt = at,
                deviceMessageId = smsId.removePrefix("sms-"),
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        parsedRepo.save(event, details)
    }

    private fun money(v: String) = Money.of(BigDecimal(v), Currency.SAR)

    private fun event(
        id: String,
        rawSmsId: String,
        family: MessageFamily,
        amount: Money?,
        bank: Bank = Bank.BANK_ALJAZIRA,
        source: AccountReference? = null,
        destination: AccountReference? = null,
        card: CardReference? = null,
        network: BankNetworkType? = null,
        channel: PurchaseChannel? = null,
        merchant: String? = null,
        counterparty: String? = null,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = bank,
        messageFamily = family,
        direction = MoneyDirection.OUTGOING,
        amount = amount,
        purchaseChannel = channel,
        sourceAccountRef = source,
        destinationAccountRef = destination,
        cardRef = card,
        merchant = merchant,
        counterparty = counterparty,
        occurredAt = null,
        bankNetworkType = network,
        confidence = Confidence(1.0),
        parseStatus = ParseStatus.SUCCESS,
    )
}
