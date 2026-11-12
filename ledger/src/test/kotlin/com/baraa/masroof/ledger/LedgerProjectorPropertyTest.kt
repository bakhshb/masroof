package com.baraa.masroof.ledger

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
        fun project(events: List<LedgerEvidence> = this.events, decisions: Map<String, EffectiveUserDecisions> = this.decisions) =
            LedgerProjector.project(events, decisions, registry, stored, ZoneOffset.ofHours(2))
    }

    private val seeds = 0 until 2000

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
        }
        assertTrue(narrower > seeds.count() / 2, "most scopes must leave evidence out")
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
        // Stored rows link one or two raw SMS each, never one SMS twice; some are kept verbatim by the keep rule.
        val storedIds = ids.filter { random.nextInt(3) == 0 }.shuffled(random)
        val storedSets = storedIds.fold(mutableListOf<MutableList<String>>()) { sets, id ->
            sets.apply { if (isNotEmpty() && last().size == 1 && random.nextInt(3) == 0) last() += id else add(mutableListOf(id)) }
        }
        val types = listOf(FinancialTransactionType.EXPENSE, FinancialTransactionType.SELF_TRANSFER, FinancialTransactionType.FEE)
        val stored = StoredFacts(
            rows = storedSets.map { set ->
                storedRow(
                    *set.toTypedArray(), zone = listOf("Asia/Dubai", "Asia/Riyadh", null).random(random), type = types.random(random),
                    rate = BigDecimal("3.75").takeIf { random.nextBoolean() }, rateSource = ExchangeRateSource.SMS,
                )
            },
            correctionClosedRawSmsIds = ids.filter { random.nextInt(6) == 0 }.toSet(),
        )
        return Corpus(events, fold(decisionRows(random, ids)), registry, stored)
    }

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
