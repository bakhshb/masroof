package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.ReviewIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.ManualReviewResolutionResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.event
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManualReviewResolutionAtomicityTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var store: RoomManualReviewResolutionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        store = RoomManualReviewResolutionRepository(db, ftRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun singleResolution_missingReview_rollsBackFinancialTransaction() = runBlocking {
        persistSmsAndEvent(
            smsId = "sms-bill",
            event = event(
                id = "pe-bill",
                rawSmsId = "sms-bill",
                family = MessageFamily.BILL_PAYMENT,
                amount = money("80.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-bill",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("bill_payment_financial_treatment_unresolved"),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-bill")),
            type = FinancialTransactionType.EXPENSE,
            amount = money("80.00"),
            occurredAt = Instant.parse("2026-08-11T12:00:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-bill"),
        )
        val result = store.persistSingleResolution(
            transaction = tx,
            rawSmsIds = listOf("sms-bill"),
            reviewId = "review:does-not-exist",
            resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE,
            resolvedAt = Instant.parse("2026-08-11T12:00:00Z"),
        )
        assertTrue(result is ManualReviewResolutionResult.Failed)
        assertEquals(0, ftRepo.listAll().size)
        assertFalse(ftRepo.isRawSmsLinked("sms-bill"))
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-bill")!!.status)
    }

    @Test
    fun pairResolution_secondReviewMissing_rollsBackEverything() = runBlocking {
        persistSmsAndEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "9999"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        persistSmsAndEvent(
            smsId = "sms-in",
            event = event(
                id = "pe-in",
                rawSmsId = "sms-in",
                bank = Bank("D360"),
                family = MessageFamily.TRANSFER_IN,
                amount = money("500.00"),
                destination = AccountReference(Bank("D360"), "6810"),
                network = BankNetworkType.INTER_BANK,
                direction = MoneyDirection.OUTGOING,
            ),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-out",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        reviewRepo.upsertRequired(
            rawSmsId = "sms-in",
            kind = ReviewKind.PENDING_MATCH,
            reasons = listOf("transfer_pending_match"),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-out", "sms-in")),
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = money("500.00"),
            occurredAt = Instant.parse("2026-08-11T12:00:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = FinancialContainerIdFactory.accountId(Bank("D360"), "6810"),
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-in", "pe-out"),
        )
        val result = store.persistPairResolution(
            transaction = tx,
            rawSmsIds = listOf("sms-out", "sms-in"),
            firstReviewId = ReviewIdFactory.fromRawSmsId("sms-out"),
            secondReviewId = "review:missing-incoming",
            resolutionKind = ReviewResolutionKind.USER_SELF_TRANSFER_PAIR,
            resolvedAt = Instant.parse("2026-08-11T12:00:00Z"),
        )
        assertTrue(result is ManualReviewResolutionResult.Failed)
        assertEquals(0, ftRepo.listAll().size)
        assertFalse(ftRepo.isRawSmsLinked("sms-out"))
        assertFalse(ftRepo.isRawSmsLinked("sms-in"))
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-out")!!.status)
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-in")!!.status)
        assertNull(reviewRepo.findByRawSmsId("sms-out")!!.resolvedAt)
        assertNull(reviewRepo.findByRawSmsId("sms-in")!!.resolvedAt)
    }

    @Test
    fun singleResolution_success_commitsFtAndReviewTogether() = runBlocking {
        persistSmsAndEvent(
            smsId = "sms-bill",
            event = event(
                id = "pe-bill",
                rawSmsId = "sms-bill",
                family = MessageFamily.BILL_PAYMENT,
                amount = money("80.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        val review = reviewRepo.upsertRequired(
            rawSmsId = "sms-bill",
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("bill_payment_financial_treatment_unresolved"),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-bill")),
            type = FinancialTransactionType.EXPENSE,
            amount = money("80.00"),
            occurredAt = Instant.parse("2026-08-11T12:00:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-bill"),
        )
        val stale = store.persistSingleResolution(tx, listOf("sms-bill"), review.id,
            ReviewResolutionKind.USER_FINANCIAL_TYPE, Instant.parse("2026-08-11T12:05:00Z"),
            validateBeforeWrite = {
                assertTrue("Evidence must be checked under the financial write transaction", db.inTransaction())
                false
            })
        assertEquals("transfer_evidence_changed", (stale as ManualReviewResolutionResult.Failed).reason)
        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(ReviewStatus.REQUIRED, reviewRepo.findByRawSmsId("sms-bill")!!.status)
        val result = store.persistSingleResolution(
            transaction = tx,
            rawSmsIds = listOf("sms-bill"),
            reviewId = review.id,
            resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE,
            resolvedAt = Instant.parse("2026-08-11T12:05:00Z"),
        ) as ManualReviewResolutionResult.Success
        assertEquals(1, ftRepo.listAll().size)
        assertTrue(ftRepo.isRawSmsLinked("sms-bill"))
        assertEquals(ReviewStatus.RESOLVED, result.reviews.single().status)
        assertEquals(ReviewResolutionKind.USER_FINANCIAL_TYPE, result.reviews.single().resolutionKind)
        assertEquals(tx.id, result.reviews.single().resolvedTransactionId)
    }

    private suspend fun persistSmsAndEvent(
        smsId: String,
        event: ParsedEvent,
    ) {
        val body = "body-$smsId"
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "AlJazira",
                body = body,
                receivedAt = Instant.parse("2026-08-01T12:00:00Z"),
                deviceMessageId = smsId,
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        parsedRepo.save(event, ParsedEventDetails())
    }

    private fun money(v: String) = Money.of(BigDecimal(v), Currency.SAR)
}
