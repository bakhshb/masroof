package com.baraa.masroof.lab.finance.dashboard

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.posting.AppliedFx
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.lab.finance.posting.PostingKey
import com.baraa.masroof.lab.finance.posting.PostingRevision
import com.baraa.masroof.lab.finance.posting.TransactionTime
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Money
import java.time.LocalDate

/** Stable financial names, not translated labels or user chart preferences. All are precomputed. */
enum class SummaryMetric {
    SPENDING_GROSS, REFUNDS, SPENDING_NET, INCOME, EXTERNAL_IN, EXTERNAL_OUT,
    CREDIT_SETTLEMENTS, CASH_WITHDRAWALS, SELF_TRANSFERS, COMBINED_INCOMING, NET_CASH_FLOW,
    ACTUAL_ACCOUNT_SPENDING, CREDIT_PURCHASES,
}

enum class ChartCategory {
    CREDIT_PURCHASES, MADA_PURCHASES, ACCOUNT_PURCHASES, OTHER_EXPENSES, CASH_WITHDRAWALS,
    EXTERNAL_TRANSFERS, BILLS, LOAN_REPAYMENTS, CREDIT_SETTLEMENTS, FEES,
    INTERNAL_TRANSFERS, UNATTRIBUTED_REFUNDS,
}

/** No inferred classifier or FX multiplication in consumers. Loan FEE attribution is already resolved. */
data class TransactionFacts(
    val key: PostingKey,
    val revision: PostingRevision,
    val time: TransactionTime,
    val storedType: FinancialType,
    val effectiveType: FinancialType,
    val amount: Money,
    val merchant: String?,
    val counterparty: String?,
    val primaryCardLast4: String?,
    val accounts: Set<AssetId>,
    val cards: Set<AssetId>,
    val debitSpendingCards: Set<AssetId>,
    val loans: Set<AssetId>,
    val categories: Set<ChartCategory>,
    val appliedFx: AppliedFx?,
    /** Excludes international fee, preserving DashboardTransactionFacts semantics. */
    val sarEquivalent: Money?,
    val internationalFee: Money?,
    /** Stable evidence references only, never SMS bodies, parsed wording or mutable repositories. */
    val evidenceIds: Set<String>,
    /** Resolved owned endpoints preserve direction; unresolved/external endpoints stay null. */
    val source: AssetId? = null,
    val destination: AssetId? = null,
    /** Opaque persisted category, separate from chart classification. */
    val categoryId: String? = null,
) {
    init {
        require(evidenceIds.none(String::isBlank))
        require((accounts + cards + debitSpendingCards + loans).all { it.environment == key.environment })
        require(listOfNotNull(source, destination).all { it.environment == key.environment })
        require(debitSpendingCards.all { it in cards })
        require(primaryCardLast4 == null || primaryCardLast4.matches(Regex("[0-9]{4}")))
        require(sarEquivalent == null || sarEquivalent.currency == Currency.SAR)
        require(sarEquivalent == null || appliedFx != null)
        require(sarEquivalent == null || amount.currency != Currency.SAR)
    }
}

enum class TrendScope { OVERALL, ACCOUNTS, CREDIT_CARDS, DEBIT_CARDS, PURCHASES, ASSET, FINANCIAL_OUTFLOWS }

/** Aggregate scopes are precomputed too; consumers never sum per-asset/category trends. */
data class SpendingTrend(
    val assetId: AssetId?,
    val category: ChartCategory?,
    val period: DisplayPeriod,
    val currency: Currency,
    val points: List<DailySpending>,
    val averageDailySpending: SignedAmount?,
    val scope: TrendScope = TrendScope.OVERALL,
    /** Elapsed-point total computed by the owning builder, never summed in Compose. */
    val totalSpending: SignedAmount? = null,
    val totalBeforeRefunds: SignedAmount? = null,
    val totalRefunds: SignedAmount? = null,
    /** Sparse same-scope contributions. Missing dates are zero only when the part total is known. */
    val categoryTrends: Map<ChartCategory, SpendingTrend> = emptyMap(),
) {
    init {
        require((scope == TrendScope.ASSET) == (assetId != null))
        val dates=points.mapTo(hashSetOf()) {it.date}
        require(categoryTrends.all { (key, part) -> part.category == key && part.categoryTrends.isEmpty() &&
            part.scope == scope && part.assetId == assetId && part.period == period && part.currency == currency &&
            part.points.all {it.date in dates} })
        require(points.map { it.date }.distinct().size == points.size)
        require(points.all {
            val day = it.date.atStartOfDay(period.zone).toInstant()
            val nextDay = it.date.plusDays(1).atStartOfDay(period.zone).toInstant()
            day < period.until && nextDay > period.from
        })
        requireSignedCurrency(currency, totalSpending, totalBeforeRefunds, totalRefunds, averageDailySpending, *points.flatMap {
            listOfNotNull(it.net, it.beforeRefunds, it.refunds)
        }.toTypedArray())
    }
}

data class DailySpending(
    val date: LocalDate,
    val net: SignedAmount?,
    val beforeRefunds: SignedAmount?,
    val refunds: SignedAmount?,
    val transactions: Set<PostingKey>,
)

data class MerchantSpending(val merchant: String, val net: SignedAmount, val purchaseTransactionCount: Int? = null) {
    init { require(purchaseTransactionCount == null || purchaseTransactionCount >= 0) }
}

/** Missing metric is rejected. Null value marks unavailable; valid empty history has explicit zero.
 * Chart selection filters trends only; it never changes these full-scope summary metrics.
 */
data class AnalysisSummary(
    val currency: Currency,
    val metrics: Map<SummaryMetric, SignedAmount?>,
    val transactionCount: Int,
    val reviewRequiredCount: Int?,
    val excludedOtherCurrencyCount: Int,
    val trends: List<SpendingTrend>,
    val merchants: List<MerchantSpending>,
) {
    init {
        require(metrics.keys == SummaryMetric.entries.toSet())
        require(transactionCount >= 0 && (reviewRequiredCount == null || reviewRequiredCount >= 0) && excludedOtherCurrencyCount >= 0)
        requireSignedCurrency(currency, *metrics.values.toTypedArray(), *merchants.map { it.net }.toTypedArray())
        require(trends.all { it.currency == currency })
        val signed = setOf(SummaryMetric.SPENDING_NET, SummaryMetric.NET_CASH_FLOW, SummaryMetric.CREDIT_PURCHASES)
        require(metrics.all { (metric, value) -> metric in signed || value == null || value.amount.signum() >= 0 })
    }
}
