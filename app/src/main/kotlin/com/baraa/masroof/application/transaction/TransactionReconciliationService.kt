package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.rules.InformationalMessagePolicy
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import java.time.Instant
import java.time.ZoneId

/**
 * Operational summary of a reconciliation pass.
 */
data class ReconciliationSummary(
    val assembledSingle: Int = 0,
    val matchedPairs: Int = 0,
    val pendingMatch: Int = 0,
    val needsReview: Int = 0,
    val ignored: Int = 0,
    val alreadyLinked: Int = 0,
    val failed: Int = 0,
)

/**
 * Orchestrates ownership-aware matching/assembly and FinancialTransaction persistence.
 *
 * When [effectiveParsedEventProvider] is present, reconciliation uses corrected
 * projections without mutating stored ParsedEvent rows.
 *
 * Only automation-eligible evidence ([TransactionAssembler.isAutomationEligible])
 * may create, pair, or heal transactions; non-SUCCESS parses become review
 * candidates. Existing transaction links are preserved.
 */
class TransactionReconciliationService(
    private val parsedEventRepository: ParsedEventRepository,
    private val rawSmsRepository: RawSmsRepository,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val ownershipResolver: OwnershipResolver,
    private val ownershipConfirmationService: OwnershipConfirmationService? = null,
    private val effectiveParsedEventProvider: EffectiveParsedEventProvider? = null,
    private val reviewRepository: ReviewRepository? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun reconcileStoredEvents(): ReconciliationSummary =
        reconcileStoredEventsDetailed().summary

    /**
     * Full-history reconciliation for explicit maintenance and recovery.
     * Interactive and incremental callers should use [reconcileAffectedRawSmsIds].
     */
    suspend fun reconcileStoredEventsDetailed(): ReconciliationReport {
        val records = loadRecords()
        return reconcileRecordsDetailed(records)
    }

    /**
     * Single pass for a batch of newly stored events (historical import): every stored event,
     * visited in RawSms arrival order like per-message processing, so transactions created by
     * the batch get the same rawSms-derived ids a message-by-message import would assign.
     */
    suspend fun reconcileBatchDetailed(): ReconciliationReport {
        val arrival = rawSmsRepository.listIdsByReceivedAt()
            .withIndex()
            .associate { (index, rawSmsId) -> rawSmsId to index }
        val records = loadRecords().sortedBy { arrival[it.event.rawSmsId] ?: Int.MAX_VALUE }
        return reconcileRecordsDetailed(records)
    }

    /**
     * Reconcile after a newly saved ParsedEvent. Failures are swallowed by callers
     * that treat P8 as derived processing.
     */
    suspend fun reconcileAfterParsedEvent(event: ParsedEvent): ReconciliationSummary =
        reconcileAfterParsedEventDetailed(event).summary

    suspend fun reconcileAfterParsedEventDetailed(event: ParsedEvent): ReconciliationReport {
        val records = loadRecordsAround(event)
        return reconcileRecordsDetailed(records)
    }

    /**
     * Reconcile [rawSmsIds] without a full-history scan.
     *
     * Non-transfer evidence is loaded by those ids only. Transfer evidence also
     * loads unlinked transfers and posted external or single-leg self-transfers
     * inside two [TransactionMatcher.TRANSFER_MATCH_WINDOW]s of each affected
     * receipt and effective time, plus the parse rows linked to those posted
     * legs. [TransactionMatcher] still decides which of those candidates pair.
     * Rows outside the affected set are candidates only: they are not posted
     * on their own.
     */
    suspend fun reconcileAffectedRawSmsIds(rawSmsIds: Collection<String>): ReconciliationReport {
        val ids = rawSmsIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) {
            return ReconciliationReport(
                summary = ReconciliationSummary(),
                reviewCandidates = emptyList(),
                settledRawSmsIds = emptySet(),
            )
        }
        val affected = loadByRawSmsIds(ids)
        val transfers = affected.filter { it.event.messageFamily.isTransferFamily() }
        val windows = transferCandidateWindows(transfers)
        val records = if (windows.isEmpty()) {
            affected
        } else {
            loadTransferScopedRecords(affected, windows)
        }
        return reconcileRecordsDetailed(
            records = records,
            scope = AffectedScope(rawSmsIds = ids.toSet(), transferWindows = windows),
        )
    }

    private suspend fun loadRecords(): List<ParsedEventRecord> =
        effectiveParsedEventProvider?.listAllEffective()
            ?: parsedEventRepository.listAll()

    private suspend fun loadRecordsAround(event: ParsedEvent): List<ParsedEventRecord> {
        val receivedAt = rawSmsRepository.getById(event.rawSmsId)?.receivedAt ?: return loadRecords()
        val window = TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(2)
        val startInclusive = receivedAt.minus(window)
        val endExclusive = receivedAt.plus(window).plusMillis(1)
        val windowed = effectiveParsedEventProvider?.listEffectiveReceivedBetween(startInclusive, endExclusive)
            ?: parsedEventRepository.listReceivedBetween(startInclusive, endExclusive)
        return supplementIncrementalReconcileRecords(windowed)
    }

    /**
     * Keeps incremental reconciliation bounded to a received-at window while still
     * loading transfer legs that pairing and stale external-upgrade passes require.
     */
    private suspend fun supplementIncrementalReconcileRecords(
        windowed: List<ParsedEventRecord>,
    ): List<ParsedEventRecord> {
        val unlinkedTransfers = effectiveParsedEventProvider?.listUnlinkedTransfersEffective()
            ?: parsedEventRepository.listUnlinkedTransfers()
        val candidateTransactions = financialTransactionRepository.listByTypes(
            listOf(
                FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                FinancialTransactionType.SELF_TRANSFER,
            ),
        ).filter { transaction ->
            transaction.type != FinancialTransactionType.SELF_TRANSFER ||
                transaction.linkedParsedEventIds.size == 1
        }
        if (unlinkedTransfers.isEmpty() && candidateTransactions.isEmpty()) {
            return windowed
        }

        val merged = windowed.associateBy { it.event.id }.toMutableMap()
        for (record in unlinkedTransfers) {
            merged.putIfAbsent(record.event.id, record)
        }
        for (transaction in candidateTransactions) {
            for (parsedEventId in transaction.linkedParsedEventIds) {
                val record = effectiveParsedEventProvider?.getEffectiveById(parsedEventId)
                    ?: parsedEventRepository.getById(parsedEventId)
                    ?: continue
                if (!record.event.messageFamily.isTransferFamily()) continue
                merged.putIfAbsent(parsedEventId, record)
            }
        }
        return merged.values.toList()
    }

    private fun MessageFamily.isTransferFamily(): Boolean =
        this == MessageFamily.TRANSFER_IN || this == MessageFamily.TRANSFER_OUT

    private suspend fun loadByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> =
        effectiveParsedEventProvider?.listEffectiveByRawSmsIds(rawSmsIds)
            ?: parsedEventRepository.listByRawSmsIds(rawSmsIds)

    private suspend fun loadUnlinkedTransfersReceivedBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<ParsedEventRecord> =
        effectiveParsedEventProvider?.listUnlinkedTransfersEffectiveReceivedBetween(
            startInclusive,
            endExclusive,
        ) ?: parsedEventRepository.listUnlinkedTransfersReceivedBetween(startInclusive, endExclusive)

    /**
     * Two matcher windows cover a competing leg that can pair with an in-window
     * counterpart. Receipt time and effective time are both anchors because the
     * matcher compares whichever clock both legs actually carry.
     */
    private suspend fun transferCandidateWindows(transfers: List<ParsedEventRecord>): List<TimeRange> {
        if (transfers.isEmpty()) return emptyList()
        val anchors = mutableListOf<Instant>()
        for (record in transfers) {
            val receivedAt = rawSmsRepository.getById(record.event.rawSmsId)?.receivedAt ?: continue
            val persistedZone = financialTransactionRepository.findByRawSmsId(record.event.rawSmsId)
                ?.occurredAtZone
            anchors += receivedAt
            anchors += TransactionTiming.effectiveOccurredAt(
                event = record.event,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = receivedAt,
                zoneId = zoneId,
                persistedZoneId = persistedZone,
            )
        }
        return mergedWindows(anchors)
    }

    private fun mergedWindows(anchors: List<Instant>): List<TimeRange> {
        if (anchors.isEmpty()) return emptyList()
        val span = TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(2)
        val sorted = anchors.map { anchor ->
            TimeRange(
                startInclusive = anchor.minus(span),
                endExclusive = anchor.plus(span).plusMillis(1),
            )
        }.sortedBy { it.startInclusive }
        val merged = mutableListOf<TimeRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last == null || range.startInclusive.isAfter(last.endExclusive)) {
                merged += range
            } else {
                val endExclusive = maxOf(last.endExclusive, range.endExclusive)
                if (endExclusive != last.endExclusive) {
                    merged[merged.lastIndex] = last.copy(endExclusive = endExclusive)
                }
            }
        }
        return merged
    }

    private suspend fun loadTransferScopedRecords(
        affected: List<ParsedEventRecord>,
        windows: List<TimeRange>,
    ): List<ParsedEventRecord> {
        val merged = affected.associateBy { it.event.id }.toMutableMap()
        for (window in windows) {
            for (record in loadUnlinkedTransfersReceivedBetween(window.startInclusive, window.endExclusive)) {
                merged.putIfAbsent(record.event.id, record)
            }
        }
        val staleLegs = loadStaleTransferTransactions(windows)
        val linkedRawSmsIds = financialTransactionRepository.listRawSmsIdsForTransactions(
            staleLegs.map { it.id },
        )
        for (record in loadByRawSmsIds(linkedRawSmsIds)) {
            if (!record.event.messageFamily.isTransferFamily()) continue
            merged.putIfAbsent(record.event.id, record)
        }
        return merged.values.sortedBy { it.event.id }
    }

    private suspend fun loadStaleTransferTransactions(
        windows: List<TimeRange>,
    ): List<FinancialTransaction> {
        if (windows.isEmpty()) return emptyList()
        val types = listOf(
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            FinancialTransactionType.SELF_TRANSFER,
        )
        return windows.flatMap { window ->
            financialTransactionRepository.listByTypesOccurredBetween(
                types = types,
                startInclusive = window.startInclusive,
                endExclusive = window.endExclusive,
            )
        }.distinctBy { it.id }
            .filter { transaction ->
                transaction.type != FinancialTransactionType.SELF_TRANSFER ||
                    transaction.linkedParsedEventIds.size == 1
            }
    }

    private suspend fun reconcileRecordsDetailed(
        records: List<ParsedEventRecord>,
        scope: AffectedScope? = null,
    ): ReconciliationReport {
        var assembledSingle = 0
        var matchedPairs = 0
        var pendingMatch = 0
        var needsReview = 0
        var ignored = 0
        var alreadyLinked = 0
        var failed = 0

        val unresolvedTransfers = mutableListOf<TransferMatchCandidate>()
        val reviewCandidates = mutableListOf<ReconciliationReviewCandidate>()
        val settledRawSmsIds = linkedSetOf<String>()

        for (record in records) {
            val event = record.event
            if (scope != null && event.rawSmsId !in scope.rawSmsIds) {
                observeUnlinkedTransfer(record)?.let { unresolvedTransfers += it }
                continue
            }
            var userConfirmed = record.automationConfirmed
            if (reviewRepository != null) {
                val review = reviewRepository.findByRawSmsId(event.rawSmsId)
                if (review?.status == ReviewStatus.RESOLVED &&
                    review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL
                ) {
                    ignored++
                    settledRawSmsIds += event.rawSmsId
                    continue
                }
                // Explicit "this SMS is financial" decision (e.g. restore from ignored).
                if (review?.resolutionKind == ReviewResolutionKind.USER_FINANCIAL_TYPE) {
                    userConfirmed = true
                }
            }
            val linkedBefore = financialTransactionRepository.findByRawSmsId(event.rawSmsId)
            if (linkedBefore != null) {
                if (
                    eventShouldNotProduceTransaction(event) &&
                    financialTransactionRepository.deleteIfExclusiveRawSmsLink(event.rawSmsId)
                ) {
                    // Stale transaction from a prior misclassification (e.g. OTP stored as a purchase).
                    // Family comes from the parsed event; the raw body is not reclassified.
                } else if (shouldReleaseStaleSelfTransferLink(record)) {
                    financialTransactionRepository.unlinkRawSms(event.rawSmsId)
                } else {
                    alreadyLinked++
                    settledRawSmsIds += event.rawSmsId
                    continue
                }
            }

            val receivedAt = rawSmsRepository.getById(event.rawSmsId)?.receivedAt
                ?: continue
            val persistedZone = linkedBefore?.occurredAtZone
            val zone = TransactionTiming.zoneFor(event.bank, persistedZone, zoneId)
            val transactionOccurredAt = TransactionTiming.effectiveOccurredAt(
                event = event,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = receivedAt,
                zoneId = zoneId,
                persistedZoneId = persistedZone,
            )

            val sourceOwn = event.sourceAccountRef?.let { ownershipResolver.resolveAccount(it) }
                ?: OwnershipStatus.UNKNOWN
            val destOwn = event.destinationAccountRef?.let { ownershipResolver.resolveAccount(it) }
                ?: OwnershipStatus.UNKNOWN
            val cardOwn = event.cardRef?.let { ownershipResolver.resolveCard(it) }
                ?: OwnershipStatus.UNKNOWN
            maybeAutoConfirmLoanOwnership(event, sourceOwn, record.details.loanType)
            val loanType = record.details.loanType
            val loanOwn = loanType?.let {
                ownershipResolver.resolveLoan(LoanReference(event.bank, it))
            } ?: OwnershipStatus.UNKNOWN

            when (event.messageFamily) {
                MessageFamily.TRANSFER_IN,
                MessageFamily.TRANSFER_OUT,
                -> {
                    val single = TransactionAssembler.assembleSingle(
                        event = event,
                        receivedAt = receivedAt,
                        sourceOwnership = sourceOwn,
                        destinationOwnership = destOwn,
                        cardOwnership = cardOwn,
                        loanOwnership = loanOwn,
                        loanType = loanType,
                        transactionOccurredAt = transactionOccurredAt,
                        userConfirmed = userConfirmed,
                        occurredAtZone = zone,
                    )
                    when (single) {
                        is TransactionAssembler.Outcome.Assembled -> {
                            when (
                                persist(single.transaction, single.rawSmsIds)
                            ) {
                                PersistOutcome.Saved -> {
                                    assembledSingle++
                                    settledRawSmsIds += event.rawSmsId
                                }

                                PersistOutcome.Already -> {
                                    alreadyLinked++
                                    settledRawSmsIds += event.rawSmsId
                                }

                                PersistOutcome.Failed -> failed++
                            }
                        }

                        TransactionAssembler.Outcome.PendingMatch -> {
                            unresolvedTransfers += TransferMatchCandidate(
                                event = event,
                                transactionReference = record.details.transactionReference,
                                occurredAtLocal = record.details.occurredAtLocal,
                                receivedAt = receivedAt,
                                sourceOwnership = sourceOwn,
                                destinationOwnership = destOwn,
                                effectiveOccurredAt = transactionOccurredAt,
                            )
                            pendingMatch++
                        }

                        is TransactionAssembler.Outcome.NeedsReview -> {
                            needsReview++
                            reviewCandidates += ReconciliationReviewCandidate(
                                rawSmsId = event.rawSmsId,
                                kind = ReviewKind.NEEDS_REVIEW,
                                reasons = single.reasons.ifEmpty { listOf("needs_review") },
                            )
                        }

                        TransactionAssembler.Outcome.Ignored -> {
                            ignored++
                            settledRawSmsIds += event.rawSmsId
                        }
                    }
                }

                else -> {
                    when (
                        val outcome = TransactionAssembler.assembleSingle(
                            event = event,
                            receivedAt = receivedAt,
                            sourceOwnership = sourceOwn,
                            destinationOwnership = destOwn,
                            cardOwnership = cardOwn,
                            loanOwnership = loanOwn,
                            loanType = loanType,
                            transactionOccurredAt = transactionOccurredAt,
                            userConfirmed = userConfirmed,
                            occurredAtZone = zone,
                        )
                    ) {
                        is TransactionAssembler.Outcome.Assembled -> {
                            when (persist(outcome.transaction, outcome.rawSmsIds)) {
                                PersistOutcome.Saved -> {
                                    assembledSingle++
                                    settledRawSmsIds += event.rawSmsId
                                }

                                PersistOutcome.Already -> {
                                    alreadyLinked++
                                    settledRawSmsIds += event.rawSmsId
                                }

                                PersistOutcome.Failed -> failed++
                            }
                        }

                        TransactionAssembler.Outcome.PendingMatch -> {
                            pendingMatch++
                            reviewCandidates += ReconciliationReviewCandidate(
                                rawSmsId = event.rawSmsId,
                                kind = ReviewKind.PENDING_MATCH,
                                reasons = listOf("transfer_pending_match"),
                            )
                        }

                        is TransactionAssembler.Outcome.NeedsReview -> {
                            needsReview++
                            reviewCandidates += ReconciliationReviewCandidate(
                                rawSmsId = event.rawSmsId,
                                kind = ReviewKind.NEEDS_REVIEW,
                                reasons = outcome.reasons.ifEmpty { listOf("needs_review") },
                            )
                        }

                        TransactionAssembler.Outcome.Ignored -> {
                            ignored++
                            settledRawSmsIds += event.rawSmsId
                        }
                    }
                }
            }
        }

        val stillOpen = unresolvedTransfers.filter {
            !financialTransactionRepository.isRawSmsLinked(it.event.rawSmsId)
        }

        val pairs = TransactionMatcher.findMutuallyUniquePairs(stillOpen)
        val matchedEventIds = mutableSetOf<String>()
        val matchedRawSmsIds = mutableSetOf<String>()
        for (pair in pairs) {
            if (pair.outgoing.event.id in matchedEventIds ||
                pair.incoming.event.id in matchedEventIds
            ) {
                continue
            }
            if (scope != null &&
                pair.outgoing.event.rawSmsId !in scope.rawSmsIds &&
                pair.incoming.event.rawSmsId !in scope.rawSmsIds
            ) {
                continue
            }
            if (financialTransactionRepository.isRawSmsLinked(pair.outgoing.event.rawSmsId) ||
                financialTransactionRepository.isRawSmsLinked(pair.incoming.event.rawSmsId)
            ) {
                continue
            }

            val outSourceOwn = pair.outgoing.sourceOwnership
            val inDestOwn = pair.incoming.destinationOwnership
            when (
                val outcome = TransactionAssembler.assembleMatchedPair(
                    pair = pair,
                    outgoingSourceOwnership = outSourceOwn,
                    incomingDestinationOwnership = inDestOwn,
                    fallbackZone = zoneId,
                )
            ) {
                is TransactionAssembler.Outcome.Assembled -> {
                    when (persist(outcome.transaction, outcome.rawSmsIds)) {
                        PersistOutcome.Saved -> {
                            matchedPairs++
                            matchedEventIds += pair.outgoing.event.id
                            matchedEventIds += pair.incoming.event.id
                            matchedRawSmsIds += pair.outgoing.event.rawSmsId
                            matchedRawSmsIds += pair.incoming.event.rawSmsId
                            settledRawSmsIds += pair.outgoing.event.rawSmsId
                            settledRawSmsIds += pair.incoming.event.rawSmsId
                            pendingMatch = (pendingMatch - 2).coerceAtLeast(0)
                        }

                        PersistOutcome.Already -> {
                            alreadyLinked += 2
                            settledRawSmsIds += pair.outgoing.event.rawSmsId
                            settledRawSmsIds += pair.incoming.event.rawSmsId
                        }

                        PersistOutcome.Failed -> failed++
                    }
                }

                else -> Unit
            }
        }

        for (candidate in stillOpen) {
            if (scope != null && candidate.event.rawSmsId !in scope.rawSmsIds) continue
            if (candidate.event.rawSmsId in matchedRawSmsIds) continue
            if (financialTransactionRepository.isRawSmsLinked(candidate.event.rawSmsId)) continue
            when (
                val unmatched = TransactionAssembler.assembleUnmatchedOwnedTransfer(
                    candidate = candidate,
                    pendingCounterparts = stillOpen.filter {
                        it.event.rawSmsId != candidate.event.rawSmsId
                    },
                    fallbackZone = zoneId,
                )
            ) {
                is TransactionAssembler.Outcome.Assembled -> {
                    when (persist(unmatched.transaction, unmatched.rawSmsIds)) {
                        PersistOutcome.Saved -> {
                            assembledSingle++
                            pendingMatch = (pendingMatch - 1).coerceAtLeast(0)
                            settledRawSmsIds += candidate.event.rawSmsId
                        }

                        PersistOutcome.Already -> {
                            alreadyLinked++
                            pendingMatch = (pendingMatch - 1).coerceAtLeast(0)
                            settledRawSmsIds += candidate.event.rawSmsId
                        }

                        PersistOutcome.Failed -> {
                            reviewCandidates += ReconciliationReviewCandidate(
                                rawSmsId = candidate.event.rawSmsId,
                                kind = ReviewKind.PENDING_MATCH,
                                reasons = listOf("transfer_pending_match"),
                            )
                        }
                    }
                }

                else -> {
                    reviewCandidates += ReconciliationReviewCandidate(
                        rawSmsId = candidate.event.rawSmsId,
                        kind = ReviewKind.PENDING_MATCH,
                        reasons = listOf("transfer_pending_match"),
                    )
                }
            }
        }

        val upgraded = upgradeStaleExternalPairs(records, scope)
        matchedPairs += upgraded.matchedPairs
        assembledSingle += upgraded.assembledSingle
        alreadyLinked += upgraded.alreadyLinked
        failed += upgraded.failed
        settledRawSmsIds += upgraded.settledRawSmsIds

        val healedLoans = upgradeStaleFeeFinancingInstallments(records, scope)
        assembledSingle += healedLoans.assembledSingle
        alreadyLinked += healedLoans.alreadyLinked
        failed += healedLoans.failed
        settledRawSmsIds += healedLoans.settledRawSmsIds

        val summary = ReconciliationSummary(
            assembledSingle = assembledSingle,
            matchedPairs = matchedPairs,
            pendingMatch = pendingMatch,
            needsReview = needsReview,
            ignored = ignored,
            alreadyLinked = alreadyLinked,
            failed = failed,
        )
        return ReconciliationReport(
            summary = summary,
            reviewCandidates = reviewCandidates
                .groupBy { it.rawSmsId }
                .map { (_, group) ->
                    val first = group.first()
                    ReconciliationReviewCandidate(
                        rawSmsId = first.rawSmsId,
                        kind = first.kind,
                        reasons = group.flatMap { it.reasons }.distinct().sorted(),
                    )
                },
            settledRawSmsIds = settledRawSmsIds,
        )
    }

    /**
     * Replace a posted single leg when a counterpart is mutually unique under
     * [TransactionMatcher]: shared reference, intra-bank accounts, or the
     * unknown-destination suffix, inside the matcher time window.
     *
     * The posted leg may be an external transfer or a single-evidence self-transfer.
     * Amount, endpoint, and timestamp equality alone never joins two transactions.
     */
    private suspend fun upgradeStaleExternalPairs(
        records: List<ParsedEventRecord>,
        scope: AffectedScope? = null,
    ): UpgradePassResult {
        val parsedById = records.associateBy { it.event.id }
        val outs: List<FinancialTransaction>
        val ins: List<FinancialTransaction>
        val singleLegSelfTransfers: List<FinancialTransaction>
        if (scope != null) {
            val loaded = loadStaleTransferTransactions(scope.transferWindows)
            outs = loaded.filter { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT }
            ins = loaded.filter { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN }
            singleLegSelfTransfers = loaded.filter { it.type == FinancialTransactionType.SELF_TRANSFER }
        } else {
            outs = financialTransactionRepository.listByTypes(
                listOf(FinancialTransactionType.EXTERNAL_TRANSFER_OUT),
            )
            ins = financialTransactionRepository.listByTypes(
                listOf(FinancialTransactionType.EXTERNAL_TRANSFER_IN),
            )
            singleLegSelfTransfers = financialTransactionRepository.listByTypes(
                listOf(FinancialTransactionType.SELF_TRANSFER),
            ).filter { it.linkedParsedEventIds.size == 1 }
        }
        if (outs.isEmpty() && ins.isEmpty() && singleLegSelfTransfers.isEmpty()) return UpgradePassResult()

        data class StaleLeg(
            val transaction: FinancialTransaction?,
            val event: ParsedEvent,
            val record: ParsedEventRecord?,
        )

        fun staleLegs(
            transactions: List<FinancialTransaction>,
            family: MessageFamily,
        ): List<StaleLeg> =
            transactions.mapNotNull { transaction ->
                val event = transaction.linkedParsedEventIds
                    .mapNotNull { parsedById[it]?.event }
                    .firstOrNull { it.messageFamily == family }
                    ?: return@mapNotNull null
                val record = parsedById[event.id]
                if (!TransactionAssembler.isAutomationEligible(event, record?.automationConfirmed == true)) {
                    return@mapNotNull null
                }
                StaleLeg(
                    transaction = transaction,
                    event = event,
                    record = record,
                )
            }

        val unlinkedOutLegs = mutableListOf<StaleLeg>()
        val unlinkedInLegs = mutableListOf<StaleLeg>()
        for (record in records) {
            val event = record.event
            if (!event.messageFamily.isTransferFamily()) continue
            if (!TransactionAssembler.isAutomationEligible(event, record.automationConfirmed)) continue
            if (financialTransactionRepository.isRawSmsLinked(event.rawSmsId)) continue
            val leg = StaleLeg(transaction = null, event = event, record = record)
            when (event.messageFamily) {
                MessageFamily.TRANSFER_OUT -> unlinkedOutLegs += leg
                MessageFamily.TRANSFER_IN -> unlinkedInLegs += leg
                else -> Unit
            }
        }

        val outLegs = (
            staleLegs(outs, MessageFamily.TRANSFER_OUT) +
                staleLegs(singleLegSelfTransfers, MessageFamily.TRANSFER_OUT) +
                unlinkedOutLegs
            )
            .associateBy { it.event.id }
            .values
            .toList()
        val inLegs = (
            staleLegs(ins, MessageFamily.TRANSFER_IN) +
                staleLegs(singleLegSelfTransfers, MessageFamily.TRANSFER_IN) +
                unlinkedInLegs
            )
            .associateBy { it.event.id }
            .values
            .toList()
        if (outLegs.isEmpty() || inLegs.isEmpty()) return UpgradePassResult()

        val outByEventId = outLegs.associateBy { it.event.id }
        val inByEventId = inLegs.associateBy { it.event.id }

        val candidates = buildList {
            for (leg in outLegs + inLegs) {
                val receivedAt = rawSmsRepository.getById(leg.event.rawSmsId)?.receivedAt
                    ?: leg.transaction?.occurredAt
                    ?: continue
                val sourceOwn = leg.event.sourceAccountRef?.let { ownershipResolver.resolveAccount(it) }
                    ?: OwnershipStatus.UNKNOWN
                val destOwn = leg.event.destinationAccountRef?.let { ownershipResolver.resolveAccount(it) }
                    ?: OwnershipStatus.UNKNOWN
                add(
                    TransferMatchCandidate(
                        event = leg.event,
                        transactionReference = leg.record?.details?.transactionReference,
                        occurredAtLocal = leg.record?.details?.occurredAtLocal,
                        receivedAt = receivedAt,
                        sourceOwnership = sourceOwn,
                        destinationOwnership = destOwn,
                        effectiveOccurredAt = TransactionTiming.effectiveOccurredAt(
                            event = leg.event,
                            occurredAtLocal = leg.record?.details?.occurredAtLocal,
                            receivedAt = receivedAt,
                            zoneId = zoneId,
                            persistedZoneId = leg.transaction?.occurredAtZone,
                        ),
                    ),
                )
            }
        }
        val pairs = TransactionMatcher.findMutuallyUniquePairs(candidates)
            .filter { pair ->
                pair.outgoing.sourceOwnership == OwnershipStatus.OWNED &&
                    pair.incoming.destinationOwnership == OwnershipStatus.OWNED
            }

        var matchedPairs = 0
        var assembledSingle = 0
        var alreadyLinked = 0
        var failed = 0
        val settledRawSmsIds = linkedSetOf<String>()

        for (pair in pairs) {
            val outLeg = outByEventId[pair.outgoing.event.id] ?: continue
            val inLeg = inByEventId[pair.incoming.event.id] ?: continue
            if (scope != null &&
                outLeg.event.rawSmsId !in scope.rawSmsIds &&
                inLeg.event.rawSmsId !in scope.rawSmsIds
            ) {
                continue
            }
            val hasStaleExternal =
                outLeg.transaction?.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT ||
                    inLeg.transaction?.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN
            val joinsSingleLegSelfTransfer = listOfNotNull(outLeg.transaction, inLeg.transaction)
                .any { posted ->
                    posted.type == FinancialTransactionType.SELF_TRANSFER &&
                        posted.linkedParsedEventIds.size == 1
                }
            if (!hasStaleExternal && !joinsSingleLegSelfTransfer) continue
            val sourceOwn = pair.outgoing.sourceOwnership
            val destOwn = pair.incoming.destinationOwnership
            val healZone = listOfNotNull(
                outLeg.transaction?.occurredAtZone,
                inLeg.transaction?.occurredAtZone,
            ).firstNotNullOfOrNull { stored ->
                runCatching { ZoneId.of(stored) }.getOrNull()
            } ?: zoneId

            when (
                val outcome = TransactionAssembler.assembleMatchedPair(
                    pair = pair,
                    outgoingSourceOwnership = sourceOwn,
                    incomingDestinationOwnership = destOwn,
                    fallbackZone = healZone,
                )
            ) {
                is TransactionAssembler.Outcome.Assembled -> {
                    val rawSmsIds = listOf(outLeg.event.rawSmsId, inLeg.event.rawSmsId)
                        .distinct()
                        .sorted()
                    when (upgradePersist(outcome.transaction, outcome.rawSmsIds, rawSmsIds)) {
                        UpgradePersistOutcome.Saved -> {
                            matchedPairs++
                            assembledSingle++
                            settledRawSmsIds += rawSmsIds
                        }

                        UpgradePersistOutcome.Already -> {
                            alreadyLinked += 2
                            settledRawSmsIds += rawSmsIds
                        }

                        UpgradePersistOutcome.Failed -> failed++
                    }
                }

                else -> failed++
            }
        }

        return UpgradePassResult(
            matchedPairs = matchedPairs,
            assembledSingle = assembledSingle,
            alreadyLinked = alreadyLinked,
            failed = failed,
            settledRawSmsIds = settledRawSmsIds,
        )
    }

    private suspend fun upgradeStaleFeeFinancingInstallments(
        records: List<ParsedEventRecord>,
        scope: AffectedScope? = null,
    ): UpgradePassResult {
        val parsedById = records.associateBy { it.event.id }
        val staleFees = if (scope != null) {
            records
                .filter { it.event.messageFamily == MessageFamily.FINANCING_INSTALLMENT }
                .filter { it.event.rawSmsId in scope.rawSmsIds }
                .mapNotNull { financialTransactionRepository.findByRawSmsId(it.event.rawSmsId) }
                .filter { it.type == FinancialTransactionType.FEE }
                .distinctBy { it.id }
        } else {
            financialTransactionRepository.listAll().filter { transaction ->
                transaction.type == FinancialTransactionType.FEE &&
                    transaction.linkedParsedEventIds.any {
                        parsedById[it]?.event?.messageFamily == MessageFamily.FINANCING_INSTALLMENT
                    }
            }
        }
        if (staleFees.isEmpty()) return UpgradePassResult()

        var assembledSingle = 0
        var alreadyLinked = 0
        var failed = 0
        val settledRawSmsIds = linkedSetOf<String>()

        for (existing in staleFees) {
            val record = existing.linkedParsedEventIds
                .mapNotNull { parsedById[it] }
                .firstOrNull { it.event.messageFamily == MessageFamily.FINANCING_INSTALLMENT }
                ?: continue
            val event = record.event
            if (!TransactionAssembler.isAutomationEligible(event, record.automationConfirmed)) continue
            val receivedAt = rawSmsRepository.getById(event.rawSmsId)?.receivedAt ?: existing.occurredAt
            val sourceOwn = event.sourceAccountRef?.let { ownershipResolver.resolveAccount(it) }
                ?: OwnershipStatus.UNKNOWN
            val destOwn = event.destinationAccountRef?.let { ownershipResolver.resolveAccount(it) }
                ?: OwnershipStatus.UNKNOWN
            val cardOwn = event.cardRef?.let { ownershipResolver.resolveCard(it) }
                ?: OwnershipStatus.UNKNOWN
            maybeAutoConfirmLoanOwnership(event, sourceOwn, record.details.loanType)
            val loanType = record.details.loanType
            val loanOwn = loanType?.let {
                ownershipResolver.resolveLoan(LoanReference(event.bank, it))
            } ?: OwnershipStatus.UNKNOWN
            val transactionOccurredAt = TransactionTiming.effectiveOccurredAt(
                event = event,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = receivedAt,
                zoneId = zoneId,
                persistedZoneId = existing.occurredAtZone,
            )
            val zone = TransactionTiming.zoneFor(event.bank, existing.occurredAtZone, zoneId)
            when (
                val outcome = TransactionAssembler.assembleSingle(
                    event = event,
                    receivedAt = receivedAt,
                    sourceOwnership = sourceOwn,
                    destinationOwnership = destOwn,
                    cardOwnership = cardOwn,
                    loanOwnership = loanOwn,
                    loanType = loanType,
                    transactionOccurredAt = transactionOccurredAt,
                    userConfirmed = record.automationConfirmed,
                    occurredAtZone = zone,
                )
            ) {
                is TransactionAssembler.Outcome.Assembled -> {
                    if (outcome.transaction.type != FinancialTransactionType.LOAN_REPAYMENT) {
                        failed++
                        continue
                    }
                    val healed = outcome.transaction.copy(id = existing.id)
                    if (financialTransactionRepository.update(healed)) {
                        assembledSingle++
                        settledRawSmsIds += event.rawSmsId
                    } else {
                        failed++
                    }
                }

                else -> failed++
            }
        }

        return UpgradePassResult(
            assembledSingle = assembledSingle,
            alreadyLinked = alreadyLinked,
            failed = failed,
            settledRawSmsIds = settledRawSmsIds,
        )
    }

    private enum class UpgradePersistOutcome { Saved, Already, Failed }

    private suspend fun upgradePersist(
        transaction: FinancialTransaction,
        rawSmsIds: List<String>,
        staleRawSmsIds: List<String>,
    ): UpgradePersistOutcome {
        when (persist(transaction, rawSmsIds)) {
            PersistOutcome.Saved -> return UpgradePersistOutcome.Saved
            PersistOutcome.Already -> return UpgradePersistOutcome.Already
            PersistOutcome.Failed -> Unit
        }

        return when (
            financialTransactionRepository.replaceExclusiveStaleLinks(
                transaction = transaction,
                rawSmsIds = rawSmsIds,
                staleRawSmsIds = staleRawSmsIds,
            )
        ) {
            FinancialTransactionSaveResult.Saved -> UpgradePersistOutcome.Saved
            FinancialTransactionSaveResult.AlreadyExists -> UpgradePersistOutcome.Already
            is FinancialTransactionSaveResult.Conflict -> UpgradePersistOutcome.Failed
        }
    }

    private data class UpgradePassResult(
        val matchedPairs: Int = 0,
        val assembledSingle: Int = 0,
        val alreadyLinked: Int = 0,
        val failed: Int = 0,
        val settledRawSmsIds: Set<String> = emptySet(),
    )

    private suspend fun persist(
        transaction: FinancialTransaction,
        rawSmsIds: List<String>,
    ): PersistOutcome =
        when (financialTransactionRepository.save(transaction, rawSmsIds)) {
            FinancialTransactionSaveResult.Saved -> PersistOutcome.Saved
            FinancialTransactionSaveResult.AlreadyExists -> PersistOutcome.Already
            is FinancialTransactionSaveResult.Conflict -> PersistOutcome.Failed
        }

    private suspend fun observeUnlinkedTransfer(record: ParsedEventRecord): TransferMatchCandidate? {
        val event = record.event
        if (!event.messageFamily.isTransferFamily()) return null
        if (financialTransactionRepository.isRawSmsLinked(event.rawSmsId)) return null
        var userConfirmed = record.automationConfirmed
        if (reviewRepository != null) {
            val review = reviewRepository.findByRawSmsId(event.rawSmsId)
            if (review?.status == ReviewStatus.RESOLVED &&
                review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL
            ) {
                return null
            }
            if (review?.resolutionKind == ReviewResolutionKind.USER_FINANCIAL_TYPE) {
                userConfirmed = true
            }
        }
        if (!TransactionAssembler.isAutomationEligible(event, userConfirmed)) return null
        val receivedAt = rawSmsRepository.getById(event.rawSmsId)?.receivedAt ?: return null
        val sourceOwn = event.sourceAccountRef?.let { ownershipResolver.resolveAccount(it) }
            ?: OwnershipStatus.UNKNOWN
        val destOwn = event.destinationAccountRef?.let { ownershipResolver.resolveAccount(it) }
            ?: OwnershipStatus.UNKNOWN
        return TransferMatchCandidate(
            event = event,
            transactionReference = record.details.transactionReference,
            occurredAtLocal = record.details.occurredAtLocal,
            receivedAt = receivedAt,
            sourceOwnership = sourceOwn,
            destinationOwnership = destOwn,
            effectiveOccurredAt = TransactionTiming.effectiveOccurredAt(
                event = event,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = receivedAt,
                zoneId = zoneId,
            ),
        )
    }

    private suspend fun shouldReleaseStaleSelfTransferLink(
        record: ParsedEventRecord,
    ): Boolean {
        val event = record.event
        if (event.messageFamily != MessageFamily.TRANSFER_IN &&
            event.messageFamily != MessageFamily.TRANSFER_OUT
        ) {
            return false
        }
        val linked = financialTransactionRepository.findByRawSmsId(event.rawSmsId) ?: return false
        if (linked.type != FinancialTransactionType.SELF_TRANSFER) return false
        val receivedAt = rawSmsRepository.getById(event.rawSmsId)?.receivedAt ?: return false
        val expectedAt = TransactionTiming.effectiveOccurredAt(
            event = event,
            occurredAtLocal = record.details.occurredAtLocal,
            receivedAt = receivedAt,
            zoneId = zoneId,
            persistedZoneId = linked.occurredAtZone,
        )
        return linked.occurredAt != expectedAt
    }

    /**
     * Legacy cleanup only: a transaction linked to a message the parser now
     * calls informational is removed. Financial and [MessageFamily.UNKNOWN]
     * families are never dropped because of SMS wording.
     */
    private fun eventShouldNotProduceTransaction(event: ParsedEvent): Boolean =
        InformationalMessagePolicy.shouldAutoIgnore(event)

    private suspend fun maybeAutoConfirmLoanOwnership(
        event: ParsedEvent,
        sourceOwnership: OwnershipStatus,
        loanType: LoanType?,
    ) {
        if (event.messageFamily != MessageFamily.FINANCING_INSTALLMENT) return
        if (sourceOwnership != OwnershipStatus.OWNED) return
        val resolvedLoanType = loanType ?: return
        val reference = LoanReference(event.bank, resolvedLoanType)
        if (ownershipResolver.resolveLoan(reference) != OwnershipStatus.UNKNOWN) return
        ownershipConfirmationService?.confirmLoanOwned(reference)
    }

    private enum class PersistOutcome { Saved, Already, Failed }

    private data class TimeRange(
        val startInclusive: Instant,
        val endExclusive: Instant,
    )

    private data class AffectedScope(
        val rawSmsIds: Set<String>,
        val transferWindows: List<TimeRange>,
    )
}
