package com.baraa.masroof.application.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.parser.SmsParseGateway
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.sms.datasource.SmsPermissionException
import com.baraa.masroof.sms.datasource.SmsProviderException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoricalSmsScannerTest {

    private lateinit var db: MasroofDatabase
    private lateinit var processRawSms: ProcessRawSmsUseCase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        processRawSms = ProcessRawSmsUseCase(
            rawSmsRepository = RoomRawSmsRepository(db.rawSmsDao()),
            parsedEventRepository = RoomParsedEventRepository(db.parsedEventDao()),
            bankSmsRegistry = BankSmsRegistry(listOf(AlJaziraSmsAdapter())),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun processesOldestToNewest_andCountsOutcomes() = runBlocking {
        val t1 = Instant.parse("2026-08-01T10:00:00Z")
        val t2 = Instant.parse("2026-08-02T10:00:00Z")
        val purchase = purchaseBody()
        val otp = "رمز التحقق الخاص بك هو 482911. لا تشاركه مع أي شخص."
        val after = Instant.parse("2026-07-01T00:00:00Z")
        val order = mutableListOf<String>()
        val source = FakeSmsDataSource(
            rows = listOf(
                InboxRow.Valid(ProviderSmsRecord("1", "AlJazira", purchase, t1)),
                InboxRow.Valid(ProviderSmsRecord("2", "OtherBank", purchase, t1)),
                InboxRow.Valid(ProviderSmsRecord("3", "AlJazira", otp, t2)),
                InboxRow.Valid(ProviderSmsRecord("1", "AlJazira", purchase, t1)),
            ),
            onQuery = { receivedAfter -> order += "after=${receivedAfter?.toEpochMilli()}" },
        )
        val result = HistoricalSmsScanner(source, processRawSms).scan(receivedAfter = after)
        assertEquals(listOf("after=${after.toEpochMilli()}"), order)
        assertEquals(4, result.scanned)
        assertEquals(1, result.parsed)
        assertEquals(1, result.nonFinancial)
        assertEquals(1, result.notRelevant)
        assertEquals(1, result.duplicates)
        assertEquals(2, db.rawSmsDao().count())
        assertNull(result.failure)
    }

    @Test
    fun permissionDenied_isDistinctFailure() = runBlocking {
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?) = throw SmsPermissionException()
        }
        val result = HistoricalSmsScanner(source, processRawSms).scan()
        assertEquals(SmsScanFailure.PermissionDenied, result.failure)
        assertEquals(0, result.scanned)
    }

    @Test
    fun providerError_isDistinctFailure() = runBlocking {
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?) =
                throw SmsProviderException("boom")
        }
        val result = HistoricalSmsScanner(source, processRawSms).scan()
        assertTrue(result.failure is SmsScanFailure.ProviderError)
        assertEquals(0, result.scanned)
    }

    @Test
    fun lazyPermissionException_duringIteration_isCaught() = runBlocking {
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> = sequence {
                throw SmsPermissionException("lazy")
            }
        }
        val result = HistoricalSmsScanner(source, processRawSms).scan()
        assertEquals(SmsScanFailure.PermissionDenied, result.failure)
        assertEquals(0, result.scanned)
    }

    @Test
    fun lazyProviderException_preservesPartialSummary() = runBlocking {
        val good = InboxRow.Valid(
            ProviderSmsRecord(
                "1",
                "AlJazira",
                purchaseBody(),
                Instant.parse("2026-08-01T00:00:00Z"),
            ),
        )
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> = sequence {
                yield(good)
                throw SmsProviderException("cursor died")
            }
        }
        val result = HistoricalSmsScanner(source, processRawSms).scan()
        assertTrue(result.failure is SmsScanFailure.ProviderError)
        assertEquals(1, result.scanned)
        assertEquals(1, result.parsed)
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun malformedProviderRows_areCounted() = runBlocking {
        val good = InboxRow.Valid(
            ProviderSmsRecord("1", "AlJazira", purchaseBody(), Instant.parse("2026-08-01T00:00:00Z")),
        )
        val source = FakeSmsDataSource(listOf(good, InboxRow.Malformed, InboxRow.Malformed))
        val result = HistoricalSmsScanner(source, processRawSms).scan()
        assertEquals(3, result.scanned)
        assertEquals(2, result.skippedMalformed)
        assertEquals(1, result.parsed)
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun failedWithNullRawSmsId_incrementsFailedOnly_notInserted() = runBlocking {
        val failingRawRepo = object : RawSmsRepository {
            override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult {
                throw IllegalStateException("db down")
            }

            override suspend fun getById(id: String): RawSms? = null
            override suspend fun existsById(id: String): Boolean = false
            override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? = null
            override suspend fun listIdsByReceivedAt(): List<String> = emptyList()
            override suspend fun findCrossSourceNearDuplicate(
                sender: String,
                bodyHash: String,
                fromInclusive: Instant,
                toInclusive: Instant,
                lookingForLiveRow: Boolean,
            ): RawSms? = null
        }
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = failingRawRepo,
            parsedEventRepository = RoomParsedEventRepository(db.parsedEventDao()),
            bankSmsRegistry = BankSmsRegistry(listOf(AlJaziraSmsAdapter())),
        )
        val source = FakeSmsDataSource(
            listOf(
                InboxRow.Valid(
                    ProviderSmsRecord(
                        "10",
                        "AlJazira",
                        purchaseBody(),
                        Instant.parse("2026-08-01T00:00:00Z"),
                    ),
                ),
            ),
        )
        val result = HistoricalSmsScanner(source, svc).scan()
        assertEquals(1, result.scanned)
        assertEquals(0, result.inserted)
        assertEquals(1, result.failed)
        assertEquals(0, result.parsed)
        assertNull(result.failure)
    }

    @Test
    fun failedWithPersistedRawSmsId_incrementsInsertedAndFailed() = runBlocking {
        val exploding = SmsParseGateway { throw IllegalStateException("parse boom") }
        val svc = ProcessRawSmsUseCase(
            rawSmsRepository = RoomRawSmsRepository(db.rawSmsDao()),
            parsedEventRepository = RoomParsedEventRepository(db.parsedEventDao()),
            bankSmsRegistry = BankSmsRegistry(listOf(AlJaziraSmsAdapter(pipeline = exploding))),
        )
        val source = FakeSmsDataSource(
            listOf(
                InboxRow.Valid(
                    ProviderSmsRecord(
                        "11",
                        "AlJazira",
                        purchaseBody(),
                        Instant.parse("2026-08-01T00:00:00Z"),
                    ),
                ),
            ),
        )
        val result = HistoricalSmsScanner(source, svc).scan()
        assertEquals(1, result.scanned)
        assertEquals(1, result.inserted)
        assertEquals(1, result.failed)
        assertEquals(0, result.parsed)
        assertEquals(1, db.rawSmsDao().count())
        assertNull(result.failure)
    }

    private fun purchaseBody() = """
        شراء عبر الانترنت
        بطاقة: 7271
        لدى: Keeta
        بمبلغ: 51.99 SAR
        في: 14:32 03-08-2026
    """.trimIndent()

    private class FakeSmsDataSource(
        private val rows: List<InboxRow>,
        private val onQuery: (Instant?) -> Unit = {},
    ) : SmsDataSource {
        override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> {
            onQuery(receivedAfter)
            return rows.asSequence()
        }
    }
}
