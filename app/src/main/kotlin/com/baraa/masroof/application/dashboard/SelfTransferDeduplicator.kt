package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.matching.EqualIntraBankLegSet
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
 * mutually unique [TransactionMatcher] pair. When several intra-bank
 * movements share one clock minute, no leg owns a unique counterpart, so
 * stored rows stay separate. The dashboard still keeps one row per outgoing
 * leg and hides the matching incoming-only rows, because every pairing would
 * move the same amount between the same accounts. A row is hidden only when
 * the row that stays already carries its account endpoints, so one leg is not
 * dropped while the other account's movement exists only on that leg.
 * Persisted transactions are left as stored; reconciliation owns any repair
 * of duplicate links.
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

        val candidates = matchCandidates(transfers, parsedById)
        val pairs = TransactionMatcher.findMutuallyUniquePairs(candidates)
        val suppressed = suppressedIds(transfers, parsedById, pairs, candidates)
        if (suppressed.isEmpty()) return transactions
        return transactions.filter { it.id !in suppressed }
    }

    private fun suppressedIds(
        transfers: List<FinancialTransaction>,
        parsedById: Map<String, ParsedEventRecord>,
        pairs: List<TransferMatchPair>,
        candidates: List<TransferMatchCandidate>,
    ): Set<String> {
        val members = DisjointSet(transfers.map { it.id })
        unionSharing(transfers, members) { tx -> tx.linkedParsedEventIds }
        unionSharing(transfers, members) { tx -> rawSmsIds(tx, parsedById) }
        unionMatcherPairs(transfers, members, pairs)
        val pairedSuppressed = transfers
            .groupBy { members.find(it.id) }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                val canonical = group.maxWith(canonicalOrder(parsedById))
                group
                    .filter { it.id != canonical.id && endpointsCovered(canonical, it) }
                    .map { it.id }
            }
            .toSet()
        val pairedEventIds = pairs
            .flatMap { listOf(it.outgoing.event.id, it.incoming.event.id) }
            .toSet()
        val ambiguousSuppressed = ambiguousIncomingLegs(
            transfers = transfers.filter { it.id !in pairedSuppressed },
            groups = TransactionMatcher.equalIntraBankLegSets(
                candidates,
                pairedEventIds,
            ),
        )
        return pairedSuppressed + ambiguousSuppressed
    }

    /**
     * Hides incoming-only rows in an equal intra-bank set. The outgoing rows
     * stay, one per movement, and already name both accounts.
     */
    private fun ambiguousIncomingLegs(
        transfers: List<FinancialTransaction>,
        groups: List<EqualIntraBankLegSet>,
    ): Set<String> {
        if (groups.isEmpty()) return emptySet()
        val suppressed = mutableSetOf<String>()
        for (group in groups) {
            val outgoing = transfers.filter { tx ->
                tx.linkedParsedEventIds.any { it in group.outgoingEventIds }
            }
            val incomingOnly = transfers.filter { tx ->
                tx.linkedParsedEventIds.any { it in group.incomingEventIds } &&
                    tx.linkedParsedEventIds.none { it in group.outgoingEventIds }
            }
            if (outgoing.size != group.outgoingEventIds.size) continue
            if (incomingOnly.size != group.incomingEventIds.size) continue
            for (incoming in incomingOnly) {
                if (outgoing.any { endpointsCovered(it, incoming) }) suppressed += incoming.id
            }
        }
        return suppressed
    }

    private fun unionSharing(
        transfers: List<FinancialTransaction>,
        members: DisjointSet,
        keys: (FinancialTransaction) -> Collection<String>,
    ) {
        val grouped = mutableMapOf<String, MutableList<String>>()
        for (tx in transfers) {
            for (key in keys(tx)) {
                grouped.getOrPut(key) { mutableListOf() }.add(tx.id)
            }
        }
        for (ids in grouped.values) {
            if (ids.size < 2) continue
            val head = ids.first()
            for (id in ids.drop(1)) members.union(head, id)
        }
    }

    private fun unionMatcherPairs(
        transfers: List<FinancialTransaction>,
        members: DisjointSet,
        pairs: List<TransferMatchPair>,
    ) {
        if (pairs.isEmpty()) return
        val holders = mutableMapOf<String, MutableList<FinancialTransaction>>()
        for (tx in transfers) {
            for (eventId in tx.linkedParsedEventIds) {
                holders.getOrPut(eventId) { mutableListOf() }.add(tx)
            }
        }
        for (pair in pairs) {
            val pairEvents = setOf(pair.outgoing.event.id, pair.incoming.event.id)
            val outgoingHolders = holders[pair.outgoing.event.id].orEmpty()
            val incomingHolders = holders[pair.incoming.event.id].orEmpty()
            for (left in outgoingHolders) {
                for (right in incomingHolders) {
                    if (left.id == right.id) continue
                    val covered = (left.linkedParsedEventIds + right.linkedParsedEventIds).toSet()
                    if (pairEvents.all { it in covered }) members.union(left.id, right.id)
                }
            }
        }
    }

    private fun matchCandidates(
        transfers: List<FinancialTransaction>,
        parsedById: Map<String, ParsedEventRecord>,
    ): List<TransferMatchCandidate> {
        val holders = mutableMapOf<String, MutableList<FinancialTransaction>>()
        for (tx in transfers) {
            for (eventId in tx.linkedParsedEventIds) {
                holders.getOrPut(eventId) { mutableListOf() }.add(tx)
            }
        }
        return holders.mapNotNull { (eventId, linked) ->
            val record = parsedById[eventId] ?: return@mapNotNull null
            val event = record.event
            if (event.messageFamily != MessageFamily.TRANSFER_OUT &&
                event.messageFamily != MessageFamily.TRANSFER_IN
            ) {
                return@mapNotNull null
            }
            if (event.amount == null) return@mapNotNull null
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

    /**
     * True when every account named on [other] is already named on [canonical].
     * Complementary external legs (out on one account, in on the other) stay
     * visible until reconciliation stores one self-transfer that carries both.
     */
    private fun endpointsCovered(
        canonical: FinancialTransaction,
        other: FinancialTransaction,
    ): Boolean {
        fun covered(containerId: String?): Boolean =
            containerId == null ||
                containerId == canonical.sourceContainerId ||
                containerId == canonical.destinationContainerId
        return covered(other.sourceContainerId) && covered(other.destinationContainerId)
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
