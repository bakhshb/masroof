package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.AccountFlowScopeMode
import com.baraa.masroof.application.dashboard.CurrentAccountSummaryCalculator
import com.baraa.masroof.application.dashboard.SelfTransferDeduplicator
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.domain.model.RawSms
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

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var accounts: RoomAccountRegistryRepository
    private lateinit var cards: RoomCardRegistryRepository
    private lateinit var confirmation: OwnershipConfirmationService
    private lateinit var reconciliation: TransactionReconciliationService
    private val pipeline = AlJaziraParsingPipeline()
    private val zoneId = ZoneId.of("Asia/Riyadh")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        accounts = RoomAccountRegistryRepository.from(db)
        cards = RoomCardRegistryRepository.from(db)
        confirmation = OwnershipConfirmationService(accounts, cards, NoOpLoanRegistryRepository)
        reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
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
        persistParsed("sms-out-a", intraOut("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:00Z"))
        persistParsed("sms-out-b", intraOut("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:01Z"))
        persistParsed("sms-in-a", intraIn("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:02Z"))
        persistParsed("sms-in-b", intraIn("2026-09-02 10:00"), Instant.parse("2026-09-02T07:00:03Z"))

        reconciliation.reconcileStoredEvents()

        assertTrue(
            "Irreducibly ambiguous same-minute legs must stay unmatched, not four self-transfers",
            ftRepo.listAll().isEmpty(),
        )
        assertEquals(4, parsedRepo.listAll().size)
        assertEquals(4, rawRepo.listIdsByReceivedAt().size)
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
}
