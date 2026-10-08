package com.baraa.masroof.application.sms

import android.content.Context
import androidx.room.Room
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.parser.SmsParseGateway
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** In-memory Room wiring of the live capture → stored processing path for worker tests. */
internal class LiveSmsProcessingHarness(context: Context) : AutoCloseable {
    val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val rawRepo = RoomRawSmsRepository(db.rawSmsDao())
    val parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
    val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
    val reviewRepo = RoomReviewRepository(db.reviewItemDao())
    private val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }
    val processingRetryRepo = RoomProcessingRetryRepository(db.processingRetryDao())
    val appLog = AppLogService(context)
    val parseCalls = AtomicInteger(0)
    private val cards = RoomCardRegistryRepository.from(db)

    /** Throws from the parser while > 0, decrementing per call. */
    val parserFailuresRemaining = AtomicInteger(0)

    /** When set, replaces the AlJazira pipeline result after the failure injection check. */
    var parseOverride: ((SmsParseInput) -> ParseResult)? = null

    val registry = BankSmsRegistry(
        listOf(
            AlJaziraSmsAdapter(
                pipeline = SmsParseGateway { input ->
                    parseCalls.incrementAndGet()
                    check(parserFailuresRemaining.getAndDecrement() <= 0) { "injected parser failure" }
                    parseOverride?.invoke(input) ?: AlJaziraParsingPipeline().parse(input)
                },
            ),
        ),
    )

    val capture = CaptureBankSmsUseCase(rawRepo, registry)

    init {
        runBlocking { cards.setOwnership(CardReference(Bank.BANK_ALJAZIRA, "7271"), OwnershipStatus.OWNED) }
    }

    fun processStored(
        rawSmsRepository: RawSmsRepository = rawRepo,
        derivedFailures: DerivedFailureInjection = DerivedFailureInjection(),
    ): ProcessStoredSmsUseCase {
        val parsedForReconcile = object : ParsedEventRepository by parsedRepo {
            override suspend fun listReceivedBetween(
                startInclusive: Instant,
                endExclusive: Instant,
            ): List<ParsedEventRecord> {
                if (derivedFailures.reconciliationCancels) throw CancellationException("reconciliation cancelled")
                if (derivedFailures.reconciliationFailuresRemaining.getAndDecrement() > 0) {
                    throw IOException("reconciliation unavailable")
                }
                return parsedRepo.listReceivedBetween(startInclusive, endExclusive)
            }
        }
        val discoveryCards = object : CardRegistryRepository by cards {
            override suspend fun observe(reference: CardReference, rawSmsId: String) {
                if (derivedFailures.ownershipFailuresRemaining.getAndDecrement() > 0) {
                    throw IOException("ownership unavailable")
                }
                cards.observe(reference, rawSmsId)
            }
        }
        val reviewForRecovery = object : ReviewRepository by reviewRepo {
            override suspend fun upsertRequired(
                rawSmsId: String,
                kind: ReviewKind,
                reasons: List<String>,
                now: Instant,
            ): ReviewItem {
                if (derivedFailures.processingErrorUpsertFailuresRemaining.getAndDecrement() > 0) {
                    throw IOException("processing_error upsert failed")
                }
                return reviewRepo.upsertRequired(rawSmsId, kind, reasons, now)
            }
        }
        val ingestionReview = IngestionReviewService(reviewForRecovery, clock)
        val recovery = ProcessingRecovery(
            processingRetryRepository = processingRetryRepo,
            reviewRepository = reviewForRecovery,
            ingestionReviewService = ingestionReview,
            clock = clock,
        )
        val reviewForUpdate = object : ReviewRepository by reviewRepo {
            override suspend fun markResolved(
                id: String,
                resolutionKind: ReviewResolutionKind,
                resolvedAt: Instant,
                resolvedTransactionId: String?,
            ): ReviewItem? {
                if (derivedFailures.reviewUpdateFailuresRemaining.getAndDecrement() > 0) {
                    throw IOException("review update unavailable")
                }
                return reviewRepo.markResolved(id, resolutionKind, resolvedAt, resolvedTransactionId)
            }
        }
        val ftForEnrichment = object : FinancialTransactionRepository by ftRepo {
            override suspend fun listAwaitingAppliedExchangeRate(
                primaryCurrency: Currency,
            ): List<FinancialTransaction> {
                if (derivedFailures.exchangeRateFailuresRemaining.getAndDecrement() > 0) {
                    throw IOException("fx unavailable")
                }
                return ftRepo.listAwaitingAppliedExchangeRate(primaryCurrency)
            }
        }
        return ProcessStoredSmsUseCase(
            rawSmsRepository = rawSmsRepository,
            parsedEventRepository = parsedForReconcile,
            bankSmsRegistry = registry,
            ownershipDiscovery = OwnershipDiscoveryService(
                accountRegistry = RoomAccountRegistryRepository.from(db),
                cardRegistry = discoveryCards,
                loanRegistry = NoOpLoanRegistryRepository,
            ),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedForReconcile,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = ftRepo,
                ownershipResolver = OwnershipResolver(
                    RoomAccountRegistryRepository.from(db),
                    cards,
                    NoOpLoanRegistryRepository,
                ),
                effectiveParsedEventProvider = EffectiveParsedEventProvider(
                    parsedForReconcile,
                    RoomUserCorrectionRepository(db.userCorrectionDao()),
                ),
                reviewRepository = reviewRepo,
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewForUpdate, ftRepo, clock),
            ingestionReviewService = ingestionReview,
            processingRecovery = recovery,
            exchangeRateEnrichmentScheduler = ImmediateExchangeRateEnrichmentScheduler(
                PendingExchangeRateEnricher {
                    ExchangeRateEnrichmentWorkflow(
                        financialTransactionRepository = ftForEnrichment,
                        parsedEventRepository = parsedRepo,
                        rawSmsRepository = rawRepo,
                        sarEquivalentResolver = TransactionSarEquivalentResolver(
                            marketRateProvider = ForeignSarMarketRateProvider { _, _ -> null },
                        ),
                    ).enrichPending()
                },
            ),
        )
    }

    /**
     * One historical batch. [reconciliationFails] makes the single derived pass throw
     * after each row has already been parsed and stored.
     */
    fun historicalBatch(
        reconciliationFails: Boolean = false,
        batchRecoveryScheduler: HistoricalBatchRecoveryScheduler? = null,
        processingRetryRepository: ProcessingRetryRepository = processingRetryRepo,
        reviewRepository: ReviewRepository = reviewRepo,
    ): HistoricalSmsBatchProcessor {
        val parsedForBatch = object : ParsedEventRepository by parsedRepo {
            override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> {
                if (reconciliationFails) throw IOException("batch reconciliation unavailable")
                return parsedRepo.listByRawSmsIds(rawSmsIds)
            }
        }
        val ingestionReview = IngestionReviewService(reviewRepo, clock)
        return HistoricalSmsBatchProcessor(
            capture = capture,
            processStored = ProcessStoredSmsUseCase(
                rawSmsRepository = rawRepo,
                parsedEventRepository = parsedRepo,
                bankSmsRegistry = registry,
            ),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedForBatch,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = ftRepo,
                ownershipResolver = OwnershipResolver(
                    RoomAccountRegistryRepository.from(db),
                    cards,
                    NoOpLoanRegistryRepository,
                ),
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepository, ftRepo, clock),
            processingRecovery = ProcessingRecovery(
                processingRetryRepository = processingRetryRepository,
                reviewRepository = reviewRepo,
                ingestionReviewService = ingestionReview,
                clock = clock,
            ),
            batchRecoveryScheduler = batchRecoveryScheduler,
        )
    }

    /** One batch derived pass over historical retry rows. Does not reparse SMS text. */
    fun derivedRecovery(reconciliationFails: Boolean = false): HistoricalDerivedRecovery {
        val parsedForBatch = object : ParsedEventRepository by parsedRepo {
            override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> {
                if (reconciliationFails) throw IOException("batch reconciliation unavailable")
                return parsedRepo.listByRawSmsIds(rawSmsIds)
            }
        }
        return HistoricalDerivedRecovery(
            parsedEventRepository = parsedRepo,
            processingRetryRepository = processingRetryRepo,
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedForBatch,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = ftRepo,
                ownershipResolver = OwnershipResolver(
                    RoomAccountRegistryRepository.from(db),
                    cards,
                    NoOpLoanRegistryRepository,
                ),
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, clock),
            rawSmsRepository = rawRepo,
        )
    }

    fun intake(scheduler: LiveSmsWorkScheduler): LiveSmsIntake =
        LiveSmsIntake(
            captureBankSms = capture,
            scheduler = scheduler,
            rawSmsRepository = rawRepo,
            reviewRepository = reviewRepo,
            processingRetryRepository = processingRetryRepo,
            appLogService = appLog,
        )

    override fun close() {
        db.close()
    }

    companion object {
        const val OTP_CODE = "482913"

        val PURCHASE_BODY = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
        """.trimIndent()

        const val OTP_BODY =
            "رمز التحقق لعملية شراء عبر الانترنت: $OTP_CODE\nبمبلغ: 250.00 SAR\nلدى: TEST_STORE\nلا تشارك الرمز مع أحد"

        fun liveSms(body: String = PURCHASE_BODY, at: String = "2026-08-03T14:32:00Z"): RawSms =
            AndroidSmsMapper.toRawSms(ProviderSmsRecord(null, "AlJazira", body, Instant.parse(at)))
    }
}

/** Counts injected failures for one derived stage. Zero means that stage runs normally. */
internal class DerivedFailureInjection(
    val ownershipFailuresRemaining: AtomicInteger = AtomicInteger(0),
    val reconciliationFailuresRemaining: AtomicInteger = AtomicInteger(0),
    val reviewUpdateFailuresRemaining: AtomicInteger = AtomicInteger(0),
    val exchangeRateFailuresRemaining: AtomicInteger = AtomicInteger(0),
    val processingErrorUpsertFailuresRemaining: AtomicInteger = AtomicInteger(0),
    val reconciliationCancels: Boolean = false,
)
