package com.baraa.masroof.application.dashboard

import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.testsupport.NoOpCardRegistryRepository
import com.baraa.masroof.testsupport.NoOpCommitmentRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DashboardServiceTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val clock = Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), zone)

    @Test
    fun visaPurchaseAndCardPayment_regression() = runBlocking {
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val ftRepo = FakeFtRepo(
            listOf(
                tx("e1", FinancialTransactionType.EXPENSE, "100", start.plusSeconds(3600)),
                tx("p1", FinancialTransactionType.CREDIT_CARD_PAYMENT, "100", start.plusSeconds(7200)),
            ),
        )
        val service = dashboardService(ftRepo, FakeReviewRepo())
        val overview = service.loadOverview(period)
        assertEquals(Money.of("100.00", Currency.SAR), overview.summary.spendingGross)
        assertEquals(SignedMoneyAmount.of(Money.of("100.00", Currency.SAR)), overview.summary.spendingNet)
        assertEquals(Money.of("100.00", Currency.SAR), overview.summary.creditCardPayments)
        assertEquals(2, overview.summary.transactionCount)
    }

    @Test
    fun selfTransfer_regression() = runBlocking {
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val service = dashboardService(
            FakeFtRepo(listOf(tx("s1", FinancialTransactionType.SELF_TRANSFER, "500", start.plusSeconds(10)))),
            FakeReviewRepo(),
        )
        val overview = service.loadOverview(period)
        assertEquals(Money.of("500.00", Currency.SAR), overview.summary.selfTransfers)
        assertEquals(Money.zero(Currency.SAR), overview.summary.spendingGross)
        assertEquals(Money.zero(Currency.SAR), overview.summary.income)
    }

    @Test
    fun wifeExternalTransferIn_notIncome() = runBlocking {
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val service = dashboardService(
            FakeFtRepo(
                listOf(
                    tx(
                        id = "w1",
                        type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                        amount = "250",
                        at = start.plusSeconds(10),
                        counterparty = "wife",
                    ),
                ),
            ),
            FakeReviewRepo(),
        )
        val overview = service.loadOverview(period)
        assertEquals(Money.of("250.00", Currency.SAR), overview.summary.externalTransfersIn)
        assertEquals(Money.zero(Currency.SAR), overview.summary.income)
    }

    @Test
    fun reviewRequiredCount_usesRequiredOnly() = runBlocking {
        val period = FinancialPeriod(
            startDate = LocalDate.parse("2026-07-27"),
            endDateExclusive = LocalDate.parse("2026-08-27"),
        )
        val service = dashboardService(
            FakeFtRepo(emptyList()),
            FakeReviewRepo(requiredCount = 3),
        )
        val overview = service.loadOverview(period)
        assertEquals(3, overview.summary.reviewRequiredCount)
    }

    @Test
    fun periodBoundaries_includeStartExcludeEnd() = runBlocking {
        val period = FinancialPeriod(
            startDate = LocalDate.parse("2026-07-27"),
            endDateExclusive = LocalDate.parse("2026-08-27"),
        )
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val end = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zone)
        val service = dashboardService(
            FakeFtRepo(
                listOf(
                    tx("before", FinancialTransactionType.EXPENSE, "1", start.minusSeconds(1)),
                    tx("start", FinancialTransactionType.EXPENSE, "2", start),
                    tx("inside", FinancialTransactionType.EXPENSE, "3", start.plusSeconds(100)),
                    tx("end", FinancialTransactionType.EXPENSE, "4", end),
                    tx("after", FinancialTransactionType.EXPENSE, "5", end.plusSeconds(1)),
                ),
            ),
            FakeReviewRepo(),
        )
        val overview = service.loadOverview(period)
        assertEquals(2, overview.summary.transactionCount)
        assertEquals(Money.of("5.00", Currency.SAR), overview.summary.spendingGross)
        assertEquals(listOf("inside", "start"), overview.transactions.map { it.id })
    }

    @Test
    fun merchantSpending_usesOnlyTransactionsInsideActivePeriod() = runBlocking {
        val period = FinancialPeriod(
            startDate = LocalDate.parse("2026-07-27"),
            endDateExclusive = LocalDate.parse("2026-08-27"),
        )
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val end = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zone)
        val purchases = (1..2).map { index ->
            tx(
                id = "inside-$index",
                type = FinancialTransactionType.EXPENSE,
                amount = "10",
                at = start.plusSeconds(index.toLong()),
                merchant = "Cafe",
            )
        }
        val overview = dashboardService(
            FakeFtRepo(
                purchases + listOf(
                    tx("before", FinancialTransactionType.EXPENSE, "99", start.minusSeconds(1), merchant = "Cafe"),
                    tx("end", FinancialTransactionType.EXPENSE, "99", end, merchant = "Cafe"),
                ),
            ),
            FakeReviewRepo(),
        ).loadOverview(period)

        val merchant = overview.merchantSpending.merchants.single()
        assertEquals(2, merchant.purchaseTransactionCount)
        assertEquals(Money.of("20.00", Currency.SAR).amount, merchant.totalSpent.amount)
        assertEquals(purchases.map { it.id }.toSet(), merchant.transactionIds)
    }

    @Test
    fun loadCurrentOverview_marksCurrentPeriod() = runBlocking {
        val service = dashboardService(FakeFtRepo(emptyList()), FakeReviewRepo())
        val overview = service.loadCurrentOverview()
        assertTrue(overview.isCurrentPeriod)
        val previous = FinancialPeriodPolicy.previous(overview.period)
        val older = service.loadOverview(previous)
        assertFalse(older.isCurrentPeriod)
    }

    @Test
    fun loadProjection_usesBatchScopedEvidence_neverWholeHistoryOrPerRowLookups() = runBlocking {
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val foreign = tx("usd", FinancialTransactionType.EXPENSE, "10", start.plusSeconds(60)).copy(
            amount = Money.of("10.00", Currency.USD),
            linkedParsedEventIds = listOf("evt-usd"),
        )
        val record = ParsedEventRecord(
            event = parsedEvent("evt-usd", "sms-usd", merchant = "AMAZON", amount = foreign.amount),
            details = ParsedEventDetails(exchangeRate = java.math.BigDecimal("3.75")),
        )
        val ftRepo = object : FinancialTransactionRepository by FakeFtRepo(listOf(foreign)) {
            override suspend fun listRawSmsIdsForTransactions(transactionIds: Collection<String>): Set<String> =
                if ("usd" in transactionIds) setOf("sms-usd") else emptySet()
        }
        val parsedRepo = object : ParsedEventRepository by FakeParsedRepo() {
            override suspend fun listAll(): List<ParsedEventRecord> = error("whole-history scan")
            override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>) =
                listOf(record).filter { it.event.rawSmsId in rawSmsIds }
            override suspend fun listCardStatementFacts() = emptyList<ParsedEventRecord>()
            override suspend fun listLatestCreditCardRowFacts() = emptyList<ParsedEventRecord>()
            override suspend fun listLatestCreditCardAvailableBalanceFacts(beforeExclusive: Instant) =
                emptyList<ParsedEventRecord>()
            override suspend fun listFinancingInstallmentFacts() = emptyList<ParsedEventRecord>()
            override suspend fun listExchangeRateFacts() = emptyList<ParsedEventRecord>()
            override suspend fun listFirstDebitCardFacts(cardLast4s: Collection<String>) = emptyList<ParsedEventRecord>()
        }
        val raw = RawSms("sms-usd", "BankAlJazira", "body", start, null, "hash")
        val rawRepo = object : RawSmsRepository by FakeRawRepo(mapOf(raw.id to raw)) {
            override suspend fun getById(id: String): RawSms? = error("per-row RawSms lookup")
            override suspend fun getByIds(ids: Collection<String>): List<RawSms> = listOf(raw).filter { it.id in ids }
        }

        val projection = dashboardService(ftRepo, FakeReviewRepo(), parsedRepo = parsedRepo, rawRepo = rawRepo)
            .loadProjection(period)

        val shown = projection.transactions.single()
        assertEquals("AMAZON", shown.merchant)
        assertEquals(Money.of("37.50", Currency.SAR), projection.summary.spendingGross)
        assertEquals(0, projection.summary.excludedOtherCurrencyCount)
    }

    private fun parsedEvent(
        id: String,
        rawSmsId: String,
        merchant: String?,
        amount: Money,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = com.baraa.masroof.domain.model.MessageFamily.PURCHASE,
        direction = null,
        amount = amount,
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = merchant,
        counterparty = null,
        occurredAt = null,
        bankNetworkType = null,
        confidence = com.baraa.masroof.domain.model.Confidence(0.9),
        parseStatus = com.baraa.masroof.domain.model.ParseStatus.SUCCESS,
    )

    private fun dashboardService(
        ftRepo: FinancialTransactionRepository,
        reviewRepo: ReviewRepository,
        parsedRepo: ParsedEventRepository = FakeParsedRepo(),
        rawRepo: RawSmsRepository = FakeRawRepo(),
        accountRepo: AccountRegistryRepository = FakeAccountRepo(),
        cardRepo: CardRegistryRepository = FakeCardRepo(),
        loanRepo: LoanRegistryRepository = FakeLoanRepo(),
        commitmentRepo: com.baraa.masroof.domain.repository.CommitmentRepository = NoOpCommitmentRepository(),
    ): DashboardService =
        DashboardService(
            financialTransactionRepository = ftRepo,
            reviewRepository = reviewRepo,
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            appLocaleRepository = FakeAppLocaleRepository(),
            accountRegistryRepository = accountRepo,
            cardRegistryRepository = cardRepo,
            loanRegistryRepository = loanRepo,
            commitmentRepository = commitmentRepo,
            sarEquivalentResolver = TransactionSarEquivalentResolver(ForeignSarMarketRateProvider { _, _ -> null }),
            zoneId = zone,
            clock = clock,
        )

    private class FakeCardRepo(
        entries: List<com.baraa.masroof.domain.model.CardRegistryEntry> = emptyList(),
    ) : NoOpCardRegistryRepository(entries)

    private class FakeAccountRepo(
        private val entries: List<AccountRegistryEntry> = emptyList(),
    ) : AccountRegistryRepository {
        override suspend fun observe(reference: AccountReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus) = Unit
        override suspend fun resolve(reference: AccountReference): OwnershipStatus = OwnershipStatus.UNKNOWN
        override suspend fun get(reference: AccountReference): AccountRegistryEntry? = null
        override suspend fun listAll(): List<AccountRegistryEntry> = entries
        override suspend fun updateDisplayName(reference: AccountReference, displayName: String?) = Unit
        override suspend fun updateAccountType(reference: AccountReference, accountType: AccountType) = Unit
    }

    private class FakeLoanRepo : LoanRegistryRepository {
        override suspend fun observe(reference: com.baraa.masroof.domain.model.LoanReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: com.baraa.masroof.domain.model.LoanReference, status: OwnershipStatus) = Unit
        override suspend fun resolve(reference: com.baraa.masroof.domain.model.LoanReference): OwnershipStatus = OwnershipStatus.UNKNOWN
        override suspend fun get(reference: com.baraa.masroof.domain.model.LoanReference): com.baraa.masroof.domain.model.LoanRegistryEntry? = null
        override suspend fun listAll(): List<com.baraa.masroof.domain.model.LoanRegistryEntry> = emptyList()
        override suspend fun updateDisplayName(reference: com.baraa.masroof.domain.model.LoanReference, displayName: String?) = Unit
    }

    private class FakeAppLocaleRepository : AppLocaleRepository {
        override fun getLanguageTag(): String = AppLocale.DEFAULT_TAG
        override fun setLanguageTag(languageTag: String) = Unit
    }

    private class FakeParsedRepo(
        private val records: List<ParsedEventRecord> = emptyList(),
    ) : ParsedEventRepository {
        override suspend fun save(event: ParsedEvent, details: ParsedEventDetails) = Unit
        override suspend fun getById(id: String): ParsedEventRecord? = null
        override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord? = null
        override suspend fun deleteByRawSmsId(rawSmsId: String) = Unit
        override suspend fun listAll(): List<ParsedEventRecord> = records

        override suspend fun listReceivedBetween(
            startInclusive: java.time.Instant,
            endExclusive: java.time.Instant,
        ): List<ParsedEventRecord> = records
    }

    private class FakeRawRepo(
        private val byId: Map<String, RawSms> = emptyMap(),
    ) : RawSmsRepository {
        override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult = RawSmsInsertResult.Inserted
        override suspend fun getById(id: String): RawSms? = byId[id]
        override suspend fun existsById(id: String): Boolean = byId.containsKey(id)
        override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? = null
        override suspend fun listIdsByReceivedAt(): List<String> = byId.keys.toList()
        override suspend fun findCrossSourceNearDuplicate(
            sender: String,
            bodyHash: String,
            fromInclusive: Instant,
            toInclusive: Instant,
            lookingForLiveRow: Boolean,
        ): RawSms? = null
    }

    private fun tx(
        id: String,
        type: FinancialTransactionType,
        amount: String,
        at: Instant,
        counterparty: String? = null,
        merchant: String? = null,
    ): FinancialTransaction =
        FinancialTransaction(
            id = id,
            type = type,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = at,
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = merchant,
            counterparty = counterparty,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )

    private class FakeFtRepo(
        private val all: List<FinancialTransaction>,
    ) : FinancialTransactionRepository {
        override suspend fun save(
            transaction: FinancialTransaction,
            rawSmsIds: Collection<String>,
        ): FinancialTransactionSaveResult = FinancialTransactionSaveResult.Saved

        override suspend fun getById(id: String): FinancialTransaction? = all.firstOrNull { it.id == id }
        override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? = null
        override suspend fun listAll(): List<FinancialTransaction> = all
        override suspend fun listOccurredBetween(
            startInclusive: Instant,
            endExclusive: Instant,
        ): List<FinancialTransaction> =
            all.filter { !it.occurredAt.isBefore(startInclusive) && it.occurredAt.isBefore(endExclusive) }
                .sortedWith(compareByDescending<FinancialTransaction> { it.occurredAt }.thenByDescending { it.id })

        override suspend fun isRawSmsLinked(rawSmsId: String): Boolean = false
        override suspend fun listRawSmsIds(transactionId: String): List<String> = emptyList()
        override suspend fun update(transaction: FinancialTransaction): Boolean = false
        override suspend fun updateAppliedExchangeRate(
            id: String,
            exchangeRate: java.math.BigDecimal,
            source: com.baraa.masroof.domain.model.ExchangeRateSource,
        ): Boolean = false
        override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean = false
        override suspend fun unlinkRawSms(rawSmsId: String): Boolean = false

        override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String): Boolean = false
    }

    private class FakeReviewRepo(
        private val requiredCount: Int = 0,
    ) : ReviewRepository {
        override suspend fun getById(id: String) = null
        override suspend fun findByRawSmsId(rawSmsId: String) = null
        override suspend fun listRequired(): List<ReviewItem> =
            (1..requiredCount).map { idx ->
                ReviewItem(
                    id = "r$idx",
                    rawSmsId = "sms$idx",
                    kind = ReviewKind.NEEDS_REVIEW,
                    status = ReviewStatus.REQUIRED,
                    reasons = listOf("x"),
                    createdAt = Instant.parse("2026-08-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
                    resolvedAt = null,
                    resolutionKind = null,
                    resolvedTransactionId = null,
                )
            }

        override suspend fun listAll(): List<ReviewItem> = listRequired()
        override suspend fun listIgnored(): List<ReviewItem> = emptyList()
        override suspend fun upsertRequired(
            rawSmsId: String,
            kind: ReviewKind,
            reasons: List<String>,
            now: Instant,
        ): ReviewItem = error("unused")

        override suspend fun markResolved(
            id: String,
            resolutionKind: com.baraa.masroof.domain.model.ReviewResolutionKind,
            resolvedAt: Instant,
            resolvedTransactionId: String?,
        ): ReviewItem? = null
    }
}
