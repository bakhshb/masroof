package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import java.time.Instant

/**
 * Parsed/raw SMS evidence one dashboard projection may read.
 *
 * [parsedRecords] are distinct and ordered by event id — the order dashboard calculators
 * iterate when a rule picks the first/last matching row. [rawSmsById] holds the RawSms of
 * every record.
 */
data class DashboardEvidence(
    val parsedRecords: List<ParsedEventRecord>,
    val rawSmsById: Map<String, RawSms>,
) {
    private val eventIds: Set<String> by lazy { parsedRecords.mapTo(hashSetOf()) { it.event.id } }

    fun covers(transaction: FinancialTransaction): Boolean =
        transaction.linkedParsedEventIds.all { it in eventIds }

    operator fun plus(other: DashboardEvidence): DashboardEvidence {
        if (other.parsedRecords.isEmpty() && other.rawSmsById.isEmpty()) return this
        return DashboardEvidence(
            parsedRecords = (parsedRecords + other.parsedRecords)
                .distinctBy { it.event.id }
                .sortedBy { it.event.id },
            rawSmsById = rawSmsById + other.rawSmsById,
        )
    }

    companion object {
        val EMPTY = DashboardEvidence(emptyList(), emptyMap())
    }
}

/** Where a dashboard load gets its [DashboardEvidence]. */
interface DashboardEvidenceSource {
    /** History facts plus evidence linked to the selected-period [transactions]. */
    suspend fun load(
        transactions: Collection<FinancialTransaction>,
        registryCards: List<CardRegistryEntry>,
        periodEndExclusive: Instant,
    ): DashboardEvidence

    /** Adds evidence linked to [transactions] that [evidence] does not cover yet. */
    suspend fun extend(
        evidence: DashboardEvidence,
        transactions: Collection<FinancialTransaction>,
    ): DashboardEvidence
}

/**
 * Explicit query scope for the dashboard read model.
 *
 * **Linked evidence** — ParsedEvents/RawSms linked (via the transaction ↔ RawSms link table)
 * to the transaction sets a projection displays: the selected salary period, the
 * credit-card statement window, commitment sources outside the period, and credit-card
 * payments that settle a statement due in the period.
 *
 * **History facts** — rows whose meaning does not depend on a displayed transaction.
 * Each lookup mirrors exactly one calculator rule, bounded by kind (and by time where
 * the rule allows):
 * - statements: every statement SMS (`CreditCardOverviewBuilder` statement cycles)
 * - credit-card identity: newest credit/statement SMS per card bank + last4
 *   (`occurredAt`, else RawSms `receivedAt`, then greatest event id)
 * - available balances: latest per card before the period end on that same clock,
 *   so a past period does not borrow a newer balance
 * - financing installments with a loan type (`LoanOverviewBuilder`)
 * - merchant exchange rates (`HistoricalExchangeRateIndex`)
 * - earliest debit-channel row and earliest debit-source-account row per registry
 *   card bank + last4 (`CardRegistryDebitClassifier`, `DebitLinkedAccountInferrer`)
 *
 * Every rule that scans "all" records therefore sees each row that can change its result,
 * so projections equal the former whole-history load. Never call `listAll()` here.
 */
class DashboardEvidenceScope(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val parsedEventRepository: ParsedEventRepository,
    private val rawSmsRepository: RawSmsRepository,
) : DashboardEvidenceSource {
    override suspend fun load(
        transactions: Collection<FinancialTransaction>,
        registryCards: List<CardRegistryEntry>,
        periodEndExclusive: Instant,
    ): DashboardEvidence {
        val facts = buildList {
            addAll(parsedEventRepository.listCardStatementFacts())
            addAll(parsedEventRepository.listLatestCreditCardRowFacts())
            addAll(parsedEventRepository.listLatestCreditCardAvailableBalanceFacts(periodEndExclusive))
            addAll(parsedEventRepository.listFinancingInstallmentFacts())
            addAll(parsedEventRepository.listExchangeRateFacts())
            addAll(parsedEventRepository.listFirstDebitCardFacts(registryCards.map { it.last4 }.distinct()))
        }
        return extend(withRawSms(facts, known = emptyMap()), transactions)
    }

    override suspend fun extend(
        evidence: DashboardEvidence,
        transactions: Collection<FinancialTransaction>,
    ): DashboardEvidence {
        val uncovered = transactions.filterNot(evidence::covers).map { it.id }
        if (uncovered.isEmpty()) return evidence
        val rawSmsIds = financialTransactionRepository.listRawSmsIdsForTransactions(uncovered)
        val linked = parsedEventRepository.listByRawSmsIds(rawSmsIds)
        return evidence + withRawSms(linked, known = evidence.rawSmsById)
    }

    private suspend fun withRawSms(
        records: List<ParsedEventRecord>,
        known: Map<String, RawSms>,
    ): DashboardEvidence {
        val missing = records.map { it.event.rawSmsId }.distinct().filterNot(known::containsKey)
        val rawSmsById = rawSmsRepository.getByIds(missing).associateBy { it.id }
        return DashboardEvidence(
            parsedRecords = records.distinctBy { it.event.id }.sortedBy { it.event.id },
            rawSmsById = rawSmsById,
        )
    }
}
