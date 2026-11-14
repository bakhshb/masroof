package com.baraa.masroof.ledger

import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Random corpora dense in competing transfers, decisions and shared loans. Each seed is a reproducible case. */
class LedgerProjectorPropertyTest {
    private class Corpus(
        val events: List<LedgerEvidence>,
        val decisions: Map<String, EffectiveUserDecisions>,
        val registry: OwnershipFacts,
        val stored: StoredFacts,
    ) {
        fun project(
            events: List<LedgerEvidence> = this.events,
            decisions: Map<String, EffectiveUserDecisions> = this.decisions,
            stored: StoredFacts = this.stored,
        ) = LedgerProjector.project(events, decisions, registry, stored)
    }

    private val seeds = 0 until 2000

    private val storedTypes = listOf(
        FinancialTransactionType.EXPENSE, FinancialTransactionType.SELF_TRANSFER, FinancialTransactionType.FEE,
        FinancialTransactionType.EXTERNAL_TRANSFER_OUT, FinancialTransactionType.EXTERNAL_TRANSFER_IN,
    )

    @Test
    fun theProjectionIsDeterministicAndIgnoresInputOrder() {
        var rows = 0
        for (seed in seeds) {
            val corpus = corpus(Random(seed))
            val expected = corpus.project()
            rows += expected.rows.size
            assertEquals(expected, corpus.project(), "seed $seed")
            assertEquals(expected, corpus.project(corpus.events.shuffled(Random(seed + 1))), "seed $seed")
            assertEquals(expected, corpus.project(decisions = corpus.decisions.entries.shuffled(Random(seed)).associate { it.toPair() }), "seed $seed")
            val shuffledStored = corpus.stored.copy(rows = corpus.stored.rows.shuffled(Random(seed)).map { it.copy(rawSmsIds = it.rawSmsIds.reversed()) })
            assertEquals(expected, corpus.project(stored = shuffledStored), "seed $seed")
        }
        assertTrue(rows > seeds.count(), "corpora must post rows")
    }

    @Test
    fun projectingTheScopeOfAnAffectedSubsetEqualsTheFullProjectionForThatSubset() {
        var narrower = 0
        for (seed in seeds) {
            val random = Random(seed)
            val corpus = corpus(random)
            val full = corpus.project()
            val affected = corpus.events.shuffled(random).take(random.nextInt(1, 4)).map { it.event.rawSmsId }.toSet()
            val scope = LedgerProjector.scope(affected, corpus.events, corpus.decisions, corpus.stored)
            if (scope.size < corpus.events.size) narrower++
            val scoped = corpus.project(
                events = corpus.events.filter { it.event.rawSmsId in scope },
                decisions = corpus.decisions.filterKeys { it in scope },
            )
            assertEquals(full.restrictedTo(affected, corpus.events), scoped.restrictedTo(affected, corpus.events), "seed $seed")
            // The ledger writer writes the whole scope, so every raw SMS in it must project as in the full pass.
            assertEquals(full.restrictedTo(scope, corpus.events), scoped.restrictedTo(scope, corpus.events), "seed $seed")
        }
        assertTrue(narrower > seeds.count() / 2, "most scopes must leave evidence out")
    }

    /**
     * The keep invariant: every stored row is in the projection verbatim, or replaced by (a) one derived row on its
     * raw SMS set with the same type and amount (FEE→LOAN_REPAYMENT allowed), (b) a self-transfer pair joining a stored
     * external or single-leg self-transfer leg, (c) dropped as a lone, unprotected informational row, or (d) released
     * as a double-posted single-leg self-transfer ([doublePosted], an independent reading of the rule): replaced by a
     * self-transfer pair containing it, or by a lone row of its own leg, or by no row and a PENDING_MATCH candidate.
     * A protected row is always verbatim.
     */
    @Test
    fun everyStoredRowIsKeptVerbatimOrReplacedByAnAllowedRule() {
        var replaced = 0
        var doubles = 0
        for (seed in seeds) {
            val corpus = corpus(Random(seed))
            val result = corpus.project()
            val events = corpus.events.associateBy { it.event.rawSmsId }
            val doublePosted = doublePosted(corpus)
            for (row in corpus.stored.rows) {
                if (row.rawSmsIds.singleOrNull() in doublePosted) {
                    val id = row.rawSmsIds.single()
                    val over = result.rows.filter { id in it.rawSmsIds }
                    val pending = result.reviewCandidates.any { it.rawSmsId == id && it.kind == ReviewKind.PENDING_MATCH }
                    val replacement = over.singleOrNull()
                    val pairOrOwn = replacement != null && replacement != row && (replacement.rawSmsIds == listOf(id) ||
                        replacement.rawSmsIds.size == 2 && replacement.transaction.type == FinancialTransactionType.SELF_TRANSFER)
                    assertTrue(pairOrOwn || over.isEmpty() && pending, "seed $seed: double-posted $row became $over")
                    doubles++
                    continue
                }
                if (row in result.rows) continue
                val sameId = result.rows.singleOrNull { it.transaction.id == row.transaction.id }
                if (row.rawSmsIds.isEmpty()) {
                    // A link-less row stays byte for byte unless a derived row took its id.
                    assertTrue(sameId != null && sameId.rawSmsIds.isNotEmpty(), "seed $seed: link-less $row changed")
                    continue
                }
                if (row.rawSmsIds.size > 2) {
                    // Rule (iv): only stale legs leave a self-transfer; the rest stays as the stored row.
                    val rest = sameId?.rawSmsIds.orEmpty()
                    assertTrue(row.transaction.type == FinancialTransactionType.SELF_TRANSFER && row.rawSmsIds.containsAll(rest) &&
                        (sameId == null || sameId.transaction.type == row.transaction.type && sameId.transaction.amount == row.transaction.amount),
                        "seed $seed: $row became $sameId")
                    replaced++
                    continue
                }
                val protected = row.rawSmsIds.any { corpus.decisions[it]?.outcome != null || it in corpus.stored.correctionClosedRawSmsIds }
                assertTrue(!protected, "seed $seed: protected $row changed")
                val over = result.rows.filter { derived -> derived.rawSmsIds.any { it in row.rawSmsIds } }
                val single = row.rawSmsIds.singleOrNull()
                val informational = single != null && over.isEmpty() &&
                    events[single]?.event?.let(com.baraa.masroof.domain.rules.InformationalMessagePolicy::shouldAutoIgnore) == true
                val derived = over.singleOrNull()
                val sameSet = derived != null && derived.rawSmsIds == row.rawSmsIds && derived.transaction.amount == row.transaction.amount &&
                    (derived.transaction.type == row.transaction.type ||
                        row.transaction.type == FinancialTransactionType.FEE && derived.transaction.type == FinancialTransactionType.LOAN_REPAYMENT)
                val joinable = row.transaction.type in setOf(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, FinancialTransactionType.EXTERNAL_TRANSFER_IN) ||
                    row.isSingleLegSelf()
                val joined = single != null && joinable && derived != null && derived.transaction.type == FinancialTransactionType.SELF_TRANSFER &&
                    derived.rawSmsIds.size == 2 && single in derived.rawSmsIds
                assertTrue(informational || sameSet || joined, "seed $seed: $row replaced by $over")
                replaced++
            }
        }
        assertTrue(replaced > 100, "corpora must replace some stored rows: $replaced")
        assertTrue(doubles > 10, "corpora must release double-posted legs: $doubles")
    }

    @Test
    fun everyTransferTheScopeReachesLiesInsideThePairingWindow() {
        var reached = 0
        for (seed in seeds) {
            val transfers = corpus(Random(seed)).events.filter {
                it.event.messageFamily == MessageFamily.TRANSFER_IN || it.event.messageFamily == MessageFamily.TRANSFER_OUT
            }
            for (a in transfers) for (b in transfers) {
                if (a === b || b.event.rawSmsId !in LedgerProjector.scope(listOf(a.event.rawSmsId), listOf(a, b), emptyMap())) continue
                reached++
                val window = LedgerProjector.pairingWindow(a)
                val inside = b.receivedAt in window.receivedAt || b.event.occurredAt?.let { it in window.occurredAt } == true ||
                    b.occurredAtLocal?.let { it in window.occurredAtLocal } == true
                assertTrue(inside, "seed $seed")
            }
        }
        assertTrue(reached > seeds.count(), "corpora must hold reachable transfer legs")
    }

    private fun LedgerProjection.restrictedTo(ids: Set<String>, events: List<LedgerEvidence>): List<Any> {
        val loans = events.filter { it.event.rawSmsId in ids && it.loanType != null }
            .map { LoanReference(it.event.bank, it.loanType!!) }.toSet()
        return listOf(
            rows.filter { row -> row.rawSmsIds.any { it in ids } },
            reviewCandidates.filter { it.rawSmsId in ids },
            settledRawSmsIds.intersect(ids),
            retryRawSmsIds.intersect(ids),
            confirmedLoans.intersect(loans),
        )
    }

    private fun corpus(random: Random): Corpus {
        val accounts = listOf(account("1111"), account("2222"), account("3333"), account("4444", D360), account("55", D360))
        val cards = listOf(CardReference(AJ, "7271"), CardReference(D360, "8080"))
        val statuses = listOf(OwnershipStatus.OWNED, OwnershipStatus.OWNED, OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL, OwnershipStatus.UNKNOWN)
        val registry = OwnershipFacts(
            accounts = accounts.filter { random.nextInt(8) > 0 }.associateWith { statuses.random(random) },
            cards = cards.associateWith { statuses.random(random) },
            loans = if (random.nextBoolean()) mapOf(LoanReference(AJ, LoanType.PERSONAL) to OwnershipStatus.OWNED) else emptyMap(),
        )
        val others = listOf(
            MessageFamily.PURCHASE, MessageFamily.FINANCING_INSTALLMENT, MessageFamily.OTP, MessageFamily.UNKNOWN,
        )
        val networks = listOf(BankNetworkType.INTRA_BANK, BankNetworkType.INTRA_BANK, BankNetworkType.INTER_BANK, null)
        // Each movement is one money move told by up to three SMS: pairs, lone legs and competing legs.
        // Movements sit in clusters a few days apart, so most scopes are narrower than the corpus.
        val events = (0 until random.nextInt(2, 9)).flatMap { _ ->
            val at = T0.plusSeconds(random.nextLong(4) * 86_400 + random.nextLong(0, 1_500))
            val source = accounts.random(random).takeIf { random.nextInt(8) > 0 }
            val destination = accounts.random(random).takeIf { random.nextInt(8) > 0 }
            val amount = listOf("100", "100", "250", "250", "250", "100", "100", null).random(random)
            val network = networks.random(random)
            val reference = listOf(null, "R1", "R2").random(random)
            val single = random.nextInt(3) == 0
            val bank = if (random.nextInt(4) == 0) D360 else AJ
            (0 until listOf(1, 2, 2, 2, 3).random(random)).map { leg ->
                val family = when {
                    single -> others.random(random)
                    leg < 2 && random.nextInt(4) > 0 -> listOf(MessageFamily.TRANSFER_OUT, MessageFamily.TRANSFER_IN)[leg]
                    else -> listOf(MessageFamily.TRANSFER_OUT, MessageFamily.TRANSFER_IN).random(random)
                }
                val legAt = at.plusSeconds(random.nextLong(-120, 120))
                LedgerEvidence(
                    event = event(
                        id = "",
                        family = family,
                        amount = if (random.nextInt(8) == 0) "250" else amount,
                        bank = if (random.nextInt(8) == 0) D360 else bank,
                        source = source,
                        destination = destination,
                        card = cards.random(random).takeIf { family == MessageFamily.PURCHASE },
                        network = network.takeIf { random.nextInt(5) > 0 } ?: networks.random(random),
                        status = if (random.nextInt(6) == 0) ParseStatus.REVIEW_REQUIRED else ParseStatus.SUCCESS,
                        occurredAt = legAt.minusSeconds(random.nextLong(0, 120)).takeIf { random.nextInt(5) == 0 },
                    ),
                    // Some SMS arrive hours late; their bank-stated time stays near the movement.
                    receivedAt = legAt.plusSeconds(if (random.nextInt(6) == 0) random.nextLong(3_600, 10_800) else 0),
                    transactionReference = reference.takeIf { random.nextInt(4) > 0 } ?: listOf(null, "R3").random(random),
                    occurredAtLocal = LocalDateTime.ofInstant(legAt, ZoneOffset.ofHours(3)).minusMinutes(random.nextLong(0, 3))
                        .takeIf { random.nextBoolean() },
                    loanType = LoanType.PERSONAL.takeIf { family == MessageFamily.FINANCING_INSTALLMENT && random.nextInt(4) > 0 },
                    automationConfirmed = random.nextInt(8) == 0,
                )
            }
        }.shuffled(random).mapIndexed { index, evidence ->
            val id = "s%02d".format(index)
            evidence.copy(event = evidence.event.copy(id = "e-$id", rawSmsId = id))
        }
        val ids = events.map { it.event.rawSmsId }
        val stored = StoredFacts(storedRows(random, events, registry), ids.filter { random.nextInt(6) == 0 }.toSet())
        // A posting decision naming an SMS that a stored row of another decided SMS links (keep rule (i) wins).
        val conflict = stored.rows.firstOrNull { it.rawSmsIds.isNotEmpty() && random.nextBoolean() }?.rawSmsIds?.first()?.let { kept ->
            val other = ids.filter { it != kept }.randomOrNull(random) ?: return@let emptyList()
            listOf(
                UserDecision(
                    other, UserDecisionKind.SELF_TRANSFER_PAIR, T0, pairedRawSmsId = kept,
                    transactionType = FinancialTransactionType.SELF_TRANSFER, occurredAt = T0, occurredAtZone = "UTC",
                ),
                UserDecision(kept, UserDecisionKind.FINANCIAL_TYPE, T0),
            )
        }.orEmpty()
        return Corpus(events, fold(decisionRows(random, ids) + conflict), registry, stored)
    }

    /**
     * Stored rows link one, two or three raw SMS, never one SMS twice; some link raw SMS without evidence, a few link
     * none. A quarter of the corpora store a consistent earlier ledger instead: the projection with nothing stored;
     * an eighth store the old per-leg ledger: every transfer leg as its own single-leg self-transfer.
     */
    private fun storedRows(random: Random, events: List<LedgerEvidence>, registry: OwnershipFacts): List<LedgerRow> {
        when (random.nextInt(8)) {
            0, 1 -> return LedgerProjector.project(events, emptyMap(), registry, StoredFacts()).rows
            2 -> return events.filter { it.event.messageFamily.isTransfer() }
                .map { storedRow(it.event.rawSmsId, type = FinancialTransactionType.SELF_TRANSFER) }
        }
        val ids = events.map { it.event.rawSmsId }
        val storedIds = (ids.filter { random.nextInt(3) == 0 } + listOf("ghost1", "ghost2").filter { random.nextInt(3) == 0 }).shuffled(random)
        val sets = mutableListOf<List<String>>()
        var rest = storedIds
        while (rest.isNotEmpty()) {
            val size = listOf(1, 1, 1, 2, 2, 3).random(random).coerceAtMost(rest.size)
            sets += rest.take(size)
            rest = rest.drop(size)
        }
        fun row(vararg set: String) = storedRow(
            *set, zone = listOf("Asia/Dubai", "Asia/Riyadh", null).random(random), type = storedTypes.random(random),
            rate = BigDecimal("3.75").takeIf { random.nextBoolean() }, rateSource = ExchangeRateSource.SMS,
        )
        val linkless = (0 until random.nextInt(0, 3)).map { n ->
            row("none").let {
                LedgerRow(it.transaction.copy(id = "transaction:linkless-$n", linkedParsedEventIds = emptyList()), emptyList())
            }
        }
        return sets.map { row(*it.toTypedArray()) } + linkless
    }

    /**
     * The double-posted rule read from the inputs alone: unprotected stored single-leg self-transfers of one raw SMS,
     * not in a mutually unique pair among the eligible transfer legs, each with another such row as its pending
     * intra-bank counterpart.
     */
    private fun doublePosted(corpus: Corpus): Set<String> {
        val eligible = corpus.events.filter { record ->
            val decided = corpus.decisions[record.event.rawSmsId] ?: EffectiveUserDecisions.NONE
            (record.event.messageFamily == MessageFamily.TRANSFER_IN || record.event.messageFamily == MessageFamily.TRANSFER_OUT) &&
                !decided.isNonFinancial &&
                TransactionAssembler.isAutomationEligible(record.event, record.automationConfirmed || decided.confirmsFinancial)
        }.associate { record ->
            val event = record.event
            event.rawSmsId to TransferMatchCandidate(
                event, record.transactionReference, record.occurredAtLocal, record.receivedAt,
                corpus.registry.account(event.sourceAccountRef), corpus.registry.account(event.destinationAccountRef),
                TransactionTiming.effectiveOccurredAt(event, record.occurredAtLocal, record.receivedAt),
            )
        }
        val paired = TransactionMatcher.findMutuallyUniquePairs(eligible.values.toList())
            .flatMap { listOf(it.outgoing.event.rawSmsId, it.incoming.event.rawSmsId) }.toSet()
        val singles = corpus.stored.rows.filter { it.isSingleLegSelf() }.map { it.rawSmsIds.single() }.filter { id ->
            corpus.decisions[id]?.outcome == null && id !in corpus.stored.correctionClosedRawSmsIds && id in eligible && id !in paired
        }
        return singles.filterTo(mutableSetOf()) { id ->
            singles.any { other -> other != id && TransactionMatcher.hasPendingIntraBankCounterpart(eligible.getValue(id), listOf(eligible.getValue(other))) }
        }
    }

    private fun LedgerRow.isSingleLegSelf() =
        rawSmsIds.size == 1 && transaction.type == FinancialTransactionType.SELF_TRANSFER && transaction.linkedParsedEventIds.size == 1

    private fun decisionRows(random: Random, ids: List<String>) = (0 until random.nextInt(0, 4)).map {
        val subject = ids.random(random)
        val posting = UserDecision(
            subject, UserDecisionKind.FINANCIAL_TYPE, T0,
            transactionType = FinancialTransactionType.EXPENSE, sourceContainerId = "chosen",
            occurredAt = T0, occurredAtZone = "Asia/Riyadh",
        )
        when (random.nextInt(5)) {
            0 -> posting
            1 -> posting.copy(kind = UserDecisionKind.SELF_TRANSFER_PAIR, pairedRawSmsId = ids.random(random).takeIf { it != subject })
            2 -> UserDecision(subject, UserDecisionKind.NON_FINANCIAL, T0)
            3 -> UserDecision(subject, UserDecisionKind.EXTERNAL_TRANSFER, T0)
            else -> UserDecision(subject, UserDecisionKind.RESTORE, T0)
        }
    }
}
