package com.baraa.masroof.application.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.ReviewIdFactory
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IngestionReviewServiceTest {

    private lateinit var db: MasroofDatabase
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var service: IngestionReviewService
    private val now = AtomicReference(Instant.parse("2026-08-11T12:00:00Z"))

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        service = IngestionReviewService(reviewRepo, InstantClock { now.get() })
        RoomRawSmsRepository(db.rawSmsDao()).insertIfAbsent(
            RawSms(
                id = "sms-1",
                sender = "AlJazira",
                body = "body",
                receivedAt = Instant.parse("2026-08-11T10:00:00Z"),
                deviceMessageId = "1",
                bodyHash = SmsBodyHasher.sha256Hex("body"),
            ),
        )
        Unit
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun requireReview_createsRequiredNeedsReviewKeyedByRawSms() = runBlocking {
        val review = service.requireReview("sms-1", IngestionReviewService.REASON_UNSUPPORTED_FORMAT)
        assertEquals(ReviewIdFactory.fromRawSmsId("sms-1"), review.id)
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertEquals(ReviewKind.NEEDS_REVIEW, review.kind)
        assertEquals(listOf("unsupported_bank_message_format"), review.reasons)
    }

    @Test
    fun requireReview_isIdempotentAndPreservesCreatedAt() = runBlocking {
        val first = service.requireReview("sms-1", IngestionReviewService.REASON_INVALID_PARSED_EVENT)
        now.set(Instant.parse("2026-08-11T13:00:00Z"))
        val second = service.requireReview("sms-1", IngestionReviewService.REASON_PROCESSING_ERROR)
        assertEquals(1, reviewRepo.listAll().size)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals(listOf("processing_error"), reviewRepo.findByRawSmsId("sms-1")!!.reasons)
    }

    @Test
    fun requireReview_neverReopensUserResolution() = runBlocking {
        val created = service.requireReview("sms-1", IngestionReviewService.REASON_UNSUPPORTED_FORMAT)
        reviewRepo.markResolved(
            id = created.id,
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = now.get(),
            resolvedTransactionId = null,
        )
        service.requireReview("sms-1", IngestionReviewService.REASON_UNSUPPORTED_FORMAT)
        val stored = reviewRepo.findByRawSmsId("sms-1")!!
        assertEquals(ReviewStatus.RESOLVED, stored.status)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, stored.resolutionKind)
    }
}
