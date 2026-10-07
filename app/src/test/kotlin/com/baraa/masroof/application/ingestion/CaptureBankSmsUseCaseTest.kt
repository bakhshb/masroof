package com.baraa.masroof.application.ingestion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.parser.SmsParseGateway
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
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
class CaptureBankSmsUseCaseTest {
    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private val parseCalls = AtomicInteger(0)
    private val countingGateway = SmsParseGateway { input ->
        parseCalls.incrementAndGet()
        AlJaziraParsingPipeline().parse(input)
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        parseCalls.set(0)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun bankSms_isPersistedDurably_withoutParsingOrDerivedWork() = runBlocking {
        val raw = live(PURCHASE_BODY, "2026-08-03T14:32:00Z")

        val result = capture().capture(raw)

        assertTrue(result is BankSmsCaptureResult.Captured)
        result as BankSmsCaptureResult.Captured
        assertEquals(raw.id, result.rawSmsId)
        assertEquals(Bank.BANK_ALJAZIRA, (result.route as BankRoutingResult.Matched).adapter.bank)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertEquals(0, parseCalls.get())
        assertNull(parsedRepo.findByRawSmsId(raw.id))
        assertEquals(0, db.financialTransactionDao().count())
        assertTrue(reviewRepo.listAll().isEmpty())
    }

    @Test
    fun nonBankSms_isNotPersisted() = runBlocking {
        val raw = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, "Mom", "See you at 6", Instant.parse("2026-08-03T10:00:00Z")),
        )

        val result = capture().capture(raw)

        assertTrue(result is BankSmsCaptureResult.NotRelevant)
        assertEquals(0, db.rawSmsDao().count())
    }

    @Test
    fun recapture_isDuplicate_andIdempotent() = runBlocking {
        val raw = live(PURCHASE_BODY, "2026-08-03T14:32:00Z")
        val useCase = capture()

        assertTrue(useCase.capture(raw) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(raw))
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(raw))
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun crossSourceTwinWithinTolerance_isDuplicate() = runBlocking {
        val live = live(PURCHASE_BODY, "2026-08-03T14:32:00.000Z")
        val historical = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("42", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T14:32:03.000Z")),
        )
        val useCase = capture()

        assertTrue(useCase.capture(live) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(historical))
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun crossSourceTwinOutsideFiveSeconds_dedupesWhenUniquelyCompatible() = runBlocking {
        val liveRow = live(PURCHASE_BODY, "2026-08-03T14:32:00.000Z")
        val historical = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("42", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T16:02:00.000Z")),
        )
        val useCase = capture()

        assertTrue(useCase.capture(liveRow) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(historical))
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun crossSourceTwinBeyondSkewWindow_staysSeparate() = runBlocking {
        val liveRow = live(PURCHASE_BODY, "2026-08-03T08:00:00.000Z")
        val historical = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("42", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T15:00:00.000Z")),
        )
        val useCase = capture()

        assertTrue(useCase.capture(liveRow) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(historical) is BankSmsCaptureResult.Captured)
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun skewedTwin_isNotMergedWhenAnotherOppositeCopyExists() = runBlocking {
        val first = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("10", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T14:00:00.000Z")),
        )
        val second = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("11", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T15:30:00.000Z")),
        )
        val liveRow = live(PURCHASE_BODY, "2026-08-03T14:40:00.000Z")
        val useCase = capture()

        assertTrue(useCase.capture(first) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(second) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(liveRow) is BankSmsCaptureResult.Captured)
        assertEquals(3, db.rawSmsDao().count())
    }

    @Test
    fun sameSourceRows_areNotMergedByBodyHash() = runBlocking {
        val first = live(PURCHASE_BODY, "2026-08-03T14:32:00.000Z")
        val second = live(PURCHASE_BODY, "2026-08-03T14:40:00.000Z")
        val useCase = capture()

        assertTrue(useCase.capture(first) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(second) is BankSmsCaptureResult.Captured)
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun ambiguousRoute_isCapturedWithItsRoute_andLeftForProcessing() = runBlocking {
        val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter(), LookalikeAdapter()))
        val raw = live(PURCHASE_BODY, "2026-08-03T14:32:00Z")

        val result = CaptureBankSmsUseCase(rawRepo, registry).capture(raw)

        assertTrue(result is BankSmsCaptureResult.Captured)
        assertTrue((result as BankSmsCaptureResult.Captured).route is BankRoutingResult.Ambiguous)
        assertEquals(raw, rawRepo.getById(raw.id))
        assertTrue("capture never writes reviews", reviewRepo.listAll().isEmpty())
    }

    @Test
    fun persistenceFailure_isFailedValue_andNothingIsStored() = runBlocking {
        val failingRepo = object : RawSmsRepository by rawRepo {
            override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult =
                throw IllegalStateException("disk-full")
        }
        val raw = live(PURCHASE_BODY, "2026-08-03T14:32:00Z")

        val result = CaptureBankSmsUseCase(failingRepo, registry()).capture(raw)

        assertEquals("disk-full", (result as BankSmsCaptureResult.Failed).message)
        assertEquals(0, db.rawSmsDao().count())
    }

    private fun capture() = CaptureBankSmsUseCase(rawRepo, registry())

    private fun registry() = BankSmsRegistry(listOf(AlJaziraSmsAdapter(pipeline = countingGateway)))

    private fun live(body: String, at: String): RawSms =
        AndroidSmsMapper.toRawSms(ProviderSmsRecord(null, "AlJazira", body, Instant.parse(at)))

    private class LookalikeAdapter : BankSmsAdapter {
        override val bank: Bank = Bank("LOOKALIKE_BANK")
        override fun detect(sender: String, body: String): BankDetectionResult =
            BankDetectionResult.Detected(bank = bank, confidence = Confidence(1.0), evidence = listOf("lookalike"))
        override fun parse(input: SmsParseInput): ParseResult = ParseResult.Unsupported(reason = "lookalike")
    }

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
