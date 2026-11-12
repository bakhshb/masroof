package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.interpretation.AutomaticInterpretationWriteGuard
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserDecisionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scoped = full for what a scoped pass must read beyond the transfer window (docs/ARCHITECTURE.md, "Ledger writer"):
 * a decision naming a far leg, a correction-closed neighbour, and a unit the production write guard refuses. Each
 * case runs one scoped pass in one database and a full pass in another, applies both reports through
 * [ReviewQueueUpdater] and compares rows, reviews and, for the affected unit, the report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerWriterScopedOutcomeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aPairDecisionWithLegsDaysApart_isWrittenByAScopedPassOnEitherLeg() = runBlocking {
        for (affected in listOf(OUT, IN)) {
            val (scoped, full) = worlds { world ->
                world.arrive(transfer(OUT, MessageFamily.TRANSFER_OUT, T0))
                world.arrive(transfer(IN, MessageFamily.TRANSFER_IN, T0.plus(Duration.ofDays(3))))
                // The decision is the only link: no stored row joins the legs, and they are outside the match window.
                world.decisions.recordWith(UserDecision.posting(UserDecisionKind.SELF_TRANSFER_PAIR, OUT, decidedPair(), T0, IN), { null }) { Unit }
            }
            val (scopedReport, fullReport) = scoped.pass(listOf(affected)) to full.pass(null)

            assertEquals("scoped on $affected", full.state(), scoped.state())
            assertEquals(setOf(listOf(IN, OUT).sorted()), scoped.rowSets())
            assertEquals(fullReport.forUnit(setOf(OUT, IN)), scopedReport.forUnit(setOf(OUT, IN)))
            scoped.close(); full.close()
        }
    }

    @Test
    fun aCorrectionClosedNeighbourStaysAsStored_inAScopedPassAsInAFullOne() = runBlocking {
        suspend fun worldsFor(closed: Boolean) =
            worlds { world ->
                world.arrive(transfer(IN, MessageFamily.TRANSFER_IN, T0))
                val external = FinancialTransaction(TransactionIdFactory.fromRawSmsIds(listOf(IN)), FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                    Money.of(BigDecimal("100.00"), Currency.SAR), T0, null, ACCOUNT_2.container(), null, null, null, listOf("pe-$IN"),
                    occurredAtZone = "Asia/Riyadh")
                assertEquals(FinancialTransactionSaveResult.Saved, world.ledger.save(external, listOf(IN)))
                if (closed) {
                    val review = world.reviews.findByRawSmsId(IN)!!
                    world.reviews.markResolved(review.id, ReviewResolutionKind.USER_CORRECTION, T0, external.id)
                }
                world.arrive(transfer(OUT, MessageFamily.TRANSFER_OUT, T0.plusSeconds(60)))
            }
        val (scoped, full) = worldsFor(true)
        val (scopedReport, fullReport) = scoped.pass(listOf(OUT)) to full.pass(null)

        assertEquals(full.state(), scoped.state())
        assertTrue("the closure keeps the external leg", scoped.rowSets().contains(listOf(IN)))
        assertEquals(fullReport.forUnit(setOf(OUT)), scopedReport.forUnit(setOf(OUT)))
        scoped.close(); full.close()

        // Without the closure the same scoped pass joins the legs: the closure is what the scoped pass had to read.
        val (open, unused) = worldsFor(false)
        unused.close()
        open.pass(listOf(OUT))
        assertEquals(setOf(listOf(IN, OUT).sorted()), open.rowSets())
        open.close()
    }

    @Test
    fun aUnitTheGuardRefuses_isReportedAlikeByAScopedAndAFullPass() = runBlocking {
        val (scoped, full) = worlds(staleInference = setOf(IN)) { world ->
            world.arrive(transfer(OUT, MessageFamily.TRANSFER_OUT, T0))
            world.arrive(transfer(IN, MessageFamily.TRANSFER_IN, T0.plusSeconds(60)))
        }
        val (scopedReport, fullReport) = scoped.pass(listOf(OUT)) to full.pass(null)

        val unit = setOf(OUT, IN)
        assertTrue("the guard refused the pair", scopedReport.failedRawSmsIds.isNotEmpty())
        assertEquals(fullReport.forUnit(unit), scopedReport.forUnit(unit))
        assertEquals(full.state(), scoped.state())
        assertNotEquals(setOf(unit.sorted()), scoped.rowSets())
        scoped.close(); full.close()
    }

    private suspend fun worlds(staleInference: Set<String> = emptySet(), seed: suspend (World) -> Unit): Pair<World, World> {
        val pair = World(context, staleInference) to World(context, staleInference)
        pair.toList().forEach { seed(it) }
        return pair
    }

    /** The report restricted to [unit]: failed and settled ids, and review candidates. */
    private fun ReconciliationReport.forUnit(unit: Set<String>) = Triple(
        failedRawSmsIds.filter { it in unit }.toSet(),
        settledRawSmsIds.filter { it in unit }.toSet(),
        reviewCandidates.filter { it.rawSmsId in unit }.toSet(),
    )

    private class World(context: Context, staleInference: Set<String>) : AutoCloseable {
        val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java).allowMainThreadQueries().build()
        private val raw = RoomRawSmsRepository(db.rawSmsDao())
        private val parsed = RoomParsedEventRepository(db.parsedEventDao())
        val ledger = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val reviews = RoomReviewRepository(db.reviewItemDao())
        val decisions = RoomUserDecisionRepository(db)
        private val accounts = RoomAccountRegistryRepository.from(db)
        private val cards = RoomCardRegistryRepository.from(db)
        private val loans = RoomLoanRegistryRepository.from(db)
        private val effective = EffectiveParsedEventProvider(parsed, decisions)
        private val queue = ReviewQueueUpdater(reviews, ledger, InstantClock { AT })
        private val guard = AutomaticInterpretationWriteGuard(
            effective, reviews, decisions, ledger,
            inferenceCurrent = { it.event.rawSmsId !in staleInference },
            inTransaction = { mutation -> db.withTransaction { mutation() } },
        )
        private val service = TransactionReconciliationService(
            rawSmsRepository = raw, financialTransactionRepository = ledger,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = OwnershipConfirmationService(accounts, cards, loans),
            effectiveParsedEventProvider = effective, reviewRepository = reviews, userDecisionRepository = decisions,
            zoneId = ZoneId.of("Asia/Riyadh"),
            automaticWrite = { expected, write -> guard.write(expected, write) },
            inTransaction = { write -> db.withTransaction { write() } },
        )

        init {
            runBlocking { listOf(ACCOUNT_1, ACCOUNT_2).forEach { accounts.setOwnership(it, OwnershipStatus.OWNED) } }
        }

        /** Stores the SMS with the review ingestion gives it; no pass runs. */
        suspend fun arrive(message: Pair<ParsedEvent, Instant>) {
            val (event, at) = message
            raw.insertIfAbsent(RawSms(event.rawSmsId, "AlJazira", "body-${event.rawSmsId}", at, event.rawSmsId, SmsBodyHasher.sha256Hex(event.rawSmsId)))
            parsed.save(event, ParsedEventDetails())
            reviews.upsertRequired(event.rawSmsId, ReviewKind.NEEDS_REVIEW, listOf("needs_review"), at)
        }

        /** One pass, scoped to [ids] or full, applied to the review queue as the production callers do. */
        suspend fun pass(ids: List<String>?): ReconciliationReport =
            (if (ids == null) service.reconcileStoredEventsDetailed() else service.reconcileAffectedRawSmsIds(ids))
                .also { queue.applyReport(it) }

        suspend fun rowSets(): Set<List<String>> = ledger.listAll().mapTo(mutableSetOf()) { ledger.listRawSmsIds(it.id).sorted() }

        /** Every row (all columns) by its raw SMS set, and every review. */
        suspend fun state(): Set<String> =
            ledger.listAll().map { "row ${ledger.listRawSmsIds(it.id).sorted()} $it" }.toSet() +
                reviews.listAll().map { "review ${it.rawSmsId} ${it.status} ${it.kind} ${it.resolutionKind} ${it.resolvedTransactionId}" }

        override fun close() = db.close()
    }

    private companion object {
        const val OUT = "sms-out"
        const val IN = "sms-in"
        val T0: Instant = Instant.parse("2026-08-01T09:00:00Z")
        val AT: Instant = Instant.parse("2026-09-01T00:00:00Z")
        val ACCOUNT_1 = AccountReference(Bank.BANK_ALJAZIRA, "1111")
        val ACCOUNT_2 = AccountReference(Bank.BANK_ALJAZIRA, "2222")

        fun AccountReference.container() = checkNotNull(FinancialContainerIdFactory.accountId(this))

        fun transfer(id: String, family: MessageFamily, at: Instant) = ParsedEvent(
            id = "pe-$id", rawSmsId = id, bank = Bank.BANK_ALJAZIRA, messageFamily = family, direction = MoneyDirection.OUTGOING,
            amount = Money.of(BigDecimal("100.00"), Currency.SAR), purchaseChannel = null, sourceAccountRef = ACCOUNT_1,
            destinationAccountRef = ACCOUNT_2, cardRef = null, merchant = null, counterparty = null, occurredAt = null,
            bankNetworkType = BankNetworkType.INTRA_BANK, confidence = Confidence(1.0), parseStatus = ParseStatus.SUCCESS,
        ) to at

        /** The row the user's pair decision posted: its type, endpoints and time are the decision's columns. */
        fun decidedPair() = FinancialTransaction(TransactionIdFactory.fromRawSmsIds(listOf(OUT, IN)), FinancialTransactionType.SELF_TRANSFER,
            Money.of(BigDecimal("100.00"), Currency.SAR), T0, ACCOUNT_1.container(), ACCOUNT_2.container(), null, null, null,
            listOf("pe-$IN", "pe-$OUT"), occurredAtZone = "Asia/Riyadh")
    }
}
