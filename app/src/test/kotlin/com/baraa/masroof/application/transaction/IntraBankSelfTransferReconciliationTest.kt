package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.AccountFlowScopeMode
import com.baraa.masroof.application.dashboard.CurrentAccountSummaryCalculator
import com.baraa.masroof.application.dashboard.SelfTransferDeduplicator
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.maintenance.BackfillOutcome
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.maintenance.MaintenanceRequirement
import com.baraa.masroof.application.maintenance.TransferIntegrityRepairCoordinator
import com.baraa.masroof.application.maintenance.TransferIntegrityRepairResult
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.ReviewIdFactory
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IntraBankSelfTransferReconciliationTest {

    private val outgoingBody =
        """
        حوالة صادرة الى حسابك الجاري
        من: 3001
        مبلغ: SAR 5,500.00
        إلى: 3002
        في: 2026-08-27 07:36
        """.trimIndent()

    private val incomingBody =
        """
        حوالة واردة داخلية
        مبلغ: SAR 5,500.00
        إلى: 3002
        اسم المرسل: براء بخش
        رقم حساب المرسل: 3001
        البنك المرسل: بنك الجزيرة
        في: 2026-08-27 07:36
        """.trimIndent()

    private lateinit var context: Context
    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var reviewRepo: ReviewRepository
    private lateinit var reviewQueueUpdater: ReviewQueueUpdater
    private lateinit var accounts: RoomAccountRegistryRepository
    private lateinit var cards: RoomCardRegistryRepository
    private lateinit var confirmation: OwnershipConfirmationService
    private lateinit var reconciliation: TransactionReconciliationService
    private val pipeline = AlJaziraParsingPipeline()
    private val zoneId = ZoneId.of("Asia/Riyadh")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        reviewQueueUpdater = ReviewQueueUpdater(
            reviewRepository = reviewRepo,
            financialTransactionRepository = ftRepo,
            clock = InstantClock.System,
        )
        accounts = RoomAccountRegistryRepository.from(db)
        cards = RoomCardRegistryRepository.from(db)
        confirmation = OwnershipConfirmationService(accounts, cards, NoOpLoanRegistryRepository)
        reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
            reviewRepository = reviewRepo,
            zoneId = zoneId,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun parser_exactBugSms_extractsIntraBankEndpoints() {
        val out = parseSms("sms-out", outgoingBody) as ParseResult.Success
        assertEquals(MessageFamily.TRANSFER_OUT, out.event.messageFamily)
        assertEquals(BankNetworkType.INTRA_BANK, out.event.bankNetworkType)
        assertEquals(Money.of("5500.00", Currency.SAR), out.event.amount)
        assertEquals("3001", out.event.sourceAccountRef?.maskedNumber)
        assertEquals("3002", out.event.destinationAccountRef?.maskedNumber)

        val inn = parseSms("sms-in", incomingBody) as ParseResult.Success
        assertEquals(MessageFamily.TRANSFER_IN, inn.event.messageFamily)
        assertEquals(BankNetworkType.INTRA_BANK, inn.event.bankNetworkType)
        assertEquals(Money.of("5500.00", Currency.SAR), inn.event.amount)
        assertEquals("3001", inn.event.sourceAccountRef?.maskedNumber)
        assertEquals("3002", inn.event.destinationAccountRef?.maskedNumber)
        assertEquals("براء بخش", inn.event.counterparty)
    }

    @Test
    fun matcher_intraBankLegs_pairByAccountBridge() {
        val out = parseSms("sms-out", outgoingBody) as ParseResult.Success
        val inn = parseSms("sms-in", incomingBody) as ParseResult.Success
        val t = LocalDateTime.parse("2026-08-27T07:36:00")
        val outCandidate = transferCandidate(out, t)
        val inCandidate = transferCandidate(inn, t)

        assertTrue(TransactionMatcher.hasIntraBankAccountBridge(outCandidate, inCandidate))
        val pairs = TransactionMatcher.findMutuallyUniquePairs(listOf(outCandidate, inCandidate))
        assertEquals(1, pairs.size)
    }

    @Test
    fun bothOwned_exactBugSms_singleSelfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        persistParsed("sms-out", outgoingBody)
        persistParsed("sms-in", incomingBody)

        reconciliation.reconcileStoredEvents()

        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            tx.sourceContainerId,
        )
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002"),
            tx.destinationContainerId,
        )
        assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun bothOwned_registryLongMasked_singleSelfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "1234567890123001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "1234567890123002"))
        persistParsed("sms-out", outgoingBody)
        persistParsed("sms-in", incomingBody)

        reconciliation.reconcileStoredEvents()

        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun bothOwned_unknownShortRowsOwnedLongMasks_singleSelfTransfer() = runBlocking {
        accounts.observe(AccountReference(Bank.BANK_ALJAZIRA, "3001"), "seed-out")
        accounts.observe(AccountReference(Bank.BANK_ALJAZIRA, "3002"), "seed-in")
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "1234567890123001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "1234567890123002"))
        persistParsed("sms-out", outgoingBody)
        persistParsed("sms-in", incomingBody)

        reconciliation.reconcileStoredEvents()

        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun confirmSourceFirst_thenDestination_upgradesToSelfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistParsed("sms-out", outgoingBody)
        persistParsed("sms-in", incomingBody)

        reconciliation.reconcileStoredEvents()
        assertTrue(ftRepo.listAll().isEmpty())

        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        reconciliation.reconcileStoredEvents()

        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun confirmDestinationFirst_thenSource_pairsWhenBothOwned() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        persistParsed("sms-out", outgoingBody)
        persistParsed("sms-in", incomingBody)

        reconciliation.reconcileStoredEvents()
        assertTrue(
            "Intra-bank counterpart should defer single-leg external posting",
            ftRepo.listAll().isEmpty(),
        )

        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        reconciliation.reconcileStoredEvents()

        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun upgrade_staleExternalPair_becomesSelfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        persistParsed("sms-out", outgoingBody)
        persistParsed("sms-in", incomingBody)
        seedStaleExternalPair("sms-out", "sms-in")

        reconciliation.reconcileStoredEvents()

        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun upgrade_staleExternalPair_outsideTimeWindow_staysExternal() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))

        val outBodyLate =
            """
            حوالة صادرة الى حسابك الجاري
            من: 3001
            مبلغ: SAR 5,500.00
            إلى: 3002
            في: 2026-08-27 08:00
            """.trimIndent()
        val inBodyEarly =
            """
            حوالة واردة داخلية
            مبلغ: SAR 5,500.00
            إلى: 3002
            اسم المرسل: براء بخش
            رقم حساب المرسل: 3001
            البنك المرسل: بنك الجزيرة
            في: 2026-08-27 07:36
            """.trimIndent()

        persistParsed("sms-out", outBodyLate)
        persistParsed("sms-in", inBodyEarly)
        seedStaleExternalPair("sms-out", "sms-in")

        reconciliation.reconcileStoredEvents()

        assertEquals(2, ftRepo.listAll().size)
        assertTrue(
            ftRepo.listAll().all {
                it.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT ||
                    it.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN
            },
        )
    }

    @Test
    fun upgrade_ambiguousSameTimestamp_doesNotMergeWrongLegs() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))

        persistParsed("sms-out-a", outgoingBody, Instant.parse("2026-08-27T04:36:00Z"))
        persistParsed("sms-out-b", outgoingBody, Instant.parse("2026-08-27T04:37:00Z"))
        persistParsed("sms-in-a", incomingBody, Instant.parse("2026-08-27T04:36:30Z"))
        persistParsed("sms-in-b", incomingBody, Instant.parse("2026-08-27T04:37:30Z"))
        seedStaleExternalPair("sms-out-a", "sms-in-a", idSuffix = "a")
        seedStaleExternalPair("sms-out-b", "sms-in-b", idSuffix = "b")

        reconciliation.reconcileStoredEvents()

        assertEquals(4, ftRepo.listAll().size)
        assertTrue(
            ftRepo.listAll().all {
                it.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT ||
                    it.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN
            },
        )
    }

    @Test
    fun twoSameAmountSelfTransfersDifferentDates_bothPersist() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))

        val julyOut =
            """
            حوالة صادرة الى حسابك الجاري
            من: 3001
            مبلغ: SAR 5,500.00
            إلى: 3002
            في: 2026-07-15 10:00
            """.trimIndent()
        val julyIn =
            """
            حوالة واردة داخلية
            مبلغ: SAR 5,500.00
            إلى: 3002
            اسم المرسل: براء بخش
            رقم حساب المرسل: 3001
            البنك المرسل: بنك الجزيرة
            في: 2026-07-15 10:00
            """.trimIndent()

        persistParsed("sms-july-out", julyOut, Instant.parse("2026-07-15T07:00:00Z"))
        persistParsed("sms-july-in", julyIn, Instant.parse("2026-07-15T07:00:00Z"))
        persistParsed("sms-aug-out", outgoingBody)
        persistParsed("sms-aug-in", incomingBody)

        reconciliation.reconcileStoredEvents()

        assertEquals(2, ftRepo.listAll().size)
        val txs = ftRepo.listAll().filter { it.type == FinancialTransactionType.SELF_TRANSFER }
        assertEquals(2, txs.size)
        assertEquals(
            setOf(
                Instant.parse("2026-07-15T07:00:00Z"),
                Instant.parse("2026-08-27T04:36:00Z"),
            ),
            txs.map { it.occurredAt }.toSet(),
        )
    }

    @Test
    fun wronglyMergedSelfTransferLink_releasedOnReconcile() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))

        persistParsed("sms-july-out", outgoingBody.replace("2026-08-27", "2026-07-15"), Instant.parse("2026-07-15T07:00:00Z"))
        persistParsed("sms-july-in", incomingBody.replace("2026-08-27", "2026-07-15"), Instant.parse("2026-07-15T07:00:00Z"))
        reconciliation.reconcileStoredEvents()
        val julyTx = ftRepo.listAll().single()

        persistParsed("sms-aug-out", outgoingBody)
        persistParsed("sms-aug-in", incomingBody)
        ftRepo.linkRawSmsIfAbsent(julyTx.id, "sms-aug-out")
        ftRepo.linkRawSmsIfAbsent(julyTx.id, "sms-aug-in")

        reconciliation.reconcileStoredEvents()

        assertEquals(2, ftRepo.listAll().size)
        val augTx = ftRepo.findByRawSmsId("sms-aug-out")!!
        assertEquals(FinancialTransactionType.SELF_TRANSFER, augTx.type)
        assertEquals(Instant.parse("2026-08-27T04:36:00Z"), augTx.occurredAt)
        assertEquals(setOf("sms-july-out", "sms-july-in"), ftRepo.listRawSmsIds(julyTx.id).toSet())
        assertEquals(setOf("sms-aug-out", "sms-aug-in"), ftRepo.listRawSmsIds(augTx.id).toSet())
    }

    @Test
    fun twoThousand_eightMinutesApart_becomeTwoMovementsNotFourLegs() = runBlocking {
        ownBothAccounts()
        persistPair("a", "2026-09-02 10:00", "2026-09-02T07:00:00Z")
        persistPair("b", "2026-09-02 10:08", "2026-09-02T07:08:00Z")

        reconciliation.reconcileStoredEvents()

        val stored = ftRepo.listAll()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.type == FinancialTransactionType.SELF_TRANSFER })
        assertEquals(Money.of("4000.00", Currency.SAR), selfTransferOutOf3001(stored))
    }

    @Test
    fun twoThousand_differentDays_remainTwoAndSumToFourThousand() = runBlocking {
        ownBothAccounts()
        persistPair("day2", "2026-09-02 10:00", "2026-09-02T07:00:00Z")
        persistPair("day9", "2026-09-09 10:00", "2026-09-09T07:00:00Z")

        reconciliation.reconcileStoredEvents()
        reconciliation.reconcileStoredEvents()

        val stored = ftRepo.listAll()
        assertEquals(2, stored.size)
        assertEquals(Money.of("4000.00", Currency.SAR), selfTransferOutOf3001(stored))
    }

    @Test
    fun salaryCycleBoundary_pairsEachClockWithoutCrossingTheWindow() = runBlocking {
        ownBothAccounts()
        persistPair("before", "2026-08-26 23:58", "2026-08-26T20:58:00Z")
        persistPair("after", "2026-08-27 00:04", "2026-08-26T21:04:00Z")

        reconciliation.reconcileStoredEvents()

        val stored = ftRepo.listAll()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.type == FinancialTransactionType.SELF_TRANSFER })
        val boundary = Instant.parse("2026-08-26T21:00:00Z")
        assertEquals(1, stored.count { it.occurredAt.isBefore(boundary) })
        assertEquals(1, stored.count { !it.occurredAt.isBefore(boundary) })
    }

    @Test
    fun lateIncomingLeg_healsOutgoingWithoutDuplicatingMoney() = runBlocking {
        ownBothAccounts()
        persistParsed("sms-late-out", intraOut("2026-09-04 11:15"), Instant.parse("2026-09-04T08:15:00Z"))
        reconciliation.reconcileStoredEvents()
        assertEquals(1, ftRepo.listAll().size)

        persistParsed("sms-late-in", intraIn("2026-09-04 11:15"), Instant.parse("2026-09-04T08:15:05Z"))
        reconciliation.reconcileStoredEvents()

        val healed = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, healed.type)
        assertEquals(Money.of("2000.00", Currency.SAR), healed.amount)
        assertEquals(setOf("sms-late-in", "sms-late-out"), ftRepo.listRawSmsIds(healed.id).toSet())

        reconciliation.reconcileStoredEvents()
        val again = ftRepo.listAll().single()
        assertEquals(healed.id, again.id)
        assertEquals(Money.of("2000.00", Currency.SAR), again.amount)
    }

    @Test
    fun sameMinuteAmbiguousLegs_areNotPostedAsFourSelfTransfers() = runBlocking {
        ownBothAccounts()
        persistSameMinuteAmbiguousLegs()

        reconcileAndApplyReviews()

        assertTrue(
            "Irreducibly ambiguous same-minute legs must stay unmatched, not four self-transfers",
            ftRepo.listAll().isEmpty(),
        )
        assertEquals(4, parsedRepo.listAll().size)
        assertEquals(4, rawRepo.listIdsByReceivedAt().size)
        assertPendingMatchReviews(SAME_MINUTE_SMS_IDS)
    }

    @Test
    fun legacyFourPostedSameMinuteLegs_areRepairedToPendingMatchReviews() = runBlocking {
        ownBothAccounts()
        persistSameMinuteAmbiguousLegs()
        seedPostedSingleLegSelf("sms-out-a", "out-a")
        seedPostedSingleLegSelf("sms-out-b", "out-b")
        seedPostedSingleLegSelf("sms-in-a", "in-a")
        seedPostedSingleLegSelf("sms-in-b", "in-b")
        assertEquals(4, ftRepo.listAll().size)
        assertTrue(ftRepo.listAll().all { it.type == FinancialTransactionType.SELF_TRANSFER })

        val prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val coordinator = TransferIntegrityRepairCoordinator(
            prefs = prefs,
            appLogService = AppLogService(context),
            repairStoredTransfers = { repairAndApplyReviews() },
        )
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement())
        assertEquals(BackfillOutcome.COMPLETED, coordinator.runIfNeeded())
        assertEquals(BackfillOutcome.UP_TO_DATE, coordinator.runIfNeeded())

        assertTrue(
            "Upgrade must drop the four pre-M1 self-transfer legs instead of leaving double-counted money",
            ftRepo.listAll().isEmpty(),
        )
        assertEquals(4, parsedRepo.listAll().size)
        assertPendingMatchReviews(SAME_MINUTE_SMS_IDS)
    }

    @Test
    fun legacyFourPostedEightMinutePairs_healToTwoSelfTransfersWithoutReview() = runBlocking {
        ownBothAccounts()
        persistPair("a", "2026-09-02 10:00", "2026-09-02T07:00:00Z")
        persistPair("b", "2026-09-02 10:08", "2026-09-02T07:08:00Z")
        seedPostedSingleLegSelf("sms-a-out", "a-out")
        seedPostedSingleLegSelf("sms-a-in", "a-in")
        seedPostedSingleLegSelf("sms-b-out", "b-out")
        seedPostedSingleLegSelf("sms-b-in", "b-in")
        assertEquals(4, ftRepo.listAll().size)

        reconcileAndApplyReviews()
        reconcileAndApplyReviews()

        val stored = ftRepo.listAll()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.type == FinancialTransactionType.SELF_TRANSFER })
        assertEquals(Money.of("4000.00", Currency.SAR), selfTransferOutOf3001(stored))
        assertTrue(reviewRepo.listRequired().isEmpty())
        val first = stored.single { it.occurredAt == Instant.parse("2026-09-02T07:00:00Z") }
        val second = stored.single { it.occurredAt == Instant.parse("2026-09-02T07:08:00Z") }
        assertEquals(setOf("sms-a-in", "sms-a-out"), ftRepo.listRawSmsIds(first.id).toSet())
        assertEquals(setOf("sms-b-in", "sms-b-out"), ftRepo.listRawSmsIds(second.id).toSet())
    }

    @Test
    fun legacyExternalLegsOnly_repairHealsUniquePairAndKeepsUnrelatedExternal() = runBlocking {
        ownBothAccounts()
        persistPair("unique", "2026-09-02 10:00", "2026-09-02T07:00:00Z")
        seedPostedExternal("sms-unique-out", "unique-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT)
        seedPostedExternal("sms-unique-in", "unique-in", FinancialTransactionType.EXTERNAL_TRANSFER_IN)
        persistParsed(
            "sms-unrelated-external",
            """
            عملية حوالة مالية صادرة مقبولة
            خصمت من حساب: 3001
            الى: TEST_BENEFICIARY
            مبلغ العملية: 75.00 SAR
            المعرف البديل \الايبان : 0593
            [البنك العربي الوطني]
            في: 2026-09-02 18:00
            رقم المعاملة: TEST_REFERENCE_UNRELATED
            """.trimIndent(),
            Instant.parse("2026-09-02T15:00:00Z"),
        )
        seedPostedExternal(
            "sms-unrelated-external",
            "unrelated",
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
        )
        assertTrue(ftRepo.listAll().none { it.type == FinancialTransactionType.SELF_TRANSFER })

        val repaired = repairAndApplyReviews()
        assertEquals(0, repaired.failedCount)

        val stored = ftRepo.listAll()
        assertEquals(1, stored.count { it.type == FinancialTransactionType.SELF_TRANSFER })
        val unrelated = ftRepo.findByRawSmsId("sms-unrelated-external")
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, unrelated?.type)
        assertEquals(Money.of("75.00", Currency.SAR), unrelated?.amount)
        assertEquals(
            setOf("sms-unique-in", "sms-unique-out"),
            ftRepo.listRawSmsIds(stored.single { it.type == FinancialTransactionType.SELF_TRANSFER }.id).toSet(),
        )

        val again = repairAndApplyReviews()
        assertEquals(0, again.failedCount)
        assertEquals(1, ftRepo.listAll().count { it.type == FinancialTransactionType.SELF_TRANSFER })
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, ftRepo.findByRawSmsId("sms-unrelated-external")?.type)
    }

    @Test
    fun repairLegacyTransfers_onCleanLedger_postsNothing() = runBlocking {
        val report = reconciliation.repairLegacyTransfersDetailed()
        assertEquals(0, report.summary.failed)
        assertTrue(ftRepo.listAll().isEmpty())
        assertTrue(rawRepo.listIdsByReceivedAt().isEmpty())
    }

    @Test
    fun failedExclusiveUnlink_keepsRepairIncompleteUntilRetry() = runBlocking {
        ownBothAccounts()
        persistSameMinuteAmbiguousLegs()
        seedPostedSingleLegSelf("sms-out-a", "out-a")
        seedPostedSingleLegSelf("sms-out-b", "out-b")
        seedPostedSingleLegSelf("sms-in-a", "in-a")
        seedPostedSingleLegSelf("sms-in-b", "in-b")
        val rejecting = RejectingDeleteRepository(ftRepo, rejectRemaining = 1)
        val repairing = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = rejecting,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
            reviewRepository = reviewRepo,
            zoneId = zoneId,
        )

        val first = repairing.repairLegacyTransfersDetailed()
        assertTrue(first.summary.failed > 0)
        assertEquals(4, rawRepo.listIdsByReceivedAt().size)
        assertTrue(SAME_MINUTE_SMS_IDS.any { ftRepo.findByRawSmsId(it) != null })

        val second = repairing.repairLegacyTransfersDetailed()
        assertEquals(0, second.summary.failed)
        assertTrue(SAME_MINUTE_SMS_IDS.all { ftRepo.findByRawSmsId(it) == null })
        assertEquals(4, parsedRepo.listAll().size)
    }

    @Test
    fun thrownUnlink_preservesSmsAndPostedRows() = runBlocking {
        ownBothAccounts()
        persistSameMinuteAmbiguousLegs()
        seedPostedSingleLegSelf("sms-out-a", "out-a")
        seedPostedSingleLegSelf("sms-in-a", "in-a")
        seedPostedSingleLegSelf("sms-out-b", "out-b")
        seedPostedSingleLegSelf("sms-in-b", "in-b")
        val rejecting = RejectingDeleteRepository(ftRepo, throwOnDelete = true)
        val repairing = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = rejecting,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
            reviewRepository = reviewRepo,
            zoneId = zoneId,
        )

        val report = repairing.repairLegacyTransfersDetailed()
        assertTrue(report.summary.failed > 0)
        assertEquals(4, rawRepo.listIdsByReceivedAt().size)
        assertEquals(4, ftRepo.listAll().size)
        assertEquals(4, parsedRepo.listAll().size)
    }

    @Test
    fun legacyAmbiguousPostedSelfTransfer_userFinancialType_isNotUnlinked() = runBlocking {
        ownBothAccounts()
        persistSameMinuteAmbiguousLegs()
        seedPostedSingleLegSelf("sms-out-a", "out-a")
        seedPostedSingleLegSelf("sms-out-b", "out-b")
        seedPostedSingleLegSelf("sms-in-a", "in-a")
        seedPostedSingleLegSelf("sms-in-b", "in-b")
        val keptId = ftRepo.findByRawSmsId("sms-out-a")!!.id
        val now = Instant.parse("2026-09-02T12:00:00Z")
        reviewRepo.upsertRequired(
            rawSmsId = "sms-out-a",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("manual_resolution"),
            now = now,
        )
        reviewRepo.markResolved(
            id = ReviewIdFactory.fromRawSmsId("sms-out-a"),
            resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE,
            resolvedAt = now,
            resolvedTransactionId = keptId,
        )

        repairAndApplyReviews()

        val kept = ftRepo.findByRawSmsId("sms-out-a")
        assertEquals(keptId, kept?.id)
        assertEquals(FinancialTransactionType.SELF_TRANSFER, kept?.type)
        assertEquals(setOf("sms-out-b", "sms-in-a", "sms-in-b"), reviewRepo.listRequired().map { it.rawSmsId }.toSet())
        assertTrue(reviewRepo.listRequired().all { it.kind == ReviewKind.PENDING_MATCH })
        assertEquals(ReviewStatus.RESOLVED, reviewRepo.findByRawSmsId("sms-out-a")?.status)
        assertEquals(ReviewResolutionKind.USER_FINANCIAL_TYPE, reviewRepo.findByRawSmsId("sms-out-a")?.resolutionKind)
    }

    @Test
    fun sameAmountExternalOut_staysVisibleBesideOneSelfTransfer() = runBlocking {
        ownBothAccounts()
        persistPair("self", "2026-09-02 10:00", "2026-09-02T07:00:00Z")
        persistParsed(
            "sms-external",
            """
            عملية حوالة مالية صادرة مقبولة
            خصمت من حساب: 3001
            الى: TEST_BENEFICIARY
            مبلغ العملية: 2,000.00 SAR
            المعرف البديل \الايبان : 0593
            [البنك العربي الوطني]
            في: 2026-09-02 15:00
            رقم المعاملة: TEST_REFERENCE_EXT
            """.trimIndent(),
            Instant.parse("2026-09-02T12:00:00Z"),
        )

        reconciliation.reconcileStoredEvents()

        val stored = ftRepo.listAll()
        assertEquals(1, stored.count { it.type == FinancialTransactionType.SELF_TRANSFER })
        assertEquals(1, stored.count { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT })
        val displayed = SelfTransferDeduplicator.filter(stored, parsedRepo.listAll())
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = displayed,
            parsedRecords = parsedRepo.listAll(),
            ownedAccountContainerIds = setOf(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        assertEquals(Money.of("2000.00", Currency.SAR), summary.outflow.selfTransfersOut)
        assertEquals(Money.of("2000.00", Currency.SAR), summary.outflow.externalTransfersOut)
    }

    private suspend fun ownBothAccounts() {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
    }

    private suspend fun persistPair(suffix: String, local: String, receivedAt: String) {
        val received = Instant.parse(receivedAt)
        persistParsed("sms-$suffix-out", intraOut(local), received)
        persistParsed("sms-$suffix-in", intraIn(local), received.plusSeconds(5))
    }

    private suspend fun persistSameMinuteAmbiguousLegs() {
        persistParsed("sms-out-a", intraOut("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:00Z"))
        persistParsed("sms-out-b", intraOut("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:01Z"))
        persistParsed("sms-in-a", intraIn("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:02Z"))
        persistParsed("sms-in-b", intraIn("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:03Z"))
    }

    private suspend fun reconcileAndApplyReviews() {
        reviewQueueUpdater.applyReport(reconciliation.reconcileStoredEventsDetailed())
    }

    private suspend fun repairAndApplyReviews(): TransferIntegrityRepairResult {
        val report = reconciliation.repairLegacyTransfersDetailed()
        reviewQueueUpdater.applyReport(report)
        return TransferIntegrityRepairResult(failedCount = report.summary.failed)
    }

    private suspend fun assertPendingMatchReviews(rawSmsIds: Set<String>) {
        val required = reviewRepo.listRequired()
        assertEquals(rawSmsIds, required.map { it.rawSmsId }.toSet())
        assertTrue(required.all { it.kind == ReviewKind.PENDING_MATCH })
        assertTrue(required.all { it.status == ReviewStatus.REQUIRED })
        assertTrue(required.all { it.reasons == listOf("transfer_pending_match") })
    }

    private suspend fun seedPostedExternal(
        rawSmsId: String,
        idSuffix: String,
        type: FinancialTransactionType,
    ) {
        val parsed = parsedRepo.listAll().first { it.event.rawSmsId == rawSmsId }
        val receivedAt = rawRepo.getById(rawSmsId)!!.receivedAt
        val occurredAt = TransactionTiming.effectiveOccurredAt(
            event = parsed.event,
            occurredAtLocal = parsed.details.occurredAtLocal,
            receivedAt = receivedAt,
            zoneId = zoneId,
        )
        ftRepo.save(
            FinancialTransaction(
                id = "tx-legacy-ext-$idSuffix",
                type = type,
                amount = parsed.event.amount!!,
                occurredAt = occurredAt,
                sourceContainerId = parsed.event.sourceAccountRef?.let(FinancialContainerIdFactory::accountId),
                destinationContainerId = parsed.event.destinationAccountRef?.let(FinancialContainerIdFactory::accountId),
                merchant = null,
                counterparty = parsed.event.counterparty,
                categoryId = null,
                linkedParsedEventIds = listOf(parsed.event.id),
                occurredAtZone = "Asia/Riyadh",
            ),
            listOf(rawSmsId),
        )
    }

    private class RejectingDeleteRepository(
        private val delegate: RoomFinancialTransactionRepository,
        private var rejectRemaining: Int = 0,
        private val throwOnDelete: Boolean = false,
    ) : com.baraa.masroof.domain.repository.FinancialTransactionRepository by delegate {
        override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean {
            if (throwOnDelete) error("unlink failed")
            if (rejectRemaining > 0) {
                rejectRemaining--
                return false
            }
            return delegate.deleteIfExclusiveRawSmsLink(rawSmsId)
        }
    }

    private suspend fun seedPostedSingleLegSelf(rawSmsId: String, idSuffix: String) {
        val parsed = parsedRepo.listAll().first { it.event.rawSmsId == rawSmsId }
        val receivedAt = rawRepo.getById(rawSmsId)!!.receivedAt
        val occurredAt = TransactionTiming.effectiveOccurredAt(
            event = parsed.event,
            occurredAtLocal = parsed.details.occurredAtLocal,
            receivedAt = receivedAt,
            zoneId = zoneId,
        )
        ftRepo.save(
            FinancialTransaction(
                id = "tx-legacy-$idSuffix",
                type = FinancialTransactionType.SELF_TRANSFER,
                amount = parsed.event.amount!!,
                occurredAt = occurredAt,
                sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
                destinationContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002"),
                merchant = null,
                counterparty = parsed.event.counterparty,
                categoryId = null,
                linkedParsedEventIds = listOf(parsed.event.id),
                occurredAtZone = "Asia/Riyadh",
            ),
            listOf(rawSmsId),
        )
    }

    private fun intraOut(local: String): String =
        """
        حوالة صادرة الى حسابك الجاري
        من: 3001
        مبلغ: SAR 2,000.00
        إلى: 3002
        في: $local
        """.trimIndent()

    private fun intraIn(local: String): String =
        """
        حوالة واردة داخلية
        مبلغ: SAR 2,000.00
        إلى: 3002
        اسم المرسل: TEST_PERSON
        رقم حساب المرسل: 3001
        البنك المرسل: بنك الجزيرة
        في: $local
        """.trimIndent()

    private suspend fun selfTransferOutOf3001(
        stored: List<com.baraa.masroof.domain.model.FinancialTransaction>,
    ): Money {
        val displayed = SelfTransferDeduplicator.filter(stored, parsedRepo.listAll())
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = displayed,
            parsedRecords = parsedRepo.listAll(),
            ownedAccountContainerIds = setOf(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        return summary.outflow.selfTransfersOut
    }

    private suspend fun seedStaleExternalPair(
        outSmsId: String,
        inSmsId: String,
        idSuffix: String = "",
    ) {
        val outParsed = parsedRepo.listAll().first { it.event.rawSmsId == outSmsId }
        val inParsed = parsedRepo.listAll().first { it.event.rawSmsId == inSmsId }
        val amount = Money.of("5500.00", Currency.SAR)
        val sourceId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")!!
        val destId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002")!!

        ftRepo.save(
            com.baraa.masroof.domain.model.FinancialTransaction(
                id = "tx-stale-out$idSuffix",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                amount = amount,
                occurredAt = Instant.parse("2026-08-27T04:36:00Z"),
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                merchant = null,
                counterparty = "براء بخش",
                categoryId = null,
                linkedParsedEventIds = listOf(outParsed.event.id),
            ),
            listOf(outSmsId),
        )
        ftRepo.save(
            com.baraa.masroof.domain.model.FinancialTransaction(
                id = "tx-stale-in$idSuffix",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                amount = amount,
                occurredAt = Instant.parse("2026-08-27T04:36:00Z"),
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                merchant = null,
                counterparty = "براء بخش",
                categoryId = null,
                linkedParsedEventIds = listOf(inParsed.event.id),
            ),
            listOf(inSmsId),
        )
    }

    private fun parseSms(
        rawSmsId: String,
        body: String,
        receivedAt: Instant = Instant.parse("2026-08-27T04:36:00Z"),
    ): ParseResult =
        pipeline.parse(
            SmsParseInput(
                rawSmsId = rawSmsId,
                sender = "AlJazira",
                body = body,
                receivedAt = receivedAt,
            ),
        )

    private suspend fun persistParsed(
        rawSmsId: String,
        body: String,
        receivedAt: Instant = Instant.parse("2026-08-27T04:36:00Z"),
    ) {
        val result = parseSms(rawSmsId, body, receivedAt)
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals(ParseStatus.SUCCESS, success.event.parseStatus)
        rawRepo.insertIfAbsent(
            RawSms(
                id = rawSmsId,
                sender = "AlJazira",
                body = body,
                receivedAt = receivedAt,
                deviceMessageId = rawSmsId,
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        parsedRepo.save(success.event, success.details)
    }

    private fun transferCandidate(
        result: ParseResult.Success,
        occurredAtLocal: LocalDateTime,
    ) = com.baraa.masroof.domain.matching.TransferMatchCandidate(
        event = result.event,
        transactionReference = result.details.transactionReference,
        occurredAtLocal = occurredAtLocal,
        receivedAt = Instant.parse("2026-08-27T04:36:00Z"),
        sourceOwnership = com.baraa.masroof.domain.model.OwnershipStatus.OWNED,
        destinationOwnership = com.baraa.masroof.domain.model.OwnershipStatus.OWNED,
    )

    private companion object {
        val SAME_MINUTE_SMS_IDS = setOf("sms-out-a", "sms-out-b", "sms-in-a", "sms-in-b")
    }
}
