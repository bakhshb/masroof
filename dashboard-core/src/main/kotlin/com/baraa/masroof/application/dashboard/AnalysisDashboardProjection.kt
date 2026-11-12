package com.baraa.masroof.application.dashboard

data class AnalysisDashboardSection(
    val summary: MonthlyFinancialSummary,
    val merchantSpending: MerchantSpendingOverview,
    val dailySpendingTrend: DailySpendingTrend,
)

/** Period totals, merchant spending, and the daily trend for the displayed transactions. */
object AnalysisDashboardProjection {
    fun build(context: DashboardProjectionContext): AnalysisDashboardSection =
        with(context) {
            AnalysisDashboardSection(
                summary = MonthlyFinancialSummaryCalculator.summarize(
                    period = period,
                    transactions = transactions,
                    reviewRequiredCount = reviewRequiredCount,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                    parsedRecords = parsedRecords,
                ),
                merchantSpending = MerchantSpendingOverviewBuilder.build(
                    transactions = transactions,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                ),
                dailySpendingTrend = DailySpendingTrendBuilder.build(
                    period = period,
                    transactions = transactions,
                    parsedRecords = parsedRecords,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                    zoneId = zoneId,
                    today = today,
                ),
            )
        }
}
