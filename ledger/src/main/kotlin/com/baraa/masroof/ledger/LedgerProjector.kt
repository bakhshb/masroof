package com.baraa.masroof.ledger

import com.baraa.masroof.domain.assembly.BankTransactionTimePolicy
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.rules.InformationalMessagePolicy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Derives the ledger from evidence, decisions and ownership, with no I/O (docs/ARCHITECTURE.md, "Derived ledger").
 *
 * It applies the rules of the app's `TransactionReconciliationService` to an empty ledger, then the keep rule the
 * owner decided at gate 3.2: a stored row the user decided, or one the projection no longer derives, is carried
 * verbatim ("Known differences" lists what still differs). Evidence and stored rows are visited in id order, so the
 * input order never changes the result.
 */
object LedgerProjector {
    /**
     * [decisions] are keyed by raw SMS id (missing = none). [zone] is the fallback zone for a bank with neither a
     * fixed nor a stored zone.
     */
    fun project(
        events: List<LedgerEvidence>,
        decisions: Map<String, EffectiveUserDecisions>,
        registry: OwnershipFacts,
        storedFacts: StoredFacts,
        zone: ZoneId,
    ): LedgerProjection {
        require(events.distinctBy { it.event.rawSmsId }.size == events.size) { "one evidence per raw SMS" }
        require(storedFacts.rows.flatMap { it.rawSmsIds }.let { it.distinct().size == it.size }) { "one stored row per raw SMS" }
        // Stored rows in id order with sorted links, so neither the caller's order nor sorting changes the result.
        val stored = storedFacts.copy(rows = storedFacts.rows.map { it.copy(rawSmsIds = it.rawSmsIds.sorted()) }.sortedBy { it.transaction.id })
        return Projection(events.sortedBy { it.event.id }, decisions, registry, stored, zone).run()
    }

    /**
     * The raw SMS a projection of [affected] must include so its rows for [affected] equal the full projection's:
     * the legs of every decision naming one, every installment of the same loan, and every transfer leg reachable
     * through legs whose times may fall inside one [TransactionMatcher.TRANSFER_MATCH_WINDOW] of each other, and
     * every raw SMS of a stored row that links one.
     */
    fun scope(
        affected: Collection<String>,
        events: List<LedgerEvidence>,
        decisions: Map<String, EffectiveUserDecisions>,
        storedFacts: StoredFacts = StoredFacts(),
    ): Set<String> {
        val byRawSmsId = events.associateBy { it.event.rawSmsId }
        val transfers = events.filter { it.event.messageFamily.isTransfer() }
        val byLoan = events.groupBy { it.loanReference() }
        // Every SMS a decision names, by each SMS it names.
        val named = mutableMapOf<String, MutableSet<String>>()
        for ((subject, decided) in decisions) {
            val outcome = decided.outcome ?: continue
            val ids = setOfNotNull(subject, outcome.rawSmsId, outcome.pairedRawSmsId)
            ids.forEach { named.getOrPut(it, ::mutableSetOf) += ids }
        }
        for (row in storedFacts.rows) row.rawSmsIds.forEach { named.getOrPut(it, ::mutableSetOf) += row.rawSmsIds }
        val scope = mutableSetOf<String>()
        val queue = ArrayDeque(affected)
        while (queue.isNotEmpty()) {
            val rawSmsId = queue.removeFirst()
            if (!scope.add(rawSmsId)) continue
            val record = byRawSmsId[rawSmsId]
            val loan = record?.loanReference()
            val related = when {
                record == null -> emptyList()
                record.event.messageFamily.isTransfer() -> transfers.filter { mayPair(record, it) }
                loan != null -> byLoan.getValue(loan)
                else -> emptyList()
            }.map { it.event.rawSmsId } + named[rawSmsId].orEmpty()
            related.filterTo(queue) { it !in scope }
        }
        return scope
    }

    /**
     * A superset of the matcher's window test: receipt times, SMS-local times, or the instants the effective time
     * can take in any zone (an SMS-local time lies within 18 hours of its UTC reading).
     */
    private fun mayPair(a: LedgerEvidence, b: LedgerEvidence): Boolean {
        val window = TransactionMatcher.TRANSFER_MATCH_WINDOW
        if (Duration.between(a.receivedAt, b.receivedAt).abs() <= window) return true
        val localA = a.occurredAtLocal
        val localB = b.occurredAtLocal
        if (localA != null && localB != null && Duration.between(localA, localB).abs() <= window) return true
        val spanA = effectiveSpan(a)
        val spanB = effectiveSpan(b)
        return !spanB.first.isAfter(spanA.second.plus(window)) && !spanA.first.isAfter(spanB.second.plus(window))
    }

    private fun effectiveSpan(evidence: LedgerEvidence): Pair<Instant, Instant> {
        evidence.event.occurredAt?.let { return it to it }
        val local = evidence.occurredAtLocal?.toInstant(ZoneOffset.UTC) ?: return evidence.receivedAt to evidence.receivedAt
        return local.minus(MAX_OFFSET) to local.plus(MAX_OFFSET)
    }

    private val MAX_OFFSET: Duration = Duration.ofHours(18)
}

private fun MessageFamily.isTransfer(): Boolean = this == MessageFamily.TRANSFER_IN || this == MessageFamily.TRANSFER_OUT

private fun LedgerEvidence.loanReference(): LoanReference? =
    loanType?.takeIf { event.messageFamily == MessageFamily.FINANCING_INSTALLMENT }?.let { LoanReference(event.bank, it) }

/** One projection run. Its ledger is in memory and starts empty; the save rules are those of the Room ledger. */
private class Projection(
    private val records: List<LedgerEvidence>,
    private val decisions: Map<String, EffectiveUserDecisions>,
    private val registry: OwnershipFacts,
    private val stored: StoredFacts,
    private val zone: ZoneId,
) {
    private val byRawSmsId = records.associateBy { it.event.rawSmsId }
    private val byEventId = records.associateBy { it.event.id }
    private val storedByRawSmsId = stored.rows.flatMap { row -> row.rawSmsIds.map { it to row } }.toMap()
    private val storedRates = stored.rows
        .filter { it.transaction.appliedExchangeRate != null && it.transaction.exchangeRateSource != null }
        .associateBy { it.rawSmsIds }
    private val rows = mutableMapOf<String, LedgerRow>()
    private val links = mutableMapOf<String, String>()
    private val settled = mutableSetOf<String>()
    private val retry = mutableSetOf<String>()
    private val reviews = mutableListOf<LedgerReviewCandidate>()
    /**
     * Keep rule (i): stored rows any of whose raw SMS has a posting outcome as its latest decision. They are saved
     * before any decided posting, so another SMS's posting that names one of their SMS conflicts and is retried.
     */
    private val keptDecided = stored.rows.filter { row -> !removed(row) && row.rawSmsIds.any { decisionsOf(it).postsRow } }
        .onEach { row -> check(save(row.transaction, row.rawSmsIds)) { "kept stored rows never overlap" } }
        .flatMap { it.rawSmsIds }.toSet()
    // Decided rows settle first, so the pre-pass sees only the evidence left to automatic assembly.
    private val automatic = records.filter(::settleDecided)
    private val confirmedLoans = confirmLoans(automatic)
    private val ownership = registry.copy(loans = registry.loans + confirmedLoans.associateWith { OwnershipStatus.OWNED })

    fun run(): LedgerProjection {
        val open = mutableListOf<TransferMatchCandidate>()
        automatic.forEach { assemble(it, open) }
        postOpenTransfers(open)
        upgradePostedTransferPairs()
        keepStoredRowsWithoutDerivation()
        return LedgerProjection(
            rows = rows.values.map(::withStoredExchangeRate).sortedBy { it.transaction.id },
            reviewCandidates = reviews.filter { it.rawSmsId !in settled }.groupBy { it.rawSmsId }
                .map { (rawSmsId, group) ->
                    LedgerReviewCandidate(rawSmsId, group.first().kind, group.flatMap { it.reasons }.distinct().sorted())
                }.sortedBy { it.rawSmsId },
            settledRawSmsIds = settled,
            retryRawSmsIds = retry,
            confirmedLoans = confirmedLoans,
        )
    }

    private fun decisionsOf(rawSmsId: String) = decisions[rawSmsId] ?: EffectiveUserDecisions.NONE

    /** Any outcome decision, or a correction closure (`ReviewCorrectionClosure.protects`). */
    private fun preserved(rawSmsId: String) =
        decisionsOf(rawSmsId).outcome != null || rawSmsId in stored.correctionClosedRawSmsIds

    private fun userConfirmed(record: LedgerEvidence) =
        record.automationConfirmed || decisionsOf(record.event.rawSmsId).confirmsFinancial

    /** Settles what the user decided and returns true for evidence left to automatic assembly. */
    private fun settleDecided(record: LedgerEvidence): Boolean {
        val rawSmsId = record.event.rawSmsId
        val decided = decisionsOf(rawSmsId)
        val posting = decidedPosting(decided)
        when {
            decided.isNonFinancial -> settled += rawSmsId
            // A kept stored row (rule i), or a decided pair, links this SMS; it stays with the row.
            rawSmsId in links -> settled += rawSmsId
            posting != null -> if (!save(posting.transaction, posting.rawSmsIds)) retry += rawSmsId
            decided.outcome?.kind == UserDecisionKind.EXTERNAL_TRANSFER -> settled += rawSmsId
            else -> return true
        }
        return false
    }

    /** The user's row for a posting decision, or null when the decision or its evidence is incomplete. */
    private fun decidedPosting(decided: EffectiveUserDecisions): LedgerRow? {
        val decision = decided.outcome ?: return null
        val type = decision.transactionType ?: return null
        val occurredAt = decision.occurredAt ?: return null
        val zone = decision.occurredAtZone ?: return null
        val legs = listOfNotNull(decision.rawSmsId, decision.pairedRawSmsId).map { byRawSmsId[it] ?: return null }
        val ids = legs.map { it.event.rawSmsId }.sorted()
        val amount = legs.first().event.amount?.takeIf { it.amount.signum() > 0 } ?: return null
        val single = FinancialTransaction.fromSingleEvent(
            legs.first().event, type, occurredAt, decision.sourceContainerId, decision.destinationContainerId, zone,
        )
        val transaction = if (legs.size == 1) single else single.copy(
            id = TransactionIdFactory.fromRawSmsIds(ids),
            amount = amount,
            merchant = null,
            counterparty = legs[0].event.counterparty ?: legs[1].event.counterparty,
            linkedParsedEventIds = legs.map { it.event.id }.sorted(),
        )
        return LedgerRow(transaction, ids)
    }

    /**
     * The pre-pass that replaces the old in-loop `maybeAutoConfirmLoanOwnership`: an eligible installment paid from
     * an owned account confirms a loan the registry does not know.
     */
    private fun confirmLoans(automatic: List<LedgerEvidence>): Set<LoanReference> =
        automatic.mapNotNull { record ->
            record.loanReference()?.takeIf {
                TransactionAssembler.isAutomationEligible(record.event, userConfirmed(record)) &&
                    registry.account(record.event.sourceAccountRef) == OwnershipStatus.OWNED &&
                    registry.loan(it) == OwnershipStatus.UNKNOWN
            }
        }.toSet()

    private fun assemble(record: LedgerEvidence, open: MutableList<TransferMatchCandidate>) {
        val event = record.event
        val persistedZone = storedByRawSmsId[event.rawSmsId]?.transaction?.occurredAtZone
        val occurredAt = TransactionTiming.effectiveOccurredAt(
            event, record.occurredAtLocal, record.receivedAt, zone, persistedZone,
        )
        val sourceOwnership = ownership.account(event.sourceAccountRef)
        val destinationOwnership = ownership.account(event.destinationAccountRef)
        val outcome = TransactionAssembler.assembleSingle(
            event = event,
            receivedAt = record.receivedAt,
            sourceOwnership = sourceOwnership,
            destinationOwnership = destinationOwnership,
            cardOwnership = ownership.card(event.cardRef),
            loanOwnership = record.loanReference()?.let(ownership::loan) ?: OwnershipStatus.UNKNOWN,
            loanType = record.loanType,
            transactionOccurredAt = occurredAt,
            userConfirmed = userConfirmed(record),
            occurredAtZone = TransactionTiming.zoneFor(event.bank, persistedZone, zone),
        )
        when (outcome) {
            is TransactionAssembler.Outcome.Assembled -> post(outcome.transaction, outcome.rawSmsIds)
            TransactionAssembler.Outcome.PendingMatch ->
                if (event.messageFamily.isTransfer()) {
                    open += TransferMatchCandidate(
                        event, record.transactionReference, record.occurredAtLocal, record.receivedAt,
                        sourceOwnership, destinationOwnership, occurredAt,
                    )
                } else {
                    pendingMatch(event.rawSmsId)
                }
            is TransactionAssembler.Outcome.NeedsReview -> reviews += LedgerReviewCandidate(
                event.rawSmsId, ReviewKind.NEEDS_REVIEW, outcome.reasons.ifEmpty { listOf("needs_review") },
            )
            TransactionAssembler.Outcome.Ignored -> settled += event.rawSmsId
        }
    }

    /** Pairs mutually unique legs, then posts a lone owned leg on its own or leaves it pending. */
    private fun postOpenTransfers(open: List<TransferMatchCandidate>) {
        val paired = mutableSetOf<String>()
        for (pair in TransactionMatcher.findMutuallyUniquePairs(open)) {
            val outcome = TransactionAssembler.assembleMatchedPair(
                pair, pair.outgoing.sourceOwnership, pair.incoming.destinationOwnership, zone,
            )
            if (outcome !is TransactionAssembler.Outcome.Assembled) continue
            post(outcome.transaction, outcome.rawSmsIds)
            paired += outcome.rawSmsIds
        }
        for (candidate in open) {
            if (candidate.event.rawSmsId in paired) continue
            val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(candidate, open, zone)
            if (outcome is TransactionAssembler.Outcome.Assembled) {
                post(outcome.transaction, outcome.rawSmsIds)
            } else {
                pendingMatch(candidate.event.rawSmsId)
            }
        }
    }

    private class Leg(val posted: FinancialTransaction?, val record: LedgerEvidence)

    /**
     * The old `upgradeStaleExternalPairs` over this run's rows: a posted external transfer or single-leg
     * self-transfer joins a counterpart that is mutually unique among the posted and still-open legs.
     */
    private fun upgradePostedTransferPairs() {
        fun eligible(record: LedgerEvidence) =
            TransactionAssembler.isAutomationEligible(record.event, record.automationConfirmed) &&
                !preserved(record.event.rawSmsId) && record.event.rawSmsId !in keptDecided
        val legs = buildList {
            for (row in rows.values) {
                val posted = row.transaction
                val families = when {
                    posted.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT -> listOf(MessageFamily.TRANSFER_OUT)
                    posted.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN -> listOf(MessageFamily.TRANSFER_IN)
                    posted.isSingleLegSelfTransfer() -> listOf(MessageFamily.TRANSFER_OUT, MessageFamily.TRANSFER_IN)
                    else -> emptyList()
                }
                for (family in families) {
                    val record = posted.linkedParsedEventIds.mapNotNull(byEventId::get)
                        .firstOrNull { it.event.messageFamily == family } ?: continue
                    if (eligible(record)) add(Leg(posted, record))
                }
            }
            records.filter { it.event.messageFamily.isTransfer() && eligible(it) && it.event.rawSmsId !in links }
                .forEach { add(Leg(null, it)) }
        }
        val byEvent = legs.associateBy { it.record.event.id }
        val candidates = legs.map { leg ->
            val event = leg.record.event
            TransferMatchCandidate(
                event, leg.record.transactionReference, leg.record.occurredAtLocal, leg.record.receivedAt,
                ownership.account(event.sourceAccountRef), ownership.account(event.destinationAccountRef),
                TransactionTiming.effectiveOccurredAt(
                    event, leg.record.occurredAtLocal, leg.record.receivedAt, zone, leg.posted?.occurredAtZone,
                ),
            )
        }
        for (pair in TransactionMatcher.findMutuallyUniquePairs(candidates)) {
            val outLeg = byEvent.getValue(pair.outgoing.event.id)
            val inLeg = byEvent.getValue(pair.incoming.event.id)
            val postedLegs = listOfNotNull(outLeg.posted, inLeg.posted)
            val hasStaleExternal = outLeg.posted?.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT ||
                inLeg.posted?.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN
            if (!hasStaleExternal && postedLegs.none { it.isSingleLegSelfTransfer() }) continue
            val healZone = postedLegs.firstNotNullOfOrNull { BankTransactionTimePolicy.storedZoneOrNull(it.occurredAtZone) }
                ?: zone
            val rawSmsIds = listOf(pair.outgoing.event.rawSmsId, pair.incoming.event.rawSmsId).sorted()
            val outcome = TransactionAssembler.assembleMatchedPair(
                pair, pair.outgoing.sourceOwnership, pair.incoming.destinationOwnership, healZone,
            )
            if (outcome is TransactionAssembler.Outcome.Assembled) {
                post(outcome.transaction, outcome.rawSmsIds, replacing = rawSmsIds)
            } else {
                retry += rawSmsIds
            }
        }
    }

    /**
     * Keep rule (ii), for undecided stored rows: a stored row stays verbatim when it links no raw SMS, when the
     * projection derived no row for its raw SMS or only rows inside its set that do not cover it (a stored pair one of
     * whose legs no longer derives), or when it derived one row on exactly its set with another type or amount (a
     * type edited only on the row), except the old fee→financing heal. A derived row that reaches outside the set, or
     * one that covers it with the stored type and amount, wins. Its raw SMS are settled and not retried.
     */
    private fun keepStoredRowsWithoutDerivation() {
        val kept = stored.rows.mapNotNull { row ->
            if (removed(row) || row.rawSmsIds.any { decisionsOf(it).outcome != null }) return@mapNotNull null
            val derivedIds = row.rawSmsIds.mapNotNull(links::get).toSet()
            val derived = derivedIds.map(rows::getValue)
            val keep = when {
                row.rawSmsIds.isEmpty() -> row.transaction.id !in rows
                derived.any { !row.rawSmsIds.containsAll(it.rawSmsIds) } -> false
                row.rawSmsIds.any { it !in links } -> true
                else -> derived.singleOrNull()?.transaction?.let { changesTypeOrAmount(row.transaction, it) } == true
            }
            (row to derivedIds).takeIf { keep }
        }
        for ((row, derivedIds) in kept) {
            derivedIds.forEach(::drop)
            check(save(row.transaction, row.rawSmsIds)) { "kept stored rows never overlap" }
            retry -= row.rawSmsIds.toSet()
        }
    }

    /** The derivation changes the stored type or amount, other than the old pass's own fee→financing heal. */
    private fun changesTypeOrAmount(stored: FinancialTransaction, derived: FinancialTransaction): Boolean {
        val feeHeal = stored.type == FinancialTransactionType.FEE && derived.type == FinancialTransactionType.LOAN_REPAYMENT
        return (stored.type != derived.type && !feeHeal) || stored.amount != derived.amount
    }

    /**
     * A stored row the old pass removes: a raw SMS decided not financial, or a lone informational SMS without a
     * human decision (`InformationalMessagePolicy`, the old stale-link cleanup).
     */
    private fun removed(row: LedgerRow): Boolean =
        row.rawSmsIds.any { decisionsOf(it).isNonFinancial } ||
            row.rawSmsIds.singleOrNull()?.let { id ->
                !preserved(id) && byRawSmsId[id]?.event?.let(InformationalMessagePolicy::shouldAutoIgnore) == true
            } == true

    private fun drop(transactionId: String) {
        rows.remove(transactionId)?.rawSmsIds?.forEach(links::remove)
    }

    private fun FinancialTransaction.isSingleLegSelfTransfer() =
        type == FinancialTransactionType.SELF_TRANSFER && linkedParsedEventIds.size == 1

    private fun pendingMatch(rawSmsId: String) {
        reviews += LedgerReviewCandidate(rawSmsId, ReviewKind.PENDING_MATCH, listOf("transfer_pending_match"))
    }

    /** Posts an automatic row; a conflict leaves all its raw SMS to retry. */
    private fun post(transaction: FinancialTransaction, rawSmsIds: List<String>, replacing: List<String> = emptyList()) {
        if (!save(transaction, rawSmsIds, replacing)) retry += rawSmsIds
    }

    /**
     * Saves like `FinancialTransactionDao.saveAtomic` (a link to another row conflicts) after dropping the rows of
     * [replacing] that link only that SMS (`replaceExclusiveStaleLinksAtomic`), and settles the linked raw SMS.
     */
    private fun save(transaction: FinancialTransaction, rawSmsIds: List<String>, replacing: List<String> = emptyList()): Boolean {
        val stale = replacing.mapNotNull(links::get).filter { it != transaction.id }.toSet()
        val conflict = stale.any { rows.getValue(it).rawSmsIds.size != 1 } ||
            rawSmsIds.any { links[it].let { id -> id != null && id != transaction.id && id !in stale } }
        if (conflict) return false
        stale.forEach(::drop)
        rows[transaction.id] = LedgerRow(transaction, rawSmsIds.sorted())
        rawSmsIds.forEach { links[it] = transaction.id }
        settled += rawSmsIds
        return true
    }

    /** A complete stored exchange-rate pair stays on a row that links the same raw SMS. */
    private fun withStoredExchangeRate(row: LedgerRow): LedgerRow {
        val match = storedRates[row.rawSmsIds]?.transaction ?: return row
        return row.copy(
            transaction = row.transaction.copy(
                appliedExchangeRate = match.appliedExchangeRate,
                exchangeRateSource = match.exchangeRateSource,
            ),
        )
    }
}
