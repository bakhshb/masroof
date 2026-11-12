package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ReviewCorrectionClosure
import com.baraa.masroof.domain.model.EffectiveUserDecisions
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
import com.baraa.masroof.ledger.StoredLedgerRow
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
    /** The stored `financial_transaction` rows with their linked raw SMS, sorted by id. */
    val storedRows: List<LedgerRow>,
    val reviews: List<ReviewItem>,
) {
    fun project(): LedgerProjection = LedgerProjector.project(events, decisions, registry, storedFacts, zone)
}

/**
 * Loads [LedgerInputs] for the whole history. Read-only. Evidence whose raw SMS is missing is left out (Known
 * differences, "Missing raw SMS"); ownership is resolved through [OwnershipResolver], so the registry's suffix
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
        val events = records.mapNotNull { record ->
            LedgerEvidence(
                event = record.event,
                receivedAt = receivedAt[record.event.rawSmsId] ?: return@mapNotNull null,
                transactionReference = record.details.transactionReference,
                occurredAtLocal = record.details.occurredAtLocal,
                loanType = record.details.loanType,
                automationConfirmed = record.automationConfirmed,
            )
        }
        val rawSmsIds = events.map { it.event.rawSmsId }
        val reviews = reviewRepository.listAll()
        val transactions = financialTransactionRepository.listAll().sortedBy { it.id }
        val links = financialTransactionRepository.listRawSmsIdsByTransaction(transactions.map { it.id })
        val storedRows = transactions.map { LedgerRow(it, links[it.id].orEmpty().sorted()) }
        return LedgerInputs(
            events = events,
            decisions = userDecisionRepository.effectiveFor(rawSmsIds),
            registry = ownershipFacts(events),
            storedFacts = StoredFacts(
                rows = storedRows.map { row ->
                    StoredLedgerRow(
                        rawSmsIds = row.rawSmsIds.toSet(),
                        occurredAtZone = row.transaction.occurredAtZone,
                        appliedExchangeRate = row.transaction.appliedExchangeRate,
                        exchangeRateSource = row.transaction.exchangeRateSource,
                    )
                },
                correctionClosedRawSmsIds = reviews.filter(ReviewCorrectionClosure::isClosed).map { it.rawSmsId }.toSet(),
            ),
            zone = zoneId,
            storedRows = storedRows,
            reviews = reviews,
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
}
