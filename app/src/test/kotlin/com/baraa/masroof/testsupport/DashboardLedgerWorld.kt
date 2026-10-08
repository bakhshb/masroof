package com.baraa.masroof.testsupport

import android.content.Context
import androidx.room.Room
import com.baraa.masroof.application.dashboard.DashboardEvidence
import com.baraa.masroof.application.dashboard.DashboardEvidenceScope
import com.baraa.masroof.application.dashboard.DashboardEvidenceSource
import com.baraa.masroof.application.dashboard.DashboardProjectionBuilder
import com.baraa.masroof.application.dashboard.DashboardService
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.sms.HistoricalSmsBatchProcessor
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomCommitmentRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.random.Random

/**
 * Room-backed ledger for dashboard read-path tests: the AlJazira fixture corpus imported
 * through production wiring, plus deterministic synthetic multi-month history whose
 * statements, balances, installments, exchange rates and card evidence fall outside any
 * single displayed period.
 */
class DashboardLedgerWorld(context: Context) : AutoCloseable {
    val zone: ZoneId = ZoneId.of("Asia/Riyadh")
    val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), zone)
    val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val rawRepo = RoomRawSmsRepository(db.rawSmsDao())
    val parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
    val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
    val reviewRepo = RoomReviewRepository(db.reviewItemDao())
    val accounts = RoomAccountRegistryRepository.from(db)
    val cards = RoomCardRegistryRepository.from(db)
    val loans = RoomLoanRegistryRepository.from(db)
    val commitments = RoomCommitmentRepository.from(db)

    private val bank = Bank.BANK_ALJAZIRA
    private var sequence = 0

    val localeRepository = object : AppLocaleRepository {
        override fun getLanguageTag(): String = AppLocale.DEFAULT_TAG
        override fun setLanguageTag(languageTag: String) = Unit
    }

    private val importClock = InstantClock { Instant.parse("2026-09-01T00:00:00Z") }
    private val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))
    private val discovery = OwnershipDiscoveryService(accounts, cards, loans)
    private val reconciliation = TransactionReconciliationService(
        parsedEventRepository = parsedRepo,
        rawSmsRepository = rawRepo,
        financialTransactionRepository = ftRepo,
        ownershipResolver = OwnershipResolver(accounts, cards, loans),
        ownershipConfirmationService = OwnershipConfirmationService(accounts, cards, loans),
        effectiveParsedEventProvider = EffectiveParsedEventProvider(
            parsedRepo,
            RoomUserCorrectionRepository(db.userCorrectionDao()),
        ),
        reviewRepository = reviewRepo,
    )
    private val reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, importClock)

    val captureBankSms = CaptureBankSmsUseCase(rawRepo, registry)

    fun processStoredSms(exchangeRateEnrichment: ExchangeRateEnrichmentWorkflow? = null) =
        ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = registry,
            ownershipDiscovery = discovery,
            reconciliation = reconciliation,
            reviewQueueUpdater = reviewQueueUpdater,
            ingestionReviewService = IngestionReviewService(reviewRepo, importClock),
            exchangeRateEnrichmentScheduler = exchangeRateEnrichment?.let { workflow ->
                com.baraa.masroof.application.sms.ImmediateExchangeRateEnrichmentScheduler(
                    com.baraa.masroof.application.sms.PendingExchangeRateEnricher { workflow.enrichPending() },
                )
            },
        )

    suspend fun ownFixtureInstruments() {
        listOf("3001", "3002", "3003").forEach {
            accounts.setOwnership(AccountReference(bank, it), OwnershipStatus.OWNED)
        }
        listOf("7271", "2210", "8219").forEach {
            cards.setOwnership(CardReference(bank, it), OwnershipStatus.OWNED)
        }
    }

    suspend fun importFixtureCorpus(
        rows: List<ProviderSmsRecord> = AlJaziraFixtureInbox.rows(),
        exchangeRateEnrichment: ExchangeRateEnrichmentWorkflow? = null,
    ) {
        ownFixtureInstruments()
        val batch = HistoricalSmsBatchProcessor(
            capture = captureBankSms,
            processStored = processStoredSms(),
            ownershipDiscovery = discovery,
            reconciliation = reconciliation,
            reviewQueueUpdater = reviewQueueUpdater,
            exchangeRateEnrichment = exchangeRateEnrichment,
        ).startBatch()
        rows.forEach { batch.ingest(AndroidSmsMapper.toRawSms(it)) }
        batch.finish()
    }

    /** Deterministic months of synthetic SMS facts and linked transactions. */
    suspend fun seedSyntheticHistory(months: List<YearMonth>) {
        val random = Random(20261006)
        accounts.setOwnership(AccountReference(bank, "3002"), OwnershipStatus.OWNED)
        listOf("7271", "5555", "2210", "8219", "6666", "3333").forEach {
            cards.setOwnership(CardReference(bank, it), OwnershipStatus.OWNED)
        }
        cards.updateCardType(CardReference(bank, "7271"), CardType.CREDIT)
        LoanType.entries.filter { it != LoanType.MORTGAGE }.forEach {
            loans.setOwnership(LoanReference(bank, it), OwnershipStatus.OWNED)
        }
        val commitmentSources = mutableListOf<FinancialTransaction>()
        months.forEachIndexed { index, month -> seedMonth(month, index, random, commitmentSources) }
        commitmentSources.forEachIndexed { index, source ->
            commitments.create(
                Commitment(
                    id = "commitment-$index",
                    name = "Subscription $index",
                    amount = source.amount,
                    transactionDate = source.occurredAt.atZone(zone).toLocalDate(),
                    recurrence = CommitmentRecurrence.MONTHLY,
                    dueDate = null,
                    active = true,
                    sourceTransactionId = source.id,
                    createdAt = source.occurredAt,
                    updatedAt = source.occurredAt,
                ),
            )
        }
    }

    fun dashboardService(
        evidenceSource: DashboardEvidenceSource? = null,
        parsedEventRepository: ParsedEventRepository = parsedRepo,
        rawSmsRepository: RawSmsRepository = rawRepo,
        financialTransactionRepository: FinancialTransactionRepository = WriteRejectingFinancialTransactionRepository(ftRepo),
        marketRateProvider: ForeignSarMarketRateProvider = NO_MARKET_RATE,
    ): DashboardService {
        val resolver = TransactionSarEquivalentResolver(marketRateProvider, zone)
        return DashboardService(
            financialTransactionRepository = financialTransactionRepository,
            reviewRepository = reviewRepo,
            parsedEventRepository = parsedEventRepository,
            rawSmsRepository = rawSmsRepository,
            appLocaleRepository = localeRepository,
            accountRegistryRepository = accounts,
            cardRegistryRepository = cards,
            loanRegistryRepository = loans,
            commitmentRepository = commitments,
            sarEquivalentResolver = resolver,
            zoneId = zone,
            clock = clock,
            evidenceSource = evidenceSource ?: DashboardEvidenceScope(
                financialTransactionRepository = financialTransactionRepository,
                parsedEventRepository = parsedEventRepository,
                rawSmsRepository = rawSmsRepository,
            ),
        )
    }

    fun projectionBuilder(
        marketRateProvider: ForeignSarMarketRateProvider = NO_MARKET_RATE,
        financialTransactionRepository: FinancialTransactionRepository = WriteRejectingFinancialTransactionRepository(ftRepo),
    ): DashboardProjectionBuilder =
        DashboardProjectionBuilder(
            financialTransactionRepository = financialTransactionRepository,
            reviewRepository = reviewRepo,
            accountRegistryRepository = accounts,
            cardRegistryRepository = cards,
            loanRegistryRepository = loans,
            commitmentRepository = commitments,
            appLocaleRepository = localeRepository,
            sarEquivalentResolver = TransactionSarEquivalentResolver(marketRateProvider, zone),
            evidenceSource = DashboardEvidenceScope(financialTransactionRepository, parsedRepo, rawRepo),
            zoneId = zone,
            clock = clock,
        )

    fun exchangeRateEnrichmentWorkflow(
        marketRateProvider: ForeignSarMarketRateProvider = NO_MARKET_RATE,
        financialTransactionRepository: FinancialTransactionRepository = ftRepo,
    ): ExchangeRateEnrichmentWorkflow =
        ExchangeRateEnrichmentWorkflow(
            financialTransactionRepository = financialTransactionRepository,
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            sarEquivalentResolver = TransactionSarEquivalentResolver(marketRateProvider, zone),
        )

    override fun close() {
        db.close()
    }

    @Suppress("LongMethod")
    private suspend fun seedMonth(
        month: YearMonth,
        index: Int,
        random: Random,
        commitmentSources: MutableList<FinancialTransaction>,
    ) {
        fun at(day: Int, hour: Int, minute: Int = 0) = month.atDay(day).atTime(LocalTime.of(hour, minute))
        fun sar(min: Int, max: Int) = Money.of("${random.nextInt(min, max)}.${random.nextInt(10, 99)}", Currency.SAR)

        // Statements stop near the end so later card windows reach back before the period.
        if (index < STATEMENT_MONTHS) {
            record(at(3, 10), MessageFamily.BALANCE_NOTICE, card = "7271", details = ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.STATEMENT,
                outstandingBalance = sar(1000, 5000),
                paymentDueDate = month.atDay(3).plusDays(24),
            ))
            record(at(9, 8), MessageFamily.BALANCE_NOTICE, card = "5555", knownInstant = false, details = ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.STATEMENT,
                outstandingBalance = sar(200, 900),
                paymentDueDate = month.atDay(9).plusDays(24),
            ))
        }
        if (index % 3 == 0 && index < STATEMENT_MONTHS) {
            record(at(15, 11), MessageFamily.BALANCE_NOTICE, details = ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.STATEMENT,
            ))
        }

        val merchants = listOf("Jarir", "Panda", "Careem", "STC")
        listOf("7271", "5555", "4444", "7271", "5555", "4444").forEachIndexed { i, card ->
            val local = at(2 + 4 * i, random.nextInt(8, 22), random.nextInt(0, 59))
            val details = ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.CREDIT,
                availableBalance = if (i % 2 == 0) sar(3000, 9000) else null,
                outstandingBalance = if (i == 1) sar(100, 500) else null,
            )
            val tx = purchase(local, card, sar(10, 400), merchants[i % merchants.size], details, knownInstant = i != 3)
            if (i == 0 && index % 4 == 1) commitmentSources += tx
        }
        val tie = at(20, 20)
        purchase(tie, "7271", sar(5, 50), "Tie A", ParsedEventDetails(availableBalance = sar(1000, 2000), cardSmsChannel = CardSmsChannel.CREDIT))
        purchase(tie, "7271", sar(5, 50), "Tie B", ParsedEventDetails(availableBalance = sar(1000, 2000), cardSmsChannel = CardSmsChannel.CREDIT))
        record(at(24, 9), MessageFamily.BALANCE_NOTICE, card = "7271", details = ParsedEventDetails(
            cardSmsChannel = CardSmsChannel.CREDIT,
            availableBalance = sar(500, 4000),
        ))

        purchase(at(11, 13), "2210", sar(5, 200), "Bakery", ParsedEventDetails(
            cardSmsChannel = CardSmsChannel.DEBIT,
            debitSourceAccountLast4 = if (index >= 2) "3001" else null,
        ))
        purchase(at(12, 18), "8219", sar(5, 200), "Google Pay Store", ParsedEventDetails(
            cardSmsChannel = CardSmsChannel.DEBIT,
        ))
        if (index == 0) {
            record(at(13, 9), MessageFamily.PURCHASE, card = "6666", sourceAccount = "XXXX3002", details = ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.CREDIT,
            ))
        }
        purchase(at(13, 19), "6666", sar(5, 80), "Kiosk", ParsedEventDetails(cardSmsChannel = CardSmsChannel.DEBIT))

        val installmentAt = at(27, 1, 10)
        val installmentRaw = record(
            installmentAt,
            MessageFamily.FINANCING_INSTALLMENT,
            amount = Money.of("3036.11", Currency.SAR),
            sourceAccount = "3001",
            knownInstant = false,
            details = ParsedEventDetails(
                loanType = LoanType.PERSONAL,
                outstandingBalance = Money.of("${90000 - index * 3036}.00", Currency.SAR),
                occurredAtLocal = installmentAt,
            ),
        )
        transaction(installmentRaw, FinancialTransactionType.FEE, Money.of("3036.11", Currency.SAR), installmentAt,
            source = FinancialContainerIdFactory.accountId(bank, "3001"))
        if (index % 2 == 0) {
            val autoAt = at(26, 2)
            val autoRaw = record(autoAt, MessageFamily.FINANCING_INSTALLMENT, amount = Money.of("1450.00", Currency.SAR),
                sourceAccount = "3001", details = ParsedEventDetails(loanType = LoanType.AUTO))
            transaction(autoRaw, FinancialTransactionType.FEE, Money.of("1450.00", Currency.SAR), autoAt,
                source = FinancialContainerIdFactory.accountId(bank, "3001"))
        }

        val usd = Money.of("${random.nextInt(20, 100)}.00", Currency.USD)
        val foreign = purchase(at(14, 16), "7271", usd, "AMAZON US", ParsedEventDetails(
            cardSmsChannel = CardSmsChannel.CREDIT,
            exchangeRate = BigDecimal("3.75").add(BigDecimal(random.nextInt(0, 20)).movePointLeft(3)),
        ), transactionMerchant = null)
        if (index % 4 == 1) commitmentSources += foreign
        // SMS states a rate but no merchant, so only transaction-linked evidence resolves SAR.
        val gym = purchase(at(15, 7), "7271", Money.of("30.00", Currency.EUR), null, ParsedEventDetails(
            cardSmsChannel = CardSmsChannel.CREDIT,
            exchangeRate = BigDecimal("4.0521"),
        ), transactionMerchant = "GYM")
        if (index % 4 == 2) commitmentSources += gym
        // Lands before the next period starts, inside a statement-free card window only.
        if (index >= STATEMENT_MONTHS) {
            purchase(at(25, 15), "7271", Money.of("44.00", Currency.USD), null, ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.CREDIT,
                exchangeRate = BigDecimal("3.7612"),
            ), transactionMerchant = "APPLE")
        }
        record(at(16, 17), MessageFamily.PURCHASE, amount = Money.of("15.99", Currency.EUR), card = "7271",
            merchant = "NETFLIX", parseStatus = ParseStatus.REVIEW_REQUIRED, details = ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.CREDIT,
                exchangeRate = BigDecimal("4.05").add(BigDecimal(index).movePointLeft(3)),
            ))
        if (index < 6) {
            record(at(16, 18), MessageFamily.PURCHASE, amount = Money.of("9.99", Currency.EUR), card = "5555",
                merchant = "SPOTIFY", parseStatus = ParseStatus.REVIEW_REQUIRED, details = ParsedEventDetails(
                    cardSmsChannel = CardSmsChannel.CREDIT,
                    exchangeRate = BigDecimal("4.10").add(BigDecimal(index).movePointLeft(3)),
                ))
        } else if (index % 4 == 3) {
            purchase(at(17, 13), "5555", Money.of("9.99", Currency.EUR), "SPOTIFY", ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.CREDIT,
            ))
        }
        if (index % 5 == 4) {
            purchase(at(17, 12), "7271", Money.of("12.99", Currency.EUR), "NETFLIX", ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.CREDIT,
            ))
        }

        val paymentAt = at(6, 12)
        val payment = if (index % 6 == 5) Money.of("250.00", Currency.USD) else sar(500, 3000)
        val paymentRaw = record(paymentAt, MessageFamily.CARD_PAYMENT, amount = payment, sourceAccount = "3001", card = "7271",
            details = ParsedEventDetails(exchangeRate = BigDecimal("3.7531").takeIf { payment.currency == Currency.USD }))
        transaction(paymentRaw, FinancialTransactionType.CREDIT_CARD_PAYMENT, payment, paymentAt,
            source = FinancialContainerIdFactory.accountId(bank, "3001"),
            destination = FinancialContainerIdFactory.cardId(bank, "7271"))
        if (index % 2 == 1) {
            val lateAt = at(28, 9)
            // After statements stop, only foreign payments can settle the last 5555 due.
            val late = if (index >= STATEMENT_MONTHS) Money.of("250.00", Currency.USD) else sar(100, 900)
            val lateRaw = record(lateAt, MessageFamily.CARD_PAYMENT, amount = late, sourceAccount = "3002", card = "5555",
                details = ParsedEventDetails(exchangeRate = BigDecimal("3.7544").takeIf { late.currency == Currency.USD }))
            transaction(lateRaw, FinancialTransactionType.CREDIT_CARD_PAYMENT, late, lateAt,
                source = FinancialContainerIdFactory.accountId(bank, "3002"),
                destination = FinancialContainerIdFactory.cardId(bank, "5555"))
        }

        val salaryAt = at(27, 1, 12)
        val salaryRaw = record(salaryAt, MessageFamily.TRANSFER_IN, amount = Money.of("15000.00", Currency.SAR),
            destinationAccount = "3001", details = ParsedEventDetails(salaryIncomeWording = true))
        transaction(salaryRaw, FinancialTransactionType.EXTERNAL_TRANSFER_IN, Money.of("15000.00", Currency.SAR), salaryAt,
            destination = FinancialContainerIdFactory.accountId(bank, "3001"))
        val transferAt = at(18, 10)
        val transferOut = record(transferAt, MessageFamily.TRANSFER_OUT, amount = Money.of("700.00", Currency.SAR),
            sourceAccount = "3001", destinationAccount = "3002")
        val transferIn = record(transferAt.plusMinutes(1), MessageFamily.TRANSFER_IN, amount = Money.of("700.00", Currency.SAR),
            sourceAccount = "3001", destinationAccount = "3002")
        transaction(listOf(transferOut, transferIn), FinancialTransactionType.SELF_TRANSFER, Money.of("700.00", Currency.SAR),
            transferAt, source = FinancialContainerIdFactory.accountId(bank, "3001"),
            destination = FinancialContainerIdFactory.accountId(bank, "3002"))

        record(at(19, 7), MessageFamily.OTP, parseStatus = ParseStatus.NON_FINANCIAL)
        if (index == 0) {
            record(at(21, 9), MessageFamily.PURCHASE, card = "3333", details = ParsedEventDetails(cardSmsChannel = CardSmsChannel.CREDIT))
        }
    }

    private suspend fun purchase(
        local: LocalDateTime,
        card: String,
        amount: Money,
        merchant: String?,
        details: ParsedEventDetails,
        knownInstant: Boolean = true,
        transactionMerchant: String? = merchant,
    ): FinancialTransaction {
        val rawId = record(local, MessageFamily.PURCHASE, amount = amount, card = card, merchant = merchant,
            knownInstant = knownInstant, details = details)
        return transaction(rawId, FinancialTransactionType.EXPENSE, amount, local,
            source = FinancialContainerIdFactory.cardId(bank, card), merchant = transactionMerchant)
    }

    @Suppress("LongParameterList")
    private suspend fun record(
        local: LocalDateTime,
        family: MessageFamily,
        amount: Money? = null,
        card: String? = null,
        sourceAccount: String? = null,
        destinationAccount: String? = null,
        merchant: String? = null,
        knownInstant: Boolean = true,
        parseStatus: ParseStatus = ParseStatus.SUCCESS,
        details: ParsedEventDetails = ParsedEventDetails(),
    ): String {
        val n = (++sequence).toString().padStart(5, '0')
        val rawId = "syn-sms:$n"
        val instant = local.atZone(zone).toInstant()
        rawRepo.insertIfAbsent(
            RawSms(
                id = rawId,
                sender = "BankAlJazira",
                body = "synthetic $family $n",
                receivedAt = instant.plusSeconds(30),
                deviceMessageId = null,
                bodyHash = "syn-hash-$n",
            ),
        )
        parsedRepo.save(
            ParsedEvent(
                id = "evt-$rawId",
                rawSmsId = rawId,
                bank = bank,
                messageFamily = family,
                direction = when (family) {
                    MessageFamily.TRANSFER_IN -> MoneyDirection.INCOMING
                    MessageFamily.OTP, MessageFamily.BALANCE_NOTICE -> null
                    else -> MoneyDirection.OUTGOING
                },
                amount = amount,
                purchaseChannel = null,
                sourceAccountRef = sourceAccount?.let { AccountReference(bank, it) },
                destinationAccountRef = destinationAccount?.let { AccountReference(bank, it) },
                cardRef = card?.let { CardReference(bank, it) },
                merchant = merchant,
                counterparty = null,
                occurredAt = if (knownInstant) instant else null,
                bankNetworkType = null,
                confidence = Confidence(0.95),
                parseStatus = parseStatus,
            ),
            details,
        )
        return rawId
    }

    private suspend fun transaction(
        rawSmsId: String,
        type: FinancialTransactionType,
        amount: Money,
        local: LocalDateTime,
        source: String? = null,
        destination: String? = null,
        merchant: String? = null,
    ): FinancialTransaction = transaction(listOf(rawSmsId), type, amount, local, source, destination, merchant)

    @Suppress("LongParameterList")
    private suspend fun transaction(
        rawSmsIds: List<String>,
        type: FinancialTransactionType,
        amount: Money,
        local: LocalDateTime,
        source: String? = null,
        destination: String? = null,
        merchant: String? = null,
    ): FinancialTransaction {
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(rawSmsIds),
            type = type,
            amount = amount,
            occurredAt = local.atZone(zone).toInstant(),
            sourceContainerId = source,
            destinationContainerId = destination,
            merchant = merchant,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = rawSmsIds.map { "evt-$it" },
        )
        ftRepo.save(tx, rawSmsIds)
        return tx
    }

    companion object {
        private const val STATEMENT_MONTHS = 19

        val NO_MARKET_RATE = ForeignSarMarketRateProvider { _, _ -> null }

        /** Months of synthetic history used by dashboard read-path tests. */
        val SYNTHETIC_MONTHS: List<YearMonth> =
            generateSequence(YearMonth.of(2025, 1)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.of(2026, 11)) }
                .toList()
    }
}

/** Pre-M4.1 evidence load: every ParsedEvent plus one RawSms lookup per record. */
class WholeHistoryDashboardEvidenceSource(
    private val parsedEventRepository: ParsedEventRepository,
    private val rawSmsRepository: RawSmsRepository,
) : DashboardEvidenceSource {
    override suspend fun load(
        transactions: Collection<FinancialTransaction>,
        registryCards: List<CardRegistryEntry>,
        periodEndExclusive: Instant,
    ): DashboardEvidence {
        val parsedRecords = parsedEventRepository.listAll()
        val rawSmsById = parsedRecords
            .map { it.event.rawSmsId }
            .distinct()
            .mapNotNull { id -> rawSmsRepository.getById(id)?.let { id to it } }
            .toMap()
        return DashboardEvidence(parsedRecords, rawSmsById)
    }

    override suspend fun extend(
        evidence: DashboardEvidence,
        transactions: Collection<FinancialTransaction>,
    ): DashboardEvidence = evidence
}

/** Fails the test on any FinancialTransaction write; dashboard loads must be read-only. */
class WriteRejectingFinancialTransactionRepository(
    private val delegate: FinancialTransactionRepository,
) : FinancialTransactionRepository by delegate {
    override suspend fun save(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult = rejected("save")

    override suspend fun replaceExclusiveStaleLinks(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        staleRawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult = rejected("replaceExclusiveStaleLinks")

    override suspend fun update(transaction: FinancialTransaction): Boolean = rejected("update")

    override suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: BigDecimal,
        source: ExchangeRateSource,
    ): Boolean = rejected("updateAppliedExchangeRate")

    override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean =
        rejected("deleteIfExclusiveRawSmsLink")

    override suspend fun unlinkRawSms(rawSmsId: String): Boolean = rejected("unlinkRawSms")

    override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String): Boolean =
        rejected("linkRawSmsIfAbsent")

    private fun rejected(operation: String): Nothing =
        throw AssertionError("FinancialTransactionRepository.$operation called during a read-only load")
}

/** Counts whole-history and per-row reads made through the wrapped repositories. */
class CountingParsedEventRepository(
    private val delegate: ParsedEventRepository,
) : ParsedEventRepository by delegate {
    var listAllCalls = 0
        private set

    override suspend fun listAll(): List<ParsedEventRecord> {
        listAllCalls++
        return delegate.listAll()
    }
}

class CountingRawSmsRepository(
    private val delegate: RawSmsRepository,
) : RawSmsRepository by delegate {
    var getByIdCalls = 0
        private set
    var getByIdsCalls = 0
        private set

    override suspend fun getById(id: String): RawSms? {
        getByIdCalls++
        return delegate.getById(id)
    }

    override suspend fun getByIds(ids: Collection<String>): List<RawSms> {
        getByIdsCalls++
        return delegate.getByIds(ids)
    }
}
