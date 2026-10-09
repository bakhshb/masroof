package com.baraa.masroof.application.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
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
import java.io.IOException
import java.time.Instant

/**
 * One historical batch finishes with one ownership pass, one reconciliation,
 * one review refresh, and one exchange-rate enrichment call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoricalSmsBatchProcessorTest {
    private val clock = InstantClock { Instant.parse("2026-08-03T12:00:00Z") }
    private val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: CountingParsedEvents
    private lateinit var transactions: CountingTransactions
    private lateinit var cards: CountingCards
    private lateinit var reviews: CountingReviews
    private lateinit var retries: CountingRetries
    private var recoverySchedules: Int = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = CountingParsedEvents(RoomParsedEventRepository(db.parsedEventDao()))
        transactions = CountingTransactions(
            RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao()),
        )
        cards = CountingCards(RoomCardRegistryRepository.from(db))
        reviews = CountingReviews(RoomReviewRepository(db.reviewItemDao()))
        retries = CountingRetries(RoomProcessingRetryRepository(db.processingRetryDao()))
        recoverySchedules = 0
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun finish_runsDerivedWorkOnceForTheWholeBatch() = runBlocking {
        val batch = processor().startBatch()
        val first = ingestParsed(batch, purchase("51.99"), "2026-08-01T10:00:00Z", "purchase-1")
        val second = ingestParsed(batch, purchase("12.50"), "2026-08-01T12:00:00Z", "purchase-2")
        val otp = sms(OTP_BODY, "2026-08-01T13:00:00Z", "otp-1")
        assertTrue(batch.ingest(otp) is SmsIngestionResult.NonFinancial)

        assertEquals(0, cards.observed.size)
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(0, parsedRepo.scopedLoadCalls)
        assertEquals(0, transactions.awaitingCalls)
        assertEquals(0, reviews.upserts)
        assertEquals(0, db.financialTransactionDao().count())

        val finished = batch.finish()
        assertTrue(finished is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(listOf(first, second), cards.observed)
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(1, parsedRepo.scopedLoadCalls)
        assertEquals(1, transactions.awaitingCalls)
        assertEquals(2, db.financialTransactionDao().count())
        assertEquals(FinancialTransactionType.EXPENSE, transactions.findByRawSmsId(first)!!.type)
        assertEquals(FinancialTransactionType.EXPENSE, transactions.findByRawSmsId(second)!!.type)
        assertEquals(null, transactions.findByRawSmsId(otp.id))
        assertTrue(retries.listRetryableRawSmsIds().isEmpty())
        assertEquals(0, recoverySchedules)

        val rejected = runCatching { batch.finish() }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(1, parsedRepo.scopedLoadCalls)
        assertEquals(1, transactions.awaitingCalls)
    }

    @Test
    fun finish_refreshesReviewOnceForEveryCandidateInTheBatch() = runBlocking {
        val batch = processor().startBatch()
        val first = sms(COLLIDING_TRANSFER_BODY, "2026-08-12T10:00:00Z", "collision-1")
        val second = sms(COLLIDING_TRANSFER_BODY, "2026-08-12T11:00:00Z", "collision-2")
        assertTrue(batch.ingest(first) is SmsIngestionResult.ReviewRequired)
        assertTrue(batch.ingest(second) is SmsIngestionResult.ReviewRequired)
        assertEquals(0, reviews.upserts)

        val finished = batch.finish()
        assertTrue(finished is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(1, parsedRepo.scopedLoadCalls)
        assertEquals(1, transactions.awaitingCalls)
        assertEquals(2, reviews.upserts)
        assertEquals(listOf(first.id, second.id), reviews.upsertedRawSmsIds)
    }

    @Test
    fun reconciliationFailure_marksFinancialRowsOnceAndStillEnriches() = runBlocking {
        parsedRepo.failScopedLoad = true
        val batch = processor().startBatch()
        val purchase = ingestParsed(batch, purchase("51.99"), "2026-08-02T10:00:00Z", "purchase-fail")
        val otp = sms(OTP_BODY, "2026-08-02T11:00:00Z", "otp-fail")
        assertTrue(batch.ingest(otp) is SmsIngestionResult.NonFinancial)

        val finished = batch.finish()
        assertEquals(
            DerivedProcessingStage.RECONCILIATION,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(1, parsedRepo.scopedLoadCalls)
        assertEquals(listOf(listOf(purchase)), retries.markedBatches)
        assertEquals(
            listOf(purchase),
            retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertTrue(retries.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())
        assertEquals(1, recoverySchedules)
        assertEquals(1, transactions.awaitingCalls)
        assertEquals(0, db.financialTransactionDao().count())
    }

    @Test
    fun nonthrowingConflict_isIncompleteAndKeepsTheHistoricalRetrySet() = runBlocking {
        transactions.conflictSaves = true
        val batch = processor().startBatch()
        val purchase = ingestParsed(batch, purchase("51.99"), "2026-08-02T10:00:00Z", "purchase-conflict")
        val otp = sms(OTP_BODY, "2026-08-02T11:00:00Z", "otp-conflict")
        assertTrue(batch.ingest(otp) is SmsIngestionResult.NonFinancial)

        val finished = batch.finish()

        assertEquals(
            DerivedProcessingStage.RECONCILIATION,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(
            listOf(purchase),
            retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertEquals(1, recoverySchedules)
        assertEquals(0, db.financialTransactionDao().count())
        assertTrue(retries.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())
    }

    @Test
    fun reviewUpdateFailure_marksTheBatchAfterTheSingleReconcile() = runBlocking {
        reviews.failUpsert = true
        val batch = processor().startBatch()
        val first = sms(COLLIDING_TRANSFER_BODY, "2026-08-13T10:00:00Z", "collision-fail-1")
        val second = sms(COLLIDING_TRANSFER_BODY, "2026-08-13T11:00:00Z", "collision-fail-2")
        assertTrue(batch.ingest(first) is SmsIngestionResult.ReviewRequired)
        assertTrue(batch.ingest(second) is SmsIngestionResult.ReviewRequired)

        val finished = batch.finish()
        assertEquals(
            DerivedProcessingStage.REVIEW_UPDATE,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(1, parsedRepo.scopedLoadCalls)
        assertEquals(1, reviews.upserts)
        assertEquals(listOf(listOf(first.id, second.id)), retries.markedBatches)
        assertEquals(1, recoverySchedules)
        assertEquals(1, transactions.awaitingCalls)
    }

    @Test
    fun ownershipFailure_skipsReconciliationAndMarksFinancialRows() = runBlocking {
        cards.failObserve = true
        val batch = processor().startBatch()
        val purchase = ingestParsed(batch, purchase("51.99"), "2026-08-04T10:00:00Z", "purchase-own")
        val otp = sms(OTP_BODY, "2026-08-04T11:00:00Z", "otp-own")
        assertTrue(batch.ingest(otp) is SmsIngestionResult.NonFinancial)

        val finished = batch.finish()
        assertEquals(
            DerivedProcessingStage.OWNERSHIP_DISCOVERY,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(listOf(listOf(purchase)), retries.markedBatches)
        assertEquals(1, recoverySchedules)
        assertEquals(1, transactions.awaitingCalls)
    }

    @Test
    fun enrichmentFailure_keepsTheDerivedSuccess() = runBlocking {
        transactions.failAwaiting = true
        val batch = processor().startBatch()
        ingestParsed(batch, purchase("51.99"), "2026-08-05T10:00:00Z", "purchase-fx")

        val finished = batch.finish()
        assertTrue(finished is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(1, transactions.awaitingCalls)
        assertEquals(1, db.financialTransactionDao().count())
        assertTrue(retries.listRetryableRawSmsIds().isEmpty())
    }

    @Test
    fun markerWriteFailure_leavesTheBatchRetryable() = runBlocking {
        parsedRepo.failScopedLoad = true
        retries.failMark = true
        val batch = processor().startBatch()
        val first = ingestParsed(batch, purchase("51.99"), "2026-08-06T10:00:00Z", "purchase-mark-1")
        val second = ingestParsed(batch, purchase("12.50"), "2026-08-06T12:00:00Z", "purchase-mark-2")

        val failure = runCatching { batch.finish() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, recoverySchedules)
        assertTrue(retries.listRetryableRawSmsIds().isEmpty())
        assertEquals(0, transactions.awaitingCalls)

        retries.failMark = false
        val finished = batch.finish()
        assertEquals(
            DerivedProcessingStage.RECONCILIATION,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertEquals(
            listOf(first, second),
            retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertEquals(1, recoverySchedules)
        assertEquals(1, transactions.awaitingCalls)
    }

    @Test
    fun successfulFinish_clearsRetryMarkersForTheStoredBatch() = runBlocking {
        val batch = processor().startBatch()
        val purchase = ingestParsed(batch, purchase("51.99"), "2026-08-07T10:00:00Z", "purchase-clear")
        retries.markRequired(purchase, clock.now(), ProcessingRetryMode.HISTORICAL_BATCH)
        assertEquals(listOf(purchase), retries.listRetryableRawSmsIds())

        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
        assertTrue(retries.listRetryableRawSmsIds().isEmpty())
        assertEquals(1, transactions.awaitingCalls)
    }

    @Test
    fun finish_oneNewPurchase_doesNotLoadOrPostOlderHistory() = runBlocking {
        val parked = processor().startBatch()
        val older = ingestParsed(parked, purchase("9.00"), "2019-01-01T10:00:00Z", "old-purchase")
        val batch = processor().startBatch()
        val fresh = ingestParsed(batch, purchase("51.99"), "2026-08-01T10:00:00Z", "new-purchase")

        val finished = batch.finish()

        assertTrue(finished is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(0, parsedRepo.globalUnlinkedCalls)
        assertEquals(1, parsedRepo.scopedLoadCalls)
        assertEquals(FinancialTransactionType.EXPENSE, transactions.findByRawSmsId(fresh)!!.type)
        assertEquals(null, transactions.findByRawSmsId(older))
    }

    @Test
    fun finish_transferMatchesStoredCounterpartInsideMatcherWindow() = runBlocking {
        val accounts = RoomAccountRegistryRepository.from(db)
        val confirmation = OwnershipConfirmationService(
            accounts,
            RoomCardRegistryRepository.from(db),
            NoOpLoanRegistryRepository,
        )
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        val parked = processor().startBatch()
        val older = ingestParsed(parked, purchase("9.00"), "2019-01-01T10:00:00Z", "old-beside-transfer")
        val incoming = ingestParsed(parked, INCOMING_TRANSFER, "2026-08-27T04:36:00Z", "incoming")
        val batch = processor().startBatch()
        val outgoing = ingestParsed(batch, OUTGOING_TRANSFER, "2026-08-27T04:38:00Z", "outgoing")

        val finished = batch.finish()

        assertTrue(finished is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(0, parsedRepo.globalUnlinkedCalls)
        assertTrue(parsedRepo.scopedLoadCalls > 0)
        val posted = transactions.findByRawSmsId(outgoing)!!
        assertEquals(FinancialTransactionType.SELF_TRANSFER, posted.type)
        assertEquals(posted.id, transactions.findByRawSmsId(incoming)!!.id)
        assertTrue(transactions.listRawSmsIds(posted.id).containsAll(listOf(outgoing, incoming)))
        assertEquals(null, transactions.findByRawSmsId(older))
    }

    @Test
    fun emptyBatch_enrichesWithoutScanningHistory() = runBlocking {
        val finished = processor().startBatch().finish()
        assertTrue(finished is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(0, parsedRepo.listAllCalls)
        assertEquals(0, parsedRepo.scopedLoadCalls)
        assertEquals(1, transactions.awaitingCalls)
        assertEquals(0, reviews.upserts)
        assertEquals(0, recoverySchedules)
    }

    private fun processor(): HistoricalSmsBatchProcessor {
        val accounts = RoomAccountRegistryRepository.from(db)
        val realCards = RoomCardRegistryRepository.from(db)
        return HistoricalSmsBatchProcessor(
            capture = CaptureBankSmsUseCase(rawRepo, registry),
            processStored = ProcessStoredSmsUseCase(
                rawSmsRepository = rawRepo,
                parsedEventRepository = parsedRepo,
                bankSmsRegistry = registry,
            ),
            ownershipDiscovery = OwnershipDiscoveryService(accounts, cards, NoOpLoanRegistryRepository),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedRepo,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = transactions,
                ownershipResolver = OwnershipResolver(accounts, realCards, NoOpLoanRegistryRepository),
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviews, transactions, clock),
            exchangeRateEnrichment = ExchangeRateEnrichmentWorkflow(
                financialTransactionRepository = transactions,
                parsedEventRepository = parsedRepo,
                rawSmsRepository = rawRepo,
                sarEquivalentResolver = TransactionSarEquivalentResolver(
                    marketRateProvider = { _, _ -> null },
                ),
            ),
            processingRecovery = ProcessingRecovery(
                processingRetryRepository = retries,
                reviewRepository = reviews,
                ingestionReviewService = IngestionReviewService(reviews, clock),
                clock = clock,
            ),
            batchRecoveryScheduler = { recoverySchedules += 1 },
        )
    }

    private suspend fun ingestParsed(
        batch: HistoricalSmsBatchProcessor.Batch,
        body: String,
        at: String,
        providerId: String,
    ): String {
        val result = batch.ingest(sms(body, at, providerId))
        assertTrue(result is SmsIngestionResult.Parsed)
        return (result as SmsIngestionResult.Parsed).rawSmsId
    }

    private fun sms(body: String, at: String, providerId: String): RawSms =
        AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(providerId, "AlJazira", body, Instant.parse(at)),
        )

    private fun purchase(amount: String) = """
        شراء عبر الانترنت
        بطاقة: 7271
        لدى: Keeta
        بمبلغ: $amount SAR
        في: 14:32 03-08-2026
    """.trimIndent()

    private class CountingParsedEvents(
        private val delegate: ParsedEventRepository,
    ) : ParsedEventRepository by delegate {
        var listAllCalls: Int = 0
        var scopedLoadCalls: Int = 0
        var globalUnlinkedCalls: Int = 0
        var failScopedLoad: Boolean = false

        override suspend fun listAll(): List<ParsedEventRecord> {
            listAllCalls += 1
            return delegate.listAll()
        }

        override suspend fun listUnlinkedTransfers(): List<ParsedEventRecord> {
            globalUnlinkedCalls += 1
            return delegate.listUnlinkedTransfers()
        }

        override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> {
            scopedLoadCalls += 1
            if (failScopedLoad) throw IOException("reconciliation unavailable")
            return delegate.listByRawSmsIds(rawSmsIds)
        }
    }

    private class CountingTransactions(
        private val delegate: FinancialTransactionRepository,
    ) : FinancialTransactionRepository by delegate {
        var awaitingCalls: Int = 0
        var failAwaiting: Boolean = false
        var conflictSaves: Boolean = false

        override suspend fun save(
            transaction: FinancialTransaction,
            rawSmsIds: Collection<String>,
        ): FinancialTransactionSaveResult {
            if (conflictSaves) {
                return FinancialTransactionSaveResult.Conflict(
                    rawSmsId = rawSmsIds.first(),
                    existingTransactionId = "conflict-existing",
                )
            }
            return delegate.save(transaction, rawSmsIds)
        }

        override suspend fun listAwaitingAppliedExchangeRate(
            primaryCurrency: Currency,
        ): List<FinancialTransaction> {
            awaitingCalls += 1
            if (failAwaiting) throw IOException("rate lookup unavailable")
            return delegate.listAwaitingAppliedExchangeRate(primaryCurrency)
        }
    }

    private class CountingCards(
        private val delegate: CardRegistryRepository,
    ) : CardRegistryRepository by delegate {
        val observed = mutableListOf<String>()
        var failObserve: Boolean = false

        override suspend fun observe(reference: CardReference, rawSmsId: String) {
            if (failObserve) throw IOException("ownership unavailable")
            observed += rawSmsId
            delegate.observe(reference, rawSmsId)
        }
    }

    private class CountingReviews(
        private val delegate: ReviewRepository,
    ) : ReviewRepository by delegate {
        var upserts: Int = 0
        val upsertedRawSmsIds = mutableListOf<String>()
        var failUpsert: Boolean = false

        override suspend fun upsertRequired(
            rawSmsId: String,
            kind: ReviewKind,
            reasons: List<String>,
            now: Instant,
        ): ReviewItem {
            upserts += 1
            upsertedRawSmsIds += rawSmsId
            if (failUpsert) throw IOException("review update failed")
            return delegate.upsertRequired(rawSmsId, kind, reasons, now)
        }
    }

    private class CountingRetries(
        private val delegate: ProcessingRetryRepository,
    ) : ProcessingRetryRepository by delegate {
        val markedBatches = mutableListOf<List<String>>()
        var failMark: Boolean = false

        override suspend fun markRequired(
            rawSmsIds: List<String>,
            createdAt: Instant,
            mode: ProcessingRetryMode,
        ) {
            markedBatches += rawSmsIds
            if (failMark) throw IOException("marker write failed")
            delegate.markRequired(rawSmsIds, createdAt, mode)
        }
    }

    companion object {
        private const val OTP_BODY =
            "رمز التحقق لعملية شراء عبر الانترنت: 482913\nبمبلغ: 250.00 SAR\nلدى: TEST_STORE\nلا تشارك الرمز مع أحد"

        private val COLLIDING_TRANSFER_BODY = """
            حوالة واردة
            حوالة صادرة
            مبلغ: SAR 50.00
            في: 2026-08-05 11:00
        """.trimIndent()

        private val OUTGOING_TRANSFER = """
            حوالة صادرة الى حسابك الجاري
            من: 3001
            مبلغ: SAR 5,500.00
            إلى: 3002
            في: 2026-08-27 07:36
        """.trimIndent()

        private val INCOMING_TRANSFER = """
            حوالة واردة داخلية
            مبلغ: SAR 5,500.00
            إلى: 3002
            اسم المرسل: براء بخش
            رقم حساب المرسل: 3001
            البنك المرسل: بنك الجزيرة
            في: 2026-08-27 07:36
        """.trimIndent()
    }
}
