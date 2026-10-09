package com.baraa.masroof.application.ingestion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.parser.SmsParseGateway
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessStoredSmsUseCaseTest {
    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var registry: BankSmsRegistry
    private lateinit var processStored: ProcessStoredSmsUseCase
    private lateinit var capture: CaptureBankSmsUseCase
    private val parseCalls = AtomicInteger(0)
    private val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        val cards = RoomCardRegistryRepository.from(db)
        cards.setOwnership(CardReference(Bank.BANK_ALJAZIRA, "7271"), OwnershipStatus.OWNED)
        parseCalls.set(0)
        registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter(pipeline = countingGateway())))
        processStored = ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = registry,
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedRepo,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = ftRepo,
                ownershipResolver = OwnershipResolver(
                    RoomAccountRegistryRepository.from(db),
                    cards,
                    NoOpLoanRegistryRepository,
                ),
                effectiveParsedEventProvider = EffectiveParsedEventProvider(
                    parsedRepo,
                    RoomUserCorrectionRepository(db.userCorrectionDao()),
                ),
                reviewRepository = reviewRepo,
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, clock),
            ingestionReviewService = IngestionReviewService(reviewRepo, clock),
        )
        capture = CaptureBankSmsUseCase(rawRepo, registry)
        Unit
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun processById_runsParseAndDerivedProcessingForCapturedEvidence() = runBlocking {
        val raw = sms(PURCHASE_BODY)
        assertTrue(capture.capture(raw) is BankSmsCaptureResult.Captured)
        assertEquals(0, parseCalls.get())

        val result = processStored.process(raw.id)

        assertTrue(result is SmsIngestionResult.Parsed)
        assertEquals(ParseStatus.SUCCESS, parsedRepo.findByRawSmsId(raw.id)!!.event.parseStatus)
        assertEquals(FinancialTransactionType.EXPENSE, ftRepo.findByRawSmsId(raw.id)!!.type)
        assertEquals(1, parseCalls.get())
    }

    @Test
    fun parseAndStore_persistsParsedEvent_withoutDerivedProcessing() = runBlocking {
        val raw = sms(PURCHASE_BODY)
        val captured = capture.capture(raw) as BankSmsCaptureResult.Captured

        val result = processStored.parseAndStore(captured.rawSms, captured.route)

        assertTrue(result is SmsIngestionResult.Parsed)
        assertEquals(ParseStatus.SUCCESS, parsedRepo.findByRawSmsId(raw.id)!!.event.parseStatus)
        assertNull(ftRepo.findByRawSmsId(raw.id))
        assertTrue(reviewRepo.listAll().isEmpty())
    }

    @Test
    fun processById_retried_isIdempotent() = runBlocking {
        val raw = sms(PURCHASE_BODY)
        capture.capture(raw)

        val first = processStored.process(raw.id)
        val transaction = ftRepo.findByRawSmsId(raw.id)
        val second = processStored.process(raw.id)
        val third = processStored.process(raw.id)

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(1, db.parsedEventDao().count())
        assertEquals(1, db.financialTransactionDao().count())
        assertEquals(transaction, ftRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun processById_ambiguousEvidence_leavesOneDurableReview_idempotently() = runBlocking {
        val ambiguous = BankSmsRegistry(listOf(AlJaziraSmsAdapter(pipeline = countingGateway()), LookalikeAdapter()))
        val svc = ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = ambiguous,
            ingestionReviewService = IngestionReviewService(reviewRepo, clock),
        )
        val raw = sms(PURCHASE_BODY)
        assertTrue(CaptureBankSmsUseCase(rawRepo, ambiguous).capture(raw) is BankSmsCaptureResult.Captured)

        assertTrue(svc.process(raw.id) is SmsIngestionResult.ReviewRequired)
        assertTrue(svc.process(raw.id) is SmsIngestionResult.ReviewRequired)

        val reviews = reviewRepo.listAll().filter { it.rawSmsId == raw.id }
        assertEquals(1, reviews.size)
        assertEquals(listOf(IngestionReviewService.REASON_AMBIGUOUS_BANK_ROUTE), reviews.single().reasons)
        assertNull(parsedRepo.findByRawSmsId(raw.id))
        assertEquals(0, parseCalls.get())
    }

    private class LookalikeAdapter : BankSmsAdapter {
        override val bank: Bank = Bank("LOOKALIKE_BANK")
        override fun detect(sender: String, body: String): BankDetectionResult =
            BankDetectionResult.Detected(bank = bank, confidence = Confidence(1.0), evidence = listOf("lookalike"))
        override fun parse(input: SmsParseInput): ParseResult = ParseResult.Unsupported(reason = "lookalike")
    }

    @Test
    fun processById_missingRow_isFailedWithoutSideEffects() = runBlocking {
        val result = processStored.process("android-sms:missing")

        assertEquals(
            SmsIngestionResult.Failed(
                rawSmsId = "android-sms:missing",
                message = ProcessStoredSmsUseCase.REASON_RAW_SMS_NOT_FOUND,
            ),
            result,
        )
        assertEquals(0, parseCalls.get())
        assertTrue(reviewRepo.listAll().isEmpty())
    }

    @Test
    fun captureThenProcess_matchesFacadeEndToEnd() = runBlocking {
        val split = sms(PURCHASE_BODY, at = "2026-08-03T14:32:00Z")
        val viaFacade = sms(PURCHASE_BODY.replace("51.99", "61.99"), at = "2026-08-03T15:32:00Z")
        val facade = ProcessRawSmsUseCase(capture, processStored)

        val captured = capture.capture(split) as BankSmsCaptureResult.Captured
        val splitResult = processStored.process(captured.rawSms, captured.route) as SmsIngestionResult.Parsed
        val facadeResult = facade.ingest(viaFacade) as SmsIngestionResult.Parsed

        assertEquals(splitResult.event.messageFamily, facadeResult.event.messageFamily)
        assertEquals(splitResult.event.parseStatus, facadeResult.event.parseStatus)
        assertEquals(ftRepo.findByRawSmsId(split.id)!!.type, ftRepo.findByRawSmsId(viaFacade.id)!!.type)
        assertEquals(SmsIngestionResult.Duplicate, facade.ingest(split))
        assertEquals(2, parseCalls.get())
    }

    @Test
    fun nonBankEvidence_isNotRelevant_whenRegistryCannotClaimIt() = runBlocking {
        val raw = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, "Mom", "See you at 6", Instant.parse("2026-08-03T10:00:00Z")),
        )
        rawRepo.insertIfAbsent(raw)
        val multiBank = ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = BankSmsRegistry(
                listOf(AlJaziraSmsAdapter(), AlJaziraSmsAdapter()),
            ),
        )

        assertTrue(multiBank.process(raw.id) is SmsIngestionResult.NotRelevant)
        assertNull(parsedRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun liveProcess_doesNotScanGlobalTransferLists() = runBlocking {
        val countedParsed = CountingParsedEvents(parsedRepo)
        val countedTransactions = CountingTransactions(ftRepo)
        val cards = RoomCardRegistryRepository.from(db)
        val live = ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = countedParsed,
            bankSmsRegistry = registry,
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = countedParsed,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = countedTransactions,
                ownershipResolver = OwnershipResolver(
                    RoomAccountRegistryRepository.from(db),
                    cards,
                    NoOpLoanRegistryRepository,
                ),
                effectiveParsedEventProvider = EffectiveParsedEventProvider(
                    countedParsed,
                    RoomUserCorrectionRepository(db.userCorrectionDao()),
                ),
                reviewRepository = reviewRepo,
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, countedTransactions, clock),
            ingestionReviewService = IngestionReviewService(reviewRepo, clock),
        )
        repeat(12) { index ->
            rawRepo.insertIfAbsent(
                sms("old transfer $index", at = "2020-01-01T00:00:${index.coerceAtMost(59).toString().padStart(2, '0')}Z"),
            )
        }
        val raw = sms(PURCHASE_BODY)
        assertTrue(capture.capture(raw) is BankSmsCaptureResult.Captured)
        countedParsed.reset()
        countedTransactions.reset()

        val result = live.process(raw.id)

        assertTrue(result is SmsIngestionResult.Parsed)
        assertEquals(FinancialTransactionType.EXPENSE, ftRepo.findByRawSmsId(raw.id)!!.type)
        assertEquals(0, countedParsed.globalUnlinkedCalls)
        assertEquals(0, countedTransactions.listByTypesCalls)
    }

    private class CountingParsedEvents(
        private val delegate: ParsedEventRepository,
    ) : ParsedEventRepository by delegate {
        var globalUnlinkedCalls: Int = 0

        fun reset() {
            globalUnlinkedCalls = 0
        }

        override suspend fun listUnlinkedTransfers(): List<ParsedEventRecord> {
            globalUnlinkedCalls += 1
            return delegate.listUnlinkedTransfers()
        }
    }

    private class CountingTransactions(
        private val delegate: FinancialTransactionRepository,
    ) : FinancialTransactionRepository by delegate {
        var listByTypesCalls: Int = 0

        fun reset() {
            listByTypesCalls = 0
        }

        override suspend fun listByTypes(
            types: Collection<FinancialTransactionType>,
        ): List<FinancialTransaction> {
            listByTypesCalls += 1
            return delegate.listByTypes(types)
        }
    }

    private fun countingGateway() = SmsParseGateway { input ->
        parseCalls.incrementAndGet()
        AlJaziraParsingPipeline().parse(input)
    }

    private fun sms(body: String, at: String = "2026-08-03T14:32:00Z"): RawSms =
        AndroidSmsMapper.toRawSms(ProviderSmsRecord(null, "AlJazira", body, Instant.parse(at)))

    private companion object {
        val PURCHASE_BODY = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
        """.trimIndent()
    }
}
