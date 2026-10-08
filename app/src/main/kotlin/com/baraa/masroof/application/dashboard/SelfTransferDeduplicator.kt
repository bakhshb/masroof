package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Hides a second stored row only when it is the same transfer evidence as another row.
 *
 * Shared parsed-event or RawSms ids are the proof. Equal amount and endpoints, with
 * different evidence, stay visible. Rows that share evidence but disagree on currency,
 * amount, direction, or account endpoints also stay visible.
 *
 * Lookup is indexed by event id and RawSms id. The dashboard does not compare every
 * transfer with every other transfer, and this filter does not write.
 */
object SelfTransferDeduplicator {
    fun filter(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
    ): List<FinancialTransaction> {
        if (transactions.size < 2) return transactions
        val parsedById = parsedRecords.associateBy { it.event.id }
        val rawSmsIdsByTx = transactions.associate { tx ->
            tx.id to tx.linkedParsedEventIds.mapNotNull { parsedById[it]?.event?.rawSmsId }.toSet()
        }
        val byEvidence = linkedMapOf<String, MutableList<FinancialTransaction>>()
        for (tx in transactions) {
            if (!isTransfer(tx.type)) continue
            for (eventId in tx.linkedParsedEventIds) {
                byEvidence.getOrPut("event:$eventId") { mutableListOf() }.add(tx)
            }
            for (rawSmsId in rawSmsIdsByTx.getValue(tx.id)) {
                byEvidence.getOrPut("raw:$rawSmsId") { mutableListOf() }.add(tx)
            }
        }

        val parent = transactions.associate { it.id to it.id }.toMutableMap()
        fun find(id: String): String {
            var current = id
            while (parent.getValue(current) != current) {
                current = parent.getValue(current)
            }
            var cursor = id
            while (parent.getValue(cursor) != current) {
                val next = parent.getValue(cursor)
                parent[cursor] = current
                cursor = next
            }
            return current
        }
        fun union(left: String, right: String) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot == rightRoot) return
            if (leftRoot < rightRoot) parent[rightRoot] = leftRoot else parent[leftRoot] = rightRoot
        }

        for (group in byEvidence.values) {
            val distinct = group.distinctBy { it.id }
            if (distinct.size < 2) continue
            for (index in distinct.indices) {
                for (other in index + 1 until distinct.size) {
                    val left = distinct[index]
                    val right = distinct[other]
                    if (sameProvenTransfer(left, right)) union(left.id, right.id)
                }
            }
        }

        val suppressed = mutableSetOf<String>()
        transactions
            .filter { isTransfer(it.type) }
            .groupBy { find(it.id) }
            .values
            .filter { it.size > 1 }
            .forEach { component ->
                val canonical = component.maxWith(canonicalOrder(rawSmsIdsByTx))
                component.filter { it.id != canonical.id }.forEach { suppressed.add(it.id) }
            }

        return transactions.filter { it.id !in suppressed }
    }

    private fun sameProvenTransfer(left: FinancialTransaction, right: FinancialTransaction): Boolean {
        if (left.amount != right.amount) return false
        if (!isTransfer(left.type) || !isTransfer(right.type)) return false
        return endpointsAgree(left, right)
    }

    private fun endpointsAgree(left: FinancialTransaction, right: FinancialTransaction): Boolean {
        val self = listOf(left, right).filter { it.type == FinancialTransactionType.SELF_TRANSFER }
        val externals = listOf(left, right).filter { it.type != FinancialTransactionType.SELF_TRANSFER }
        if (self.size == 2) {
            return left.sourceContainerId != null &&
                left.destinationContainerId != null &&
                left.sourceContainerId == right.sourceContainerId &&
                left.destinationContainerId == right.destinationContainerId
        }
        if (self.size == 1 && externals.size == 1) {
            val internal = self.single()
            val external = externals.single()
            return when (external.type) {
                FinancialTransactionType.EXTERNAL_TRANSFER_OUT ->
                    internal.sourceContainerId != null &&
                        internal.sourceContainerId == external.sourceContainerId

                FinancialTransactionType.EXTERNAL_TRANSFER_IN ->
                    internal.destinationContainerId != null &&
                        internal.destinationContainerId == external.destinationContainerId

                else -> false
            }
        }
        return left.type == right.type &&
            left.sourceContainerId == right.sourceContainerId &&
            left.destinationContainerId == right.destinationContainerId
    }

    private fun canonicalOrder(
        rawSmsIdsByTx: Map<String, Set<String>>,
    ): Comparator<FinancialTransaction> =
        compareBy<FinancialTransaction> { if (it.type == FinancialTransactionType.SELF_TRANSFER) 1 else 0 }
            .thenBy { it.linkedParsedEventIds.size }
            .thenBy { rawSmsIdsByTx[it.id].orEmpty().size }
            .thenBy { it.id }

    private fun isTransfer(type: FinancialTransactionType): Boolean =
        type == FinancialTransactionType.SELF_TRANSFER ||
            type == FinancialTransactionType.EXTERNAL_TRANSFER_IN ||
            type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT
}
