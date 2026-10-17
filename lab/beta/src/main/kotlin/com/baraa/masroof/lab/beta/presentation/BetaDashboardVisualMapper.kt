package com.baraa.masroof.lab.beta.presentation

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
