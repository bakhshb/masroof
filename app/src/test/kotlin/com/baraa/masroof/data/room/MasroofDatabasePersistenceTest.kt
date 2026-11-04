package com.baraa.masroof.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
/**
 * In-memory Room persistence tests (Robolectric). Verifies real SQLite behavior
 * for RawSms dedupe and ParsedEvent + details round-trips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MasroofDatabasePersistenceTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawSmsRepo: RoomRawSmsRepository
    private lateinit var parsedEventRepo: RoomParsedEventRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawSmsRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedEventRepo = RoomParsedEventRepository(db.parsedEventDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rawSms_roundTrip_preservesBodyAndReceivedAt() = runBlocking {
        val sms = sampleSms(id = "r1", body = "exact\nbody  ", receivedAt = Instant.parse("2026-08-01T12:26:00Z"))
        assertEquals(RawSmsInsertResult.Inserted, rawSmsRepo.insertIfAbsent(sms))
        assertEquals(sms, rawSmsRepo.getById("r1"))
    }

    @Test
    fun rawSms_exactDuplicate_doesNotInsertSecondRow() = runBlocking {
        val sms = sampleSms(id = "r2")
        assertEquals(RawSmsInsertResult.Inserted, rawSmsRepo.insertIfAbsent(sms))
        assertEquals(RawSmsInsertResult.AlreadyExists, rawSmsRepo.insertIfAbsent(sms))
        assertEquals(RawSmsInsertResult.AlreadyExists, rawSmsRepo.insertIfAbsent(sms.copy(id = "r2-other")))
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(sms, rawSmsRepo.getById("r2"))
    }

    @Test
    fun rawSms_sameBodyDifferentTimestamp_areDistinct() = runBlocking {
        val t1 = Instant.parse("2026-08-01T10:00:00Z")
        val t2 = Instant.parse("2026-08-01T11:00:00Z")
        val a = sampleSms(id = "a", body = "same", receivedAt = t1, deviceMessageId = null)
        val b = sampleSms(id = "b", body = "same", receivedAt = t2, deviceMessageId = null)
        assertEquals(RawSmsInsertResult.Inserted, rawSmsRepo.insertIfAbsent(a))
        assertEquals(RawSmsInsertResult.Inserted, rawSmsRepo.insertIfAbsent(b))
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun rawSms_nullableDeviceMessageId_allowsMultipleNulls() = runBlocking {
        val a = sampleSms(id = "n1", deviceMessageId = null, body = "one", receivedAt = Instant.ofEpochMilli(1))
        val b = sampleSms(id = "n2", deviceMessageId = null, body = "two", receivedAt = Instant.ofEpochMilli(2))
        assertEquals(RawSmsInsertResult.Inserted, rawSmsRepo.insertIfAbsent(a))
        assertEquals(RawSmsInsertResult.Inserted, rawSmsRepo.insertIfAbsent(b))
        assertEquals(2, db.rawSmsDao().count())
    }

    @Test
    fun rawSms_sameDeviceMessageId_isDuplicate() = runBlocking {
        val a = sampleSms(id = "d1", deviceMessageId = "device-9", body = "x", receivedAt = Instant.ofEpochMilli(1))
        val b = sampleSms(id = "d2", deviceMessageId = "device-9", body = "y", receivedAt = Instant.ofEpochMilli(2))
        assertEquals(RawSmsInsertResult.Inserted, rawSmsRepo.insertIfAbsent(a))
        assertEquals(RawSmsInsertResult.AlreadyExists, rawSmsRepo.insertIfAbsent(b))
        assertEquals(a, rawSmsRepo.findByDeviceMessageId("device-9"))
        assertNull(rawSmsRepo.getById("d2"))
    }

    @Test
    fun rawSms_concurrentIdenticalInserts_areAtomic() = runBlocking {
        val sms = sampleSms(id = "conc-1", deviceMessageId = "conc-device-1")
        val inserted = AtomicInteger(0)
        val alreadyExists = AtomicInteger(0)
        val jobs = (1..32).map {
            async(Dispatchers.IO) {
                when (rawSmsRepo.insertIfAbsent(sms)) {
                    RawSmsInsertResult.Inserted -> inserted.incrementAndGet()
                    RawSmsInsertResult.AlreadyExists -> alreadyExists.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        assertEquals(1, inserted.get())
        assertEquals(31, alreadyExists.get())
        assertEquals(1, db.rawSmsDao().count())
        assertEquals(sms, rawSmsRepo.getById(sms.id))
    }

    @Test
    fun rawSms_concurrentSameDeviceMessageId_areAtomic() = runBlocking {
        val inserted = AtomicInteger(0)
        val alreadyExists = AtomicInteger(0)
        val jobs = (1..24).map { i ->
            async(Dispatchers.IO) {
                val sms = sampleSms(
                    id = "dev-conc-$i",
                    deviceMessageId = "shared-device",
                    body = "body-$i",
                    receivedAt = Instant.ofEpochMilli(i.toLong()),
                )
                when (rawSmsRepo.insertIfAbsent(sms)) {
                    RawSmsInsertResult.Inserted -> inserted.incrementAndGet()
                    RawSmsInsertResult.AlreadyExists -> alreadyExists.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        assertEquals(1, inserted.get())
        assertEquals(23, alreadyExists.get())
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun rawSms_concurrentSameDedupeKey_areAtomic() = runBlocking {
        val receivedAt = Instant.parse("2026-08-10T12:00:00Z")
        val body = "identical-body"
        val bodyHash = "hash-$body"
        val inserted = AtomicInteger(0)
        val alreadyExists = AtomicInteger(0)
        val jobs = (1..24).map { i ->
            async(Dispatchers.IO) {
                val sms = RawSms(
                    id = "dedupe-conc-$i",
                    sender = "AlJazira",
                    body = body,
                    receivedAt = receivedAt,
                    deviceMessageId = null,
                    bodyHash = bodyHash,
                )
                when (rawSmsRepo.insertIfAbsent(sms)) {
                    RawSmsInsertResult.Inserted -> inserted.incrementAndGet()
                    RawSmsInsertResult.AlreadyExists -> alreadyExists.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        assertEquals(1, inserted.get())
        assertEquals(23, alreadyExists.get())
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun parsedEvent_fullRoundTrip_withDetails() = runBlocking {
        val sms = sampleSms(id = "sms-pe")
        rawSmsRepo.insertIfAbsent(sms)
        val event = ParsedEvent(
            id = "evt-pe",
            rawSmsId = sms.id,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.TRANSFER_OUT,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("13258.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
            destinationAccountRef = AccountReference(Bank.UNKNOWN, "0593"),
            cardRef = null,
            merchant = null,
            counterparty = "TEST_BENEFICIARY",
            occurredAt = null,
            bankNetworkType = BankNetworkType.INTER_BANK,
            confidence = Confidence(0.95, listOf("transfer_out", "labeled_amount:مبلغ العملية")),
            parseStatus = ParseStatus.SUCCESS,
        )
        val details = ParsedEventDetails(
            transactionReference = "TEST_REFERENCE_1",
            availableBalance = null,
            outstandingBalance = null,
            biller = null,
            billerCode = null,
            occurredAtLocal = LocalDateTime.parse("2026-08-01T12:26:00"),
        )
        parsedEventRepo.save(event, details)
        val loaded = parsedEventRepo.getById("evt-pe")
        assertNotNull(loaded)
        assertEquals(event, loaded!!.event)
        assertEquals(details, loaded.details)
        assertEquals(Bank.BANK_ALJAZIRA, loaded.event.sourceAccountRef?.bank)
        assertEquals(Bank.UNKNOWN, loaded.event.destinationAccountRef?.bank)
    }

    @Test
    fun bankD360_andUnknown_roundTrip() = runBlocking {
        rawSmsRepo.insertIfAbsent(sampleSms(id = "sms-bank"))
        val event = ParsedEvent(
            id = "evt-bank",
            rawSmsId = "sms-bank",
            bank = Bank("D360"),
            messageFamily = MessageFamily.UNKNOWN,
            direction = null,
            amount = null,
            purchaseChannel = null,
            sourceAccountRef = AccountReference(Bank.UNKNOWN, "1111"),
            destinationAccountRef = AccountReference(Bank("D360"), "2222"),
            cardRef = CardReference(Bank("D360"), "3333"),
            merchant = null,
            counterparty = null,
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            bankNetworkType = BankNetworkType.UNKNOWN,
            confidence = Confidence(0.4, listOf("review")),
            parseStatus = ParseStatus.REVIEW_REQUIRED,
        )
        parsedEventRepo.save(event, ParsedEventDetails())
        val loaded = parsedEventRepo.findByRawSmsId("sms-bank")!!.event
        assertEquals(Bank("D360"), loaded.bank)
        assertEquals(Bank.UNKNOWN, loaded.sourceAccountRef?.bank)
        assertEquals(Bank("D360"), loaded.destinationAccountRef?.bank)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), loaded.occurredAt)
    }

    @Test
    fun merchantCounterpartyBiller_andBalances_staySeparate() = runBlocking {
        rawSmsRepo.insertIfAbsent(sampleSms(id = "sms-sep"))
        val event = ParsedEvent(
            id = "evt-sep",
            rawSmsId = "sms-sep",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.BILL_PAYMENT,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("210.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destinationAccountRef = null,
            cardRef = null,
            merchant = "NOT_BILLER",
            counterparty = "NOT_BILLER_EITHER",
            occurredAt = null,
            bankNetworkType = null,
            confidence = Confidence(0.9),
            parseStatus = ParseStatus.SUCCESS,
        )
        val details = ParsedEventDetails(
            biller = "TEST_BILLER",
            availableBalance = Money.of("1000.00", Currency.SAR),
            outstandingBalance = Money.of("50.00", Currency.SAR),
            transactionReference = "REF",
            occurredAtLocal = LocalDateTime.parse("2026-08-03T16:40:00"),
        )
        parsedEventRepo.save(event, details)
        val loaded = parsedEventRepo.getById("evt-sep")!!
        assertEquals("NOT_BILLER", loaded.event.merchant)
        assertEquals("NOT_BILLER_EITHER", loaded.event.counterparty)
        assertEquals("TEST_BILLER", loaded.details.biller)
        assertEquals(Money.of("210.00", Currency.SAR), loaded.event.amount)
        assertEquals(Money.of("1000.00", Currency.SAR), loaded.details.availableBalance)
        assertEquals(Money.of("50.00", Currency.SAR), loaded.details.outstandingBalance)
        assertEquals("REF", loaded.details.transactionReference)
    }

    @Test
    fun enums_roundTrip() = runBlocking {
        rawSmsRepo.insertIfAbsent(sampleSms(id = "sms-enum"))
        val event = ParsedEvent(
            id = "evt-enum",
            rawSmsId = "sms-enum",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("1.00", Currency.SAR),
            purchaseChannel = PurchaseChannel.POS,
            sourceAccountRef = null,
            destinationAccountRef = null,
            cardRef = null,
            merchant = "M",
            counterparty = null,
            occurredAt = null,
            bankNetworkType = BankNetworkType.INTRA_BANK,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.PARTIAL,
        )
        parsedEventRepo.save(event, ParsedEventDetails())
        val loaded = parsedEventRepo.getById("evt-enum")!!.event
        assertEquals(MessageFamily.PURCHASE, loaded.messageFamily)
        assertEquals(PurchaseChannel.POS, loaded.purchaseChannel)
        assertEquals(BankNetworkType.INTRA_BANK, loaded.bankNetworkType)
        assertEquals(ParseStatus.PARTIAL, loaded.parseStatus)
    }

    @Test
    fun deleteParsedEvent_doesNotDeleteRawSms() = runBlocking {
        val sms = sampleSms(id = "sms-keep")
        rawSmsRepo.insertIfAbsent(sms)
        parsedEventRepo.save(
            ParsedEvent(
                id = "evt-del",
                rawSmsId = sms.id,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.FEE,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of("5.00", Currency.SAR),
                purchaseChannel = null,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = null,
                merchant = null,
                counterparty = null,
                occurredAt = null,
                bankNetworkType = null,
                confidence = Confidence(0.8),
                parseStatus = ParseStatus.SUCCESS,
            ),
            ParsedEventDetails(),
        )
        parsedEventRepo.deleteByRawSmsId(sms.id)
        assertNull(parsedEventRepo.findByRawSmsId(sms.id))
        assertEquals(sms, rawSmsRepo.getById(sms.id))
    }

    @Test
    fun save_replacesParsedEventForSameRawSms() = runBlocking {
        val sms = sampleSms(id = "sms-re")
        rawSmsRepo.insertIfAbsent(sms)
        parsedEventRepo.save(
            ParsedEvent(
                id = "evt-old",
                rawSmsId = sms.id,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.UNKNOWN,
                direction = null,
                amount = null,
                purchaseChannel = null,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = null,
                merchant = null,
                counterparty = null,
                occurredAt = null,
                bankNetworkType = null,
                confidence = Confidence(0.3),
                parseStatus = ParseStatus.REVIEW_REQUIRED,
            ),
            ParsedEventDetails(),
        )
        parsedEventRepo.save(
            ParsedEvent(
                id = "evt-new",
                rawSmsId = sms.id,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.PURCHASE,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of("10.00", Currency.SAR),
                purchaseChannel = PurchaseChannel.ONLINE,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = null,
                merchant = "Shop",
                counterparty = null,
                occurredAt = null,
                bankNetworkType = null,
                confidence = Confidence(0.99, listOf("reparsed")),
                parseStatus = ParseStatus.SUCCESS,
            ),
            ParsedEventDetails(occurredAtLocal = LocalDateTime.parse("2026-08-10T09:00:00")),
        )
        assertEquals(1, db.parsedEventDao().count())
        val loaded = parsedEventRepo.findByRawSmsId(sms.id)!!
        assertEquals("evt-new", loaded.event.id)
        assertEquals(MessageFamily.PURCHASE, loaded.event.messageFamily)
        assertEquals(Money.of("10.00", Currency.SAR), loaded.event.amount)
    }

    @Test
    fun nullOptionalFields_persist() = runBlocking {
        rawSmsRepo.insertIfAbsent(sampleSms(id = "sms-nulls", deviceMessageId = null))
        val event = ParsedEvent(
            id = "evt-nulls",
            rawSmsId = "sms-nulls",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.OTP,
            direction = null,
            amount = null,
            purchaseChannel = null,
            sourceAccountRef = null,
            destinationAccountRef = null,
            cardRef = null,
            merchant = null,
            counterparty = null,
            occurredAt = null,
            bankNetworkType = null,
            confidence = Confidence(1.0, emptyList()),
            parseStatus = ParseStatus.NON_FINANCIAL,
        )
        parsedEventRepo.save(event, ParsedEventDetails())
        assertEquals(event, parsedEventRepo.getById("evt-nulls")!!.event)
    }

    private fun sampleSms(
        id: String,
        body: String = "body-$id",
        receivedAt: Instant = Instant.parse("2026-08-03T08:00:00Z"),
        deviceMessageId: String? = "device-$id",
        bodyHash: String = "hash-$body",
    ) = RawSms(
        id = id,
        sender = "AlJazira",
        body = body,
        receivedAt = receivedAt,
        deviceMessageId = deviceMessageId,
        bodyHash = bodyHash,
    )
}
