package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.ledger.LedgerProjector
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
 * Reconciliation: the derived ledger written for the affected raw SMS (plan 3.3a). Every entry point projects with
 * [LedgerProjector] and writes the differences in one transaction through [LedgerProjectionWriter]
 * (docs/ARCHITECTURE.md, "Ledger writer"); [automaticWrite] guards each automatic row and [inTransaction] is the
 * financial write transaction.
 */
class TransactionReconciliationService(
    rawSmsRepository: RawSmsRepository,
    financialTransactionRepository: FinancialTransactionRepository,
    ownershipResolver: OwnershipResolver,
    ownershipConfirmationService: OwnershipConfirmationService,
    effectiveParsedEventProvider: EffectiveParsedEventProvider,
    reviewRepository: ReviewRepository,
    userDecisionRepository: UserDecisionRepository,
    zoneId: ZoneId = ZoneId.systemDefault(),
    automaticWrite: AutomaticWrite? = null,
    inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private val writer = LedgerProjectionWriter(
        LedgerInputLoader(
            rawSmsRepository, financialTransactionRepository, reviewRepository, userDecisionRepository,
            effectiveParsedEventProvider, ownershipResolver, zoneId,
        ),
        financialTransactionRepository, ownershipConfirmationService, automaticWrite, inTransaction,
    )

    suspend fun reconcileStoredEvents(): ReconciliationSummary =
        reconcileStoredEventsDetailed().summary

    /** Full-history reconciliation for explicit maintenance and recovery; incremental callers use [reconcileAffectedRawSmsIds]. */
    suspend fun reconcileStoredEventsDetailed(): ReconciliationReport = writer.reconcile(null)

    /**
     * Reconcile [rawSmsIds]: the projection of [LedgerProjector.scope] around them, so the rows written equal a full
     * pass's. The report covers [rawSmsIds] and the units written; a neighbour that fails or is not written is left
     * to its own pass (docs/ARCHITECTURE.md, "Ledger writer", Report).
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
        return writer.reconcile(ids)
    }
}
