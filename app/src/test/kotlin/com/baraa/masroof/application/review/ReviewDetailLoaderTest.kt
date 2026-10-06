package com.baraa.masroof.application.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewDetailLoaderTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var ingestionReviews: IngestionReviewService
    private lateinit var loader: ReviewDetailLoader
    private val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        val parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        val correctionRepo = RoomUserCorrectionRepository(db.userCorrectionDao())
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val loans = RoomLoanRegistryRepository.from(db)
        val resolver = OwnershipResolver(accounts, cards, loans)
        val effective = EffectiveParsedEventProvider(parsedRepo, correctionRepo)
        val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = resolver,
            effectiveParsedEventProvider = effective,
            reviewRepository = reviewRepo,
        )
        val workflow = ReviewWorkflowService(
            reviewRepository = reviewRepo,
            userCorrectionRepository = correctionRepo,
            financialTransactionRepository = ftRepo,
            rawSmsRepository = rawRepo,
            ownershipResolver = resolver,
            ownershipConfirmationService = OwnershipConfirmationService(accounts, cards, loans),
            effectiveParsedEventProvider = effective,
            reconciliationService = reconciliation,
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, clock),
            manualReviewResolutionRepository = RoomManualReviewResolutionRepository(db, ftRepo),
            clock = clock,
        )
        ingestionReviews = IngestionReviewService(reviewRepo, clock)
        loader = ReviewDetailLoader(workflow, rawRepo, effective)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ingestionReviewWithoutParsedEvent_showsSenderBodyAndDate() = runBlocking {
        val body = "رسالة بنكية بصيغة غير مدعومة"
        val receivedAt = Instant.parse("2026-08-10T09:30:00Z")
        rawRepo.insertIfAbsent(
            RawSms(
                id = "sms-unsupported",
                sender = "AlJazira",
                body = body,
                receivedAt = receivedAt,
                deviceMessageId = "u1",
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        val review = ingestionReviews.requireReview(
            "sms-unsupported",
            IngestionReviewService.REASON_UNSUPPORTED_FORMAT,
        )

        val detail = loader.loadDetail(review.id)!!
        assertEquals("AlJazira", detail.sender)
        assertEquals(body, detail.body)
        assertEquals(receivedAt, detail.receivedAt)
        assertNull(detail.messageFamily)
        assertNull(detail.amount)

        val summary = loader.loadSummaries().single()
        assertEquals(review.id, summary.review.id)
        assertEquals(body, summary.body)
        assertEquals(listOf("unsupported_bank_message_format"), summary.review.reasons)
    }
}
