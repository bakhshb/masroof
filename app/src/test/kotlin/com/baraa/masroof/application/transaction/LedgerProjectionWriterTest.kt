package com.baraa.masroof.application.transaction

import androidx.room.withTransaction
import com.baraa.masroof.data.repository.RoomCommitmentRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.testsupport.EmptyReviewRepository
import com.baraa.masroof.testsupport.NoOpUserDecisionRepository
import com.baraa.masroof.testsupport.noOpEffectiveParsedEventProvider
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The plan 3.3a writer: what it writes, what follows a re-keyed row, atomicity, the guard and the debug switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerProjectionWriterTest : ReconciliationTestFixture() {
    private val source = AccountReference(Bank.BANK_ALJAZIRA, "3001")
    private val destination = AccountReference(Bank.BANK_ALJAZIRA, "3003")
    private val card = CardReference(Bank.BANK_ALJAZIRA, "7271")
    private val pairId = TransactionIdFactory.fromRawSmsIds(listOf(IN, OUT))

    @Test
    fun reDerivedPairId_carriesItsExchangeRatePairCommitmentAndReviewToTheNewId() = runBlocking {
        persistLegs()
        val legacy = pair().copy(id = "transaction:legacy-pair", appliedExchangeRate = BigDecimal("3.75"),
            exchangeRateSource = ExchangeRateSource.HISTORICAL_MERCHANT)
        assertEquals(FinancialTransactionSaveResult.Saved, ftRepo.save(legacy, listOf(OUT, IN)))
        commit(legacy.id)
        val reviews = RoomReviewRepository(db.reviewItemDao())
        val review = reviews.upsertRequired(OUT, ReviewKind.PENDING_MATCH, listOf("transfer_pending_match"), AT)
        reviews.markResolved(review.id, ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED, AT, legacy.id)

        val report = writing().reconcileStoredEventsDetailed()

        assertTrue(ReconciliationCompletionPolicy.isComplete(report))
        val row = ftRepo.listAll().single()
        assertEquals(pairId, row.id)
        assertEquals(BigDecimal("3.75") to ExchangeRateSource.HISTORICAL_MERCHANT, row.appliedExchangeRate to row.exchangeRateSource)
        assertEquals(pairId, RoomCommitmentRepository.from(db).listAll().single().sourceTransactionId)
        assertEquals(pairId, reviews.findByRawSmsId(OUT)?.resolvedTransactionId)
    }

    @Test
    fun aLegJoiningItsCounterpart_movesTheCommitmentToThePair() = runBlocking {
        persistLegs()
        val external = pair().copy(id = TransactionIdFactory.fromRawSmsIds(listOf(OUT)),
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT, destinationContainerId = null, linkedParsedEventIds = listOf("pe-out"))
        ftRepo.save(external, listOf(OUT))
        commit(external.id)

        writing().reconcileStoredEventsDetailed()

        val row = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, row.type)
        assertEquals(setOf(OUT, IN), ftRepo.listRawSmsIds(row.id).toSet())
        assertEquals(row.id, RoomCommitmentRepository.from(db).listAll().single().sourceTransactionId)
    }

    @Test
    fun aFailureMidWrite_rollsTheWholeWriteBack() = runBlocking {
        persistPurchases()
        var saves = 0
        val failing = object : FinancialTransactionRepository by ftRepo {
            override suspend fun save(transaction: FinancialTransaction, rawSmsIds: Collection<String>) =
                if (++saves == 2) error("disk full") else ftRepo.save(transaction, rawSmsIds)
        }

        try {
            writing(failing).reconcileStoredEventsDetailed()
            fail("the write did not fail")
        } catch (expected: IllegalStateException) {
            assertEquals("disk full", expected.message)
        }
        assertTrue("a partial write survived", ftRepo.listAll().isEmpty())

        val retried = writing().reconcileAffectedRawSmsIds(listOf(P1, P2))
        assertTrue(ReconciliationCompletionPolicy.isComplete(retried))
        assertEquals(2, ftRepo.listAll().size)
    }

    @Test
    fun theGuardBlocksAnAutomaticPostOnStaleEvidence_andLeavesItRetryable() = runBlocking {
        persistPurchases()
        val blocked = mutableListOf<String>()
        val guard: AutomaticWrite = { expected, write ->
            if (expected.any { it.event.rawSmsId == P1 }) {
                blocked += P1
                FinancialTransactionSaveResult.Conflict(P1, "stale_pattern_definition")
            } else write()
        }

        val report = writing(guard = guard).reconcileStoredEventsDetailed()

        assertEquals(listOf(P1), blocked)
        assertFalse(ReconciliationCompletionPolicy.isComplete(report))
        assertEquals(setOf(P1), report.failedRawSmsIds)
        assertEquals(listOf(listOf(P2)), ftRepo.listAll().map { ftRepo.listRawSmsIds(it.id) })
    }

    @Test
    fun aBlockedRefinementOfAPostedRow_keepsTheStoredRowAndIsComplete() = runBlocking {
        persistPurchases()
        writing().reconcileStoredEventsDetailed()
        val stored = ftRepo.findByRawSmsId(P1)!!
        ftRepo.update(stored.copy(occurredAtZone = null))
        val before = ftRepo.findByRawSmsId(P1)

        val report = writing(guard = blocking(P1))
            .reconcileStoredEventsDetailed()

        assertTrue(ReconciliationCompletionPolicy.isComplete(report))
        assertTrue(P1 in report.settledRawSmsIds)
        assertEquals(before, ftRepo.findByRawSmsId(P1))
    }

    @Test
    fun aBlockedHealOfARowTheUserProtected_keepsTheStoredRowAndIsComplete() = runBlocking {
        confirmation.confirmAccountOwned(source)
        persistEvent(LOAN, event("pe-loan", LOAN, MessageFamily.FINANCING_INSTALLMENT, money("300.00"), source = source),
            ParsedEventDetails(loanType = LoanType.PERSONAL), AT)
        val fee = FinancialTransaction(TransactionIdFactory.fromRawSmsIds(listOf(LOAN)), FinancialTransactionType.FEE,
            money("300.00"), AT, FinancialContainerIdFactory.accountId(source), null, null, null, null, listOf("pe-loan"),
            occurredAtZone = "Asia/Riyadh")
        ftRepo.save(fee, listOf(LOAN))
        val before = ftRepo.findByRawSmsId(LOAN)
        val reviews = RoomReviewRepository(db.reviewItemDao())
        // Unprotected, a blocked heal of the FEE into a repayment is a failure to retry.
        val unprotected = writing(guard = blocking(LOAN), reviews = reviews)
            .reconcileStoredEventsDetailed()
        assertEquals(setOf(LOAN), unprotected.failedRawSmsIds)
        assertEquals(before, ftRepo.findByRawSmsId(LOAN))

        val review = reviews.upsertRequired(LOAN, ReviewKind.NEEDS_REVIEW, listOf("needs_review"), AT)
        reviews.markResolved(review.id, ReviewResolutionKind.USER_CORRECTION, AT, fee.id)
        val report = writing(guard = blocking(LOAN), reviews = reviews)
            .reconcileStoredEventsDetailed()

        assertTrue(ReconciliationCompletionPolicy.isComplete(report))
        assertEquals(before, ftRepo.findByRawSmsId(LOAN))
    }

    /** A chain of transfers longer than the scope rounds is read whole: the scoped report still covers all of it. */
    @Test
    fun aLongTransferChain_isScopedFromAFullRead() = runBlocking {
        val ids = (0 until 16).map { "sms-chain-$it" }
        ids.forEachIndexed { day, id ->
            persistEvent(id, event("pe-$id", id, MessageFamily.TRANSFER_OUT, money("5.00"), source = source),
                ParsedEventDetails(occurredAtLocal = LocalDateTime.parse("2026-08-01T12:00:00").plusDays(day.toLong())),
                AT.plusSeconds(day * 86_400L))
        }

        val report = writing().reconcileAffectedRawSmsIds(listOf(ids.first()))

        val covered = report.settledRawSmsIds + report.reviewCandidates.map { it.rawSmsId }
        assertEquals(ids.toSet(), covered.filter { it in ids }.toSet())
    }

    @Test
    fun unchangedRows_areNotRewritten() = runBlocking {
        persistPurchases()
        writing().reconcileStoredEventsDetailed()
        var writes = 0
        val counting = object : FinancialTransactionRepository by ftRepo {
            override suspend fun save(transaction: FinancialTransaction, rawSmsIds: Collection<String>) =
                ftRepo.save(transaction, rawSmsIds).also { writes++ }

            override suspend fun unlinkRawSms(rawSmsId: String) = ftRepo.unlinkRawSms(rawSmsId).also { writes++ }
        }

        val report = writing(counting).reconcileStoredEventsDetailed()

        assertEquals(0, writes)
        assertEquals(2, report.summary.alreadyLinked)
    }

    @Test
    fun theDebugSwitch_selectsTheOldPathOnlyWhileItsFileExists() = runBlocking {
        val dir = Files.createTempDirectory("legacy-switch").toFile()
        assertFalse(DebugLegacyReconciliation.enabled(dir))
        File(dir, DebugLegacyReconciliation.FILE_NAME).writeText("")
        assertTrue(DebugLegacyReconciliation.enabled(dir))

        persistPurchases()
        writing().reconcileStoredEventsDetailed()
        ftRepo.update(ftRepo.findByRawSmsId(P1)!!.copy(occurredAtZone = null))
        // The old path never rewrites a posted row; the writer fills its zone.
        writing(legacy = { DebugLegacyReconciliation.enabled(dir) }).reconcileStoredEventsDetailed()
        assertEquals(null, ftRepo.findByRawSmsId(P1)?.occurredAtZone)
        File(dir, DebugLegacyReconciliation.FILE_NAME).delete()
        writing(legacy = { DebugLegacyReconciliation.enabled(dir) }).reconcileStoredEventsDetailed()
        assertEquals("Asia/Riyadh", ftRepo.findByRawSmsId(P1)?.occurredAtZone)
    }

    private fun writing(
        ledger: FinancialTransactionRepository = ftRepo,
        guard: AutomaticWrite? = null,
        legacy: () -> Boolean = { false },
        reviews: ReviewRepository = EmptyReviewRepository(),
    ) = TransactionReconciliationService(
        rawSmsRepository = rawRepo,
        financialTransactionRepository = ledger,
        ownershipResolver = OwnershipResolver(accounts, cards, loans),
        ownershipConfirmationService = confirmation,
        effectiveParsedEventProvider = noOpEffectiveParsedEventProvider(parsedRepo),
        reviewRepository = reviews,
        userDecisionRepository = NoOpUserDecisionRepository,
        zoneId = ZoneId.of("Asia/Riyadh"),
        automaticWrite = guard,
        inTransaction = { write -> db.withTransaction { write() } },
        useLegacyPath = legacy,
    )

    /** A guard that rejects every automatic write, as stale evidence would. */
    private fun blocking(rawSmsId: String): AutomaticWrite = { _, _ -> FinancialTransactionSaveResult.Conflict(rawSmsId, "stale_pattern_definition") }

    private suspend fun commit(transactionId: String) = RoomCommitmentRepository.from(db).create(
        Commitment("commitment-1", "Rent", money("700.00"), LocalDate.parse("2026-08-10"), null, null, true,
            sourceTransactionId = transactionId, createdAt = AT, updatedAt = AT),
    )

    private suspend fun persistPurchases() {
        confirmation.confirmCardOwned(card)
        listOf(P1, P2).forEachIndexed { index, id ->
            persistEvent(id, event("pe-$id", id, MessageFamily.PURCHASE, money("10.00"), card = card, merchant = "Shop $index"),
                at = AT.plusSeconds(index * 60L))
        }
    }

    private suspend fun persistLegs() {
        confirmation.confirmAccountOwned(source)
        confirmation.confirmAccountOwned(destination)
        val local = LocalDateTime.parse("2026-08-10T12:00:00")
        persistEvent(OUT, event("pe-out", OUT, MessageFamily.TRANSFER_OUT, money("700.00"), source = source,
            destination = destination, network = BankNetworkType.INTER_BANK),
            ParsedEventDetails(occurredAtLocal = local, transactionReference = "REF-1"), Instant.parse("2026-08-10T09:00:00Z"))
        persistEvent(IN, event("pe-in", IN, MessageFamily.TRANSFER_IN, money("700.00"), source = source,
            destination = destination, network = BankNetworkType.INTER_BANK),
            ParsedEventDetails(occurredAtLocal = local.plusMinutes(1), transactionReference = "REF-1"), Instant.parse("2026-08-10T09:01:00Z"))
    }

    /** The pair the projection derives for [persistLegs], with every column but its id taken from a first pass. */
    private suspend fun pair(): FinancialTransaction {
        writing().reconcileStoredEventsDetailed()
        val derived = ftRepo.findByRawSmsId(OUT)!!
        listOf(OUT, IN).forEach { ftRepo.unlinkRawSms(it) }
        assertEquals(FinancialContainerIdFactory.accountId(source), derived.sourceContainerId)
        return derived
    }

    private companion object {
        const val OUT = "sms-writer-out"
        const val IN = "sms-writer-in"
        const val P1 = "sms-writer-p1"
        const val P2 = "sms-writer-p2"
        const val LOAN = "sms-writer-loan"
        val AT: Instant = Instant.parse("2026-08-01T12:00:00Z")
    }
}
