package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CoreEvidenceRepositoryContractTest {
    private lateinit var database: MasroofDatabase
    private lateinit var rawSms: RoomRawSmsRepository
    private lateinit var reviews: RoomReviewRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawSms = RoomRawSmsRepository(database.rawSmsDao())
        reviews = RoomReviewRepository(database.reviewItemDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rawSmsInsert_isIdempotentForTheSameEvidence() = runBlocking {
        val evidence = rawSms(id = "sms-1", deviceMessageId = "device-1")

        assertEquals(RawSmsInsertResult.Inserted, rawSms.insertIfAbsent(evidence))
        assertEquals(RawSmsInsertResult.AlreadyExists, rawSms.insertIfAbsent(evidence))
        assertEquals(evidence, rawSms.getById("sms-1"))
        assertTrue(rawSms.existsById("sms-1"))
    }

    @Test
    fun rawSmsInsert_rejectsSameDeviceMessageIdAcrossDifferentRows() = runBlocking {
        assertEquals(RawSmsInsertResult.Inserted, rawSms.insertIfAbsent(rawSms("sms-1", "device-1")))

        assertEquals(
            RawSmsInsertResult.AlreadyExists,
            rawSms.insertIfAbsent(rawSms("sms-2", "device-1")),
        )
        assertNull(rawSms.getById("sms-2"))
    }

    /** A queue refresh racing a bank choice must never drop the user's selection. */
    @Test
    fun concurrentReviewUpserts_keepTheBankSelection() = runBlocking {
        val selection = com.baraa.masroof.domain.model.ExplicitBankSelection.reasonFor(com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA)
        repeat(20) { round ->
            val id = "sms-race-$round"
            rawSms.insertIfAbsent(rawSms(id, "device-race-$round"))
            reviews.upsertRequired(id, ReviewKind.NEEDS_REVIEW, listOf("ambiguous_bank_route"), now)
            listOf(listOf(selection), listOf("missing_amount")).map { reasons ->
                async(Dispatchers.IO) { reviews.upsertRequired(id, ReviewKind.NEEDS_REVIEW, reasons, now) }
            }.awaitAll()

            assertTrue("round $round", selection in reviews.findByRawSmsId(id)!!.reasons)
        }
    }

    @Test
    fun reviewUpsert_mergesReasonsButDoesNotReopenResolvedReview() = runBlocking {
        rawSms.insertIfAbsent(rawSms("sms-1", "device-1"))
        val initial = reviews.upsertRequired(
            rawSmsId = "sms-1",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("missing_amount", "missing_amount"),
            now = now,
        )
        assertEquals(listOf("missing_amount"), initial.reasons)

        val resolved = reviews.markResolved(
            id = initial.id,
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = now.plusSeconds(1),
            resolvedTransactionId = null,
        )!!
        val afterRefresh = reviews.upsertRequired(
            rawSmsId = "sms-1",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = now.plusSeconds(2),
        )

        assertEquals(ReviewStatus.RESOLVED, resolved.status)
        assertEquals(resolved, afterRefresh)
        assertTrue(reviews.listRequired().isEmpty())
    }

    private fun rawSms(id: String, deviceMessageId: String) = RawSms(
        id = id,
        sender = "AlJazira",
        body = "body-$id",
        receivedAt = now,
        deviceMessageId = deviceMessageId,
        bodyHash = "hash-$id",
    )

    private companion object {
        val now: Instant = Instant.parse("2026-08-10T09:00:00Z")
    }
}
