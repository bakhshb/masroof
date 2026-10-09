package com.baraa.masroof.domain.matching

import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParsedEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Locale

/**
 * Facts required for conservative TRANSFER_OUT ↔ TRANSFER_IN matching.
 * Does not import parsing-layer types.
 */
data class TransferMatchCandidate(
    val event: ParsedEvent,
    val transactionReference: String?,
    val occurredAtLocal: LocalDateTime?,
    val receivedAt: Instant,
    val sourceOwnership: OwnershipStatus,
    val destinationOwnership: OwnershipStatus,
    /**
     * Caller-supplied instant for this leg: local wall time in the bank zone when
     * present, otherwise [receivedAt]. The matcher does not read a clock or zone.
     * Used only when exactly one leg has [occurredAtLocal].
     */
    val effectiveOccurredAt: Instant? = null,
) {
    val amount: Money? get() = event.amount
}

data class TransferMatchPair(
    val outgoing: TransferMatchCandidate,
    val incoming: TransferMatchCandidate,
)

/**
 * Pure conservative transfer-pair matcher. No Room / Android.
 *
 * Requires exact Money equality, documented time window, OWNED local sides,
 * mutually unique relationship, and at least one strong identity bridge.
 */
object TransactionMatcher {
    /** Maximum |t1 − t2| for automatic transfer pairing. */
    val TRANSFER_MATCH_WINDOW: Duration = Duration.ofMinutes(10)

    /**
     * Pairs transfer legs inside the caller-supplied candidate set.
     *
     * Callers pass a receipt or bank-local window, not the whole ledger.
     * An exact shared reference or equal bank-local time wins over any other
     * candidate that is merely inside [TRANSFER_MATCH_WINDOW]. Equal best
     * candidates stay unpaired.
     */
    fun findMutuallyUniquePairs(
        candidates: List<TransferMatchCandidate>,
    ): List<TransferMatchPair> {
        val outgoing = candidates.filter {
            it.event.messageFamily == MessageFamily.TRANSFER_OUT &&
                it.event.amount != null &&
                it.sourceOwnership == OwnershipStatus.OWNED
        }
        val incoming = candidates.filter {
            it.event.messageFamily == MessageFamily.TRANSFER_IN &&
                it.event.amount != null &&
                it.destinationOwnership == OwnershipStatus.OWNED
        }

        val byAmount = (outgoing + incoming).groupBy { moneyKey(it.event.amount!!) }

        val pairs = mutableListOf<TransferMatchPair>()
        for ((_, group) in byAmount) {
            val outs = group.filter { it.event.messageFamily == MessageFamily.TRANSFER_OUT }
            val inns = group.filter { it.event.messageFamily == MessageFamily.TRANSFER_IN }
            if (outs.isEmpty() || inns.isEmpty()) continue
            pairs += uniqueBestPairs(outs, inns)
        }
        return pairs
    }

    private fun uniqueBestPairs(
        outs: List<TransferMatchCandidate>,
        inns: List<TransferMatchCandidate>,
    ): List<TransferMatchPair> {
        val edges = mutableListOf<RankedPair>()
        for (outgoing in outs) {
            for (incoming in inns) {
                val rank = pairRank(outgoing, incoming) ?: continue
                edges += RankedPair(outgoing, incoming, rank)
            }
        }
        if (edges.isEmpty()) return emptyList()

        val bestForOut = edges.groupBy { it.outgoing.event.id }.mapValues { (_, group) ->
            val best = group.minOf { it.rank }
            group.filter { it.rank == best }
        }
        val bestForIn = edges.groupBy { it.incoming.event.id }.mapValues { (_, group) ->
            val best = group.minOf { it.rank }
            group.filter { it.rank == best }
        }

        val pairs = mutableListOf<TransferMatchPair>()
        for ((outId, outEdges) in bestForOut) {
            if (outEdges.size != 1) continue
            val edge = outEdges.single()
            val inEdges = bestForIn[edge.incoming.event.id].orEmpty()
            if (inEdges.size != 1 || inEdges.single().outgoing.event.id != outId) continue
            pairs += TransferMatchPair(edge.outgoing, edge.incoming)
        }
        return pairs
    }

    /**
     * Best identity for a compatible pair. Lower ordinal is stronger.
     * [WINDOW] is only the best rank when nothing more specific is compatible.
     */
    private enum class PairRank {
        REFERENCE,
        EXACT_LOCAL_TIME,
        EXACT_RECEIPT,
        WINDOW,
    }

    private data class RankedPair(
        val outgoing: TransferMatchCandidate,
        val incoming: TransferMatchCandidate,
        val rank: PairRank,
    )

    private fun pairRank(
        outgoing: TransferMatchCandidate,
        incoming: TransferMatchCandidate,
    ): PairRank? {
        if (!compatiblePair(outgoing, incoming)) return null
        val outRef = normalizedReference(outgoing.transactionReference)
        val inRef = normalizedReference(incoming.transactionReference)
        if (outRef.isNotEmpty() && outRef == inRef) return PairRank.REFERENCE
        val outLocal = outgoing.occurredAtLocal
        val inLocal = incoming.occurredAtLocal
        if (outLocal != null && outLocal == inLocal) return PairRank.EXACT_LOCAL_TIME
        if (outLocal == null && inLocal == null && outgoing.receivedAt == incoming.receivedAt) {
            return PairRank.EXACT_RECEIPT
        }
        return PairRank.WINDOW
    }

    private fun normalizedReference(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private fun referencesConflict(
        outgoing: TransferMatchCandidate,
        incoming: TransferMatchCandidate,
    ): Boolean {
        val outRef = normalizedReference(outgoing.transactionReference)
        val inRef = normalizedReference(incoming.transactionReference)
        return outRef.isNotEmpty() && inRef.isNotEmpty() && outRef != inRef
    }

    fun compatiblePair(
        outgoing: TransferMatchCandidate,
        incoming: TransferMatchCandidate,
    ): Boolean {
        if (outgoing.event.id == incoming.event.id) return false
        if (outgoing.event.rawSmsId == incoming.event.rawSmsId) return false
        if (outgoing.event.messageFamily != MessageFamily.TRANSFER_OUT) return false
        if (incoming.event.messageFamily != MessageFamily.TRANSFER_IN) return false

        val outAmount = outgoing.event.amount ?: return false
        val inAmount = incoming.event.amount ?: return false
        if (outAmount != inAmount) return false

        if (outgoing.sourceOwnership != OwnershipStatus.OWNED) return false
        if (incoming.destinationOwnership != OwnershipStatus.OWNED) return false
        if (referencesConflict(outgoing, incoming)) return false

        if (!withinWindow(outgoing, incoming)) return false
        if (!hasStrongBridge(outgoing, incoming)) return false
        return true
    }

    private fun withinWindow(
        a: TransferMatchCandidate,
        b: TransferMatchCandidate,
    ): Boolean {
        val aLocal = a.occurredAtLocal
        val bLocal = b.occurredAtLocal
        if (aLocal != null && bLocal != null) {
            val seconds = kotlin.math.abs(java.time.Duration.between(aLocal, bLocal).seconds)
            return seconds <= TRANSFER_MATCH_WINDOW.seconds
        }
        if (aLocal == null && bLocal == null) {
            val delta = kotlin.math.abs(
                java.time.Duration.between(a.receivedAt, b.receivedAt).seconds,
            )
            return delta <= TRANSFER_MATCH_WINDOW.seconds
        }
        val aEffective = a.effectiveOccurredAt ?: return false
        val bEffective = b.effectiveOccurredAt ?: return false
        val mixedDelta = kotlin.math.abs(
            java.time.Duration.between(aEffective, bEffective).seconds,
        )
        return mixedDelta <= TRANSFER_MATCH_WINDOW.seconds
    }

    private fun hasStrongBridge(
        outgoing: TransferMatchCandidate,
        incoming: TransferMatchCandidate,
    ): Boolean {
        val outRef = outgoing.transactionReference?.trim().orEmpty()
        val inRef = incoming.transactionReference?.trim().orEmpty()
        if (outRef.isNotEmpty() && outRef == inRef) return true

        if (hasIntraBankAccountBridge(outgoing, incoming)) return true

        // UNKNOWN-side suffix bridge: outgoing UNKNOWN dest masked ↔ incoming known dest masked
        val outDest = outgoing.event.destinationAccountRef ?: return false
        val inDest = incoming.event.destinationAccountRef ?: return false
        if (outDest.bank != Bank.UNKNOWN) return false
        if (inDest.bank == Bank.UNKNOWN) return false
        val outSuffix = outDest.maskedNumber?.trim().orEmpty()
        val inSuffix = inDest.maskedNumber?.trim().orEmpty()
        if (outSuffix.isEmpty() || inSuffix.isEmpty()) return false
        return outSuffix == inSuffix
    }

    /**
     * True when an OUT leg and an IN leg describe the same intra-bank movement
     * (matching source/destination account suffixes on known-bank refs).
     */
    fun hasIntraBankAccountBridge(
        outgoing: TransferMatchCandidate,
        incoming: TransferMatchCandidate,
    ): Boolean = hasIntraBankAccountBridge(outgoing.event, incoming.event)

    fun hasIntraBankAccountBridge(
        outgoing: ParsedEvent,
        incoming: ParsedEvent,
    ): Boolean {
        if (outgoing.messageFamily != MessageFamily.TRANSFER_OUT) return false
        if (incoming.messageFamily != MessageFamily.TRANSFER_IN) return false
        if (outgoing.bankNetworkType != BankNetworkType.INTRA_BANK) {
            return false
        }
        if (incoming.bankNetworkType != BankNetworkType.INTRA_BANK) {
            return false
        }

        val outSource = outgoing.sourceAccountRef ?: return false
        val outDest = outgoing.destinationAccountRef ?: return false
        val inSource = incoming.sourceAccountRef ?: return false
        val inDest = incoming.destinationAccountRef ?: return false

        if (outSource.bank == Bank.UNKNOWN || outDest.bank == Bank.UNKNOWN) return false
        if (inSource.bank == Bank.UNKNOWN || inDest.bank == Bank.UNKNOWN) return false

        val outSourceSuffix = outSource.maskedNumber?.trim().orEmpty()
        val outDestSuffix = outDest.maskedNumber?.trim().orEmpty()
        val inSourceSuffix = inSource.maskedNumber?.trim().orEmpty()
        val inDestSuffix = inDest.maskedNumber?.trim().orEmpty()
        if (outSourceSuffix.isEmpty() || outDestSuffix.isEmpty()) return false
        if (inSourceSuffix.isEmpty() || inDestSuffix.isEmpty()) return false
        if (outgoing.bank != incoming.bank) return false
        if (outSource.bank != inSource.bank || outDest.bank != inDest.bank) return false

        return outSourceSuffix == inSourceSuffix && outDestSuffix == inDestSuffix
    }

    /**
     * Whether [candidate] has a pending opposite-leg intra-bank counterpart in [pending].
     * Used to defer single-leg external posting until ownership can confirm self-transfer.
     */
    fun hasPendingIntraBankCounterpart(
        candidate: TransferMatchCandidate,
        pending: List<TransferMatchCandidate>,
    ): Boolean {
        val amount = candidate.event.amount ?: return false
        return pending.any { other ->
            if (other.event.id == candidate.event.id) return@any false
            if (other.event.rawSmsId == candidate.event.rawSmsId) return@any false
            if (other.event.amount != amount) return@any false
            if (!withinWindow(candidate, other)) return@any false

            when (candidate.event.messageFamily) {
                MessageFamily.TRANSFER_OUT ->
                    other.event.messageFamily == MessageFamily.TRANSFER_IN &&
                        hasIntraBankAccountBridge(candidate.event, other.event)

                MessageFamily.TRANSFER_IN ->
                    other.event.messageFamily == MessageFamily.TRANSFER_OUT &&
                        hasIntraBankAccountBridge(other.event, candidate.event)

                else -> false
            }
        }
    }

    private fun moneyKey(money: Money): String =
        "${money.currency.name}|${money.amount.stripTrailingZeros().toPlainString()}"
}
