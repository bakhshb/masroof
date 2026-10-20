package com.baraa.masroof.lab.finance.dashboard

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Environment
import java.time.Instant
import java.time.ZoneId

data class DashboardQuery(
    val environment: Environment,
    val from: Instant,
    val until: Instant,
    val zone: ZoneId,
    val currency: Currency,
) {
    init { require(from < until) }
}

/** One consistent read revision. asOf records data freshness. */
data class ReadRevision(val token: String, val asOf: Instant) {
    init { require(token.isNotBlank()) }
}

/** Computed and persisted-fact-backed by application builders; presentation only displays it. */
data class DashboardSnapshot(
    val query: DashboardQuery,
    val accounts: List<AccountSummary>,
    val debitCards: List<DebitSpending>,
    val creditFacilities: List<FacilitySummary>,
    val loans: List<LoanSummary> = emptyList(),
    val commitments: CommitmentsSummary? = null,
    val analysis: AnalysisSummary? = null,
    val transactions: List<TransactionFacts> = emptyList(),
    val totals: PortfolioTotals? = null,
    val revision: ReadRevision? = null,
    /** Full precomputed fleet buckets; UI must not derive them by summing account rows. */
    val fleetFlow: AccountFlow? = null,
    val standaloneCreditCards: List<CreditCardSummary> = emptyList(),
) {
    init {
        val cards = creditFacilities.flatMap { it.cards } + standaloneCreditCards
        val ids = accounts.map { it.id } + debitCards.map { it.cardId } +
            creditFacilities.map { it.facilityId } + cards.map { it.cardId } + loans.map { it.id }
        require(ids.all { it.environment == query.environment })
        require(ids.distinct().size == ids.size)
        val accountIds = accounts.map { it.id }.toSet()
        val facilityIds = creditFacilities.map { it.facilityId }.toSet()
        val loanIds = loans.map { it.id }.toSet()
        val debitIds = debitCards.map { it.cardId }.toSet()
        val cardIds = debitIds + cards.map { it.cardId }
        require(debitCards.all { it.accountId == null || it.accountId in accountIds })
        require(accounts.all { it.flow == null || it.flow.currency == query.currency })
        require(fleetFlow == null || fleetFlow.currency == query.currency)
        requireSignedCurrency(query.currency, *debitCards.map { it.spending }.toTypedArray(),
            *creditFacilities.flatMap { listOf(it.salarySpending, it.statementSpending) }.toTypedArray(),
            *cards.flatMap { listOf(it.calendarSpending?.net, it.salarySpending?.net, it.statementSpending?.net) }.toTypedArray(),
            *loans.map { it.salaryPayment }.toTypedArray())
        val periods = debitCards.mapNotNull { it.salaryPeriod } + loans.mapNotNull { it.salaryPeriod } +
            cards.flatMap { listOfNotNull(it.calendarSpending?.period, it.salarySpending?.period, it.statementSpending?.period) }
        require(periods.all { it.zone == query.zone })
        require(transactions.map { it.key }.distinct().size == transactions.size)
        transactions.forEach { tx ->
            require(tx.key.environment == query.environment)
            require(tx.time.instant >= query.from && tx.time.instant < query.until)
            require(tx.accounts.all { it in accountIds })
            require(tx.cards.all { it in cardIds })
            require(tx.debitSpendingCards.all { it in debitIds })
            require(tx.loans.all { it in loanIds })
            require(listOfNotNull(tx.source, tx.destination).all { it in ids })
        }
        commitments?.rows?.forEach { row ->
            require(row.environment == query.environment)
            require(row.facilityId == null || row.facilityId in facilityIds)
            require(row.loanId == null || row.loanId in loanIds)
        }
        require(analysis?.merchants?.all {row -> row.transactionIds.all {it.environment==query.environment}} != false)
        analysis?.trends?.forEach { trend ->
            require(trend.period.zone == query.zone)
            require(trend.assetId == null || trend.assetId in ids)
            // Comparison windows may precede the selected transaction list; IDs are provenance only.
            require(trend.points.all { point -> point.transactions.all { it.environment == query.environment } })
        }
        require(listOfNotNull(analysis?.currency, commitments?.currency, totals?.currency)
            .all { it == query.currency })
    }
}

/** One bounded, consistent, read-only call. Period is [from, until).
 * Producers freeze collection inputs for the lifetime of a snapshot. Presentation displays results;
 * evidence is batched by the producer, never queried per row by the UI. M8/M9 supply runtime adapters.
 */
fun interface DashboardReader {
    suspend fun read(query: DashboardQuery): DashboardSnapshot
}
