package com.baraa.masroof.application.ingestion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.parser.SmsParseGateway
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessRawSmsUseCaseTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var useCase: ProcessRawSmsUseCase
    private val parseCalls = AtomicInteger(0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        parseCalls.set(0)
        val countingGateway = SmsParseGateway { input ->
            parseCalls.incrementAndGet()
            AlJaziraParsingPipeline().parse(input)
        }
        useCase = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(pipeline = countingGateway),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun firstAlJaziraMessage_insertsAndPersistsParsedEvent() = runBlocking {
        val raw = aljaziraPurchase(id = "android-sms:1", deviceId = "1")
        val result = useCase.ingest(raw)
        assertTrue(result is SmsIngestionResult.Parsed)
        assertEquals(raw, rawRepo.getById(raw.id))
        val record = parsedRepo.findByRawSmsId(raw.id)!!
        assertEquals(MessageFamily.PURCHASE, record.event.messageFamily)
        assertEquals(ParseStatus.SUCCESS, record.event.parseStatus)
        assertEquals(Money.of("51.99", Currency.SAR), record.event.amount)
        assertEquals(1, parseCalls.get())
    }

    @Test
    fun duplicate_doesNotReparseOrDuplicateEvent() = runBlocking {
        val raw = aljaziraPurchase(id = "android-sms:2", deviceId = "2")
        assertTrue(useCase.ingest(raw) is SmsIngestionResult.Parsed)
        assertEquals(1, parseCalls.get())
        assertEquals(SmsIngestionResult.Duplicate, useCase.ingest(raw))
        assertEquals(1, parseCalls.get())
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(1, db.parsedEventDao().count())
    }

    @Test
    fun reviewRequired_persistsEvent() = runBlocking {
        val body = "تنبيه بنك الجزيرة: حدث تحديث في خدماتك. راجع التطبيق للتفاصيل."
        val raw = RawSms(
            id = "android-sms:3",
            sender = "AlJazira",
            body = body,
            receivedAt = Instant.parse("2026-08-03T08:00:00Z"),
            deviceMessageId = "3",
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
        val result = useCase.ingest(raw)
        assertTrue(result is SmsIngestionResult.ReviewRequired)
        assertEquals(
            ParseStatus.REVIEW_REQUIRED,
            parsedRepo.findByRawSmsId(raw.id)!!.event.parseStatus,
        )
    }

    @Test
    fun nonFinancial_persistsEvent() = runBlocking {
        val body = "رمز التحقق الخاص بك هو 482911. لا تشاركه مع أي شخص."
        val raw = RawSms(
            id = "android-sms:4",
            sender = "AlJazira",
            body = body,
            receivedAt = Instant.parse("2026-08-03T08:00:00Z"),
            deviceMessageId = "4",
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
        val result = useCase.ingest(raw)
        assertTrue(result is SmsIngestionResult.NonFinancial)
        assertEquals(ParseStatus.NON_FINANCIAL, parsedRepo.findByRawSmsId(raw.id)!!.event.parseStatus)
        assertNull(parsedRepo.findByRawSmsId(raw.id)!!.event.amount)
    }

    @Test
    fun nearMissSender_notPersisted() = runBlocking {
        listOf("JaziraNews", "NotAlJazira", "OtherBank").forEach { sender ->
            val body = "شراء عبر الانترنت بمبلغ: 10.00 SAR"
            val raw = RawSms(
                id = "android-sms-live:$sender",
                sender = sender,
                body = body,
                receivedAt = Instant.parse("2026-08-03T08:00:00Z"),
                deviceMessageId = null,
                bodyHash = SmsBodyHasher.sha256Hex(body),
            )
            val result = useCase.ingest(raw)
            assertTrue(result is SmsIngestionResult.NotRelevant)
            assertEquals(
                "sender_not_recognized_as_bank_aljazira",
                (result as SmsIngestionResult.NotRelevant).reason,
            )
            assertNull(rawRepo.getById(raw.id))
        }
        assertEquals(0, db.rawSmsDao().count())
        assertEquals(0, parseCalls.get())
    }

    @Test
    fun reparseStored_alJaziraPurchase_runsParserAgain() = runBlocking {
        val raw = aljaziraPurchase(id = "android-sms:reparse", deviceId = "reparse")
        assertTrue(useCase.ingest(raw) is SmsIngestionResult.Parsed)
        parseCalls.set(0)
        val result = useCase.reparseStored(raw)
        assertTrue(result is SmsIngestionResult.Parsed)
        assertEquals(Money.of("51.99", Currency.SAR), (result as SmsIngestionResult.Parsed).event.amount)
        assertEquals(MessageFamily.PURCHASE, result.event.messageFamily)
        assertEquals(1, parseCalls.get())
    }

    @Test
    fun reparseStored_doesNotReapplyIngestDetection() = runBlocking {
        val exploding = SmsParseGateway { throw IllegalStateException("reparse-boom") }
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(pipeline = exploding),
        )
        val raw = RawSms(
            id = "android-sms:other-bank-stored",
            sender = "OtherBank",
            body = "شراء عبر الانترنت بمبلغ: 10.00 SAR",
            receivedAt = Instant.parse("2026-08-03T08:00:00Z"),
            deviceMessageId = "other-bank-stored",
            bodyHash = SmsBodyHasher.sha256Hex("شراء عبر الانترنت بمبلغ: 10.00 SAR"),
        )
        rawRepo.insertIfAbsent(raw)
        parsedRepo.save(
            ParsedEvent(
                id = "evt-other-bank-stored",
                rawSmsId = raw.id,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.PURCHASE,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of("10.00", Currency.SAR),
                purchaseChannel = null,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = null,
                merchant = null,
                counterparty = null,
                occurredAt = null,
                bankNetworkType = null,
                confidence = Confidence(score = 1.0),
                parseStatus = ParseStatus.SUCCESS,
            ),
        )
        val result = svc.reparseStored(raw)
        assertTrue(result is SmsIngestionResult.Failed)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertNotNull(parsedRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun reparseStored_rawSmsWithoutParsedEvent_stillInvokesParser() = runBlocking {
        val exploding = SmsParseGateway { throw IllegalStateException("reparse-no-event-boom") }
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(pipeline = exploding),
        )
        val raw = RawSms(
            id = "android-sms:unsupported-stored",
            sender = "OtherBank",
            body = "شراء عبر الانترنت بمبلغ: 10.00 SAR",
            receivedAt = Instant.parse("2026-08-03T08:00:00Z"),
            deviceMessageId = "unsupported-stored",
            bodyHash = SmsBodyHasher.sha256Hex("شراء عبر الانترنت بمبلغ: 10.00 SAR"),
        )
        rawRepo.insertIfAbsent(raw)
        val result = svc.reparseStored(raw)
        assertTrue(result is SmsIngestionResult.Failed)
        assertTrue(result !is SmsIngestionResult.NotRelevant)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertNull(parsedRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun reparseStored_recoversRawSmsThatNeverHadParsedEvent() = runBlocking {
        val raw = aljaziraPurchase(id = "android-sms:recover", deviceId = "recover")
        rawRepo.insertIfAbsent(raw)
        assertNull(parsedRepo.findByRawSmsId(raw.id))
        val result = useCase.reparseStored(raw)
        assertTrue(result is SmsIngestionResult.Parsed)
        assertEquals(ParseStatus.SUCCESS, parsedRepo.findByRawSmsId(raw.id)!!.event.parseStatus)
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(1, db.parsedEventDao().count())
        useCase.reparseStored(raw)
        assertEquals(1, db.parsedEventDao().count())
    }

    @Test
    fun parserFailure_keepsRawSmsAndReturnsFailed() = runBlocking {
        val exploding = SmsParseGateway { throw IllegalStateException("boom") }
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(pipeline = exploding),
        )
        val raw = aljaziraPurchase(id = "android-sms:fail", deviceId = "fail")
        val result = svc.ingest(raw)
        assertTrue(result is SmsIngestionResult.Failed)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertNull(parsedRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun parserCancellation_propagates() = runBlocking {
        val cancelling = SmsParseGateway { throw CancellationException("cancel") }
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(pipeline = cancelling),
        )
        val raw = aljaziraPurchase(id = "android-sms:cancel", deviceId = "cancel")
        try {
            svc.ingest(raw)
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(raw, rawRepo.getById(raw.id))
    }

    @Test
    fun parsedEventSaveFailure_returnsFailed_keepsRawSms() = runBlocking {
        val failingParsedRepo = object : ParsedEventRepository {
            override suspend fun save(event: ParsedEvent, details: ParsedEventDetails) {
                throw IllegalStateException("save failed")
            }

            override suspend fun getById(id: String): ParsedEventRecord? = null
            override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord? = null
            override suspend fun deleteByRawSmsId(rawSmsId: String) = Unit
            override suspend fun listAll(): List<ParsedEventRecord> = emptyList()

            override suspend fun listReceivedBetween(
                startInclusive: java.time.Instant,
                endExclusive: java.time.Instant,
            ): List<ParsedEventRecord> = emptyList()
        }
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = failingParsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(),
        )
        val raw = aljaziraPurchase(id = "android-sms:save-fail", deviceId = "save-fail")
        val result = svc.ingest(raw)
        assertTrue(result is SmsIngestionResult.Failed)
        assertEquals(raw, rawRepo.getById(raw.id))
    }

    @Test
    fun liveThenHistorical_withSlightlyDifferentMillis_dedupes() = runBlocking {
        val body = transferBody()
        val liveAt = Instant.parse("2026-08-10T12:00:00.000Z")
        val historicalAt = Instant.parse("2026-08-10T12:00:02.000Z")
        val live = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, "AlJazira", body, liveAt),
        )
        val historical = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("999", "AlJazira", body, historicalAt),
        )
        assertTrue(live.id != historical.id)
        assertEquals(live.bodyHash, historical.bodyHash)
        assertTrue(useCase.ingest(live) is SmsIngestionResult.Parsed)
        assertEquals(SmsIngestionResult.Duplicate, useCase.ingest(historical))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(1, parseCalls.get())
    }

    @Test
    fun twoHistoricalNearby_sameBody_areNotCollapsedByTolerance() = runBlocking {
        val body = transferBody()
        val a = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("10", "AlJazira", body, Instant.parse("2026-08-10T12:00:00Z")),
        )
        val b = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("11", "AlJazira", body, Instant.parse("2026-08-10T12:00:02Z")),
        )
        assertTrue(useCase.ingest(a) is SmsIngestionResult.Parsed)
        assertTrue(useCase.ingest(b) is SmsIngestionResult.Parsed)
        assertEquals(2, db.rawSmsDao().count())
        assertEquals(2, parseCalls.get())
    }

    @Test
    fun twoLiveNearby_sameBody_areNotCollapsedByCrossSourceTolerance() = runBlocking {
        val body = transferBody()
        val a = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, "AlJazira", body, Instant.parse("2026-08-10T12:00:00Z")),
        )
        val b = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, "AlJazira", body, Instant.parse("2026-08-10T12:00:02Z")),
        )
        assertTrue(useCase.ingest(a) is SmsIngestionResult.Parsed)
        assertTrue(useCase.ingest(b) is SmsIngestionResult.Parsed)
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun liveReceivedAt_usesReceiptClockNotSmsc() {
        val fixed = Instant.parse("2026-08-10T15:30:00Z")
        val clock = InstantClock { fixed }
        assertEquals(fixed, clock.now())
        // IncomingSmsReceiver uses AppContainer.clock; assembler does not touch SMSC time.
        val assembled = com.baraa.masroof.sms.receiver.ReceivedSmsAssembler.assemble(
            listOf(
                com.baraa.masroof.sms.receiver.ReceivedSmsAssembler.Part("AlJazira", "body"),
            ),
        )!!
        val raw = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, assembled.sender, assembled.body, clock.now()),
        )
        assertEquals(fixed, raw.receivedAt)
    }

    @Test
    fun p8DerivedSaveException_keepsSuccessfulEvidenceAndDoesNotFailIngest() = runBlocking {
        val throwingFtRepo = object : FinancialTransactionRepository {
            override suspend fun save(
                transaction: FinancialTransaction,
                rawSmsIds: Collection<String>,
            ): FinancialTransactionSaveResult {
                throw IllegalStateException("p8-save-boom")
            }

            override suspend fun getById(id: String): FinancialTransaction? = null
            override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? = null
            override suspend fun listAll(): List<FinancialTransaction> = emptyList()
            override suspend fun listOccurredBetween(
                startInclusive: java.time.Instant,
                endExclusive: java.time.Instant,
            ): List<FinancialTransaction> = emptyList()
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
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = throwingFtRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
        )
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(),
            reconciliation = reconciliation,
        )
        val raw = aljaziraPurchase(id = "android-sms:p8-fail", deviceId = "p8-fail")
        val result = svc.ingest(raw)
        assertTrue(result is SmsIngestionResult.Parsed)
        assertNotNull(rawRepo.getById(raw.id))
        assertNotNull(parsedRepo.findByRawSmsId(raw.id))
        assertEquals(MessageFamily.PURCHASE, parsedRepo.findByRawSmsId(raw.id)!!.event.messageFamily)
    }

    @Test
    fun p9ReviewQueueException_keepsEvidenceAndDoesNotFailIngest() = runBlocking {
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val ftRepo = RoomFinancialTransactionRepository(
            db.financialTransactionDao(),
            db.parsedEventDao(),
        )
        val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
        )
        val throwingReviews = object : ReviewRepository {
            override suspend fun getById(id: String): ReviewItem? = null
            override suspend fun findByRawSmsId(rawSmsId: String): ReviewItem? = null
            override suspend fun listRequired(): List<ReviewItem> = emptyList()
            override suspend fun listIgnored(): List<ReviewItem> = emptyList()
            override suspend fun listAll(): List<ReviewItem> = emptyList()
            override suspend fun upsertRequired(
                rawSmsId: String,
                kind: ReviewKind,
                reasons: List<String>,
                now: Instant,
            ): ReviewItem {
                throw IllegalStateException("p9-review-boom")
            }

            override suspend fun markResolved(
                id: String,
                resolutionKind: ReviewResolutionKind,
                resolvedAt: Instant,
                resolvedTransactionId: String?,
            ): ReviewItem? = null
        }
        val updater = ReviewQueueUpdater(
            reviewRepository = throwingReviews,
            financialTransactionRepository = ftRepo,
            clock = InstantClock.System,
        )
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(),
            reconciliation = reconciliation,
            reviewQueueUpdater = updater,
        )
        val raw = aljaziraBillPayment(id = "android-sms:p9-fail", deviceId = "p9-fail")
        val result = svc.ingest(raw)
        assertTrue(result is SmsIngestionResult.Parsed)
        assertNotNull(rawRepo.getById(raw.id))
        assertNotNull(parsedRepo.findByRawSmsId(raw.id))
        assertEquals(MessageFamily.BILL_PAYMENT, parsedRepo.findByRawSmsId(raw.id)!!.event.messageFamily)
        assertEquals(FinancialTransactionType.BILL_PAYMENT, ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun p9ReviewCancellation_propagatesOutOfIngestion() = runBlocking {
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val ftRepo = RoomFinancialTransactionRepository(
            db.financialTransactionDao(),
            db.parsedEventDao(),
        )
        val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
        )
        val cancellingReviews = object : ReviewRepository {
            override suspend fun getById(id: String): ReviewItem? = null
            override suspend fun findByRawSmsId(rawSmsId: String): ReviewItem? = null
            override suspend fun listRequired(): List<ReviewItem> = emptyList()
            override suspend fun listIgnored(): List<ReviewItem> = emptyList()
            override suspend fun listAll(): List<ReviewItem> = emptyList()
            override suspend fun upsertRequired(
                rawSmsId: String,
                kind: ReviewKind,
                reasons: List<String>,
                now: Instant,
            ): ReviewItem {
                throw CancellationException("p9-cancel")
            }

            override suspend fun markResolved(
                id: String,
                resolutionKind: ReviewResolutionKind,
                resolvedAt: Instant,
                resolvedTransactionId: String?,
            ): ReviewItem? = null
        }
        val updater = ReviewQueueUpdater(
            reviewRepository = cancellingReviews,
            financialTransactionRepository = ftRepo,
            clock = InstantClock.System,
        )
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(),
            reconciliation = reconciliation,
            reviewQueueUpdater = updater,
        )
        val raw = RawSms(
            id = "android-sms:p9-cancel",
            sender = "AlJazira",
            body = transferBody(),
            receivedAt = Instant.parse("2026-08-01T12:26:00Z"),
            deviceMessageId = "p9-cancel",
            bodyHash = SmsBodyHasher.sha256Hex(transferBody()),
        )
        try {
            svc.ingest(raw)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertNotNull(rawRepo.getById(raw.id))
        assertNotNull(parsedRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun p8DerivedCancellation_propagatesOutOfIngestion() = runBlocking {
        val cancellingFtRepo = object : FinancialTransactionRepository {
            override suspend fun save(
                transaction: FinancialTransaction,
                rawSmsIds: Collection<String>,
            ): FinancialTransactionSaveResult {
                throw CancellationException("p8-cancel")
            }

            override suspend fun getById(id: String): FinancialTransaction? = null
            override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? = null
            override suspend fun listAll(): List<FinancialTransaction> = emptyList()
            override suspend fun listOccurredBetween(
                startInclusive: java.time.Instant,
                endExclusive: java.time.Instant,
            ): List<FinancialTransaction> = emptyList()
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
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = cancellingFtRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
        )
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(),
            reconciliation = reconciliation,
        )
        val raw = aljaziraPurchase(id = "android-sms:p8-cancel", deviceId = "p8-cancel")
        try {
            svc.ingest(raw)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected — must not become SmsIngestionResult.Failed
        }
        // Evidence persisted before cancellation remains.
        assertNotNull(rawRepo.getById(raw.id))
        assertNotNull(parsedRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun multipartBodies_joinIntoOneRawSms() = runBlocking {
        val parts = listOf(
            "شراء عبر الانترنت\n",
            "بطاقة: 7271\n",
            "لدى: Keeta\n",
            "بمبلغ: 51.99 SAR\n",
            "في: 14:32 03-08-2026",
        )
        val combined = parts.joinToString("")
        val raw = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, "AlJazira", combined, Instant.parse("2026-08-03T14:32:00Z")),
        )
        assertEquals(combined, raw.body)
        val result = useCase.ingest(raw) as SmsIngestionResult.Parsed
        assertEquals(Money.of("51.99", Currency.SAR), result.event.amount)
        assertEquals("7271", result.event.cardRef?.last4)
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun reviewRequiredPurchaseWithAmount_persistsEvidenceAndReview_butNoTransaction() = runBlocking {
        val reviewRequiredGateway = SmsParseGateway { input ->
            val parsed = AlJaziraParsingPipeline().parse(input) as com.baraa.masroof.parsing.model.ParseResult.Success
            val gated = parsed.event.copy(parseStatus = ParseStatus.REVIEW_REQUIRED)
            com.baraa.masroof.parsing.model.ParseResult.ReviewRequired(
                draft = null,
                event = gated,
                findings = emptyList(),
                reasons = listOf("forced_low_confidence"),
                details = parsed.details,
            )
        }
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        cards.setOwnership(com.baraa.masroof.domain.model.CardReference(Bank.BANK_ALJAZIRA, "7271"), com.baraa.masroof.domain.model.OwnershipStatus.OWNED)
        val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(pipeline = reviewRequiredGateway),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedRepo,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = ftRepo,
                ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, InstantClock.System),
        )
        val raw = aljaziraPurchase(id = "android-sms:rr-amount", deviceId = "rr-amount")
        val result = svc.ingest(raw)
        assertTrue(result is SmsIngestionResult.ReviewRequired)
        assertEquals(Money.of("51.99", Currency.SAR), parsedRepo.findByRawSmsId(raw.id)!!.event.amount)
        assertNull(ftRepo.findByRawSmsId(raw.id))
        val review = reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(com.baraa.masroof.domain.model.ReviewStatus.REQUIRED, review.status)
        assertTrue(review.reasons.contains("parse_review_required"))
    }

    @Test
    fun successPurchase_stillAssemblesExpense() = runBlocking {
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        cards.setOwnership(com.baraa.masroof.domain.model.CardReference(Bank.BANK_ALJAZIRA, "7271"), com.baraa.masroof.domain.model.OwnershipStatus.OWNED)
        val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = alJaziraSmsRegistry(),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedRepo,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = ftRepo,
                ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
            ),
        )
        val raw = aljaziraPurchase(id = "android-sms:ok-amount", deviceId = "ok-amount")
        assertTrue(svc.ingest(raw) is SmsIngestionResult.Parsed)
        assertEquals(FinancialTransactionType.EXPENSE, ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    private fun reviewingUseCase(
        pipeline: SmsParseGateway,
        reviewRepo: com.baraa.masroof.data.repository.RoomReviewRepository,
    ) = ProcessRawSmsUseCase(
        rawSmsRepository = rawRepo,
        parsedEventRepository = parsedRepo,
        bankSmsRegistry = alJaziraSmsRegistry(pipeline = pipeline),
        ingestionReviewService = com.baraa.masroof.application.review.IngestionReviewService(
            reviewRepo,
            InstantClock { Instant.parse("2026-08-03T15:00:00Z") },
        ),
    )

    private fun assertDirectReview(
        reviewRepo: com.baraa.masroof.data.repository.RoomReviewRepository,
        rawSmsId: String,
        reason: String,
    ) = runBlocking {
        val review = reviewRepo.findByRawSmsId(rawSmsId)
        assertNotNull("expected review for $rawSmsId", review)
        assertEquals(com.baraa.masroof.domain.model.ReviewStatus.REQUIRED, review!!.status)
        assertEquals(ReviewKind.NEEDS_REVIEW, review.kind)
        assertEquals(listOf(reason), review.reasons)
    }

    @Test
    fun recognizedBankUnsupported_retainsRawSmsAndCreatesRequiredReview() = runBlocking {
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val svc = reviewingUseCase(
            SmsParseGateway { com.baraa.masroof.parsing.model.ParseResult.Unsupported("unsupported_test") },
            reviewRepo,
        )
        val raw = aljaziraPurchase(id = "android-sms:unsupported", deviceId = "unsupported")
        val result = svc.ingest(raw)
        assertTrue(result is SmsIngestionResult.Unsupported)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertNull(parsedRepo.findByRawSmsId(raw.id))
        assertDirectReview(reviewRepo, raw.id, "unsupported_bank_message_format")
    }

    @Test
    fun recognizedBankInvalid_retainsRawSmsAndCreatesRequiredReview() = runBlocking {
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val svc = reviewingUseCase(
            SmsParseGateway { com.baraa.masroof.parsing.model.ParseResult.Invalid(findings = emptyList()) },
            reviewRepo,
        )
        val raw = aljaziraPurchase(id = "android-sms:invalid", deviceId = "invalid")
        assertTrue(svc.ingest(raw) is SmsIngestionResult.Invalid)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertDirectReview(reviewRepo, raw.id, "invalid_parsed_event")
    }

    @Test
    fun reviewRequiredWithoutEvent_createsRequiredReview() = runBlocking {
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val svc = reviewingUseCase(
            SmsParseGateway {
                com.baraa.masroof.parsing.model.ParseResult.ReviewRequired(
                    draft = null,
                    event = null,
                    findings = emptyList(),
                    reasons = listOf("no_event"),
                )
            },
            reviewRepo,
        )
        val raw = aljaziraPurchase(id = "android-sms:rr-null", deviceId = "rr-null")
        val result = svc.ingest(raw)
        assertTrue(result is SmsIngestionResult.ReviewRequired)
        assertNull((result as SmsIngestionResult.ReviewRequired).event)
        assertDirectReview(reviewRepo, raw.id, "parse_review_required")
    }

    @Test
    fun parserException_afterPersist_createsProcessingErrorReview() = runBlocking {
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val svc = reviewingUseCase(SmsParseGateway { throw IllegalStateException("boom") }, reviewRepo)
        val raw = aljaziraPurchase(id = "android-sms:boom", deviceId = "boom")
        assertTrue(svc.ingest(raw) is SmsIngestionResult.Failed)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertDirectReview(reviewRepo, raw.id, "processing_error")
    }

    @Test
    fun nonBankSms_isNotPersistedAndCreatesNoReview() = runBlocking {
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val svc = reviewingUseCase(AlJaziraParsingPipeline(), reviewRepo)
        val body = "شراء عبر الانترنت بمبلغ: 10.00 SAR"
        val raw = RawSms(
            id = "android-sms-live:OtherBank",
            sender = "OtherBank",
            body = body,
            receivedAt = Instant.parse("2026-08-03T08:00:00Z"),
            deviceMessageId = null,
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
        assertTrue(svc.ingest(raw) is SmsIngestionResult.NotRelevant)
        assertEquals(0, db.rawSmsDao().count())
        assertTrue(reviewRepo.listAll().isEmpty())
    }

    @Test
    fun directIngestionReview_survivesFullReconcileRefresh() = runBlocking {
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val svc = reviewingUseCase(
            SmsParseGateway { com.baraa.masroof.parsing.model.ParseResult.Unsupported("unsupported_test") },
            reviewRepo,
        )
        val raw = aljaziraPurchase(id = "android-sms:survive", deviceId = "survive")
        svc.ingest(raw)
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository),
        )
        ReviewQueueUpdater(reviewRepo, ftRepo, InstantClock.System)
            .applyReport(reconciliation.reconcileStoredEventsDetailed())
        assertDirectReview(reviewRepo, raw.id, "unsupported_bank_message_format")
    }

    private fun aljaziraPurchase(id: String, deviceId: String): RawSms {
        val body = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
            الرصيد المتاح: SAR 17230.03
        """.trimIndent()
        return RawSms(
            id = id,
            sender = "AlJazira",
            body = body,
            receivedAt = Instant.parse("2026-08-03T14:32:00Z"),
            deviceMessageId = deviceId,
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
    }

    private fun aljaziraBillPayment(id: String, deviceId: String): RawSms {
        val body = """
            سداد فاتورة
            المفوتر: TEST_BILLER
            بمبلغ: 210.00 SAR
            من حساب: 3001
            في: 2026-08-03 16:40
        """.trimIndent()
        return RawSms(
            id = id,
            sender = "AlJazira",
            body = body,
            receivedAt = Instant.parse("2026-08-03T16:40:00Z"),
            deviceMessageId = deviceId,
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
    }

    private fun transferBody() = """
        عملية حوالة مالية صادرة مقبولة
        خصمت من حساب: 3002
        الى: TEST_BENEFICIARY
        مبلغ العملية: 13,258.00 SAR
        المعرف البديل \الايبان : 0593
        [البنك العربي الوطني]
        في: 2026-08-01 12:26
        رقم المعاملة: TEST_REFERENCE_1
    """.trimIndent()

    private fun alJaziraSmsRegistry(pipeline: SmsParseGateway = AlJaziraParsingPipeline()): BankSmsRegistry =
        BankSmsRegistry(listOf(AlJaziraSmsAdapter(pipeline = pipeline)))
}
