package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.hash.SmsBodyHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Scoped reconciliation must match full reconciliation on the affected evidence
 * and must not scan every parsed event or every unlinked transfer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScopedReconciliationTest {
    private val zoneId = ZoneId.of("Asia/Riyadh")
    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedDelegate: RoomParsedEventRepository
    private lateinit var ftDelegate: RoomFinancialTransactionRepository
    private lateinit var parsed: CountingParsedEventRepository
    private lateinit var transactions: CountingFinancialTransactionRepository
    private lateinit var confirmation: OwnershipConfirmationService
    private lateinit var loans: RoomLoanRegistryRepository
    private lateinit var reconciliation: TransactionReconciliationService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedDelegate = RoomParsedEventRepository(db.parsedEventDao())
        ftDelegate = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        parsed = CountingParsedEventRepository(parsedDelegate)
        transactions = CountingFinancialTransactionRepository(ftDelegate)
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        loans = RoomLoanRegistryRepository.from(db)
        confirmation = OwnershipConfirmationService(accounts, cards, loans)
        reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsed,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = transactions,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = confirmation,
            zoneId = zoneId,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun boundedQueries_keepOnlyUnlinkedTransfersAndTypedRowsInsideTheRange() = runBlocking {
        val inside = Instant.parse("2026-08-04T09:00:00Z")
        persistTransfer("sms-in-range", "pe-in-range", inside, MessageFamily.TRANSFER_OUT)
        persistTransfer("sms-out-range", "pe-out-range", inside.plus(Duration.ofDays(30)), MessageFamily.TRANSFER_IN)
        persistTransfer("sms-linked", "pe-linked", inside.plusSeconds(30), MessageFamily.TRANSFER_IN)
        persistPurchase("sms-purchase", "pe-purchase", inside.plusSeconds(10))
        ftDelegate.save(
            transaction(
                id = "tx-linked",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                occurredAt = inside.plusSeconds(30),
                eventId = "pe-linked",
            ),
            listOf("sms-linked"),
        )
        ftDelegate.save(
            transaction(
                id = "tx-old-expense",
                type = FinancialTransactionType.EXPENSE,
                occurredAt = inside,
                eventId = "pe-purchase",
            ),
            listOf("sms-purchase"),
        )
        ftDelegate.save(
            transaction(
                id = "tx-later-external",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                occurredAt = inside.plus(Duration.ofDays(30)),
                eventId = "pe-out-range",
            ),
            listOf("sms-out-range"),
        )

        val start = inside.minusSeconds(1)
        val end = inside.plus(Duration.ofMinutes(5))
        assertEquals(
            listOf("pe-in-range"),
            parsedDelegate.listUnlinkedTransfersReceivedBetween(start, end).map { it.event.id },
        )
        assertEquals(
            listOf("tx-linked"),
            ftDelegate.listByTypesOccurredBetween(
                types = listOf(
                    FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                    FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                ),
                startInclusive = start,
                endExclusive = end,
            ).map { it.id },
        )
        assertEquals(
            emptyList<FinancialTransaction>(),
            ftDelegate.listByTypesOccurredBetween(
                types = emptyList(),
                startInclusive = start,
                endExclusive = end,
            ),
        )

        val localInside = LocalDateTime.parse("2026-08-04T12:05:00")
        val localOutside = localInside.plusHours(3)
        persistTransfer(
            smsId = "sms-local-in",
            eventId = "pe-local-in",
            at = inside.plus(Duration.ofDays(4)),
            family = MessageFamily.TRANSFER_OUT,
            details = ParsedEventDetails(occurredAtLocal = localInside),
        )
        persistTransfer(
            smsId = "sms-local-out",
            eventId = "pe-local-out",
            at = inside,
            family = MessageFamily.TRANSFER_IN,
            details = ParsedEventDetails(occurredAtLocal = localOutside),
        )
        persistTransfer(
            smsId = "sms-local-linked",
            eventId = "pe-local-linked",
            at = inside.plus(Duration.ofDays(4)),
            family = MessageFamily.TRANSFER_IN,
            details = ParsedEventDetails(occurredAtLocal = localInside.plusMinutes(1)),
        )
        ftDelegate.save(
            transaction(
                id = "tx-local-linked",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                occurredAt = inside.plus(Duration.ofDays(4)),
                eventId = "pe-local-linked",
            ),
            listOf("sms-local-linked"),
        )
        assertEquals(
            listOf("pe-local-in"),
            parsedDelegate.listUnlinkedTransfersOccurredLocalBetween(
                startInclusive = localInside.minusMinutes(30),
                endExclusive = localInside.plusMinutes(30),
            ).map { it.event.id },
        )
    }

    @Test
    fun oneSmsNonTransfer_doesNotLoadAllParsedEvents() = runBlocking {
        persistPurchase("sms-purchase", "pe-purchase", Instant.parse("2026-08-04T09:00:00Z"))
        persistTransfer(
            smsId = "sms-old-transfer",
            eventId = "pe-old-transfer",
            at = Instant.parse("2020-01-01T00:00:00Z"),
            family = MessageFamily.TRANSFER_OUT,
        )
        parsed.reset()
        transactions.reset()

        reconciliation.reconcileAffectedRawSmsIds(listOf("sms-purchase"))

        assertEquals(FinancialTransactionType.EXPENSE, ftDelegate.findByRawSmsId("sms-purchase")?.type)
        assertEquals(null, ftDelegate.findByRawSmsId("sms-old-transfer"))
        assertNoGlobalScan()
        assertEquals(0, parsed.boundedUnlinkedCalls)
        assertEquals(0, transactions.boundedTypeCalls)
        assertTrue(parsed.listByRawSmsIdsCalls > 0)
    }

    @Test
    fun correctedNonTransfer_loadsEffectiveRowsById() = runBlocking {
        persistPurchase(
            smsId = "sms-corrected",
            eventId = "pe-corrected",
            at = Instant.parse("2026-08-08T09:00:00Z"),
            amount = null,
            status = ParseStatus.REVIEW_REQUIRED,
        )
        val corrections = RoomUserCorrectionRepository(db.userCorrectionDao())
        corrections.save(
            UserCorrection(
                id = "corr-amount",
                targetRawSmsId = "sms-corrected",
                correctedType = null,
                correctedAmount = Money.of("42.00", Currency.SAR),
                correctedMerchant = null,
                correctedCounterparty = null,
                createdAt = Instant.parse("2026-08-08T12:00:00Z"),
            ),
        )
        val scoped = TransactionReconciliationService(
            parsedEventRepository = parsed,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = transactions,
            ownershipResolver = reconciliationOwnership(),
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = EffectiveParsedEventProvider(parsed, corrections),
            zoneId = zoneId,
        )
        parsed.reset()
        transactions.reset()

        scoped.reconcileAffectedRawSmsIds(listOf("sms-corrected"))

        val posted = ftDelegate.findByRawSmsId("sms-corrected")
        assertEquals(FinancialTransactionType.EXPENSE, posted?.type)
        assertEquals(Money.of("42.00", Currency.SAR), posted?.amount)
        assertNoGlobalScan()
        assertEquals(0, parsed.boundedUnlinkedCalls)
    }

    @Test
    fun transfer_examinesTheBoundedWindowAndLinkedStaleEvidence() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        val occurredAt = LocalDateTime.parse("2026-08-07T12:00:00")
        val outAt = Instant.parse("2026-08-07T09:00:00Z")
        persistTransfer(
            smsId = "sms-stale-out",
            eventId = "pe-stale-out",
            at = outAt,
            family = MessageFamily.TRANSFER_OUT,
            source = "3001",
            destination = "3003",
            amount = "4445.67",
            details = ParsedEventDetails(occurredAtLocal = occurredAt),
        )
        reconciliation.reconcileStoredEvents()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, ftDelegate.listAll().single().type)

        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        val inAt = outAt.plus(TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(3))
        persistTransfer(
            smsId = "sms-stale-in",
            eventId = "pe-stale-in",
            at = inAt,
            family = MessageFamily.TRANSFER_IN,
            source = "3001",
            destination = "3003",
            amount = "4445.67",
            details = ParsedEventDetails(occurredAtLocal = occurredAt.plusMinutes(2)),
        )
        persistTransfer(
            smsId = "sms-ancient",
            eventId = "pe-ancient",
            at = Instant.parse("2019-01-01T00:00:00Z"),
            family = MessageFamily.TRANSFER_OUT,
            amount = "10.00",
        )
        ftDelegate.save(
            transaction(
                id = "tx-ancient",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                occurredAt = Instant.parse("2019-01-01T00:00:00Z"),
                eventId = "pe-ancient",
            ),
            listOf("sms-ancient"),
        )
        parsed.reset()
        transactions.reset()

        reconciliation.reconcileAffectedRawSmsIds(listOf("sms-stale-in"))

        val healed = ftDelegate.listAll().single { it.id != "tx-ancient" }
        assertEquals(FinancialTransactionType.SELF_TRANSFER, healed.type)
        assertEquals(setOf("sms-stale-in", "sms-stale-out"), ftDelegate.listRawSmsIds(healed.id).toSet())
        assertEquals("tx-ancient", ftDelegate.findByRawSmsId("sms-ancient")?.id)
        assertNoGlobalScan()
        assertTrue(parsed.boundedUnlinkedCalls > 0)
        assertTrue(transactions.boundedTypeCalls > 0)
        assertTrue(parsed.boundedWindows.all { (start, _) -> start.isAfter(Instant.parse("2026-08-01T00:00:00Z")) })
        assertTrue(
            transactions.boundedWindows.all { (start, end) ->
                start.isAfter(Instant.parse("2026-08-01T00:00:00Z")) && end.isAfter(start)
            },
        )
    }

    @Test
    fun competingLegInsideTwoMatcherWindows_staysUnpaired() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        val start = Instant.parse("2026-08-06T04:36:00Z")
        persistTransfer(
            smsId = "sms-a",
            eventId = "pe-a",
            at = start,
            family = MessageFamily.TRANSFER_OUT,
            source = "3001",
            destination = "3003",
        )
        persistTransfer(
            smsId = "sms-b",
            eventId = "pe-b",
            at = start.plus(TransactionMatcher.TRANSFER_MATCH_WINDOW).minus(Duration.ofMinutes(1)),
            family = MessageFamily.TRANSFER_IN,
            source = "3001",
            destination = "3003",
        )
        persistTransfer(
            smsId = "sms-d",
            eventId = "pe-d",
            at = start.plus(TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(2)).minus(Duration.ofMinutes(2)),
            family = MessageFamily.TRANSFER_OUT,
            source = "3001",
            destination = "3003",
        )

        reconciliation.reconcileAffectedRawSmsIds(listOf("sms-a"))

        val posted = ftDelegate.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, posted.type)
        assertEquals(listOf("pe-a"), posted.linkedParsedEventIds)
        assertEquals(setOf("sms-a"), ftDelegate.listRawSmsIds(posted.id).toSet())
        assertEquals(null, ftDelegate.findByRawSmsId("sms-b"))
        assertEquals(null, ftDelegate.findByRawSmsId("sms-d"))
        assertNoGlobalScan()
    }

    @Test
    fun localCompetitorOutsideReceiptWindow_matchesFullAndRefusesUniquePair() = runBlocking {
        val aReceived = Instant.parse("2026-08-06T04:36:00Z")
        val bReceived = aReceived.plus(Duration.ofMinutes(4))
        val cReceived = aReceived.plus(Duration.ofDays(2))
        val aLocal = LocalDateTime.parse("2026-08-06T07:36:00")
        val bLocal = aLocal.plusMinutes(2)
        val cLocal = aLocal.plusMinutes(8)
        val receiptSpan = TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(2)
        assertTrue(Duration.between(aReceived, cReceived).abs() > receiptSpan)
        assertTrue(Duration.between(bReceived, cReceived).abs() > receiptSpan)
        assertTrue(abs(Duration.between(aLocal, cLocal).seconds) <= TransactionMatcher.TRANSFER_MATCH_WINDOW.seconds)
        assertTrue(abs(Duration.between(bLocal, cLocal).seconds) <= TransactionMatcher.TRANSFER_MATCH_WINDOW.seconds)

        val full = ambiguousLocalOutcome(
            scoped = false,
            aReceived = aReceived,
            bReceived = bReceived,
            cReceived = cReceived,
            aLocal = aLocal,
            bLocal = bLocal,
            cLocal = cLocal,
        )
        val scoped = ambiguousLocalOutcome(
            scoped = true,
            aReceived = aReceived,
            bReceived = bReceived,
            cReceived = cReceived,
            aLocal = aLocal,
            bLocal = bLocal,
            cLocal = cLocal,
        )

        assertEquals(full.affected, scoped.affected)
        assertEquals(setOf("sms-local-a"), full.affected.rawSmsIds)
        assertEquals(listOf("pe-local-a"), full.affected.linkedParsedEventIds)
        assertEquals(FinancialTransactionType.SELF_TRANSFER, full.affected.type)
        assertEquals(0, full.multiEvidenceTransfers)
        assertEquals(0, scoped.multiEvidenceTransfers)
        assertEquals(0, scoped.listAllCalls)
        assertEquals(0, scoped.globalUnlinkedCalls)
        assertEquals(0, scoped.listByTypesCalls)
        assertTrue(scoped.boundedLocalCalls > 0)
    }

    @Test
    fun inWindowUnrelatedTransfer_isNotPostedOnItsOwn() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "4101"))
        val at = Instant.parse("2026-08-05T09:00:00Z")
        persistTransfer(
            smsId = "sms-affected",
            eventId = "pe-affected",
            at = at,
            family = MessageFamily.TRANSFER_OUT,
            source = "4101",
            destination = "4102",
            amount = "80.00",
            network = BankNetworkType.INTER_BANK,
        )
        persistTransfer(
            smsId = "sms-neighbor",
            eventId = "pe-neighbor",
            at = at.plus(Duration.ofMinutes(5)),
            family = MessageFamily.TRANSFER_OUT,
            source = "4101",
            destination = "9999",
            amount = "11.00",
            network = BankNetworkType.INTER_BANK,
        )

        reconciliation.reconcileAffectedRawSmsIds(listOf("sms-affected"))

        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, ftDelegate.findByRawSmsId("sms-affected")?.type)
        assertEquals(null, ftDelegate.findByRawSmsId("sms-neighbor"))
    }

    @Test
    fun fullReconcile_remainsAvailableForMaintenance() = runBlocking {
        persistPurchase("sms-purchase", "pe-purchase", Instant.parse("2026-08-04T09:00:00Z"))
        parsed.reset()
        transactions.reset()

        reconciliation.reconcileStoredEventsDetailed()

        assertTrue(parsed.listAllCalls > 0)
        assertEquals(FinancialTransactionType.EXPENSE, ftDelegate.findByRawSmsId("sms-purchase")?.type)
    }

    @Test
    fun staleFee_upgradesWithoutListingEveryTransaction() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "sms-loan")
        persistPurchase("sms-other", "pe-other", Instant.parse("2024-01-01T00:00:00Z"))
        reconciliation.reconcileStoredEvents()
        persistTransfer(
            smsId = "sms-loan",
            eventId = "evt-loan",
            at = Instant.parse("2026-08-27T01:10:00Z"),
            family = MessageFamily.FINANCING_INSTALLMENT,
            source = "3001",
            amount = "3036.11",
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
            counterparty = "تمويل شخصي",
        )
        ftDelegate.save(
            transaction(
                id = TransactionIdFactory.fromRawSmsIds(listOf("sms-loan")),
                type = FinancialTransactionType.FEE,
                occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                eventId = "evt-loan",
                sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
                counterparty = "تمويل شخصي",
            ),
            listOf("sms-loan"),
        )
        parsed.reset()
        transactions.reset()

        reconciliation.reconcileAffectedRawSmsIds(listOf("sms-loan"))

        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, ftDelegate.findByRawSmsId("sms-loan")?.type)
        assertNoGlobalScan()
        assertEquals(0, transactions.boundedTypeCalls)
        assertEquals(FinancialTransactionType.EXPENSE, ftDelegate.findByRawSmsId("sms-other")?.type)
    }

    private suspend fun ambiguousLocalOutcome(
        scoped: Boolean,
        aReceived: Instant,
        bReceived: Instant,
        cReceived: Instant,
        aLocal: LocalDateTime,
        bLocal: LocalDateTime,
        cLocal: LocalDateTime,
    ): AmbiguousLocalOutcome {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val raw = RoomRawSmsRepository(database.rawSmsDao())
            val parsedEvents = RoomParsedEventRepository(database.parsedEventDao())
            val financial = RoomFinancialTransactionRepository(
                database.financialTransactionDao(),
                database.parsedEventDao(),
            )
            val countedParsed = CountingParsedEventRepository(parsedEvents)
            val countedFinancial = CountingFinancialTransactionRepository(financial)
            val accounts = RoomAccountRegistryRepository.from(database)
            val cards = RoomCardRegistryRepository.from(database)
            val loanRegistry = RoomLoanRegistryRepository.from(database)
            val ownership = OwnershipConfirmationService(accounts, cards, loanRegistry)
            ownership.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
            ownership.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
            val service = TransactionReconciliationService(
                parsedEventRepository = countedParsed,
                rawSmsRepository = raw,
                financialTransactionRepository = countedFinancial,
                ownershipResolver = OwnershipResolver(accounts, cards, loanRegistry),
                ownershipConfirmationService = ownership,
                zoneId = zoneId,
            )
            persistTransfer(
                smsId = "sms-local-a",
                eventId = "pe-local-a",
                at = aReceived,
                family = MessageFamily.TRANSFER_OUT,
                source = "3001",
                destination = "3003",
                details = ParsedEventDetails(occurredAtLocal = aLocal),
                raw = raw,
                parsedEvents = parsedEvents,
            )
            persistTransfer(
                smsId = "sms-local-b",
                eventId = "pe-local-b",
                at = bReceived,
                family = MessageFamily.TRANSFER_IN,
                source = "3001",
                destination = "3003",
                details = ParsedEventDetails(occurredAtLocal = bLocal),
                raw = raw,
                parsedEvents = parsedEvents,
            )
            persistTransfer(
                smsId = "sms-local-c",
                eventId = "pe-local-c",
                at = cReceived,
                family = MessageFamily.TRANSFER_OUT,
                source = "3001",
                destination = "3003",
                details = ParsedEventDetails(occurredAtLocal = cLocal),
                raw = raw,
                parsedEvents = parsedEvents,
            )
            countedParsed.reset()
            countedFinancial.reset()
            if (scoped) {
                service.reconcileAffectedRawSmsIds(listOf("sms-local-a"))
            } else {
                service.reconcileStoredEventsDetailed()
            }
            val affected = financial.findByRawSmsId("sms-local-a")
            check(affected != null)
            val multiEvidence = financial.listAll().count { financial.listRawSmsIds(it.id).size > 1 }
            return AmbiguousLocalOutcome(
                affected = AffectedPosting(
                    type = affected.type,
                    amount = affected.amount,
                    rawSmsIds = financial.listRawSmsIds(affected.id).toSet(),
                    linkedParsedEventIds = affected.linkedParsedEventIds,
                ),
                multiEvidenceTransfers = multiEvidence,
                listAllCalls = countedParsed.listAllCalls,
                globalUnlinkedCalls = countedParsed.globalUnlinkedCalls,
                listByTypesCalls = countedFinancial.listByTypesCalls,
                boundedLocalCalls = countedParsed.boundedLocalCalls,
            )
        } finally {
            database.close()
        }
    }

    private fun assertNoGlobalScan() {
        assertEquals(0, parsed.listAllCalls)
        assertEquals(0, parsed.globalUnlinkedCalls)
        assertEquals(0, transactions.listAllCalls)
        assertEquals(0, transactions.listByTypesCalls)
    }

    private fun reconciliationOwnership(): OwnershipResolver {
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        return OwnershipResolver(accounts, cards, loans)
    }

    private suspend fun persistPurchase(
        smsId: String,
        eventId: String,
        at: Instant,
        amount: String? = "51.99",
        status: ParseStatus = ParseStatus.SUCCESS,
    ) {
        persist(
            smsId = smsId,
            at = at,
            event = event(
                id = eventId,
                rawSmsId = smsId,
                family = MessageFamily.PURCHASE,
                amount = amount?.let { Money.of(it, Currency.SAR) },
                status = status,
                merchant = "Keeta",
            ),
        )
    }

    private suspend fun persistTransfer(
        smsId: String,
        eventId: String,
        at: Instant,
        family: MessageFamily,
        source: String? = "3001",
        destination: String? = "3003",
        amount: String = "80.00",
        network: BankNetworkType = BankNetworkType.INTRA_BANK,
        details: ParsedEventDetails = ParsedEventDetails(),
        counterparty: String? = null,
        raw: RoomRawSmsRepository = rawRepo,
        parsedEvents: RoomParsedEventRepository = parsedDelegate,
    ) {
        persist(
            smsId = smsId,
            at = at,
            details = details,
            raw = raw,
            parsedEvents = parsedEvents,
            event = event(
                id = eventId,
                rawSmsId = smsId,
                family = family,
                amount = Money.of(amount, Currency.SAR),
                source = source?.let { AccountReference(Bank.BANK_ALJAZIRA, it) },
                destination = destination?.let { AccountReference(Bank.BANK_ALJAZIRA, it) },
                network = network,
                counterparty = counterparty,
            ),
        )
    }

    private suspend fun persist(
        smsId: String,
        event: ParsedEvent,
        at: Instant,
        details: ParsedEventDetails = ParsedEventDetails(),
        raw: RoomRawSmsRepository = rawRepo,
        parsedEvents: RoomParsedEventRepository = parsedDelegate,
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

    private data class AffectedPosting(
        val type: FinancialTransactionType,
        val amount: Money,
        val rawSmsIds: Set<String>,
        val linkedParsedEventIds: List<String>,
    )

    private data class AmbiguousLocalOutcome(
        val affected: AffectedPosting,
        val multiEvidenceTransfers: Int,
        val listAllCalls: Int,
        val globalUnlinkedCalls: Int,
        val listByTypesCalls: Int,
        val boundedLocalCalls: Int,
    )

    private fun event(
        id: String,
        rawSmsId: String,
        family: MessageFamily,
        amount: Money?,
        source: AccountReference? = null,
        destination: AccountReference? = null,
        network: BankNetworkType? = null,
        merchant: String? = null,
        counterparty: String? = null,
        status: ParseStatus = ParseStatus.SUCCESS,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = family,
        direction = if (family == MessageFamily.TRANSFER_IN) MoneyDirection.INCOMING else MoneyDirection.OUTGOING,
        amount = amount,
        purchaseChannel = null,
        sourceAccountRef = source,
        destinationAccountRef = destination,
        cardRef = null,
        merchant = merchant,
        counterparty = counterparty,
        occurredAt = null,
        bankNetworkType = network,
        confidence = Confidence(1.0),
        parseStatus = status,
    )

    private fun transaction(
        id: String,
        type: FinancialTransactionType,
        occurredAt: Instant,
        eventId: String,
        sourceContainerId: String? = null,
        counterparty: String? = null,
    ) = FinancialTransaction(
        id = id,
        type = type,
        amount = Money.of("10.00", Currency.SAR),
        occurredAt = occurredAt,
        sourceContainerId = sourceContainerId,
        destinationContainerId = null,
        merchant = null,
        counterparty = counterparty,
        categoryId = null,
        linkedParsedEventIds = listOf(eventId),
        occurredAtZone = zoneId.id,
    )

    private class CountingParsedEventRepository(
        private val delegate: ParsedEventRepository,
    ) : ParsedEventRepository by delegate {
        var listAllCalls: Int = 0
        var globalUnlinkedCalls: Int = 0
        var boundedUnlinkedCalls: Int = 0
        var boundedLocalCalls: Int = 0
        var listByRawSmsIdsCalls: Int = 0
        val boundedWindows: MutableList<Pair<Instant, Instant>> = mutableListOf()

        fun reset() {
            listAllCalls = 0
            globalUnlinkedCalls = 0
            boundedUnlinkedCalls = 0
            boundedLocalCalls = 0
            listByRawSmsIdsCalls = 0
            boundedWindows.clear()
        }

        override suspend fun listAll(): List<ParsedEventRecord> {
            listAllCalls += 1
            return delegate.listAll()
        }

        override suspend fun listUnlinkedTransfers(): List<ParsedEventRecord> {
            globalUnlinkedCalls += 1
            return delegate.listUnlinkedTransfers()
        }

        override suspend fun listUnlinkedTransfersReceivedBetween(
            startInclusive: Instant,
            endExclusive: Instant,
        ): List<ParsedEventRecord> {
            boundedUnlinkedCalls += 1
            boundedWindows += startInclusive to endExclusive
            return delegate.listUnlinkedTransfersReceivedBetween(startInclusive, endExclusive)
        }

        override suspend fun listUnlinkedTransfersOccurredLocalBetween(
            startInclusive: LocalDateTime,
            endExclusive: LocalDateTime,
        ): List<ParsedEventRecord> {
            boundedLocalCalls += 1
            return delegate.listUnlinkedTransfersOccurredLocalBetween(startInclusive, endExclusive)
        }

        override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> {
            listByRawSmsIdsCalls += 1
            return delegate.listByRawSmsIds(rawSmsIds)
        }
    }

    private class CountingFinancialTransactionRepository(
        private val delegate: FinancialTransactionRepository,
    ) : FinancialTransactionRepository by delegate {
        var listAllCalls: Int = 0
        var listByTypesCalls: Int = 0
        var boundedTypeCalls: Int = 0
        val boundedWindows: MutableList<Pair<Instant, Instant>> = mutableListOf()

        fun reset() {
            listAllCalls = 0
            listByTypesCalls = 0
            boundedTypeCalls = 0
            boundedWindows.clear()
        }

        override suspend fun listAll(): List<FinancialTransaction> {
            listAllCalls += 1
            return delegate.listAll()
        }

        override suspend fun listByTypes(
            types: Collection<FinancialTransactionType>,
        ): List<FinancialTransaction> {
            listByTypesCalls += 1
            return delegate.listByTypes(types)
        }

        override suspend fun listByTypesOccurredBetween(
            types: Collection<FinancialTransactionType>,
            startInclusive: Instant,
            endExclusive: Instant,
        ): List<FinancialTransaction> {
            boundedTypeCalls += 1
            boundedWindows += startInclusive to endExclusive
            return delegate.listByTypesOccurredBetween(types, startInclusive, endExclusive)
        }
    }
}
