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
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.rules.InformationalMessagePolicy
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
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
     * The user's row for a posting decision ([UserDecision.posting] columns) from the effective evidence of its legs
     * ([UserDecision.legs], in that order), or null when the decision lacks its type or time or a leg has no positive
     * amount. A decision without a zone rebuilds the row with none, as the stored row of a backfilled decision has
     * it. A pair is one movement for both legs.
     */
    fun decidedRow(decision: UserDecision, legs: List<ParsedEvent>): LedgerRow? {
        val type = decision.transactionType ?: return null
        val occurredAt = decision.occurredAt ?: return null
        if (legs.isEmpty()) return null
        val ids = legs.map { it.rawSmsId }.sorted()
        val amount = legs.first().amount?.takeIf { it.amount.signum() > 0 } ?: return null
        val single = FinancialTransaction.fromSingleEvent(
            legs.first(), type, occurredAt, decision.sourceContainerId, decision.destinationContainerId, decision.occurredAtZone,
        )
        val transaction = if (legs.size == 1) single else single.copy(
            id = TransactionIdFactory.fromRawSmsIds(ids),
            amount = amount,
            merchant = null,
            counterparty = legs[0].counterparty ?: legs[1].counterparty,
            linkedParsedEventIds = legs.map { it.id }.sorted(),
        )
        return LedgerRow(transaction, ids)
    }

    /**
     * Where every transfer leg [mayPair] accepts for [evidence] lies: its receipt time, its event time, or its
     * SMS-local time falls inside the matching range (inclusive). A query on these ranges is a superset that
     * [scope] narrows; callers use it to load a scope without reading the whole history.
     */
    fun pairingWindow(evidence: LedgerEvidence): PairingWindow {
        val window = TransactionMatcher.TRANSFER_MATCH_WINDOW
        val (start, end) = effectiveSpan(evidence)
        val received = evidence.receivedAt
        val local = evidence.occurredAtLocal
        val spanStart = LocalDateTime.ofInstant(start.minus(window).minus(MAX_OFFSET), ZoneOffset.UTC)
        val spanEnd = LocalDateTime.ofInstant(end.plus(window).plus(MAX_OFFSET), ZoneOffset.UTC)
        return PairingWindow(
            receivedAt = minOf(received, start).minus(window)..maxOf(received, end).plus(window),
            occurredAt = start.minus(window)..end.plus(window),
            occurredAtLocal = (local?.minus(window)?.let { minOf(it, spanStart) } ?: spanStart)..
                (local?.plus(window)?.let { maxOf(it, spanEnd) } ?: spanEnd),
        )
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

/** A transfer leg the projector may pair. */
fun MessageFamily.isTransfer(): Boolean = this == MessageFamily.TRANSFER_IN || this == MessageFamily.TRANSFER_OUT

/** The loan of a financing installment, the unit the loan pre-pass and the scope group by. */
fun LedgerEvidence.loanReference(): LoanReference? =
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
        .filter { it.rawSmsIds.isNotEmpty() && it.transaction.appliedExchangeRate != null && it.transaction.exchangeRateSource != null }
        .associateBy { it.rawSmsIds }
    private val rows = mutableMapOf<String, LedgerRow>()
    private val links = mutableMapOf<String, String>()
    private val settled = mutableSetOf<String>()
    private val retry = mutableSetOf<String>()
    private val reviews = mutableListOf<LedgerReviewCandidate>()
    /**
     * Keep rule (i): stored rows any of whose raw SMS a human decision protects ([preserved]: an outcome as its
     * latest decision, or a correction closure), as the old pass never re-opened, paired, healed or released one. A
     * posting outcome keeps the user's row; a not-financial one keeps a row that still links it, as only the
     * decision's own writer deletes the row. They are saved before any decided posting, so another SMS's posting that
     * names one of their SMS conflicts and is retried.
     */
    private val keptRows = stored.rows.filter { row -> row.rawSmsIds.any(::preserved) }
        .onEach { row -> check(save(row.transaction, row.rawSmsIds)) { "kept stored rows never overlap" } }
    private val keptDecided = keptRows.flatMap { it.rawSmsIds }.toSet()

    /**
     * Keep rule (iii): an undecided stored row of several raw SMS (a pair) is settled as that pair before any leg is
     * assembled, so its legs never re-enter the matching pool, as the old pass left linked legs alone. It takes today's
     * detail when its legs alone re-derive exactly it with the same type and amount ([asItself]), else stays verbatim.
     */
    private val keptPairs = stored.rows.filter { row -> row.rawSmsIds.size == 2 && row.rawSmsIds.none(::preserved) }
        .onEach { row -> (asItself(row) ?: row).let { check(save(it.transaction, it.rawSmsIds)) { "kept stored rows never overlap" } } }

    /**
     * Keep rule (iv): an undecided stored row of more than two raw SMS stays as stored, its legs settled with it, except
     * the old stale-link release ([staleLegs]): those legs leave the row and are assembled again.
     */
    private val keptMerged = stored.rows.filter { row -> row.rawSmsIds.size > 2 && row.rawSmsIds.none(::preserved) }
        .onEach { row ->
            val released = staleLegs(row)
            val remaining = row.rawSmsIds - released
            if (remaining.isEmpty()) return@onEach
            val releasedEvents = released.mapNotNull { byRawSmsId[it]?.event?.id }.toSet()
            val transaction = row.transaction.copy(linkedParsedEventIds = row.transaction.linkedParsedEventIds - releasedEvents)
            check(save(transaction, remaining)) { "kept stored rows never overlap" }
        }
    private val keptPairIds = (keptPairs + keptMerged).flatMap { it.rawSmsIds }.toSet()

    // Decided rows settle first, so the pre-pass sees only the evidence left to automatic assembly.
    private val automatic = records.filter(::settleDecided)
    private val confirmedLoans = confirmLoans(automatic)
    private val ownership = registry.copy(loans = registry.loans + confirmedLoans.associateWith { OwnershipStatus.OWNED })

    /** The transfer legs left open by single assembly; [keepStoredRowsUnlessReplaced] re-runs the lone-leg step on it. */
    private val open = mutableListOf<TransferMatchCandidate>()

    fun run(): LedgerProjection {
        automatic.forEach { assemble(it, open) }
        postOpenTransfers(open)
        upgradePostedTransferPairs()
        keepStoredRowsUnlessReplaced()
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

    private fun decidedPosting(decided: EffectiveUserDecisions): LedgerRow? {
        val decision = decided.outcome ?: return null
        return LedgerProjector.decidedRow(decision, decision.legs().map { byRawSmsId[it]?.event ?: return null })
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

    /**
     * The old `shouldReleaseStaleSelfTransferLink`, leg by leg in event order: a transfer leg of a stored
     * self-transfer leaves it when its effective time, read in the row's zone, differs from the row's time (by more
     * than the match window while the row still links two parsed events, the earlier leg's time being the pair's).
     */
    private fun staleLegs(row: LedgerRow): List<String> {
        if (row.transaction.type != FinancialTransactionType.SELF_TRANSFER) return emptyList()
        var linkedEvents = row.transaction.linkedParsedEventIds.size
        val released = mutableListOf<String>()
        for (record in row.rawSmsIds.mapNotNull(byRawSmsId::get).sortedBy { it.event.id }) {
            if (!record.event.messageFamily.isTransfer()) continue
            val expected = TransactionTiming.effectiveOccurredAt(
                record.event, record.occurredAtLocal, record.receivedAt, zone, row.transaction.occurredAtZone,
            )
            val stale = if (linkedEvents != 2) row.transaction.occurredAt != expected
            else Duration.between(row.transaction.occurredAt, expected).abs() > TransactionMatcher.TRANSFER_MATCH_WINDOW
            if (stale) {
                released += record.event.rawSmsId
                if (record.event.id in row.transaction.linkedParsedEventIds) linkedEvents--
            }
        }
        return released
    }

    /** The single assembly of [record] and its matching candidate, read with [facts] and the stored zone. */
    private fun single(record: LedgerEvidence, facts: OwnershipFacts): Pair<TransactionAssembler.Outcome, TransferMatchCandidate> {
        val event = record.event
        val persistedZone = storedByRawSmsId[event.rawSmsId]?.transaction?.occurredAtZone
        val occurredAt = TransactionTiming.effectiveOccurredAt(
            event, record.occurredAtLocal, record.receivedAt, zone, persistedZone,
        )
        val sourceOwnership = facts.account(event.sourceAccountRef)
        val destinationOwnership = facts.account(event.destinationAccountRef)
        val outcome = TransactionAssembler.assembleSingle(
            event = event,
            receivedAt = record.receivedAt,
            sourceOwnership = sourceOwnership,
            destinationOwnership = destinationOwnership,
            cardOwnership = facts.card(event.cardRef),
            loanOwnership = record.loanReference()?.let(facts::loan) ?: OwnershipStatus.UNKNOWN,
            loanType = record.loanType,
            transactionOccurredAt = occurredAt,
            userConfirmed = userConfirmed(record),
            occurredAtZone = TransactionTiming.zoneFor(event.bank, persistedZone, zone),
        )
        return outcome to TransferMatchCandidate(
            event, record.transactionReference, record.occurredAtLocal, record.receivedAt,
            sourceOwnership, destinationOwnership, occurredAt,
        )
    }

    /**
     * The stored pair [row] as its legs alone re-derive it, when that is exactly its raw SMS set with the same type
     * and amount; else null. Account ownership is enough here: no loan takes part in a transfer pair.
     */
    private fun asItself(row: LedgerRow): LedgerRow? {
        val legs = row.rawSmsIds.map { byRawSmsId[it] ?: return null }
        val candidates = legs.map { leg ->
            val (outcome, candidate) = single(leg, registry)
            candidate.takeIf { outcome == TransactionAssembler.Outcome.PendingMatch && leg.event.messageFamily.isTransfer() } ?: return null
        }
        val pair = TransactionMatcher.findMutuallyUniquePairs(candidates).singleOrNull() ?: return null
        val outcome = TransactionAssembler.assembleMatchedPair(
            pair, pair.outgoing.sourceOwnership, pair.incoming.destinationOwnership,
            storedZone(pair.outgoing.event.rawSmsId) ?: storedZone(pair.incoming.event.rawSmsId) ?: zone,
        ) as? TransactionAssembler.Outcome.Assembled ?: return null
        val derived = LedgerRow(outcome.transaction, outcome.rawSmsIds.sorted())
        return derived.takeIf { it.rawSmsIds == row.rawSmsIds && !changesTypeOrAmount(row.transaction, it.transaction) }
    }

    private fun assemble(record: LedgerEvidence, open: MutableList<TransferMatchCandidate>) {
        val event = record.event
        val (outcome, candidate) = single(record, ownership)
        when (outcome) {
            is TransactionAssembler.Outcome.Assembled -> post(outcome.transaction, outcome.rawSmsIds)
            TransactionAssembler.Outcome.PendingMatch ->
                if (event.messageFamily.isTransfer() && linkedAlone(event.rawSmsId) != null) {
                    // A leg its own stored row links stays out of the matching pool, as the old pass left a linked leg
                    // alone; only the upgrade below may join a posted external or single-leg self-transfer leg.
                    linkedAlone(event.rawSmsId)?.let { check(save(it.transaction, it.rawSmsIds)) { "kept stored rows never overlap" } }
                } else if (event.messageFamily.isTransfer()) {
                    open += candidate
                } else {
                    pendingMatch(event.rawSmsId)
                }
            is TransactionAssembler.Outcome.NeedsReview -> reviews += LedgerReviewCandidate(
                event.rawSmsId, ReviewKind.NEEDS_REVIEW, outcome.reasons.ifEmpty { listOf("needs_review") },
            )
            TransactionAssembler.Outcome.Ignored -> settled += event.rawSmsId
        }
    }

    /** The stored row that links [rawSmsId] and nothing else, unless the informational cleanup removes it. */
    private fun linkedAlone(rawSmsId: String): LedgerRow? =
        storedByRawSmsId[rawSmsId]?.takeIf { row -> row.rawSmsIds.size == 1 && !removed(row) }

    /** Pairs mutually unique legs, then posts a lone owned leg on its own or leaves it pending. */
    private fun postOpenTransfers(open: List<TransferMatchCandidate>) {
        val paired = mutableSetOf<String>()
        for (pair in TransactionMatcher.findMutuallyUniquePairs(open)) {
            val outcome = TransactionAssembler.assembleMatchedPair(
                pair, pair.outgoing.sourceOwnership, pair.incoming.destinationOwnership,
                storedZone(pair.outgoing.event.rawSmsId) ?: storedZone(pair.incoming.event.rawSmsId) ?: zone,
            )
            if (outcome !is TransactionAssembler.Outcome.Assembled) continue
            post(outcome.transaction, outcome.rawSmsIds)
            paired += outcome.rawSmsIds
        }
        val counterparts = open + storedSingleLegCounterparts(open.mapTo(mutableSetOf()) { it.event.rawSmsId })
        for (candidate in open) {
            if (candidate.event.rawSmsId in paired) continue
            val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
                candidate, counterparts, storedZone(candidate.event.rawSmsId) ?: zone,
            )
            if (outcome is TransactionAssembler.Outcome.Assembled) {
                post(outcome.transaction, outcome.rawSmsIds)
            } else {
                pendingMatch(candidate.event.rawSmsId)
            }
        }
    }

    /**
     * A stored single-leg self-transfer whose leg is not in the open pool (kept for a decision, or kept by rule (ii))
     * still counts as a pending counterpart, so an intra-bank leg near it stays pending instead of being posted on its
     * own (the old pass's `postedSingleLegCounterparts`). It never pairs here.
     */
    private fun storedSingleLegCounterparts(open: Set<String>): List<TransferMatchCandidate> = stored.rows.mapNotNull { row ->
        if (!row.transaction.isSingleLegSelfTransfer() || row.rawSmsIds.singleOrNull() in open) return@mapNotNull null
        val record = row.rawSmsIds.singleOrNull()?.let(byRawSmsId::get) ?: return@mapNotNull null
        val event = record.event
        val decided = decisionsOf(event.rawSmsId)
        if (!event.messageFamily.isTransfer() || decided.isNonFinancial ||
            decided.outcome?.kind == UserDecisionKind.EXTERNAL_TRANSFER ||
            !TransactionAssembler.isAutomationEligible(event, userConfirmed(record))
        ) return@mapNotNull null
        TransferMatchCandidate(
            event, record.transactionReference, record.occurredAtLocal, record.receivedAt,
            ownership.account(event.sourceAccountRef), ownership.account(event.destinationAccountRef),
            TransactionTiming.effectiveOccurredAt(event, record.occurredAtLocal, record.receivedAt, zone, row.transaction.occurredAtZone),
        )
    }

    /**
     * The zone stored on the row that links [rawSmsId], as the old pass healed a posted leg in it: a bank without a
     * fixed zone keeps its stored zone when its leg is re-derived (`BankTransactionTimePolicy`).
     */
    private fun storedZone(rawSmsId: String): ZoneId? =
        BankTransactionTimePolicy.storedZoneOrNull(storedByRawSmsId[rawSmsId]?.transaction?.occurredAtZone)

    private class Leg(val posted: FinancialTransaction?, val record: LedgerEvidence)

    /**
     * The old `upgradeStaleExternalPairs` over this run's rows: a posted external transfer or single-leg
     * self-transfer joins a counterpart that is mutually unique among the posted and still-open legs.
     */
    private fun upgradePostedTransferPairs() {
        fun eligible(record: LedgerEvidence) =
            TransactionAssembler.isAutomationEligible(record.event, record.automationConfirmed) &&
                !preserved(record.event.rawSmsId) && record.event.rawSmsId !in keptDecided && record.event.rawSmsId !in keptPairIds
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
     * Keep rule (ii), the one place a stored row may be replaced. The projection replaces an undecided stored row of
     * one raw SMS only by (a) exactly one derived row on the same raw SMS set with the same type and amount (the old
     * fee→financing heal excepted), or (b) a derived self-transfer pair that joins it with its counterpart where the
     * old upgrade would have ([oldUpgradePairs]; a posted external transfer or single-leg self-transfer leg);
     * (c) [removed] drops a lone informational row. Any other stored row stays verbatim: the derived rows on its raw
     * SMS are dropped, and a raw SMS of theirs that no row links any more is assembled alone again (the lone-owned-leg
     * step), else pending. Protected rows (rule (i)), pairs (iii) and rows of three or more SMS (iv) are already settled. A link-less row stays unless a derived row took its id. Kept rows' raw SMS are not retried.
     */
    private fun keepStoredRowsUnlessReplaced() {
        val candidates = stored.rows.filter { row -> row.rawSmsIds.size <= 1 && row.rawSmsIds.none(::preserved) && !removed(row) }
        val dropped = mutableSetOf<String>()
        var changed = true
        while (changed) {
            changed = false
            for (row in candidates) {
                if (row.rawSmsIds.isEmpty()) {
                    if (row.transaction.id !in rows) save(row.transaction, row.rawSmsIds).also { changed = it }
                    continue
                }
                val derived = row.rawSmsIds.mapNotNull(links::get).toSet().map(rows::getValue)
                if (derived == listOf(row) || replaces(row, derived.singleOrNull())) continue
                derived.forEach { dropped += it.rawSmsIds; drop(it.transaction.id) }
                check(save(row.transaction, row.rawSmsIds)) { "kept stored rows never overlap" }
                retry -= row.rawSmsIds.toSet()
                changed = true
            }
        }
        // A leg whose derived row was dropped is assembled alone, as the old pass assembled it with its
        // counterpart linked elsewhere: the lone-owned-leg step against the legs still open.
        for (rawSmsId in (dropped - links.keys).sorted()) {
            settled -= rawSmsId
            val candidate = open.firstOrNull { it.event.rawSmsId == rawSmsId }
            val counterparts = open.filter { it.event.rawSmsId !in links } +
                storedSingleLegCounterparts(open.mapTo(mutableSetOf()) { it.event.rawSmsId })
            val outcome = candidate?.let {
                TransactionAssembler.assembleUnmatchedOwnedTransfer(it, counterparts, storedZone(rawSmsId) ?: zone)
            }
            if (outcome is TransactionAssembler.Outcome.Assembled) post(outcome.transaction, outcome.rawSmsIds) else pendingMatch(rawSmsId)
        }
    }

    /** Rule (a) or (b) of [keepStoredRowsUnlessReplaced]. */
    private fun replaces(row: LedgerRow, derived: LedgerRow?): Boolean {
        derived ?: return false
        if (derived.rawSmsIds == row.rawSmsIds) return !changesTypeOrAmount(row.transaction, derived.transaction)
        val joinable = row.transaction.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT ||
            row.transaction.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN || row.transaction.isSingleLegSelfTransfer()
        return joinable && derived.transaction.type == FinancialTransactionType.SELF_TRANSFER &&
            derived.rawSmsIds.size == 2 && derived.rawSmsIds.containsAll(row.rawSmsIds) && derived.rawSmsIds in oldUpgradePairs
    }

    /**
     * The pairs the old `upgradeStaleExternalPairs` could form: mutually unique, owned at both ends, among the legs it
     * looked at (a posted external or single-leg self-transfer leg, or an unlinked leg; eligible and not protected).
     * A leg stored in any other row, or in a kept pair, was not in its pool.
     */
    private val oldUpgradePairs: Set<List<String>> by lazy {
        val pool = records.filter { record ->
            val id = record.event.rawSmsId
            val storedRow = storedByRawSmsId[id]?.transaction
            record.event.messageFamily.isTransfer() && !preserved(id) && id !in keptPairIds &&
                TransactionAssembler.isAutomationEligible(record.event, record.automationConfirmed) &&
                (storedRow == null || storedRow.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT ||
                    storedRow.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN || storedRow.isSingleLegSelfTransfer())
        }.map { single(it, ownership).second }
        TransactionMatcher.findMutuallyUniquePairs(pool)
            .filter { it.outgoing.sourceOwnership == OwnershipStatus.OWNED && it.incoming.destinationOwnership == OwnershipStatus.OWNED }
            .map { listOf(it.outgoing.event.rawSmsId, it.incoming.event.rawSmsId).sorted() }.toSet()
    }

    /** The derivation changes the stored type or amount, other than the old pass's own fee→financing heal. */
    private fun changesTypeOrAmount(stored: FinancialTransaction, derived: FinancialTransaction): Boolean {
        val feeHeal = stored.type == FinancialTransactionType.FEE && derived.type == FinancialTransactionType.LOAN_REPAYMENT
        return (stored.type != derived.type && !feeHeal) || stored.amount != derived.amount
    }

    /**
     * A stored row the old pass removes: a lone informational SMS without a human decision
     * (`InformationalMessagePolicy`, the old stale-link cleanup).
     */
    private fun removed(row: LedgerRow): Boolean =
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
