package com.baraa.masroof.application.transaction

import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.ledger.LedgerProjection
import com.baraa.masroof.ledger.LedgerProjector
import com.baraa.masroof.ledger.LedgerRow
import com.baraa.masroof.ledger.isTransfer
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/** The automatic write guard: runs the write only when the evidence it was derived from is still current. */
typealias AutomaticWrite =
    suspend (List<ParsedEventRecord>, suspend () -> FinancialTransactionSaveResult) -> FinancialTransactionSaveResult

/**
 * The reconciliation writer (plan 3.3a; docs/ARCHITECTURE.md, "Ledger writer"): projects the affected raw SMS with
 * [LedgerProjector], compares the projection with the stored rows of that scope and writes only the differences, in
 * one database transaction ([inTransaction]). A difference is one unit: the stored and projected rows that share a
 * raw SMS or an id. Each unit is checked inside the transaction before anything is written: the stored rows must
 * still be the ones projected from, and the [automaticWrite] guard must accept the projected rows' evidence.
 */
internal class LedgerProjectionWriter(
    private val loader: LedgerInputLoader,
    private val transactions: FinancialTransactionRepository,
    private val ownershipConfirmation: OwnershipConfirmationService,
    private val automaticWrite: AutomaticWrite?,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    /** Projects and writes [affected] and what [LedgerProjector.scope] adds to it, or the whole history when null. */
    suspend fun reconcile(affected: Collection<String>?): ReconciliationReport {
        var attempt = 1
        while (true) {
            val inputs = if (affected == null) loader.load() else loader.loadScope(affected)
            val stored = inputs.storedFacts.rows
            val projection = inputs.project()
            projection.confirmedLoans.forEach { ownershipConfirmation.confirmLoanOwned(it) }
            val units = units(stored, projection.rows)
            val written = try {
                write(units, inputs.records)
            } catch (_: ConcurrentChange) {
                // Another writer changed these rows before this one wrote: project again from what it wrote, once.
                if (attempt++ < ATTEMPTS) continue
                Written(failed = units.toMutableList())
            }
            // A save that lost to another writer: project again once if the rows really moved meanwhile.
            if (written.conflicted && attempt++ < ATTEMPTS && changedMeanwhile(units, written)) continue
            return report(projection, written, affected?.toSet(), inputs.records)
        }
    }

    /** True when the stored rows of [units] are neither what this pass read nor what it wrote. */
    private suspend fun changedMeanwhile(units: List<Change>, written: Written): Boolean {
        val current = currentRows(units)
        return units.any { unit ->
            val expected = if (unit in written.applied) unit.projected else unit.stored
            unit.now(current) != expected.normalized()
        }
    }

    /** Stored and projected rows grouped by shared raw SMS or id; only groups that differ. */
    private fun units(stored: List<LedgerRow>, projected: List<LedgerRow>): List<Change> {
        // A link-less row has no raw SMS set to match: it is kept byte for byte and never takes another's pair.
        val storedBySet = stored.filter { it.rawSmsIds.isNotEmpty() }.associateBy { it.rawSmsIds }
        // A stored exchange-rate pair, complete or half, follows its raw SMS set; enrichment repairs a half pair.
        val carried = projected.map { row ->
            val match = storedBySet[row.rawSmsIds]?.transaction ?: return@map row
            row.copy(transaction = row.transaction.copy(
                appliedExchangeRate = match.appliedExchangeRate, exchangeRateSource = match.exchangeRateSource,
            ))
        }
        val rows = stored + carried
        val parent = IntArray(rows.size) { it }
        fun root(i: Int): Int = if (parent[i] == i) i else root(parent[i]).also { parent[i] = it }
        val firstByKey = mutableMapOf<String, Int>()
        rows.forEachIndexed { index, row ->
            (row.rawSmsIds.map { "sms:$it" } + "id:${row.transaction.id}").forEach { key ->
                firstByKey.putIfAbsent(key, index)?.let { parent[root(index)] = root(it) }
            }
        }
        return rows.indices.groupBy(::root).values.map { group ->
            Change(group.filter { it < stored.size }.map(rows::get), group.filter { it >= stored.size }.map(rows::get))
        }.filter { it.stored.normalized() != it.projected.normalized() }
    }

    private suspend fun write(units: List<Change>, records: Map<String, ParsedEventRecord>): Written {
        if (units.isEmpty()) return Written()
        val written = Written()
        inTransaction {
            val current = currentRows(units)
            val admitted = mutableListOf<Change>()
            for (unit in units) {
                val now = unit.now(current)
                when {
                    now == unit.projected.normalized() -> Unit // Another pass wrote the same rows.
                    now != unit.stored.normalized() -> throw ConcurrentChange()
                    // A blocked refinement stays as stored, as the old pass never rewrote a posted row. (A protected
                    // stored row never reaches here: the projector keeps it verbatim, so it is in no unit.)
                    !guardAccepts(unit, records) -> if (unit.refinesOnly()) written.kept += unit else written.failed += unit
                    else -> admitted += unit
                }
            }
            // Two commitments cannot name one row: the unit stays as stored rather than lose a commitment's row.
            val (clashing, clear) = admitted.partition(commitmentClashes(admitted))
            written.kept += clashing
            for (unit in clear) {
                if (apply(unit)) {
                    written.applied += unit
                } else {
                    written.failed += unit
                    written.conflicted = true
                }
            }
        }
        return written
    }

    private suspend fun currentRows(units: List<Change>): List<LedgerRow> {
        val ids = units.flatMap { it.ids }
        val rawSmsIds = units.flatMap { it.rawSmsIds }
        return transactions.ledgerRows(transactions.getByIds(ids) + transactions.findByRawSmsIds(rawSmsIds).values)
    }

    private suspend fun guardAccepts(unit: Change, records: Map<String, ParsedEventRecord>): Boolean {
        val guard = automaticWrite ?: return true
        if (unit.projected.isEmpty()) return true // Removing a row the rules no longer post is not an automatic post.
        val expected = unit.projected.flatMap { it.rawSmsIds }.map { records[it] ?: return false }
        return guard(expected) { FinancialTransactionSaveResult.Saved } !is FinancialTransactionSaveResult.Conflict
    }

    /**
     * Units whose re-keyed commitment would name a row that already has one (the commitment source is unique):
     * they are not written and stay as stored.
     */
    private suspend fun commitmentClashes(units: List<Change>): (Change) -> Boolean {
        val committed = transactions.listCommittedTransactionIds(units.flatMap { it.ids })
        return { unit ->
            val removed = unit.removed()
            val removedIds = removed.mapTo(mutableSetOf()) { it.transaction.id }
            val targets = removed.filter { it.transaction.id in committed }.mapNotNull { unit.replacementOf(it) }
            targets.size != targets.distinct().size || targets.any { it in committed && it !in removedIds }
        }
    }

    /**
     * Unlinks what moves, saves every projected row (a complete stored exchange-rate pair stays) and re-keys. A save
     * that conflicts puts the unit's stored rows back and returns false: inside the financial write transaction no
     * other writer can link these raw SMS, so this only happens without one.
     */
    private suspend fun apply(unit: Change): Boolean {
        val target = unit.projected.flatMap { row -> row.rawSmsIds.map { it to row.transaction.id } }.toMap()
        for (row in unit.stored) {
            row.rawSmsIds.filter { target[it] != row.transaction.id }.forEach { transactions.unlinkRawSms(it) }
        }
        val saved = mutableListOf<LedgerRow>()
        for (row in unit.projected) {
            if (transactions.save(row.transaction, row.rawSmsIds) is FinancialTransactionSaveResult.Conflict) {
                saved.forEach { done -> done.rawSmsIds.forEach { transactions.unlinkRawSms(it) } }
                unit.stored.forEach { transactions.save(it.transaction, it.rawSmsIds) }
                return false
            }
            saved += row
        }
        for (row in unit.removed()) {
            val replacement = unit.replacementOf(row) ?: continue
            transactions.moveTransactionReferences(
                row.transaction.id, replacement, row.rawSmsIds.associateWith { target[it] ?: replacement },
            )
        }
        return true
    }

    /**
     * The report of this pass. A scoped pass ([affected] not null) reports the affected raw SMS and the raw SMS of the
     * units it wrote; a neighbour unit that failed or was not written is left as stored and not reported (it is
     * reported by its own pass), as the old scoped pass treated neighbours as candidates only. A unit that was not
     * written ([Written.kept]) settles only the raw SMS its stored rows link; any other raw SMS of its projected rows
     * has no row, so it is reported as a review candidate, never as settled.
     */
    private fun report(
        projection: LedgerProjection,
        written: Written,
        affected: Set<String>?,
        records: Map<String, ParsedEventRecord>,
    ): ReconciliationReport {
        fun reported(unit: Change) = affected == null || unit.rawSmsIds.any { it in affected }
        val reportable = affected?.let { it + written.applied.flatMap { unit -> unit.rawSmsIds } }
        fun inReport(rawSmsId: String) = reportable == null || rawSmsId in reportable
        val failedUnits = written.failed.filter(::reported)
        val failedUnitIds = failedUnits.flatMapTo(mutableSetOf()) { it.rawSmsIds }
        val retryIds = projection.retryRawSmsIds.filterTo(mutableSetOf()) { affected == null || it in affected }
        val failedIds = retryIds + failedUnitIds
        val unwritten = written.failed.flatMap { it.rawSmsIds } + written.kept.flatMap { unit ->
            unit.projected.flatMap { it.rawSmsIds } - unit.stored.flatMap { it.rawSmsIds }.toSet()
        }
        val settled = projection.settledRawSmsIds.filterTo(mutableSetOf()) { inReport(it) } - failedIds - unwritten.toSet()
        val posted = written.applied.flatMap { unit ->
            unit.projected.filter { row -> unit.stored.none { it.rawSmsIds == row.rawSmsIds && it.transaction.id == row.transaction.id } }
        }
        val postedIds = posted.flatMap { it.rawSmsIds }.toSet()
        val linkedIds = projection.rows.flatMap { it.rawSmsIds }.toSet()
        val keptCandidates = written.kept.flatMap { unit ->
            unit.projected.flatMap { it.rawSmsIds } - unit.stored.flatMap { it.rawSmsIds }.toSet()
        }.filter(::inReport).map { rawSmsId ->
            val transfer = records[rawSmsId]?.event?.messageFamily?.isTransfer() == true
            if (transfer) ReconciliationReviewCandidate(rawSmsId, ReviewKind.PENDING_MATCH, listOf("transfer_pending_match"))
            else ReconciliationReviewCandidate(rawSmsId, ReviewKind.NEEDS_REVIEW, listOf("needs_review"))
        }
        // A lone leg whose write failed is a pending match again, as the old pass reported it.
        val failedLoneLegs = failedUnitIds.filter { it in projection.lonePostedRawSmsIds && inReport(it) }
            .map { ReconciliationReviewCandidate(it, ReviewKind.PENDING_MATCH, listOf("transfer_pending_match")) }
        val candidates = projection.reviewCandidates.filter { inReport(it.rawSmsId) }
            .map { ReconciliationReviewCandidate(it.rawSmsId, it.kind, it.reasons) } + keptCandidates + failedLoneLegs
        return ReconciliationReport(
            summary = ReconciliationSummary(
                assembledSingle = posted.count { it.rawSmsIds.size == 1 },
                matchedPairs = posted.count { it.rawSmsIds.size > 1 },
                pendingMatch = candidates.count { it.kind == ReviewKind.PENDING_MATCH },
                needsReview = candidates.count { it.kind == ReviewKind.NEEDS_REVIEW },
                ignored = settled.count { it !in linkedIds },
                alreadyLinked = settled.count { it in linkedIds && it !in postedIds },
                failed = failedUnits.size + (retryIds - failedUnitIds).size,
            ),
            reviewCandidates = candidates,
            settledRawSmsIds = settled,
            failedRawSmsIds = failedIds,
        )
    }

    /** One difference: the stored rows it replaces and the projected rows it writes. */
    private class Change(val stored: List<LedgerRow>, val projected: List<LedgerRow>) {
        private val rows = stored + projected
        val rawSmsIds: Set<String> = rows.flatMapTo(mutableSetOf()) { it.rawSmsIds }
        val ids: Set<String> = rows.mapTo(mutableSetOf()) { it.transaction.id }

        /** The [current] rows that share a raw SMS or an id with this unit, normalized. */
        fun now(current: List<LedgerRow>): Set<LedgerRow> =
            current.filter { row -> row.rawSmsIds.any(rawSmsIds::contains) || row.transaction.id in ids }.normalized()

        /** Stored rows whose id the projection does not keep. */
        fun removed(): List<LedgerRow> = stored.filter { row -> projected.none { it.transaction.id == row.transaction.id } }

        /** The projected row that takes over [row]: the one linking its first raw SMS that is still linked. */
        fun replacementOf(row: LedgerRow): String? =
            row.rawSmsIds.firstNotNullOfOrNull { id -> projected.firstOrNull { id in it.rawSmsIds }?.transaction?.id }

        /** Only the zone, time, container detail or id of rows on the same raw SMS sets with the same type and amount. */
        fun refinesOnly(): Boolean = stored.size == projected.size && stored.all { old ->
            projected.any { it.rawSmsIds == old.rawSmsIds && it.transaction.type == old.transaction.type && it.transaction.amount == old.transaction.amount }
        }
    }

    private class Written(
        val applied: MutableList<Change> = mutableListOf(),
        val failed: MutableList<Change> = mutableListOf(),
        /** Units left as stored on purpose (a blocked refinement, a commitment clash): not retried. */
        val kept: MutableList<Change> = mutableListOf(),
    ) {
        /** A save lost to another writer (only without a write transaction). */
        var conflicted = false
    }

    private class ConcurrentChange : Exception("ledger_changed_during_write")

    private companion object {
        const val ATTEMPTS = 2

        /** Rows compared as sets, with their parsed-event ids sorted. */
        fun List<LedgerRow>.normalized(): Set<LedgerRow> = mapTo(mutableSetOf()) { row ->
            row.copy(transaction = row.transaction.copy(linkedParsedEventIds = row.transaction.linkedParsedEventIds.sorted()))
        }
    }
}
