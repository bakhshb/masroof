package com.baraa.masroof.application.transaction

import com.baraa.masroof.testsupport.EmptyFinancialTransactionRepository
import com.baraa.masroof.testsupport.EmptyReviewRepository
import com.baraa.masroof.testsupport.EmptyParsedEventRepository
import com.baraa.masroof.testsupport.InterpretationTestAdapter
import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.testsupport.SettingsViewModelTestSupport
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TransactionRestoreServiceTest {
    @Test
    fun restore_rollsBackReviewWhenReconcilePostsNothing() = runBlocking {
        val review = ignoredReview()
        val reviewRepo = TrackingReviewRepository(review)
        val service = service(
            reviewRepository = reviewRepo,
            financialTransactionRepository = RollbackFriendlyEmptyFinancialTransactionRepository(),
        )

        val result = service.restore("sms-1")

        assertTrue(result is RestoreResult.Rejected)
        assertEquals("reconcile_failed", (result as RestoreResult.Rejected).reason)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, reviewRepo.lastResolutionKind)
    }

    @Test
    fun restore_rejectsNonResolvedReview() = runBlocking {
        val review = ignoredReview().copy(status = ReviewStatus.REQUIRED, resolvedAt = null)
        val service = service(
            reviewRepository = TrackingReviewRepository(review),
            financialTransactionRepository = SettingsViewModelTestSupport.emptyFinancialTransactionRepository(),
        )

        val result = service.restore("sms-1")

        assertTrue(result is RestoreResult.Rejected)
        assertEquals("not_ignored", (result as RestoreResult.Rejected).reason)
    }

    @Test
    fun restore_reportsRollbackDeleteFailureWhenDeleteFails() = runBlocking {
        val reviewRepo = TrackingReviewRepository(ignoredReview())
        val service = service(
            reviewRepository = reviewRepo,
            financialTransactionRepository = FailingDeleteFinancialTransactionRepository(),
        )

        val result = service.restore("sms-1")

        assertTrue(result is RestoreResult.Rejected)
        assertEquals("rollback_delete_failed", (result as RestoreResult.Rejected).reason)
    }

    @Test
    fun restore_reportsRollbackReviewFailureWhenMarkResolvedFails() = runBlocking {
        val reviewRepo = FailingRollbackReviewRepository(ignoredReview())
        val service = service(
            reviewRepository = reviewRepo,
            financialTransactionRepository = RollbackFriendlyEmptyFinancialTransactionRepository(),
        )

        val result = service.restore("sms-1")

        assertTrue(result is RestoreResult.Rejected)
        assertEquals("rollback_review_failed", (result as RestoreResult.Rejected).reason)
    }

    @Test
    fun restore_rollsBackWhenReclassifyFails() = runBlocking {
        val reviewRepo = TrackingReviewRepository(ignoredReview())
        val tx = sampleTransaction()
        val service = service(
            reviewRepository = reviewRepo,
            financialTransactionRepository = SingleTransactionRepository(tx),
        )

        val result = service.restore("sms-1", FinancialTransactionType.INCOME)

        assertTrue(result is RestoreResult.Rejected)
        assertEquals("parsed_event_missing", (result as RestoreResult.Rejected).reason)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, reviewRepo.lastResolutionKind)
    }

    @Test
    fun restore_keepsPostedRowAndFinancialDecisionWhenReclassifyWriteFails() = runBlocking {
        val reviewRepo = TrackingReviewRepository(ignoredReview())
        val txRepo = FailingUpdateRepository(sampleTransaction())
        val service = SettingsViewModelTestSupport.restoreService(
            reviewRepository = reviewRepo,
            financialTransactionRepository = txRepo,
            clock = InstantClock { Instant.parse("2026-08-02T00:00:00Z") },
            reclassificationEvents = object : EmptyParsedEventRepository() {
                override suspend fun findByRawSmsId(rawSmsId: String) = purchaseRecord().takeIf { rawSmsId == "sms-1" }
            },
        )

        val result = service.restore("sms-1", FinancialTransactionType.INCOME)

        assertEquals(RestoreResult.TypeNotChanged(sampleTransaction()), result)
        assertEquals(ReviewResolutionKind.USER_FINANCIAL_TYPE, reviewRepo.lastResolutionKind)
        assertEquals(0, txRepo.deletes)
    }

    private fun purchaseRecord(): ParsedEventRecord {
        val engine = RuleInterpreter(AlJaziraRuleProfile.current())
        val result = InterpretationTestAdapter { engine.interpretRouted(it, BankId("BANK_ALJAZIRA")) }.parse(
            SmsParseInput("sms-1", "AlJazira", "شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR 60.99\nلدى: TEST\nفي: 2026-10-06 13:57",
                Instant.parse("2026-10-06T12:00:00Z"))) as ParseResult.Success
        return ParsedEventRecord(result.event, result.details)
    }

    private class FailingUpdateRepository(transaction: FinancialTransaction) : SingleTransactionRepository(transaction) {
        var deletes = 0
        override suspend fun update(transaction: FinancialTransaction): Boolean = false
        override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean { deletes++; return true }
    }

    private fun ignoredReview() = ReviewItem(
        id = "review-1",
        rawSmsId = "sms-1",
        kind = ReviewKind.NEEDS_REVIEW,
        reasons = listOf("user_ignored_transaction"),
        status = ReviewStatus.RESOLVED,
        resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        resolvedAt = Instant.parse("2026-08-01T00:00:00Z"),
        resolvedTransactionId = null,
    )

    private fun sampleTransaction() = FinancialTransaction(
        id = "tx-1",
        type = FinancialTransactionType.EXPENSE,
        amount = Money.of("10.00", Currency.SAR),
        occurredAt = Instant.parse("2026-08-01T12:00:00Z"),
        sourceContainerId = null,
        destinationContainerId = null,
        merchant = null,
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = emptyList(),
        appliedExchangeRate = null,
        exchangeRateSource = null,
    )

    private fun service(
        reviewRepository: ReviewRepository,
        financialTransactionRepository: FinancialTransactionRepository,
    ): TransactionRestoreService = SettingsViewModelTestSupport.restoreService(
        reviewRepository = reviewRepository,
        financialTransactionRepository = financialTransactionRepository,
        clock = InstantClock { Instant.parse("2026-08-02T00:00:00Z") },
    )

    private open class TrackingReviewRepository(
        private var review: ReviewItem,
    ) : EmptyReviewRepository() {
        var lastResolutionKind: ReviewResolutionKind? = review.resolutionKind

        override suspend fun getById(id: String): ReviewItem? = review.takeIf { it.id == id }

        override suspend fun findByRawSmsId(rawSmsId: String): ReviewItem? =
            review.takeIf { it.rawSmsId == rawSmsId }

        override suspend fun listAll(): List<ReviewItem> = listOf(review)

        override suspend fun markResolved(
            id: String,
            resolutionKind: ReviewResolutionKind,
            resolvedAt: Instant,
            resolvedTransactionId: String?,
        ): ReviewItem? {
            lastResolutionKind = resolutionKind
            review = review.copy(
                resolutionKind = resolutionKind,
                resolvedAt = resolvedAt,
                resolvedTransactionId = resolvedTransactionId,
            )
            return review
        }
    }

    /** The first write (clear to financial) succeeds; the rollback write fails. */
    private class FailingRollbackReviewRepository(review: ReviewItem) : TrackingReviewRepository(review) {
        private var markResolvedCalls = 0

        override suspend fun markResolved(
            id: String,
            resolutionKind: ReviewResolutionKind,
            resolvedAt: Instant,
            resolvedTransactionId: String?,
        ): ReviewItem? {
            markResolvedCalls += 1
            return if (markResolvedCalls == 1) super.markResolved(id, resolutionKind, resolvedAt, resolvedTransactionId) else null
        }
    }

    private class RollbackFriendlyEmptyFinancialTransactionRepository :
        EmptyFinancialTransactionRepository() {
        override suspend fun unlinkRawSms(rawSmsId: String): Boolean = true
    }

    private class FailingDeleteFinancialTransactionRepository :
        EmptyFinancialTransactionRepository() {
        override suspend fun isRawSmsLinked(rawSmsId: String): Boolean = true
    }

    private open class SingleTransactionRepository(
        private val transaction: FinancialTransaction,
    ) : FinancialTransactionRepository {
        override suspend fun save(transaction: FinancialTransaction, rawSmsIds: Collection<String>) =
            FinancialTransactionSaveResult.Saved

        override suspend fun getById(id: String): FinancialTransaction? =
            transaction.takeIf { it.id == id }

        override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? =
            transaction.takeIf { rawSmsId == "sms-1" }

        override suspend fun listAll(): List<FinancialTransaction> = listOf(transaction)

        override suspend fun listOccurredBetween(
            startInclusive: Instant,
            endExclusive: Instant,
        ): List<FinancialTransaction> = listOf(transaction)

        override suspend fun isRawSmsLinked(rawSmsId: String): Boolean = rawSmsId == "sms-1"

        override suspend fun listRawSmsIds(transactionId: String): List<String> =
            if (transactionId == transaction.id) listOf("sms-1") else emptyList()

        override suspend fun update(transaction: FinancialTransaction): Boolean = true

        override suspend fun updateAppliedExchangeRate(
            id: String,
            exchangeRate: java.math.BigDecimal,
            source: com.baraa.masroof.domain.model.ExchangeRateSource,
        ): Boolean = false

        override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean = true

        override suspend fun unlinkRawSms(rawSmsId: String): Boolean = true

    }
}
