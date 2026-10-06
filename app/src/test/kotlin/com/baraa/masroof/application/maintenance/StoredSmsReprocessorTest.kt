package com.baraa.masroof.application.maintenance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
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
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.parser.SmsParseGateway
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StoredSmsReprocessorTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var correctionRepo: RoomUserCorrectionRepository
    private lateinit var cards: RoomCardRegistryRepository
    private lateinit var reconciliation: TransactionReconciliationService
    private lateinit var reviewQueueUpdater: ReviewQueueUpdater
    private lateinit var ingestionReviews: IngestionReviewService
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
        correctionRepo = RoomUserCorrectionRepository(db.userCorrectionDao())
        cards = RoomCardRegistryRepository.from(db)
        cards.setOwnership(CardReference(Bank.BANK_ALJAZIRA, "7271"), OwnershipStatus.OWNED)
        reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(
                RoomAccountRegistryRepository.from(db),
                cards,
                NoOpLoanRegistryRepository,
            ),
            effectiveParsedEventProvider = EffectiveParsedEventProvider(parsedRepo, correctionRepo),
            reviewRepository = reviewRepo,
        )
        reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, clock)
        ingestionReviews = IngestionReviewService(reviewRepo, clock)
        Unit
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun storedRawSmsWithoutParsedEvent_isRetried() = runBlocking {
        val raw = purchase("android-sms:orphan")
        rawRepo.insertIfAbsent(raw)
        assertNull(parsedRepo.findByRawSmsId(raw.id))

        val result = reprocessor(AlJaziraParsingPipeline()).reprocessAll()

        assertEquals(ReparseAllStoredEventsResult(refreshedCount = 1, failedCount = 0), result)
        val record = parsedRepo.findByRawSmsId(raw.id)!!
        assertEquals(ParseStatus.SUCCESS, record.event.parseStatus)
        assertEquals(Money.of("51.99", Currency.SAR), record.event.amount)
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(FinancialTransactionType.EXPENSE, ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun previouslyUnsupported_recoversAndAutoResolvesIngestionReview() = runBlocking {
        val raw = purchase("android-sms:was-unsupported")
        val unsupported = useCase(SmsParseGateway { ParseResult.Unsupported("old_parser") })
        assertTrue(unsupported.ingest(raw) is SmsIngestionResult.Unsupported)
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId(raw.id)!!.status)

        reprocessor(AlJaziraParsingPipeline()).reprocessAll()

        assertNotNull(parsedRepo.findByRawSmsId(raw.id))
        assertEquals(1, db.rawSmsDao().count())
        val review = reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(ReviewStatus.RESOLVED, review.status)
        assertEquals(ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED, review.resolutionKind)
        assertNotNull(ftRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun reprocessing_isIdempotent() = runBlocking {
        rawRepo.insertIfAbsent(purchase("android-sms:a"))
        rawRepo.insertIfAbsent(purchase("android-sms:b", at = Instant.parse("2026-08-04T10:00:00Z")))
        val reprocessor = reprocessor(AlJaziraParsingPipeline())

        val first = reprocessor.reprocessAll()
        val transactionsAfterFirst = ftRepo.listAll().sortedBy { it.id }
        val second = reprocessor.reprocessAll()

        assertEquals(first, second)
        assertEquals(2, db.rawSmsDao().count())
        assertEquals(2, db.parsedEventDao().count())
        assertEquals(transactionsAfterFirst, ftRepo.listAll().sortedBy { it.id })
    }

    @Test
    fun reprocessing_preservesCorrectionsAndTransactionLinks() = runBlocking {
        val raw = purchase("android-sms:linked")
        useCase(AlJaziraParsingPipeline()).ingest(raw)
        val linked = ftRepo.findByRawSmsId(raw.id)!!
        correctionRepo.save(
            UserCorrection(
                id = "corr-linked",
                targetRawSmsId = raw.id,
                correctedType = null,
                correctedAmount = null,
                correctedMerchant = "Keeta Corrected",
                correctedCounterparty = null,
                createdAt = clock.now(),
            ),
        )

        reprocessor(AlJaziraParsingPipeline()).reprocessAll()

        val after = ftRepo.findByRawSmsId(raw.id)!!
        assertEquals(linked.id, after.id)
        assertEquals(linked.type, after.type)
        assertEquals(1, ftRepo.listAll().size)
        assertNotNull(correctionRepo.latestForRawSmsId(raw.id))
        assertEquals(
            "Keeta Corrected",
            EffectiveParsedEventProvider(parsedRepo, correctionRepo).findEffectiveByRawSmsId(raw.id)!!.event.merchant,
        )
    }

    @Test
    fun parserFailure_countsAsFailed_andKeepsEvidence() = runBlocking {
        val raw = purchase("android-sms:still-broken")
        rawRepo.insertIfAbsent(raw)
        val result = reprocessor(SmsParseGateway { throw IllegalStateException("boom") }).reprocessAll()
        assertEquals(ReparseAllStoredEventsResult(refreshedCount = 0, failedCount = 1), result)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertEquals(listOf("processing_error"), reviewRepo.findByRawSmsId(raw.id)!!.reasons)
    }

    private fun useCase(pipeline: SmsParseGateway) = ProcessRawSmsUseCase(
        rawSmsRepository = rawRepo,
        parsedEventRepository = parsedRepo,
        bankSmsRegistry = BankSmsRegistry(listOf(AlJaziraSmsAdapter(pipeline = pipeline))),
        reconciliation = reconciliation,
        reviewQueueUpdater = reviewQueueUpdater,
        ingestionReviewService = ingestionReviews,
    )

    private fun reprocessor(pipeline: SmsParseGateway) = StoredSmsReprocessor(
        rawSmsRepository = rawRepo,
        processRawSms = useCase(pipeline),
        refreshDerivedState = {
            reviewQueueUpdater.applyReport(reconciliation.reconcileStoredEventsDetailed())
        },
    )

    private fun purchase(id: String, at: Instant = Instant.parse("2026-08-03T14:32:00Z")): RawSms {
        val body = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
        """.trimIndent()
        return RawSms(
            id = id,
            sender = "AlJazira",
            body = body,
            receivedAt = at,
            deviceMessageId = id.substringAfter(':'),
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
    }
}
