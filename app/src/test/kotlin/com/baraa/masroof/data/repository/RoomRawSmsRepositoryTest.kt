package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.sms.hash.SmsBodyHasher
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomRawSmsRepositoryTest {

    private lateinit var db: MasroofDatabase
    private lateinit var repo: RoomRawSmsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomRawSmsRepository(db.rawSmsDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun listIdsByReceivedAt_returnsEveryRowOldestFirst() = runBlocking {
        repo.insertIfAbsent(raw("sms-c", "2026-08-03T10:00:00Z"))
        repo.insertIfAbsent(raw("sms-a", "2026-08-01T10:00:00Z"))
        repo.insertIfAbsent(raw("sms-b", "2026-08-02T10:00:00Z"))

        assertEquals(listOf("sms-a", "sms-b", "sms-c"), repo.listIdsByReceivedAt())
    }

    @Test
    fun listIdsByReceivedAt_isEmptyWithoutEvidence() = runBlocking {
        assertTrue(repo.listIdsByReceivedAt().isEmpty())
    }

    @Test
    fun duplicateInsert_doesNotAddAnotherId() = runBlocking {
        val row = raw("sms-a", "2026-08-01T10:00:00Z")
        assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(row))
        assertEquals(RawSmsInsertResult.AlreadyExists, repo.insertIfAbsent(row))
        assertEquals(listOf("sms-a"), repo.listIdsByReceivedAt())
    }

    @Test
    fun listIdsAwaitingProcessing_excludesRowsWithParsedEventOrReview() = runBlocking {
        val parsed = raw("sms-parsed", "2026-08-01T10:00:00Z", PURCHASE_BODY)
        val reviewed = raw("sms-reviewed", "2026-08-02T10:00:00Z")
        val pendingLater = raw("sms-pending-b", "2026-08-04T10:00:00Z")
        val pendingEarlier = raw("sms-pending-a", "2026-08-03T10:00:00Z")
        listOf(parsed, reviewed, pendingLater, pendingEarlier).forEach { repo.insertIfAbsent(it) }
        val success = AlJaziraParsingPipeline().parse(
            SmsParseInput(parsed.id, parsed.sender, parsed.body, parsed.receivedAt),
        ) as ParseResult.Success
        RoomParsedEventRepository(db.parsedEventDao()).save(success.event, success.details)
        IngestionReviewService(RoomReviewRepository(db.reviewItemDao()), InstantClock.System)
            .requireReview(reviewed.id, IngestionReviewService.REASON_UNSUPPORTED_FORMAT)

        assertEquals(listOf("sms-pending-a", "sms-pending-b"), repo.listIdsAwaitingProcessing())
    }

    @Test
    fun listIdsAwaitingProcessing_isEmptyWithoutEvidence() = runBlocking {
        assertTrue(repo.listIdsAwaitingProcessing().isEmpty())
    }

    private fun raw(id: String, at: String, body: String = "body-$id"): RawSms {
        return RawSms(
            id = id,
            sender = "AlJazira",
            body = body,
            receivedAt = Instant.parse(at),
            deviceMessageId = id,
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
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
