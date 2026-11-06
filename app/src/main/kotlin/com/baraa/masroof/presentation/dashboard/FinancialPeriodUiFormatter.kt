package com.baraa.masroof.presentation.dashboard

import android.content.Context
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.DashboardSalaryCycleAdjustment
import com.baraa.masroof.application.dashboard.DashboardSalaryPeriod
import java.time.format.DateTimeFormatter
import java.util.Locale

object FinancialPeriodUiFormatter {
    fun formatRange(context: Context, period: DashboardSalaryPeriod, compact: Boolean = false): String {
        val locale = context.resources.configuration.locales[0]
        val dayMonth = if (compact) DateTimeFormatter.ofPattern("d MMM", locale) else dayMonthFormatter(context)
        val start = dayMonth.format(period.startDate)
        val end = dayMonth.format(period.displayEndDateInclusive)
        return "$start — $end ${period.displayEndDateInclusive.year}"
    }

    fun formatSalaryPeriodTitle(context: Context, period: DashboardSalaryPeriod): String =
        context.getString(R.string.dashboard_salary_period_label, formatRange(context, period))

    fun formatAdjustmentHint(
        context: Context,
        adjustment: DashboardSalaryCycleAdjustment?,
    ): String? =
        when (adjustment) {
            DashboardSalaryCycleAdjustment.EARLY_FOR_FRIDAY ->
                context.getString(R.string.dashboard_period_adjustment_early_friday)
            DashboardSalaryCycleAdjustment.LATE_FOR_SATURDAY ->
                context.getString(R.string.dashboard_period_adjustment_late_saturday)
            null -> null
        }

    private fun dayMonthFormatter(context: Context): DateTimeFormatter {
        val locale: Locale = context.resources.configuration.locales[0]
        return DateTimeFormatter.ofPattern("d MMMM", locale)
    }
}
