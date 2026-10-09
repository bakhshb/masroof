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
import com.baraa.masroof.sms.receiver.LiveReceiptTimestamp
import com.baraa.masroof.sms.receiver.ReceivedSmsAssembler
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
            ProviderSmsRecord("42", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T14:33:30.000Z")),
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
    fun repeatedIdenticalNotifications_staySeparateWhenHoursApart() = runBlocking {
        val liveRow = live(PURCHASE_BODY, "2026-08-03T14:32:00.000Z")
        val historical = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("42", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T16:02:00.000Z")),
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
            ProviderSmsRecord("11", "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T14:00:40.000Z")),
        )
        val liveRow = live(PURCHASE_BODY, "2026-08-03T14:01:00.000Z")
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
    fun liveThenInbox_exactProviderInstant_isOneRow() = runBlocking {
        val at = "2026-08-03T14:32:00.000Z"
        val liveRow = live(PURCHASE_BODY, at)
        val historical = inbox("42", at)
        val useCase = capture()

        assertTrue(useCase.capture(liveRow) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(historical))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(liveRow, rawRepo.getById(liveRow.id))
        assertEquals(liveRow.id, rawRepo.findByProviderMessageId("42")?.id)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(historical))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(liveRow, rawRepo.getById(liveRow.id))
    }

    @Test
    fun liveThenTwoProviderIds_keepsBothIdentifiedMessages_inEveryOrder() = runBlocking {
        val orders = listOf(
            listOf("live", "10", "11"),
            listOf("live", "11", "10"),
            listOf("10", "11", "live"),
            listOf("10", "live", "11"),
            listOf("11", "live", "10"),
        )
        val base = Instant.parse("2026-08-03T14:32:00.000Z")
        orders.forEachIndexed { index, order ->
            val at = base.plusSeconds(index * 3_600L).toString()
            val providerA = "a$index"
            val providerB = "b$index"
            val useCase = capture()
            val liveRow = live(PURCHASE_BODY, at)
            val first = inbox(providerA, at)
            val second = inbox(providerB, at)
            val before = db.rawSmsDao().count()
            order.forEach { step ->
                val sms = when (step) {
                    "live" -> liveRow
                    "10" -> first
                    else -> second
                }
                useCase.capture(sms)
            }
            assertEquals("order $order", before + 2, db.rawSmsDao().count())
            assertNotNull("order $order missing $providerA", rawRepo.findByProviderMessageId(providerA))
            assertNotNull("order $order missing $providerB", rawRepo.findByProviderMessageId(providerB))
            if (order.first() == "live") {
                assertEquals("order $order rewrote the live row", liveRow, rawRepo.getById(liveRow.id))
            }
            assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(first))
            assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(second))
            assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(liveRow))
            assertEquals("order $order replay", before + 2, db.rawSmsDao().count())
        }
    }

    @Test
    fun inboxThenLive_exactProviderInstant_isOneRow() = runBlocking {
        val at = "2026-08-03T14:32:00.000Z"
        val historical = inbox("42", at)
        val liveRow = live(PURCHASE_BODY, at)
        val useCase = capture()

        assertTrue(useCase.capture(historical) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(liveRow))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(historical, rawRepo.getById(historical.id))
    }

    @Test
    fun inboxThenLive_withinFiveSeconds_isOneRow() = runBlocking {
        val historical = inbox("42", "2026-08-03T14:32:00.000Z")
        val liveRow = live(PURCHASE_BODY, "2026-08-03T14:32:05.000Z")
        val useCase = capture()

        assertTrue(useCase.capture(historical) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(liveRow))
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun crossSourceTwin_atTwoMinuteSkew_isDuplicate_andOneMillisecondBeyondStaysSeparate() = runBlocking {
        val base = Instant.parse("2026-08-03T14:32:00.000Z")
        val useCase = capture()
        val liveRow = live(PURCHASE_BODY, base.toString())
        val atBoundary = inbox("42", base.plusSeconds(120).toString())

        assertTrue(useCase.capture(liveRow) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(atBoundary))
        assertEquals(1, db.rawSmsDao().count())

        val outside = inbox("43", base.plusSeconds(120).plusMillis(1).toString())
        assertTrue(useCase.capture(outside) is BankSmsCaptureResult.Captured)
        assertEquals(2, db.rawSmsDao().count())
        assertEquals(outside, rawRepo.getById(outside.id))
    }

    @Test
    fun inboxThenLive_uniqueSkewWithinTwoMinutes_isOneRow() = runBlocking {
        val historical = inbox("42", "2026-08-03T14:32:00.000Z")
        val liveRow = live(PURCHASE_BODY, "2026-08-03T14:33:30.000Z")
        val useCase = capture()

        assertTrue(useCase.capture(historical) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(liveRow))
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun twoIdentifiedNotices_oneSecondApart_bothPersist() = runBlocking {
        val first = inbox("10", "2026-08-03T14:32:00.000Z")
        val second = inbox("11", "2026-08-03T14:32:01.000Z")
        val useCase = capture()

        assertTrue(useCase.capture(first) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(second) is BankSmsCaptureResult.Captured)
        assertEquals(2, db.rawSmsDao().count())
        assertEquals(first, rawRepo.getById(first.id))
        assertEquals(second, rawRepo.getById(second.id))
    }

    @Test
    fun twoIdentifiedNotices_sameInstant_bothPersist() = runBlocking {
        val at = "2026-08-03T14:32:00.000Z"
        val first = inbox("10", at)
        val second = inbox("11", at)
        val useCase = capture()

        assertTrue(useCase.capture(first) is BankSmsCaptureResult.Captured)
        assertTrue(
            "distinct inbox ids at one instant must not be discarded",
            useCase.capture(second) is BankSmsCaptureResult.Captured,
        )
        assertEquals(2, db.rawSmsDao().count())
        assertEquals(first, rawRepo.getById(first.id))
        assertEquals(second, rawRepo.getById(second.id))
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(second))
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun twoIdentifiedNotices_andTheirLiveCopies_withinFiveSeconds_remainTwoRows() = runBlocking {
        val firstInbox = inbox("10", "2026-08-03T14:32:00.000Z")
        val secondInbox = inbox("11", "2026-08-03T14:32:04.000Z")
        val firstLive = live(PURCHASE_BODY, "2026-08-03T14:32:00.000Z")
        val secondLive = live(PURCHASE_BODY, "2026-08-03T14:32:04.000Z")
        val useCase = capture()

        assertTrue(useCase.capture(firstLive) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(secondLive) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(firstInbox))
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(secondInbox))
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun twoLiveNotices_oneSecondApart_bothPersist() = runBlocking {
        val first = live(PURCHASE_BODY, "2026-08-03T14:32:00.000Z")
        val second = live(PURCHASE_BODY, "2026-08-03T14:32:01.000Z")
        val useCase = capture()

        assertTrue(useCase.capture(first) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(second) is BankSmsCaptureResult.Captured)
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun differentSenders_sameBodyWithinFiveSeconds_staySeparate() = runBlocking {
        val aljazira = live(PURCHASE_BODY, "2026-08-03T14:32:00.000Z")
        val bankAljazira = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("77", "BankAlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T14:32:02.000Z")),
        )
        val useCase = capture()

        assertTrue(useCase.capture(aljazira) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(bankAljazira) is BankSmsCaptureResult.Captured)
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun multipartLiveAndInbox_withinPartSkew_isOneRow() = runBlocking {
        val earliest = Instant.parse("2026-08-03T14:32:00.000Z")
        val laterPart = earliest.plusSeconds(30)
        val liveRow = multipartLive(earliest.toEpochMilli(), laterPart.toEpochMilli())
        val historical = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("42", "AlJazira", liveRow.body, laterPart),
        )
        val useCase = capture()

        assertEquals(earliest, liveRow.receivedAt)
        assertTrue(useCase.capture(liveRow) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(historical))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(liveRow, rawRepo.getById(liveRow.id))
        assertEquals(liveRow.id, rawRepo.findByProviderMessageId("42")?.id)
    }

    @Test
    fun twoMultipartNotices_differentProviderIds_nearInTime_bothPersist() = runBlocking {
        val earliest = Instant.parse("2026-08-03T14:32:00.000Z")
        val assembled = multipartLive(earliest.toEpochMilli(), earliest.plusSeconds(1).toEpochMilli())
        val first = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("11", "AlJazira", assembled.body, earliest),
        )
        val second = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("12", "AlJazira", assembled.body, earliest.plusSeconds(2)),
        )
        val useCase = capture()

        assertTrue(useCase.capture(first) is BankSmsCaptureResult.Captured)
        assertTrue(useCase.capture(second) is BankSmsCaptureResult.Captured)
        assertEquals(2, db.rawSmsDao().count())
        assertEquals(first, rawRepo.getById(first.id))
        assertEquals(second, rawRepo.getById(second.id))
    }

    @Test
    fun multipartReplay_andRestartedCapture_stayOneRow() = runBlocking {
        val earliest = Instant.parse("2026-08-03T14:32:00.000Z")
        val liveRow = multipartLive(earliest.toEpochMilli(), earliest.plusMillis(400).toEpochMilli())
        assertTrue(capture().capture(liveRow) is BankSmsCaptureResult.Captured)

        assertEquals(BankSmsCaptureResult.Duplicate, capture().capture(liveRow))
        val inboxCopy = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord("42", "AlJazira", liveRow.body, earliest.plusSeconds(2)),
        )
        assertEquals(BankSmsCaptureResult.Duplicate, capture().capture(inboxCopy))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(liveRow, rawRepo.getById(liveRow.id))
        assertEquals(liveRow.id, rawRepo.findByProviderMessageId("42")?.id)
    }

    @Test
    fun sameProviderId_replaysAsDuplicate_andKeepsOriginalEvidence() = runBlocking {
        val original = inbox("42", "2026-08-03T14:32:00.000Z")
        val rewritten = original.copy(
            id = "android-sms:other",
            body = original.body + "\nchanged",
            receivedAt = original.receivedAt.plusSeconds(30),
            bodyHash = "different-body-hash",
        )
        val useCase = capture()

        assertTrue(useCase.capture(original) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, useCase.capture(rewritten))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(original, rawRepo.getById(original.id))
        assertNull(rawRepo.getById(rewritten.id))
    }

    @Test
    fun differentProviderIds_areNotReplaysOfEachOther() = runBlocking {
        val first = inbox("42", "2026-08-03T14:32:00.000Z")
        val second = inbox("43", "2026-08-03T14:32:00.000Z").copy(body = first.body + "\nsecond")
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

    private fun inbox(providerMessageId: String, at: String, body: String = PURCHASE_BODY): RawSms =
        AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(providerMessageId, "AlJazira", body, Instant.parse(at)),
        )

    /**
     * One logical SMS joined from PDU parts. Receipt time is the earliest valid
     * part timestamp, matching [com.baraa.masroof.sms.receiver.IncomingSmsReceiver].
     */
    private fun multipartLive(earliestPartMillis: Long, laterPartMillis: Long): RawSms {
        val splitAt = PURCHASE_BODY.length / 2
        val assembled = ReceivedSmsAssembler.assemble(
            listOf(
                ReceivedSmsAssembler.Part(
                    sender = "AlJazira",
                    body = PURCHASE_BODY.substring(0, splitAt),
                    providerTimestampMillis = earliestPartMillis,
                ),
                ReceivedSmsAssembler.Part(
                    sender = "AlJazira",
                    body = PURCHASE_BODY.substring(splitAt),
                    providerTimestampMillis = laterPartMillis,
                ),
            ),
        )!!
        val receivedAt = LiveReceiptTimestamp.resolve(
            providerTimestampsMillis = assembled.providerTimestampsMillis,
            deviceNow = Instant.ofEpochMilli(laterPartMillis + 60_000L),
        )
        return AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(
                providerMessageId = null,
                sender = assembled.sender,
                body = assembled.body,
                receivedAt = receivedAt,
            ),
        )
    }

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
