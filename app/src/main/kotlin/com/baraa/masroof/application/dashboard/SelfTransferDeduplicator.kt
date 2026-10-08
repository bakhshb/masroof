package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.matching.TransferMatchPair
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Hides a second dashboard row when it is another representation of a transfer
 * already shown.
 *
 * A shared amount and the same endpoints are not evidence. Two rows collapse
 * only when they share a parsed event or raw SMS, or when their legs are one
 * mutually unique [TransactionMatcher] pair. Persisted transactions are left
 * as stored; reconciliation owns any repair of duplicate links.
 */
object SelfTransferDeduplicator {
    private val transferTypes = setOf(
        FinancialTransactionType.SELF_TRANSFER,
        FinancialTransactionType.EXTERNAL_TRANSFER_IN,
        FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
    )

    fun filter(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
    ): List<FinancialTransaction> {
        val parsedById = parsedRecords.associateBy { it.event.id }
        val transfers = transactions.filter { it.type in transferTypes }
        if (transfers.size < 2) return transactions

        val pairs = TransactionMatcher.findMutuallyUniquePairs(
            matchCandidates(transfers, parsedById),
        )
        val suppressed = suppressedIds(transfers, parsedById, pairs)
        if (suppressed.isEmpty()) return transactions
        return transactions.filter { it.id !in suppressed }
    }

    private fun suppressedIds(
        transfers: List<FinancialTransaction>,
        parsedById: Map<String, ParsedEventRecord>,
        pairs: List<TransferMatchPair>,
    ): Set<String> {
        val members = DisjointSet(transfers.map { it.id })
        for (leftIndex in transfers.indices) {
            for (rightIndex in leftIndex + 1 until transfers.size) {
                val left = transfers[leftIndex]
                val right = transfers[rightIndex]
                if (sameMovement(left, right, parsedById, pairs)) {
                    members.union(left.id, right.id)
                }
            }
        }
        return transfers
            .groupBy { members.find(it.id) }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                val canonical = group.maxWith(canonicalOrder(parsedById))
                group.filter { it.id != canonical.id }.map { it.id }
            }
            .toSet()
    }

    private fun sameMovement(
        left: FinancialTransaction,
        right: FinancialTransaction,
        parsedById: Map<String, ParsedEventRecord>,
        pairs: List<TransferMatchPair>,
    ): Boolean {
        val leftEvents = left.linkedParsedEventIds.toSet()
        val rightEvents = right.linkedParsedEventIds.toSet()
        if (leftEvents.intersect(rightEvents).isNotEmpty()) return true
        if (rawSmsIds(left, parsedById).intersect(rawSmsIds(right, parsedById)).isNotEmpty()) {
            return true
        }
        return pairs.any { pair ->
            val pairEvents = setOf(pair.outgoing.event.id, pair.incoming.event.id)
            val fromLeft = leftEvents.intersect(pairEvents)
            val fromRight = rightEvents.intersect(pairEvents)
            fromLeft.isNotEmpty() && fromRight.isNotEmpty() && fromLeft + fromRight == pairEvents
        }
    }

    private fun matchCandidates(
        transfers: List<FinancialTransaction>,
        parsedById: Map<String, ParsedEventRecord>,
    ): List<TransferMatchCandidate> {
        val eventIds = transfers.flatMap { it.linkedParsedEventIds }.distinct()
        return eventIds.mapNotNull { eventId ->
            val record = parsedById[eventId] ?: return@mapNotNull null
            val event = record.event
            if (event.messageFamily != MessageFamily.TRANSFER_OUT &&
                event.messageFamily != MessageFamily.TRANSFER_IN
            ) {
                return@mapNotNull null
            }
            if (event.amount == null) return@mapNotNull null
            val linked = transfers.filter { eventId in it.linkedParsedEventIds }
            val occurredAt = linked.minOfOrNull { it.occurredAt } ?: return@mapNotNull null
            val (sourceOwned, destinationOwned) = ownership(linked)
            TransferMatchCandidate(
                event = event,
                transactionReference = record.details.transactionReference,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = occurredAt,
                sourceOwnership = sourceOwned,
                destinationOwnership = destinationOwned,
                effectiveOccurredAt = occurredAt,
            )
        }
    }

    private fun ownership(
        linked: List<FinancialTransaction>,
    ): Pair<OwnershipStatus, OwnershipStatus> {
        if (linked.any { it.type == FinancialTransactionType.SELF_TRANSFER }) {
            return OwnershipStatus.OWNED to OwnershipStatus.OWNED
        }
        val source = if (linked.any { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT }) {
            OwnershipStatus.OWNED
        } else {
            OwnershipStatus.UNKNOWN
        }
        val destination = if (linked.any { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN }) {
            OwnershipStatus.OWNED
        } else {
            OwnershipStatus.UNKNOWN
        }
        return source to destination
    }

    private fun canonicalOrder(
        parsedById: Map<String, ParsedEventRecord>,
    ): Comparator<FinancialTransaction> =
        compareBy<FinancialTransaction> { it.type == FinancialTransactionType.SELF_TRANSFER }
            .thenBy { it.linkedParsedEventIds.size }
            .thenBy { rawSmsIds(it, parsedById).size }
            .thenBy { it.id }

    private fun rawSmsIds(
        transaction: FinancialTransaction,
        parsedById: Map<String, ParsedEventRecord>,
    ): Set<String> =
        transaction.linkedParsedEventIds.mapNotNull { parsedById[it]?.event?.rawSmsId }.toSet()

    private class DisjointSet(ids: Collection<String>) {
        private val parent = ids.associateWithTo(mutableMapOf()) { it }

        fun find(id: String): String {
            val current = parent.getValue(id)
            if (current == id) return id
            val root = find(current)
            parent[id] = root
            return root
        }

        fun union(left: String, right: String) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot == rightRoot) return
            if (leftRoot < rightRoot) parent[rightRoot] = leftRoot else parent[leftRoot] = rightRoot
        }
    }
}
