package com.baraa.masroof.application.review

import com.baraa.masroof.testsupport.CountingFinancialTransactionRepository
import com.baraa.masroof.testsupport.CountingParsedEventRepository
import com.baraa.masroof.testsupport.RoomReviewWiring
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.ReviewIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import com.baraa.masroof.testsupport.event
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import com.baraa.masroof.testsupport.NoOpAppLog
import com.baraa.masroof.testsupport.noOpOwnershipConfirmation
import com.baraa.masroof.testsupport.noOpProcessingRecovery
import com.baraa.masroof.testsupport.noOpReclassificationRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScopedMutationReconciliationTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedDelegate: RoomParsedEventRepository
    private lateinit var parsed: CountingParsedEventRepository
    private lateinit var ftDelegate: RoomFinancialTransactionRepository
    private lateinit var transactions: CountingFinancialTransactionRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var confirmation: OwnershipConfirmationService
    private lateinit var workflow: ReviewWorkflowService
    private lateinit var restore: TransactionRestoreService
    private val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedDelegate = RoomParsedEventRepository(db.parsedEventDao())
        parsed = CountingParsedEventRepository(parsedDelegate)
        ftDelegate = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        transactions = CountingFinancialTransactionRepository(ftDelegate)
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        val wiring = RoomReviewWiring(
            db = db,
            clock = clock,
            raw = rawRepo,
            parsed = parsed,
            ledger = transactions,
            reviews = reviewRepo,
            reconcileWithConfirmation = true,
        )
        confirmation = wiring.confirmation
        workflow = wiring.workflow
        restore = TransactionRestoreService(
            reviewRepository = reviewRepo,
            userDecisionRepository = com.baraa.masroof.data.repository.RoomUserDecisionRepository(db),
            financialTransactionRepository = transactions,
            reconciliation = wiring.reconciliation,
            reclassification = TransactionReclassificationService(
                financialTransactionRepository = transactions,
                effectiveParsedEventProvider = wiring.effective,
                ownershipResolver = wiring.resolver,
                appLogService = NoOpAppLog,
                ownershipConfirmationService = noOpOwnershipConfirmation(),
                persistence = noOpReclassificationRepository(transactions),
            ),
            clock = clock,
            reviewQueueUpdater = wiring.updater,
            appLogService = NoOpAppLog,
            processingRecovery = noOpProcessingRecovery(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun correction_onePurchase_doesNotScanUnrelatedHistory() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistPurchase(smsId = "sms-buy", eventId = "pe-buy", amount = null)
        persistTransfer(smsId = "sms-old", eventId = "pe-old")
        reviewRepo.upsertRequired(
            rawSmsId = "sms-buy",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("missing_amount"),
            now = clock.now(),
        )
        parsed.reset()
        transactions.reset()

        val result = workflow.applyCorrection(
            reviewId = ReviewIdFactory.fromRawSmsId("sms-buy"),
            correctedAmount = money("51.99"),
        )

        assertTrue(result is ReviewWorkflowResult.Success)
        val success = result as ReviewWorkflowResult.Success
        assertEquals(ReviewStatus.RESOLVED, success.review.status)
        assertEquals(ReviewResolutionKind.USER_CORRECTION, success.review.resolutionKind)
        assertEquals(FinancialTransactionType.EXPENSE, success.transaction!!.type)
        assertEquals(money("51.99"), success.transaction.amount)
        assertNull(transactions.findByRawSmsId("sms-old"))
        assertNoGlobalScan()
        assertTrue(parsed.loadedRawSmsIdSets.flatten().contains("sms-buy"))
        assertTrue(parsed.loadedRawSmsIdSets.flatten().none { it == "sms-old" })
    }

    @Test
    fun restore_onePurchase_doesNotScanUnrelatedHistory() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistPurchase(smsId = "sms-buy", eventId = "pe-buy", amount = "40.00")
        persistTransfer(smsId = "sms-old", eventId = "pe-old")
        val review = reviewRepo.upsertRequired(
            rawSmsId = "sms-buy",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("user_ignored_transaction"),
            now = clock.now(),
        )
        reviewRepo.markResolved(
            id = review.id,
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = clock.now(),
            resolvedTransactionId = null,
        )
        parsed.reset()
        transactions.reset()

        val result = restore.restore("sms-buy")

        assertTrue(result is com.baraa.masroof.application.transaction.RestoreResult.Success)
        val posted = transactions.findByRawSmsId("sms-buy")!!
        assertEquals(FinancialTransactionType.EXPENSE, posted.type)
        assertEquals(money("40.00"), posted.amount)
        assertNull(transactions.findByRawSmsId("sms-old"))
        assertNoGlobalScan()
        assertTrue(parsed.loadedRawSmsIdSets.flatten().none { it == "sms-old" })
    }

    @Test
    fun cardOwnership_reconcilesThatCardAndLeavesOtherHistory() = runBlocking {
        persistPurchase(smsId = "sms-owned", eventId = "pe-owned", amount = "12.00", last4 = "7271")
        persistPurchase(smsId = "sms-other", eventId = "pe-other", amount = "9.00", last4 = "1111")
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        parsed.reset()
        transactions.reset()

        workflow.reconcileOwnershipChange(
            ReviewWorkflowService.OwnershipChange.Card(CardReference(Bank.BANK_ALJAZIRA, "7271")),
        )

        val posted = transactions.findByRawSmsId("sms-owned")!!
        assertEquals(FinancialTransactionType.EXPENSE, posted.type)
        assertEquals(money("12.00"), posted.amount)
        assertNull(transactions.findByRawSmsId("sms-other"))
        assertEquals(listOf("sms-owned"), parsedDelegate.listRawSmsIdsReferencingCard(CardReference(Bank.BANK_ALJAZIRA, "7271")))
        assertNoGlobalScan()
        assertEquals(listOf(setOf("sms-owned")), parsed.loadedRawSmsIdSets.map { it.toSet() }.distinct())
    }

    @Test
    fun cardOwnership_matchesFullReconcileForTheAffectedCard() = runBlocking {
        persistPurchase(smsId = "sms-owned", eventId = "pe-owned", amount = "12.00", last4 = "7271")
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))

        workflow.reconcileOwnershipChange(
            ReviewWorkflowService.OwnershipChange.Card(CardReference(Bank.BANK_ALJAZIRA, "7271")),
        )
        val scoped = transactions.findByRawSmsId("sms-owned")!!

        val fullDb = openDatabase()
        try {
            val fullRaw = RoomRawSmsRepository(fullDb.rawSmsDao())
            val fullParsed = RoomParsedEventRepository(fullDb.parsedEventDao())
            val fullTx = RoomFinancialTransactionRepository(
                fullDb.financialTransactionDao(),
                fullDb.parsedEventDao(),
            )
            val fullReviews = RoomReviewRepository(fullDb.reviewItemDao())
            val fullCards = RoomCardRegistryRepository.from(fullDb)
            val fullAccounts = RoomAccountRegistryRepository.from(fullDb)
            val fullLoans = RoomLoanRegistryRepository.from(fullDb)
            OwnershipConfirmationService(fullAccounts, fullCards, fullLoans)
                .confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
            persistPurchase(
                smsId = "sms-owned",
                eventId = "pe-owned",
                amount = "12.00",
                last4 = "7271",
                raw = fullRaw,
                parsedEvents = fullParsed,
            )
            val fullWorkflow = workflowOn(fullDb, fullParsed, fullTx, fullReviews, fullRaw)
            fullWorkflow.refreshReviewQueue()
            val full = fullTx.findByRawSmsId("sms-owned")!!
            assertEquals(full.type, scoped.type)
            assertEquals(full.amount, scoped.amount)
            assertEquals(fullTx.listRawSmsIds(full.id), transactions.listRawSmsIds(scoped.id))
            assertEquals(full.linkedParsedEventIds, scoped.linkedParsedEventIds)
            assertEquals(
                fullReviews.findByRawSmsId("sms-owned")?.status,
                reviewRepo.findByRawSmsId("sms-owned")?.status,
            )
        } finally {
            fullDb.close()
        }
    }

    @Test
    fun accountOwnership_reconcilesReferencingTransferOnly() = runBlocking {
        persistTransfer(smsId = "sms-out", eventId = "pe-out", source = "3001", destination = "6810")
        persistTransfer(smsId = "sms-other", eventId = "pe-other", source = "3003", destination = "6810")
        persistPurchase(smsId = "sms-buy", eventId = "pe-buy", amount = "15.00", last4 = "7271")
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        parsed.reset()
        transactions.reset()

        workflow.reconcileOwnershipChange(
            ReviewWorkflowService.OwnershipChange.Account(AccountReference(Bank.BANK_ALJAZIRA, "3001")),
        )

        val posted = transactions.findByRawSmsId("sms-out")!!
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, posted.type)
        assertNull(transactions.findByRawSmsId("sms-other"))
        assertNull(transactions.findByRawSmsId("sms-buy"))
        assertEquals(
            listOf("sms-out"),
            parsedDelegate.listRawSmsIdsReferencingAccount(AccountReference(Bank.BANK_ALJAZIRA, "3001")),
        )
        assertNoGlobalScan()
    }

    @Test
    fun referencingQueries_matchOnlyTheChangedInstrument() = runBlocking {
        persistTransfer(smsId = "sms-src", eventId = "pe-src", source = "3001", destination = "6810")
        persist(
            smsId = "sms-dest",
            at = Instant.parse("2026-08-06T04:36:00Z"),
            event = event(
                id = "pe-dest",
                rawSmsId = "sms-dest",
                family = MessageFamily.TRANSFER_IN,
                amount = money("80.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "4100"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                network = BankNetworkType.INTRA_BANK,
                merchant = "Shop",
            ),
        )
        persistTransfer(smsId = "sms-else", eventId = "pe-else", source = "3003", destination = "6810")
        persistPurchase(smsId = "sms-card", eventId = "pe-card", amount = "8.00", last4 = "7271")
        persist(
            smsId = "sms-loan",
            at = Instant.parse("2026-08-06T04:36:00Z"),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
            event = event(
                id = "pe-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                merchant = "Shop",
            ),
        )
        persist(
            smsId = "sms-auto",
            at = Instant.parse("2026-08-06T05:00:00Z"),
            details = ParsedEventDetails(loanType = LoanType.AUTO),
            event = event(
                id = "pe-auto",
                rawSmsId = "sms-auto",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                merchant = "Shop",
            ),
        )

        assertEquals(
            listOf("sms-dest", "sms-loan", "sms-src"),
            parsedDelegate.listRawSmsIdsReferencingAccount(AccountReference(Bank.BANK_ALJAZIRA, "3001")).sorted(),
        )
        assertEquals(
            emptyList<String>(),
            parsedDelegate.listRawSmsIdsReferencingAccount(AccountReference(Bank("D360"), "3001")),
        )
        assertEquals(
            listOf("sms-card"),
            parsedDelegate.listRawSmsIdsReferencingCard(CardReference(Bank.BANK_ALJAZIRA, "7271")),
        )
        assertEquals(
            listOf("sms-loan"),
            parsedDelegate.listRawSmsIdsReferencingLoan(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)),
        )
        assertEquals(
            listOf("sms-auto"),
            parsedDelegate.listRawSmsIdsReferencingLoan(LoanReference(Bank.BANK_ALJAZIRA, LoanType.AUTO)),
        )
    }

    @Test
    fun loanOwnership_doesNotLoadUnrelatedHistory() = runBlocking {
        persist(
            smsId = "sms-loan",
            at = Instant.parse("2026-08-06T04:36:00Z"),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
            event = event(
                id = "pe-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                merchant = "Shop",
            ),
        )
        persistPurchase(smsId = "sms-buy", eventId = "pe-buy", amount = "15.00", last4 = "7271")
        confirmation.confirmLoanOwned(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        parsed.reset()
        transactions.reset()

        workflow.reconcileOwnershipChange(
            ReviewWorkflowService.OwnershipChange.Loan(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)),
        )

        assertNull(transactions.findByRawSmsId("sms-buy"))
        assertNoGlobalScan()
        assertTrue(parsed.loadedRawSmsIdSets.flatten().none { it == "sms-buy" })
    }

    @Test
    fun refreshReviewQueue_stillLoadsFullHistory() = runBlocking {
        persistPurchase(smsId = "sms-buy", eventId = "pe-buy", amount = "10.00")
        parsed.reset()
        transactions.reset()

        workflow.refreshReviewQueue()

        assertTrue(parsed.listAllCalls > 0)
    }

    private fun assertNoGlobalScan() {
        assertEquals(0, parsed.listAllCalls)
        assertEquals(0, transactions.listAllCalls)
        assertEquals(0, transactions.listByTypesCalls)
    }

    private fun openDatabase(): MasroofDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun workflowOn(
        database: MasroofDatabase,
        parsedEvents: ParsedEventRepository,
        financial: FinancialTransactionRepository,
        reviews: RoomReviewRepository,
        raw: RoomRawSmsRepository,
    ): ReviewWorkflowService =
        RoomReviewWiring(database, clock, raw = raw, parsed = parsedEvents, ledger = financial, reviews = reviews).workflow

    private suspend fun persistPurchase(
        smsId: String,
        eventId: String,
        amount: String?,
        last4: String = "7271",
        raw: RoomRawSmsRepository = rawRepo,
        parsedEvents: ParsedEventRepository = parsedDelegate,
    ) {
        persist(
            smsId = smsId,
            at = Instant.parse("2026-08-06T04:36:00Z"),
            raw = raw,
            parsedEvents = parsedEvents,
            event = event(
                id = eventId,
                rawSmsId = smsId,
                family = MessageFamily.PURCHASE,
                amount = amount?.let(::money),
                card = CardReference(Bank.BANK_ALJAZIRA, last4),
                merchant = "Shop",
            ),
        )
    }

    private suspend fun persistTransfer(
        smsId: String,
        eventId: String,
        source: String = "3001",
        destination: String = "6810",
    ) {
        persist(
            smsId = smsId,
            at = Instant.parse("2026-08-06T04:36:00Z"),
            event = event(
                id = eventId,
                rawSmsId = smsId,
                family = MessageFamily.TRANSFER_OUT,
                amount = money("80.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, source),
                destination = AccountReference(Bank.UNKNOWN, destination),
                network = BankNetworkType.INTER_BANK,
                merchant = "Shop",
            ),
        )
    }

    private suspend fun persist(
        smsId: String,
        event: ParsedEvent,
        at: Instant,
        details: ParsedEventDetails = ParsedEventDetails(),
        raw: RoomRawSmsRepository = rawRepo,
        parsedEvents: ParsedEventRepository = parsedDelegate,
    ) {
        val body = "body-$smsId"
        raw.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "AlJazira",
                body = body,
                receivedAt = at,
                deviceMessageId = smsId,
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        parsedEvents.save(event, details)
    }

    private fun money(value: String) = Money.of(value, Currency.SAR)
}
