package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ReviewCorrectionClosure
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.ZoneId

/**
 * The one-time release run by [TransactionReconciliationService.repairLegacyTransfersDetailed] before its full pass
 * (plan 3.3c deletes both). Older assemblers posted each owned-owned transfer SMS as its own self-transfer; the keep
 * rule would keep that double-counted money. Uniquely pairable legs stay for the projection's upgrade, and
 * irreducibly ambiguous exclusive legs are unlinked so the following pass puts them in PENDING_MATCH.
 *
 * A false delete is rechecked. A protected, shared, or already-settled row is a skip. A row that still needs release
 * counts as a failure. Explicit user resolutions, two-evidence self-transfers, and lone legs are not touched.
 */
internal class LegacySelfTransferRelease(
    private val rawSmsRepository: RawSmsRepository,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val ownershipResolver: OwnershipResolver,
    private val effectiveParsedEventProvider: EffectiveParsedEventProvider,
    private val reviewRepository: ReviewRepository,
    private val userDecisionRepository: UserDecisionRepository,
    private val zoneId: ZoneId,
) {
    data class Result(
        val failedCount: Int = 0,
        val failedRawSmsIds: Set<String> = emptySet(),
    )

    suspend fun release(): Result {
        val records = effectiveParsedEventProvider.listAllEffective()
        val postedSingleLeg = mutableListOf<ParsedEventRecord>()
        val postedZones = mutableMapOf<String, String?>()
        for (record in records) {
            val event = record.event
            if (!event.messageFamily.isTransferFamily()) continue
            val linked = financialTransactionRepository.findByRawSmsId(event.rawSmsId) ?: continue
            postedZones[event.rawSmsId] = linked.occurredAtZone
            if (linked.type != FinancialTransactionType.SELF_TRANSFER) continue
            if (linked.linkedParsedEventIds.size != 1) continue
            if (preservesExplicitUserDecision(event.rawSmsId)) continue
            postedSingleLeg += record
        }
        if (postedSingleLeg.isEmpty()) return Result()

        val candidates = records.mapNotNull { transferCandidateFor(it, postedZones[it.event.rawSmsId]) }
        if (candidates.isEmpty()) return Result()
        val uniquelyPairedEventIds = TransactionMatcher.findMutuallyUniquePairs(candidates)
            .flatMap { pair -> listOf(pair.outgoing.event.id, pair.incoming.event.id) }
            .toSet()

        val failedRawSmsIds = linkedSetOf<String>()
        var failed = 0
        for (record in postedSingleLeg) {
            val event = record.event
            if (event.id in uniquelyPairedEventIds) continue
            val candidate = candidates.firstOrNull { it.event.id == event.id } ?: continue
            if (!TransactionMatcher.hasPendingIntraBankCounterpart(candidate, candidates)) continue
            val deleted = try {
                financialTransactionRepository.deleteIfExclusiveRawSmsLink(event.rawSmsId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
                failedRawSmsIds += event.rawSmsId
                continue
            }
            if (!deleted && releaseStillRequired(event.rawSmsId)) {
                failed++
                failedRawSmsIds += event.rawSmsId
            }
        }
        return Result(failedCount = failed, failedRawSmsIds = failedRawSmsIds)
    }

    /** True when an exclusive single-leg self-transfer is still posted after a delete that returned false. */
    private suspend fun releaseStillRequired(rawSmsId: String): Boolean {
        if (preservesExplicitUserDecision(rawSmsId)) return false
        val linked = financialTransactionRepository.findByRawSmsId(rawSmsId) ?: return false
        if (linked.type != FinancialTransactionType.SELF_TRANSFER) return false
        if (linked.linkedParsedEventIds.size != 1) return false
        return financialTransactionRepository.listRawSmsIds(linked.id).size == 1
    }

    /** Any outcome decision, or a correction whose own pass posted the SMS ([ReviewCorrectionClosure]). */
    private suspend fun preservesExplicitUserDecision(rawSmsId: String): Boolean =
        ReviewCorrectionClosure.protects(
            userDecisionRepository.effectiveFor(rawSmsId),
            reviewRepository.findByRawSmsId(rawSmsId),
        )

    private suspend fun transferCandidateFor(
        record: ParsedEventRecord,
        persistedZoneId: String?,
    ): TransferMatchCandidate? {
        val event = record.event
        if (!event.messageFamily.isTransferFamily()) return null
        val decisions = userDecisionRepository.effectiveFor(event.rawSmsId)
        if (decisions.isNonFinancial) return null
        val userConfirmed = record.automationConfirmed || decisions.confirmsFinancial
        if (!TransactionAssembler.isAutomationEligible(event, userConfirmed)) return null
        val receivedAt = rawSmsRepository.getById(event.rawSmsId)?.receivedAt ?: return null
        return TransferMatchCandidate(
            event = event,
            transactionReference = record.details.transactionReference,
            occurredAtLocal = record.details.occurredAtLocal,
            receivedAt = receivedAt,
            sourceOwnership = accountOwnership(event.sourceAccountRef),
            destinationOwnership = accountOwnership(event.destinationAccountRef),
            effectiveOccurredAt = TransactionTiming.effectiveOccurredAt(
                event = event,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = receivedAt,
                zoneId = zoneId,
                persistedZoneId = persistedZoneId,
            ),
        )
    }

    private suspend fun accountOwnership(reference: AccountReference?): OwnershipStatus =
        reference?.let { ownershipResolver.resolveAccount(it) } ?: OwnershipStatus.UNKNOWN

    private fun MessageFamily.isTransferFamily(): Boolean =
        this == MessageFamily.TRANSFER_IN || this == MessageFamily.TRANSFER_OUT
}
