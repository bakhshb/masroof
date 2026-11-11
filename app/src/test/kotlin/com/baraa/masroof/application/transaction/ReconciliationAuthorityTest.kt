package com.baraa.masroof.application.transaction

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.ownership.OwnershipResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import com.baraa.masroof.testsupport.NoOpAppLog
import com.baraa.masroof.testsupport.noOpReclassificationRepository
import com.baraa.masroof.testsupport.noOpProcessingRecovery
import com.baraa.masroof.testsupport.noOpReviewQueueUpdater
import com.baraa.masroof.testsupport.EmptyReviewRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReconciliationAuthorityTest : ReconciliationTestFixture() {
    @Test
    fun staleOtpLinkedTransaction_isRemovedOnReconcile() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val otpBody =
            """
            One Time Password for Online Purchase
            Code: 8811
            For: SAUDI ELECTRICITY COMPANY
            Amount: SAR 438.5
            Date: 2026-08-12 07:49
            """.trimIndent()
        persistEvent(
            smsId = "sms-otp-dup",
            body = otpBody,
            event = event(
                id = "pe-otp-dup",
                rawSmsId = "sms-otp-dup",
                family = MessageFamily.OTP,
                amount = null,
            ),
        )
        val staleTx = com.baraa.masroof.domain.model.FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-otp-dup")),
            type = FinancialTransactionType.EXPENSE,
            amount = money("438.50"),
            occurredAt = Instant.parse("2026-08-12T04:49:00Z"),
            sourceContainerId = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"),
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-otp-dup"),
        )
        ftRepo.save(staleTx, listOf("sms-otp-dup"))
        assertEquals(1, ftRepo.listAll().size)

        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, summary.assembledSingle)
        assertEquals(1, summary.ignored)
        assertTrue(ftRepo.listAll().isEmpty())
        assertFalse(ftRepo.isRawSmsLinked("sms-otp-dup"))
    }

    @Test
    fun unknownWithInformationalWording_staysInReview() = runBlocking {
        persistEvent(
            smsId = "sms-unknown-statement",
            body = """
                بطاقة إئتمانية: إصدار كشف حساب
                إجمالي المبلغ المستحق: SAR 0.00
                تاريخ الاستحقاق: 07/09/2026
            """.trimIndent(),
            event = event(
                id = "pe-unknown-statement",
                rawSmsId = "sms-unknown-statement",
                family = MessageFamily.UNKNOWN,
                amount = money("0.00"),
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, summary.ignored)
        assertEquals(0, summary.assembledSingle)
        assertEquals(1, summary.needsReview)
        assertTrue(ftRepo.listAll().isEmpty())
    }

    @Test
    fun linkedPurchase_isNotRemovedBecauseBodyHasInformationalWording() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-linked-buy",
            body = "شراء\nتاريخ الاستحقاق: 25/08/2026\nالمبلغ المستحق: 10 SAR",
            event = event(
                id = "pe-linked-buy",
                rawSmsId = "sms-linked-buy",
                family = MessageFamily.PURCHASE,
                amount = money("40.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        assertEquals(1, ftRepo.listAll().size)

        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(1, summary.alreadyLinked)
        assertEquals(0, summary.ignored)
        assertEquals(1, ftRepo.listAll().size)
    }

    @Test
    fun unknownBeneficiaryNotice_staysInReview() = runBlocking {
        val body = """
            اسم المستفيد : براء ف بن
            الاسم المختصر : حسابي D360
            حالة: غير نشط
            حساب: SA2036036036045864332670
            بنك: D360 بنك
            في : 14:04 2026-07-29
        """.trimIndent()
        persistEvent(
            smsId = "sms-info",
            event = event(
                id = "pe-info",
                rawSmsId = "sms-info",
                family = MessageFamily.UNKNOWN,
                amount = null,
            ),
            body = body,
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertEquals(0, summary.ignored)
        assertEquals(1, summary.needsReview)
    }

    @Test
    fun parsedNonFinancialBeneficiary_isIgnoredWithoutReadingBody() = runBlocking {
        persistEvent(
            smsId = "sms-beneficiary",
            body = """
                اسم المستفيد : براء ف بن
                حالة: غير نشط
            """.trimIndent(),
            event = event(
                id = "pe-beneficiary",
                rawSmsId = "sms-beneficiary",
                family = MessageFamily.NON_FINANCIAL,
                amount = null,
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertEquals(1, summary.ignored)
        assertEquals(0, summary.needsReview)
    }

    @Test
    fun unknownWithMoneyInBodyButNoParsedAmount_stillNeedsReview() = runBlocking {
        persistEvent(
            smsId = "sms-parse-fail",
            event = event(
                id = "pe-parse-fail",
                rawSmsId = "sms-parse-fail",
                family = MessageFamily.UNKNOWN,
                amount = null,
            ),
            body = "عملية غير معروفة بمبلغ: 15000.00 SAR",
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertTrue(summary.needsReview >= 1)
    }

    @Test
    fun balanceAndNonFinancial_ignored() = runBlocking {
        persistEvent(
            smsId = "sms-bal",
            event = event(
                id = "pe-bal",
                rawSmsId = "sms-bal",
                family = MessageFamily.BALANCE_NOTICE,
                amount = null,
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        persistEvent(
            smsId = "sms-nf",
            event = event(
                id = "pe-nf",
                rawSmsId = "sms-nf",
                family = MessageFamily.NON_FINANCIAL,
                amount = null,
            ),
            at = Instant.parse("2026-08-02T00:00:00Z"),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertTrue(summary.ignored >= 2)
    }

    @Test
    fun missingAmount_needsReview() = runBlocking {
        persistEvent(
            smsId = "sms-na",
            event = event(
                id = "pe-na",
                rawSmsId = "sms-na",
                family = MessageFamily.PURCHASE,
                amount = null,
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertTrue(summary.needsReview >= 1)
    }

    @Test
    fun reviewRequiredPurchaseWithAmount_neverBecomesExpense() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-rr-buy",
            event = event(
                id = "pe-rr-buy",
                rawSmsId = "sms-rr-buy",
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
                merchant = "Keeta",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(0, report.summary.assembledSingle)
        assertEquals(1, report.summary.needsReview)
        val candidate = report.reviewCandidates.single()
        assertEquals("sms-rr-buy", candidate.rawSmsId)
        assertEquals(ReviewKind.NEEDS_REVIEW, candidate.kind)
        assertTrue(candidate.reasons.contains("parse_review_required"))
        assertFalse("sms-rr-buy" in report.settledRawSmsIds)
    }

    @Test
    fun partialPurchase_neverAutoCreatesTransaction() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-partial",
            event = event(
                id = "pe-partial",
                rawSmsId = "sms-partial",
                family = MessageFamily.PURCHASE,
                amount = money("10.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                status = ParseStatus.PARTIAL,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertTrue(report.reviewCandidates.single().reasons.contains("parse_partial"))
    }

    @Test
    fun reviewRequiredTransfers_areNotPairedOrPostedAsExternal() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-rr-out",
            event = event(
                id = "pe-rr-out",
                rawSmsId = "sms-rr-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        persistEvent(
            smsId = "sms-rr-in",
            event = event(
                id = "pe-rr-in",
                rawSmsId = "sms-rr-in",
                family = MessageFamily.TRANSFER_IN,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        persistEvent(
            smsId = "sms-rr-ext",
            event = event(
                id = "pe-rr-ext",
                rawSmsId = "sms-rr-ext",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(0, report.summary.matchedPairs)
        assertEquals(
            setOf("sms-rr-out", "sms-rr-in", "sms-rr-ext"),
            report.reviewCandidates.map { it.rawSmsId }.toSet(),
        )
        assertTrue(report.reviewCandidates.all { it.kind == ReviewKind.NEEDS_REVIEW })
        assertTrue(report.reviewCandidates.all { "parse_review_required" in it.reasons })
    }

    @Test
    fun reviewRequiredTransfer_doesNotUpgradeStaleExternalToSelfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-ok-out",
            event = event(
                id = "pe-ok-out",
                rawSmsId = "sms-ok-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        val staleExternal = com.baraa.masroof.domain.model.FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-ok-out")),
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            amount = money("700.00"),
            occurredAt = Instant.parse("2026-08-01T12:00:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-ok-out"),
        )
        ftRepo.save(staleExternal, listOf("sms-ok-out"))
        persistEvent(
            smsId = "sms-rr-in2",
            event = event(
                id = "pe-rr-in2",
                rawSmsId = "sms-rr-in2",
                family = MessageFamily.TRANSFER_IN,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, tx.type)
        assertFalse(ftRepo.isRawSmsLinked("sms-rr-in2"))
    }

    @Test
    fun nonFinancialStatus_isIgnoredEvenWithFinancialFamily() = runBlocking {
        persistEvent(
            smsId = "sms-nf",
            event = event(
                id = "pe-nf",
                rawSmsId = "sms-nf",
                family = MessageFamily.FEE,
                amount = money("1.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                status = ParseStatus.NON_FINANCIAL,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertTrue(report.reviewCandidates.isEmpty())
        assertEquals(1, report.summary.ignored)
    }

    @Test
    fun merchantOnlyCorrection_doesNotLiftParseStatusGate() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val correctionRepo = com.baraa.masroof.data.repository.RoomUserCorrectionRepository(db.userCorrectionDao())
        val withCorrections = TransactionReconciliationService(
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
                parsedRepo,
                correctionRepo,
            ),
            reviewRepository = EmptyReviewRepository(),
        )
        persistEvent(
            smsId = "sms-rr-merchant-only",
            event = event(
                id = "pe-rr-merchant-only",
                rawSmsId = "sms-rr-merchant-only",
                family = MessageFamily.PURCHASE,
                amount = money("42.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                merchant = "Original",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        correctionRepo.save(
            com.baraa.masroof.domain.model.UserCorrection(
                id = "corr-rr-merchant-only",
                targetRawSmsId = "sms-rr-merchant-only",
                correctedType = null,
                correctedAmount = null,
                correctedMerchant = "Corrected Merchant",
                correctedCounterparty = null,
                createdAt = Instant.parse("2026-08-02T12:00:00Z"),
            ),
        )

        val report = withCorrections.reconcileStoredEventsDetailed()

        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(1, report.summary.needsReview)
        assertTrue(report.reviewCandidates.single().reasons.contains("parse_review_required"))
    }

    @Test
    fun restoreIgnoredReviewRequiredPurchase_assemblesExpense() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val correctionRepo = com.baraa.masroof.data.repository.RoomUserCorrectionRepository(db.userCorrectionDao())
        val effective = com.baraa.masroof.application.review.EffectiveParsedEventProvider(parsedRepo, correctionRepo)
        val resolver = OwnershipResolver(accounts, cards, loans)
        val withReviews = TransactionReconciliationService(
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = resolver,
            ownershipConfirmationService = confirmation,
            reviewRepository = reviewRepo,
            effectiveParsedEventProvider = effective,
        )
        val clock = com.baraa.masroof.sms.time.InstantClock { Instant.parse("2026-08-02T12:00:00Z") }
        persistEvent(
            smsId = "sms-rr-restore",
            event = event(
                id = "pe-rr-restore",
                rawSmsId = "sms-rr-restore",
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                merchant = "Keeta",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val review = reviewRepo.upsertRequired(
            "sms-rr-restore",
            ReviewKind.NEEDS_REVIEW,
            listOf("parse_review_required"),
            clock.now(),
        )
        reviewRepo.markResolved(
            id = review.id,
            resolutionKind = com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = clock.now(),
            resolvedTransactionId = null,
        )
        withReviews.reconcileStoredEvents()
        assertTrue(ftRepo.listAll().isEmpty())

        val restore = TransactionRestoreService(
            reviewRepository = reviewRepo,
            financialTransactionRepository = ftRepo,
            reconciliation = withReviews,
            reclassification = TransactionReclassificationService(ftRepo, effective, resolver, confirmation, appLogService = NoOpAppLog, persistence = noOpReclassificationRepository(ftRepo)),
            clock = clock,
            appLogService = NoOpAppLog,
            processingRecovery = noOpProcessingRecovery(),
            reviewQueueUpdater = noOpReviewQueueUpdater(),
        )
        val result = restore.restore("sms-rr-restore")

        assertTrue("restore result: $result", result is RestoreResult.Success)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXPENSE, tx.type)
        assertEquals(money("51.99"), tx.amount)
    }

}
