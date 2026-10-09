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

    @Test
    fun insertIfAbsent_twoProviderIds_sameInstantAndBody_bothPersist() = runBlocking {
        val at = "2026-08-03T14:32:00.000Z"
        val body = "identical-notice"
        val first = evidence("android-sms:10", at, deviceMessageId = "10", body = body)
        val second = evidence("android-sms:11", at, deviceMessageId = "11", body = body)
        val third = evidence("android-sms:12", at, deviceMessageId = "12", body = body)

        assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(first))
        assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(second))
        assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(third))
        assertEquals(RawSmsInsertResult.AlreadyExists, repo.insertIfAbsent(first))
        assertEquals(RawSmsInsertResult.AlreadyExists, repo.insertIfAbsent(second))
        assertEquals(first, repo.getById(first.id))
        assertEquals(second, repo.getById(second.id))
        assertEquals(third, repo.getById(third.id))
        assertEquals(listOf(first.id, second.id, third.id), repo.listIdsByReceivedAt())
    }

    @Test
    fun insertIfAbsent_sameInstantLiveAndInbox_collapsesToTheFirstRow() = runBlocking {
        val at = "2026-08-03T14:32:00.000Z"
        val body = "identical-notice"
        val live = evidence("android-sms-live:1", at, deviceMessageId = null, body = body)
        val inbox = evidence("android-sms:42", at, deviceMessageId = "42", body = body)

        assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(live))
        assertEquals(RawSmsInsertResult.AlreadyExists, repo.insertIfAbsent(inbox))
        assertEquals(live, repo.getById(live.id))
        assertEquals(listOf(live.id), repo.listIdsByReceivedAt())
    }

    @Test
    fun insertIfAbsent_sameInstantInboxThenLive_collapsesToTheInboxRow() = runBlocking {
        val at = "2026-08-03T14:32:00.000Z"
        val body = "identical-notice"
        val inbox = evidence("android-sms:42", at, deviceMessageId = "42", body = body)
        val live = evidence("android-sms-live:1", at, deviceMessageId = null, body = body)

        assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(inbox))
        assertEquals(RawSmsInsertResult.AlreadyExists, repo.insertIfAbsent(live))
        assertEquals(inbox, repo.getById(inbox.id))
        assertEquals(listOf(inbox.id), repo.listIdsByReceivedAt())
    }

    @Test
    fun listCrossSourceNearDuplicates_matchesOnlyTheOppositeSourceInsideInclusiveBounds() = runBlocking {
        val body = "same-body"
        val liveEarly = evidence("live-early", "2026-08-03T14:00:00.000Z", deviceMessageId = null, body = body)
        val liveMiddle = evidence("live-middle", "2026-08-03T14:00:01.000Z", deviceMessageId = null, body = body)
        val liveLate = evidence("live-late", "2026-08-03T14:00:02.000Z", deviceMessageId = null, body = body)
        val inbox = evidence("android-sms:9", "2026-08-03T14:00:01.500Z", deviceMessageId = "9", body = body)
        val otherSender = evidence(
            "android-sms:other",
            "2026-08-03T14:00:01.000Z",
            deviceMessageId = "other",
            body = body,
            sender = "OtherBank",
        )
        val otherBody = evidence(
            "live-other-body",
            "2026-08-03T14:00:01.000Z",
            deviceMessageId = null,
            body = "different-body",
        )
        listOf(liveEarly, liveMiddle, liveLate, inbox, otherSender, otherBody).forEach {
            assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(it))
        }

        val from = Instant.parse("2026-08-03T14:00:00.000Z")
        val to = Instant.parse("2026-08-03T14:00:02.000Z")
        assertEquals(
            listOf(liveEarly.id, liveMiddle.id),
            repo.listCrossSourceNearDuplicates(
                sender = "AlJazira",
                bodyHash = liveEarly.bodyHash,
                fromInclusive = from,
                toInclusive = to,
                lookingForLiveRow = true,
            ).map { it.id },
        )
        assertEquals(
            listOf(inbox.id),
            repo.listCrossSourceNearDuplicates(
                sender = "AlJazira",
                bodyHash = liveEarly.bodyHash,
                fromInclusive = from,
                toInclusive = to,
                lookingForLiveRow = false,
            ).map { it.id },
        )
        assertEquals(
            listOf(liveMiddle.id),
            repo.listCrossSourceNearDuplicates(
                sender = "AlJazira",
                bodyHash = liveEarly.bodyHash,
                fromInclusive = from.plusMillis(1),
                toInclusive = to.minusMillis(1),
                lookingForLiveRow = true,
            ).map { it.id },
        )
        assertEquals(
            listOf(liveLate.id),
            repo.listCrossSourceNearDuplicates(
                sender = "AlJazira",
                bodyHash = liveEarly.bodyHash,
                fromInclusive = liveLate.receivedAt,
                toInclusive = liveLate.receivedAt,
                lookingForLiveRow = true,
            ).map { it.id },
        )
    }

    private fun raw(id: String, at: String, body: String = "body-$id"): RawSms =
        evidence(id = id, at = at, deviceMessageId = id, body = body)

    private fun evidence(
        id: String,
        at: String,
        deviceMessageId: String?,
        body: String = "body-$id",
        sender: String = "AlJazira",
    ): RawSms = RawSms(
        id = id,
        sender = sender,
        body = body,
        receivedAt = Instant.parse(at),
        deviceMessageId = deviceMessageId,
        bodyHash = SmsBodyHasher.sha256Hex(body),
    )

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
