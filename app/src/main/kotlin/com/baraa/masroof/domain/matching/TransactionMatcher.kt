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
 * Equal number of intra-bank legs that are all compatible with each other.
 * Any one-to-one pairing has the same accounts and amount, but the matcher
 * does not choose a pairing: reconciliation must not store a guessed link.
 */
data class EqualIntraBankLegSet(
    val outgoingEventIds: Set<String>,
    val incomingEventIds: Set<String>,
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

            val edges = mutableListOf<Pair<TransferMatchCandidate, TransferMatchCandidate>>()
            for (o in outs) {
                for (i in inns) {
                    if (compatiblePair(o, i)) edges += o to i
                }
            }
            val exactEdges = edges.filter { (out, inn) -> timeDistanceSeconds(out, inn) == 0L }
            val exactInsByOut = exactEdges.groupBy { it.first.event.id }
            val exactOutsByIn = exactEdges.groupBy { it.second.event.id }
            val proposedExact = exactEdges.filter { (out, inn) ->
                exactInsByOut[out.event.id].orEmpty().size == 1 &&
                    exactOutsByIn[inn.event.id].orEmpty().size == 1
            }
            val proposedIds = proposedExact
                .flatMap { (out, inn) -> listOf(out.event.id, inn.event.id) }
                .toSet()
            val acceptedExact = proposedExact.filter { (out, inn) ->
                val outNeighbors = edges.filter { it.first.event.id == out.event.id }.map { it.second.event.id }
                val inNeighbors = edges.filter { it.second.event.id == inn.event.id }.map { it.first.event.id }
                outNeighbors.all { it == inn.event.id || it in proposedIds } &&
                    inNeighbors.all { it == out.event.id || it in proposedIds }
            }
            val consumed = acceptedExact
                .flatMap { (out, inn) -> listOf(out.event.id, inn.event.id) }
                .toSet()
            for ((out, inn) in acceptedExact) {
                pairs += TransferMatchPair(out, inn)
            }

            val restOuts = outs.filter { it.event.id !in consumed }
            val restIns = inns.filter { it.event.id !in consumed }
            val eligibleOutToIn = mutableMapOf<String, MutableList<String>>()
            val eligibleInToOut = mutableMapOf<String, MutableList<String>>()
            for (o in restOuts) {
                for (i in restIns) {
                    if (!compatiblePair(o, i)) continue
                    eligibleOutToIn.getOrPut(o.event.id) { mutableListOf() }.add(i.event.id)
                    eligibleInToOut.getOrPut(i.event.id) { mutableListOf() }.add(o.event.id)
                }
            }
            for (o in restOuts) {
                val inIds = eligibleOutToIn[o.event.id].orEmpty()
                if (inIds.size != 1) continue
                val inId = inIds.single()
                val outIds = eligibleInToOut[inId].orEmpty()
                if (outIds.size != 1 || outIds.single() != o.event.id) continue
                val incomingCandidate = restIns.first { it.event.id == inId }
                pairs += TransferMatchPair(o, incomingCandidate)
            }
        }
        return pairs
    }

    /**
     * Intra-bank legs that share an amount and a fully connected window, with
     * the same number of outgoing and incoming events, after unique pairs are
     * removed. Presentation can count one movement per outgoing leg. Persisted
     * links stay untouched because no specific outgoing leg owns a specific
     * incoming leg.
     */
    fun equalIntraBankLegSets(
        candidates: List<TransferMatchCandidate>,
        alreadyPairedEventIds: Set<String> = emptySet(),
    ): List<EqualIntraBankLegSet> {
        val remaining = candidates.mapNotNull { candidate ->
            if (candidate.event.id in alreadyPairedEventIds) return@mapNotNull null
            val amount = candidate.event.amount ?: return@mapNotNull null
            moneyKey(amount) to candidate
        }
        if (remaining.isEmpty()) return emptyList()
        val byAmount = remaining.groupBy({ it.first }, { it.second })
        val sets = mutableListOf<EqualIntraBankLegSet>()
        for ((_, group) in byAmount) {
            val outs = group.filter { it.event.messageFamily == MessageFamily.TRANSFER_OUT }
            val inns = group.filter { it.event.messageFamily == MessageFamily.TRANSFER_IN }
            if (outs.isEmpty() || inns.isEmpty()) continue
            val byId = group.associateBy { it.event.id }
            val adjacent = mutableMapOf<String, MutableSet<String>>()
            for (out in outs) {
                for (inn in inns) {
                    if (!compatiblePair(out, inn) || !hasIntraBankAccountBridge(out, inn)) continue
                    adjacent.getOrPut(out.event.id) { mutableSetOf() }.add(inn.event.id)
                    adjacent.getOrPut(inn.event.id) { mutableSetOf() }.add(out.event.id)
                }
            }
            val seen = mutableSetOf<String>()
            for (start in adjacent.keys) {
                if (!seen.add(start)) continue
                val component = mutableListOf<String>()
                val stack = ArrayDeque<String>()
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val current = stack.removeLast()
                    component += current
                    for (next in adjacent[current].orEmpty()) {
                        if (seen.add(next)) stack.add(next)
                    }
                }
                val componentOuts = component.mapNotNull { id ->
                    byId[id]?.takeIf { it.event.messageFamily == MessageFamily.TRANSFER_OUT }
                }
                val componentIns = component.mapNotNull { id ->
                    byId[id]?.takeIf { it.event.messageFamily == MessageFamily.TRANSFER_IN }
                }
                if (componentOuts.size != componentIns.size || componentOuts.isEmpty()) continue
                val complete = componentOuts.all { out ->
                    componentIns.all { inn -> inn.event.id in adjacent[out.event.id].orEmpty() }
                }
                if (!complete) continue
                sets += EqualIntraBankLegSet(
                    outgoingEventIds = componentOuts.map { it.event.id }.toSet(),
                    incomingEventIds = componentIns.map { it.event.id }.toSet(),
                )
            }
        }
        return sets
    }

    private fun timeDistanceSeconds(
        a: TransferMatchCandidate,
        b: TransferMatchCandidate,
    ): Long? {
        val aLocal = a.occurredAtLocal
        val bLocal = b.occurredAtLocal
        if (aLocal != null && bLocal != null) {
            return kotlin.math.abs(java.time.Duration.between(aLocal, bLocal).seconds)
        }
        if (aLocal == null && bLocal == null) {
            return kotlin.math.abs(java.time.Duration.between(a.receivedAt, b.receivedAt).seconds)
        }
        val aEffective = a.effectiveOccurredAt ?: return null
        val bEffective = b.effectiveOccurredAt ?: return null
        return kotlin.math.abs(java.time.Duration.between(aEffective, bEffective).seconds)
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
