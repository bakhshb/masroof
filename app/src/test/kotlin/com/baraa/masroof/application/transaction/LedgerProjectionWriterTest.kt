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
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.testsupport.EmptyReviewRepository
import com.baraa.masroof.testsupport.NoOpUserDecisionRepository
import com.baraa.masroof.testsupport.noOpEffectiveParsedEventProvider
import java.math.BigDecimal
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
    fun reDerivedPairId_carriesAHalfExchangeRatePairForEnrichmentToRepair() = runBlocking {
        persistLegs()
        val legacy = pair().copy(id = "transaction:legacy-pair", appliedExchangeRate = BigDecimal("3.75"), exchangeRateSource = null)
        assertEquals(FinancialTransactionSaveResult.Saved, ftRepo.save(legacy, listOf(OUT, IN)))

        assertTrue(ReconciliationCompletionPolicy.isComplete(writing().reconcileStoredEventsDetailed()))

        val row = ftRepo.listAll().single()
        assertEquals(pairId, row.id)
        assertEquals(BigDecimal("3.75") to null, row.appliedExchangeRate to row.exchangeRateSource)
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

    /** A chain of transfers longer than the scope rounds is read whole, once, and narrowed to the scope. */
    @Test
    fun aLongTransferChain_isScopedFromAFullRead() = runBlocking {
        val ids = (0 until 16).map { "sms-chain-$it" }
        ids.forEachIndexed { day, id ->
            persistEvent(id, event("pe-$id", id, MessageFamily.TRANSFER_OUT, money("5.00"), source = source),
                ParsedEventDetails(occurredAtLocal = LocalDateTime.parse("2026-08-01T12:00:00").plusDays(day.toLong())),
                AT.plusSeconds(day * 86_400L))
        }

        val counting = com.baraa.masroof.testsupport.CountingParsedEventRepository(parsedRepo)

        val report = writing(parsed = counting).reconcileAffectedRawSmsIds(listOf(ids.first()))

        assertEquals(1, counting.listAllCalls)
        assertEquals(writing().reconcileStoredEventsDetailed().reviewCandidates.filter { it.rawSmsId == ids.first() }, report.reviewCandidates)
    }

    /** A second full pass right after one writes nothing: no save, unlink, update or reference move, no transaction. */
    @Test
    fun aSecondFullPass_writesNothing() = runBlocking {
        persistLegs()
        val legacy = pair().copy(id = "transaction:legacy-pair", appliedExchangeRate = BigDecimal("3.75"),
            exchangeRateSource = ExchangeRateSource.HISTORICAL_MERCHANT)
        ftRepo.save(legacy, listOf(OUT, IN))
        commit(legacy.id)
        persistPurchases()
        val linkless = linklessRows()
        writing().reconcileStoredEventsDetailed()
        var writes = 0
        val counting = object : FinancialTransactionRepository by ftRepo {
            override suspend fun save(transaction: FinancialTransaction, rawSmsIds: Collection<String>) =
                ftRepo.save(transaction, rawSmsIds).also { writes++ }

            override suspend fun unlinkRawSms(rawSmsId: String) = ftRepo.unlinkRawSms(rawSmsId).also { writes++ }

            override suspend fun update(transaction: FinancialTransaction) = ftRepo.update(transaction).also { writes++ }

            override suspend fun moveTransactionReferences(oldId: String, newId: String, newIdByRawSmsId: Map<String, String>) {
                writes++
                ftRepo.moveTransactionReferences(oldId, newId, newIdByRawSmsId)
            }
        }
        var transactions = 0

        val report = writing(counting, onTransaction = { transactions++ }).reconcileStoredEventsDetailed()

        assertEquals(0 to 0, writes to transactions)
        assertEquals(0 to 0, report.summary.assembledSingle to report.summary.matchedPairs)
        assertEquals(pairId, RoomCommitmentRepository.from(db).listAll().single().sourceTransactionId)
        assertEquals(linkless, ftRepo.getByIds(linkless.map { it.id }))
    }

    /**
     * A transfer split by a daily limit, inter-bank without a reference: the stored pair [a1,b] stays through a later
     * out-leg a2 (posted on its own, as the old pass did) and its in-leg b2 (also on its own: these legs never match).
     * The scoped pass for each new leg writes what a full pass writes.
     */
    @Test
    fun aStoredPairIsNeverSplitByLaterLegs() = runBlocking {
        confirmation.confirmAccountOwned(source)
        confirmation.confirmAccountOwned(destination)
        fun transfer(id: String, family: MessageFamily) = event("pe-$id", id, family, money("700.00"), source = source,
            destination = destination, network = BankNetworkType.INTER_BANK)
        persistEvent(OUT, transfer(OUT, MessageFamily.TRANSFER_OUT), at = Instant.parse("2026-08-10T09:00:00Z"))
        persistEvent(IN, transfer(IN, MessageFamily.TRANSFER_IN), at = Instant.parse("2026-08-10T09:01:00Z"))
        val pair = singleLeg(OUT, FinancialTransactionType.SELF_TRANSFER).copy(id = pairId, linkedParsedEventIds = listOf("pe-$IN", "pe-$OUT").sorted())
        ftRepo.save(pair, listOf(OUT, IN))
        suspend fun sets() = ftRepo.listAll().map { ftRepo.listRawSmsIds(it.id).toSet() }.toSet()

        persistEvent(P1, transfer(P1, MessageFamily.TRANSFER_OUT), at = Instant.parse("2026-08-10T09:02:00Z"))
        assertTrue(ReconciliationCompletionPolicy.isComplete(writing().reconcileAffectedRawSmsIds(listOf(P1))))
        assertEquals(setOf(setOf(OUT, IN), setOf(P1)), sets())
        assertEquals(pair, ftRepo.getById(pairId))
        writing().reconcileStoredEventsDetailed()
        assertEquals(setOf(setOf(OUT, IN), setOf(P1)), sets())

        persistEvent(P2, transfer(P2, MessageFamily.TRANSFER_IN), at = Instant.parse("2026-08-10T09:03:00Z"))
        writing().reconcileAffectedRawSmsIds(listOf(P2))
        val scoped = sets()
        writing().reconcileStoredEventsDetailed()
        assertEquals(scoped, sets())
        assertTrue(setOf(OUT, IN) in scoped)
    }

    /** Link-less stored rows share no raw SMS set: each keeps its own exchange-rate pair (or none), byte for byte. */
    @Test
    fun linklessRows_keepTheirOwnExchangeRatePair() = runBlocking {
        val linkless = linklessRows()

        writing().reconcileStoredEventsDetailed()

        assertEquals(linkless, ftRepo.getByIds(linkless.map { it.id }))
    }

    /** Two rows that link no raw SMS (left by older versions), one with a complete exchange-rate pair, sorted by id. */
    private suspend fun linklessRows(): List<FinancialTransaction> {
        val rows = listOf(
            singleLeg(OUT, FinancialTransactionType.EXPENSE).copy(id = "transaction:linkless-a", linkedParsedEventIds = emptyList(),
                appliedExchangeRate = BigDecimal("3.75"), exchangeRateSource = ExchangeRateSource.SMS),
            singleLeg(OUT, FinancialTransactionType.EXPENSE).copy(id = "transaction:linkless-b", linkedParsedEventIds = emptyList()),
        )
        rows.forEach { db.financialTransactionDao().insertTransactionIfAbsent(com.baraa.masroof.data.room.mapper.FinancialTransactionMapper.toEntity(it)) }
        return rows
    }

    /**
     * The first pass over an old-path ledger: every changed row is written in one transaction, and a unit the guard
     * blocks does not stop the others.
     */
    @Test
    fun theFirstPass_writesEveryUnitInOneTransaction_andABlockedUnitDoesNotStopTheOthers() = runBlocking {
        persistPurchases()
        writing().reconcileStoredEventsDetailed()
        listOf(P1, P2).forEach { ftRepo.update(ftRepo.findByRawSmsId(it)!!.copy(occurredAtZone = null)) }
        persistEvent(P3, event("pe-$P3", P3, MessageFamily.PURCHASE, money("10.00"), card = card, merchant = "Shop 3"),
            at = AT.plusSeconds(600))
        var transactions = 0

        val report = writing(guard = { expected, write ->
            if (expected.any { it.event.rawSmsId == P3 }) FinancialTransactionSaveResult.Conflict(P3, "stale_pattern_definition") else write()
        }, onTransaction = { transactions++ }).reconcileStoredEventsDetailed()

        assertEquals(1, transactions)
        assertEquals(setOf(P3), report.failedRawSmsIds)
        assertEquals(listOf("Asia/Riyadh", "Asia/Riyadh"), listOf(P1, P2).map { ftRepo.findByRawSmsId(it)?.occurredAtZone })
        assertEquals(null, ftRepo.findByRawSmsId(P3))
    }

    /**
     * A correction-closed leg posted alone and its counterpart arriving later: the leg is not re-opened, and the
     * counterpart keeps an open pending-match review through the review queue, on the first and on a repeated pass.
     */
    @Test
    fun aProtectedPostedLeg_leavesItsCounterpartReviewOpen() = runBlocking {
        // Intra-bank, so the old pass counted the posted leg as a pending counterpart (an inter-bank counterpart is
        // posted on its own, as the old pass did).
        persistLegs(BankNetworkType.INTRA_BANK)
        val leg = singleLeg(OUT, FinancialTransactionType.SELF_TRANSFER)
        ftRepo.save(leg, listOf(OUT))
        val reviews = RoomReviewRepository(db.reviewItemDao())
        val closure = reviews.upsertRequired(OUT, ReviewKind.NEEDS_REVIEW, listOf("needs_review"), AT)
        reviews.markResolved(closure.id, ReviewResolutionKind.USER_CORRECTION, AT, leg.id)
        reviews.upsertRequired(IN, ReviewKind.PENDING_MATCH, listOf("transfer_pending_match"), AT)
        val queue = com.baraa.masroof.application.review.ReviewQueueUpdater(reviews, ftRepo) { AT }
        val before = ftRepo.findByRawSmsId(OUT)

        repeat(2) { pass ->
            // The guard refuses any automatic write on the protected, posted leg, as the real one does.
            val report = writing(guard = { expected, write -> if (expected.any { it.event.rawSmsId == OUT }) blocking(OUT)(expected, write) else write() },
                reviews = reviews).reconcileStoredEventsDetailed()
            queue.applyReport(report)

            assertTrue("pass $pass", ReconciliationCompletionPolicy.isComplete(report))
            assertFalse("pass $pass: the counterpart was settled without a row", IN in report.settledRawSmsIds)
            assertEquals("pass $pass", before, ftRepo.findByRawSmsId(OUT))
            assertEquals("pass $pass", null, ftRepo.findByRawSmsId(IN))
            val open = reviews.findByRawSmsId(IN)!!
            assertEquals("pass $pass", ReviewStatus.REQUIRED to ReviewKind.PENDING_MATCH, open.status to open.kind)
        }
    }

    /**
     * Re-keying would put two commitments on the pair row: the unit stays as stored, no commitment loses its row, the
     * leg without a row comes back as a review candidate, and nothing is failed or retried.
     */
    @Test
    fun aCommitmentClash_keepsTheUnitAsStored_andReportsTheUnpostedLegForReview() = runBlocking {
        persistLegs()
        val external = singleLeg(OUT, FinancialTransactionType.EXTERNAL_TRANSFER_OUT).copy(destinationContainerId = null)
        ftRepo.save(external, listOf(OUT))
        commit(external.id)
        commit(pairId, id = "commitment-2")

        repeat(2) { pass ->
            val report = writing().reconcileStoredEventsDetailed()

            assertTrue("pass $pass", ReconciliationCompletionPolicy.isComplete(report))
            assertEquals("pass $pass", listOf(external), ftRepo.listAll())
            assertTrue("pass $pass", OUT in report.settledRawSmsIds && IN !in report.settledRawSmsIds)
            assertEquals("pass $pass", listOf(IN), report.reviewCandidates.map { it.rawSmsId })
            assertEquals("pass $pass", setOf(external.id, pairId),
                RoomCommitmentRepository.from(db).listAll().map { it.sourceTransactionId }.toSet())
        }
    }

    /** A live SMS whose scope reaches a neighbour that fails on every pass is still complete; a full pass is not. */
    @Test
    fun aScopedPass_doesNotReportANeighboursLastingFailure() = runBlocking {
        persistLegs()
        persistEvent(NEIGHBOUR, event("pe-neighbour", NEIGHBOUR, MessageFamily.TRANSFER_OUT, money("11.00"), source = source,
            destination = com.baraa.masroof.domain.model.AccountReference(Bank.BANK_ALJAZIRA, "9999"), network = BankNetworkType.INTER_BANK),
            at = Instant.parse("2026-08-10T09:00:30Z"))
        val guard = { neighbourBlocked: Boolean -> blocking(NEIGHBOUR).takeIf { neighbourBlocked } }
        val failing: AutomaticWrite = { expected, write -> if (expected.any { it.event.rawSmsId == NEIGHBOUR }) guard(true)!!(expected, write) else write() }

        val scoped = writing(guard = failing).reconcileAffectedRawSmsIds(listOf(IN))

        assertTrue(ReconciliationCompletionPolicy.isComplete(scoped))
        assertTrue(NEIGHBOUR !in scoped.failedRawSmsIds && NEIGHBOUR !in scoped.settledRawSmsIds)
        assertEquals(setOf(OUT, IN), ftRepo.listRawSmsIds(pairId).toSet())
        assertEquals(null, ftRepo.findByRawSmsId(NEIGHBOUR))
        val full = writing(guard = failing).reconcileStoredEventsDetailed()
        assertEquals(setOf(NEIGHBOUR), full.failedRawSmsIds)
    }

    /** A stored FEE whose amount differs from its installment stays exactly as stored (no stored amount changes). */
    @Test
    fun aStoredFeeWithAnotherAmount_staysAFee() = runBlocking {
        confirmation.confirmAccountOwned(source)
        persistEvent(LOAN, event("pe-loan", LOAN, MessageFamily.FINANCING_INSTALLMENT, money("300.00"), source = source),
            ParsedEventDetails(loanType = LoanType.PERSONAL), AT)
        val fee = FinancialTransaction(TransactionIdFactory.fromRawSmsIds(listOf(LOAN)), FinancialTransactionType.FEE,
            money("10.00"), AT, FinancialContainerIdFactory.accountId(source), null, null, null, null, listOf("pe-loan"),
            occurredAtZone = "Asia/Riyadh")
        ftRepo.save(fee, listOf(LOAN))

        val report = writing().reconcileStoredEventsDetailed()

        assertTrue(ReconciliationCompletionPolicy.isComplete(report))
        assertEquals(fee, ftRepo.findByRawSmsId(LOAN))
    }

    private fun singleLeg(rawSmsId: String, type: FinancialTransactionType) = FinancialTransaction(
        TransactionIdFactory.fromRawSmsIds(listOf(rawSmsId)), type, money("700.00"), Instant.parse("2026-08-10T09:00:00Z"),
        FinancialContainerIdFactory.accountId(source), FinancialContainerIdFactory.accountId(destination), null, null, null,
        listOf(if (rawSmsId == OUT) "pe-out" else "pe-in"), occurredAtZone = "Asia/Riyadh",
    )

    /** A lone owned leg the guard refuses is failed and, as in the old pass, a pending match again. */
    @Test
    fun aLoneLegTheGuardRefuses_isFailedAndPending() = runBlocking {
        confirmation.confirmAccountOwned(source)
        confirmation.confirmAccountOwned(destination)
        persistEvent(OUT, event("pe-out", OUT, MessageFamily.TRANSFER_OUT, money("700.00"), source = source,
            destination = destination, network = BankNetworkType.INTRA_BANK), at = AT)

        val report = writing(guard = blocking(OUT)).reconcileAffectedRawSmsIds(listOf(OUT))

        assertEquals(setOf(OUT), report.failedRawSmsIds)
        assertEquals(listOf(ReviewKind.PENDING_MATCH), report.reviewCandidates.filter { it.rawSmsId == OUT }.map { it.kind })
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

    private fun writing(
        ledger: FinancialTransactionRepository = ftRepo,
        guard: AutomaticWrite? = null,
        reviews: ReviewRepository = EmptyReviewRepository(),
        onTransaction: () -> Unit = {},
        parsed: com.baraa.masroof.parsing.repository.ParsedEventRepository = parsedRepo,
    ) = TransactionReconciliationService(
        rawSmsRepository = rawRepo,
        financialTransactionRepository = ledger,
        ownershipResolver = OwnershipResolver(accounts, cards, loans),
        ownershipConfirmationService = confirmation,
        effectiveParsedEventProvider = noOpEffectiveParsedEventProvider(parsed),
        reviewRepository = reviews,
        userDecisionRepository = NoOpUserDecisionRepository,
        zoneId = ZoneId.of("Asia/Riyadh"),
        automaticWrite = guard,
        inTransaction = { write -> onTransaction(); db.withTransaction { write() } },
    )

    /** A guard that rejects every automatic write, as stale evidence would. */
    private fun blocking(rawSmsId: String): AutomaticWrite = { _, _ -> FinancialTransactionSaveResult.Conflict(rawSmsId, "stale_pattern_definition") }

    private suspend fun commit(transactionId: String, id: String = "commitment-1") = RoomCommitmentRepository.from(db).create(
        Commitment(id, "Rent", money("700.00"), LocalDate.parse("2026-08-10"), null, null, true,
            sourceTransactionId = transactionId, createdAt = AT, updatedAt = AT),
    )

    private suspend fun persistPurchases() {
        confirmation.confirmCardOwned(card)
        listOf(P1, P2).forEachIndexed { index, id ->
            persistEvent(id, event("pe-$id", id, MessageFamily.PURCHASE, money("10.00"), card = card, merchant = "Shop $index"),
                at = AT.plusSeconds(index * 60L))
        }
    }

    private suspend fun persistLegs(network: BankNetworkType = BankNetworkType.INTER_BANK) {
        confirmation.confirmAccountOwned(source)
        confirmation.confirmAccountOwned(destination)
        val local = LocalDateTime.parse("2026-08-10T12:00:00")
        persistEvent(OUT, event("pe-out", OUT, MessageFamily.TRANSFER_OUT, money("700.00"), source = source,
            destination = destination, network = network),
            ParsedEventDetails(occurredAtLocal = local, transactionReference = "REF-1"), Instant.parse("2026-08-10T09:00:00Z"))
        persistEvent(IN, event("pe-in", IN, MessageFamily.TRANSFER_IN, money("700.00"), source = source,
            destination = destination, network = network),
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
        const val P3 = "sms-writer-p3"
        const val NEIGHBOUR = "sms-writer-neighbour"
        const val LOAN = "sms-writer-loan"
        val AT: Instant = Instant.parse("2026-08-01T12:00:00Z")
    }
}
