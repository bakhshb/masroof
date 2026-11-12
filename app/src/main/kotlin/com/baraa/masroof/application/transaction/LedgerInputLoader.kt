package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ReviewCorrectionClosure
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.ledger.LedgerEvidence
import com.baraa.masroof.ledger.LedgerProjection
import com.baraa.masroof.ledger.LedgerProjector
import com.baraa.masroof.ledger.LedgerRow
import com.baraa.masroof.ledger.OwnershipFacts
import com.baraa.masroof.ledger.StoredFacts
import com.baraa.masroof.ledger.isTransfer
import com.baraa.masroof.ledger.loanReference
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.ZoneId

/**
 * The projector's inputs read from the database, with the stored ledger and reviews they are compared with
 * (docs/ARCHITECTURE.md, "Derived ledger").
 */
data class LedgerInputs(
    val events: List<LedgerEvidence>,
    val decisions: Map<String, EffectiveUserDecisions>,
    val registry: OwnershipFacts,
    val storedFacts: StoredFacts,
    val zone: ZoneId,
    val reviews: List<ReviewItem>,
    /** The effective parse behind each of [events], by raw SMS id: the snapshot the write guard compares. */
    val records: Map<String, ParsedEventRecord> = emptyMap(),
) {
    fun project(): LedgerProjection = LedgerProjector.project(events, decisions, registry, storedFacts, zone)
}

/**
 * Loads [LedgerInputs] for the whole history. Read-only. Evidence whose raw SMS is missing is left out (Rules of the
 * derived ledger, "Missing raw SMS"); ownership is resolved through [OwnershipResolver], so the registry's suffix
 * fallback applies as in reconciliation.
 */
class LedgerInputLoader(
    private val rawSmsRepository: RawSmsRepository,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val reviewRepository: ReviewRepository,
    private val userDecisionRepository: UserDecisionRepository,
    private val effectiveParsedEventProvider: EffectiveParsedEventProvider,
    private val ownershipResolver: OwnershipResolver,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun load(): LedgerInputs {
        val records = effectiveParsedEventProvider.listAllEffective()
        val receivedAt = rawSmsRepository.receivedAtByIds(records.map { it.event.rawSmsId })
        val events = evidence(records, receivedAt)
        val rawSmsIds = events.map { it.event.rawSmsId }
        val reviews = reviewRepository.listAll()
        val rows = financialTransactionRepository.ledgerRows(financialTransactionRepository.listAll()).sortedBy { it.transaction.id }
        return LedgerInputs(
            events = events,
            decisions = userDecisionRepository.effectiveFor(rawSmsIds),
            registry = ownershipFacts(events),
            storedFacts = StoredFacts(
                // The stored `financial_transaction` rows with their linked raw SMS, sorted by id.
                rows = rows,
                correctionClosedRawSmsIds = reviews.filter(ReviewCorrectionClosure::isClosed).map { it.rawSmsId }.toSet(),
            ),
            zone = zoneId,
            reviews = reviews,
            records = records.filter { it.event.rawSmsId in receivedAt }.associateBy { it.event.rawSmsId },
        )
    }

    /**
     * Loads only [LedgerProjector.scope] around [affected], without reading the whole history: it grows the scope
     * from the raw SMS, decisions and stored rows it names, the installments of the same loan and the transfer
     * candidates of [LedgerProjector.pairingWindow], until nothing new is reachable. The result projects the same
     * rows for that scope as a full [load] (scoped = full).
     */
    suspend fun loadScope(affected: Collection<String>): LedgerInputs {
        // shortcut: a large batch (a historical scan) reads the whole history once rather than one query per transfer.
        if (affected.size > FULL_READ_AFFECTED) return restrictedTo(load(), affected)
        val records = mutableMapOf<String, ParsedEventRecord>()
        val receivedAt = mutableMapOf<String, java.time.Instant>()
        val decisions = mutableMapOf<String, EffectiveUserDecisions>()
        val rows = mutableMapOf<String, LedgerRow>()
        val loaded = mutableSetOf<String>()
        val searched = mutableSetOf<String>()
        var pending = affected.toSet()
        var scope: Set<String>
        var rounds = 0
        do {
            // shortcut: a long chain of transfers whose times touch reads the whole history instead, in a fixed
            // number of queries; a per-round batch query would keep it bounded if that ever becomes slow.
            if (++rounds > MAX_ROUNDS) return restrictedTo(load(), affected)
            val withoutRecord = pending - records.keys
            if (withoutRecord.isNotEmpty()) {
                effectiveParsedEventProvider.listEffectiveByRawSmsIds(withoutRecord).forEach { records[it.event.rawSmsId] = it }
            }
            receivedAt += rawSmsRepository.receivedAtByIds(pending)
            decisions += userDecisionRepository.effectiveFor(pending)
            val found = financialTransactionRepository.findByRawSmsIds(pending).values.filter { it.id !in rows }
            financialTransactionRepository.ledgerRows(found).forEach { rows[it.transaction.id] = it }
            loaded += pending
            val events = evidence(records.values, receivedAt)
            scope = LedgerProjector.scope(affected, events, decisions, StoredFacts(rows.values.toList()))
            val next = scope.toMutableSet()
            for (event in events.filter { it.event.rawSmsId in scope && it.event.rawSmsId !in searched }) {
                next += neighbours(event, records)
                searched += event.event.rawSmsId
            }
            pending = next - loaded
        } while (pending.isNotEmpty())
        val scopedRecords = records.values.filter { it.event.rawSmsId in scope }
        val events = evidence(scopedRecords, receivedAt)
        val reviews = reviewRepository.findByRawSmsIds(scope).values.toList()
        return LedgerInputs(
            events = events,
            decisions = decisions.filterKeys { it in scope },
            registry = ownershipFacts(events),
            storedFacts = StoredFacts(
                rows = rows.values.filter { row -> row.rawSmsIds.any { it in scope } }.sortedBy { it.transaction.id },
                correctionClosedRawSmsIds = reviews.filter(ReviewCorrectionClosure::isClosed).map { it.rawSmsId }.toSet(),
            ),
            zone = zoneId,
            reviews = reviews,
            records = scopedRecords.filter { it.event.rawSmsId in receivedAt }.associateBy { it.event.rawSmsId },
        )
    }

    private fun restrictedTo(full: LedgerInputs, affected: Collection<String>): LedgerInputs {
        val scope = LedgerProjector.scope(affected, full.events, full.decisions, full.storedFacts)
        return full.copy(
            events = full.events.filter { it.event.rawSmsId in scope },
            decisions = full.decisions.filterKeys { it in scope },
            storedFacts = full.storedFacts.copy(rows = full.storedFacts.rows.filter { row -> row.rawSmsIds.any { it in scope } }),
            reviews = full.reviews.filter { it.rawSmsId in scope },
            records = full.records.filterKeys { it in scope },
        )
    }

    /** The raw SMS [LedgerProjector.scope] may reach from [evidence]: a superset, loaded into [records]. */
    private suspend fun neighbours(evidence: LedgerEvidence, records: MutableMap<String, ParsedEventRecord>): Set<String> {
        val event = evidence.event
        if (event.messageFamily.isTransfer()) {
            val window = LedgerProjector.pairingWindow(evidence)
            return effectiveParsedEventProvider.listTransferCandidatesEffective(window.receivedAt, window.occurredAt, window.occurredAtLocal)
                .onEach { records.putIfAbsent(it.event.rawSmsId, it) }
                .mapTo(mutableSetOf()) { it.event.rawSmsId }
        }
        val loan = evidence.loanReference() ?: return emptySet()
        return effectiveParsedEventProvider.listRawSmsIdsReferencingLoan(loan).toSet()
    }

    private fun evidence(records: Collection<ParsedEventRecord>, receivedAt: Map<String, java.time.Instant>): List<LedgerEvidence> =
        records.mapNotNull { record ->
            LedgerEvidence(
                event = record.event,
                receivedAt = receivedAt[record.event.rawSmsId] ?: return@mapNotNull null,
                transactionReference = record.details.transactionReference,
                occurredAtLocal = record.details.occurredAtLocal,
                loanType = record.details.loanType,
                automationConfirmed = record.automationConfirmed,
            )
        }

    private suspend fun ownershipFacts(events: List<LedgerEvidence>): OwnershipFacts {
        val accounts = events.flatMap { listOfNotNull(it.event.sourceAccountRef, it.event.destinationAccountRef) }.toSet()
        val cards = events.mapNotNull { it.event.cardRef }.toSet()
        val loans = events.mapNotNull { evidence -> evidence.loanType?.let { LoanReference(evidence.event.bank, it) } }.toSet()
        return OwnershipFacts(
            accounts = accounts.associateWith { ownershipResolver.resolveAccount(it) },
            cards = cards.associateWith { ownershipResolver.resolveCard(it) },
            loans = loans.associateWith { ownershipResolver.resolveLoan(it) },
        )
    }

    private companion object {
        /** Scope rounds before a full read: each round follows one more hop of decisions, rows, loans or transfers. */
        const val MAX_ROUNDS = 12

        /** Affected raw SMS above which one full read is cheaper than growing the scope query by query. */
        const val FULL_READ_AFFECTED = 200
    }
}

/** [transactions] (distinct by id) with the raw SMS each links, sorted: the stored rows the projector and writer compare. */
internal suspend fun FinancialTransactionRepository.ledgerRows(transactions: Collection<FinancialTransaction>): List<LedgerRow> {
    val distinct = transactions.distinctBy { it.id }
    val links = listRawSmsIdsByTransaction(distinct.map { it.id })
    return distinct.map { LedgerRow(it, links[it.id].orEmpty().sorted()) }
}
