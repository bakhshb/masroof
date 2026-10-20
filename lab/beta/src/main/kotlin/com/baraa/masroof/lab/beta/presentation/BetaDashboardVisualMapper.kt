package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.finance.dashboard.AccountFlow
import com.baraa.masroof.lab.finance.dashboard.CreditCardSummary
import com.baraa.masroof.lab.finance.dashboard.FlowBucket
import com.baraa.masroof.lab.finance.dashboard.SignedAmount
import com.baraa.masroof.lab.finance.dashboard.SpendingTrend
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardProduction
import com.baraa.masroof.ui.dashboard.DashboardHeroVisualState
import java.math.BigDecimal

/** Reads already computed Beta facts only. Null proof stays unavailable; never sums rows. */
object BetaDashboardVisualMapper {
    fun hero(read: DashboardProduction, title: String, inflowLabel: String, outflowLabel: String,
        unavailable: String, languageTag: String = "en"): DashboardHeroVisualState {
        val totals = read.snapshot.totals
        return DashboardHeroVisualState(title,
            totals?.accountRemaining?.let { com.baraa.masroof.ui.components.MasroofMoneyDisplay.format(it.amount, it.currency.name, languageTag) } ?: unavailable,
            inflowLabel, totals?.accountInflow?.let { com.baraa.masroof.ui.components.MasroofMoneyDisplay.format(it.amount, it.currency.name, languageTag) } ?: unavailable,
            outflowLabel, totals?.accountOutflow?.let { com.baraa.masroof.ui.components.MasroofMoneyDisplay.format(it.amount, it.currency.name, languageTag) } ?: unavailable)
    }

    /** A gap cannot become a zero or be bridged by a line suggesting known spending. */
    fun trend(trend: SpendingTrend): BetaTrendVisualState? {
        if (trend.points.isEmpty() || trend.points.any { it.net == null }) return null
        return BetaTrendVisualState(trend.points.map { checkNotNull(it.net).amount },
            trend.averageDailySpending?.amount, trend.points.map { it.date.toString() }, trend.currency.name)
    }
}

data class BetaTrendVisualState(val values: List<BigDecimal>, val average: BigDecimal?,
    val dates: List<String>, val currency: String)

/** Display selection over an already computed account flow. Zero rows are omitted; null flow is not zero. */
object BetaAssetDetailFacts {
    val externalIn: List<FlowBucket> = listOf(
        FlowBucket.SALARY, FlowBucket.EXTERNAL_IN, FlowBucket.ACCOUNT_REFUNDS, FlowBucket.OTHER_INCOME)
    val externalOut: List<FlowBucket> = listOf(
        FlowBucket.POS_PURCHASES, FlowBucket.CREDIT_SETTLEMENT, FlowBucket.LOAN, FlowBucket.BILLS,
        FlowBucket.EXTERNAL_OUT, FlowBucket.CASH_WITHDRAWAL, FlowBucket.FEES)
    val internal: List<FlowBucket> = listOf(FlowBucket.INTERNAL_IN, FlowBucket.INTERNAL_OUT)

    fun lines(flow: AccountFlow, buckets: List<FlowBucket>): List<BetaFlowLine> = buckets.mapNotNull { bucket ->
        val money = flow.buckets.getValue(bucket)
        if (money.amount.signum() == 0) null else BetaFlowLine(bucket, money.amount, money.currency.name)
    }

    fun knownLoanType(type: String): String? = type.takeIf { it == "PERSONAL" || it == "AUTO" || it == "MORTGAGE" }
}

data class BetaFlowLine(val bucket: FlowBucket, val amount: BigDecimal, val currency: String)
