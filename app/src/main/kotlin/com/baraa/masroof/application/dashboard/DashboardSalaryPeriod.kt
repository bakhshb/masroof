package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.SalaryCycleStartAdjustment
import java.time.LocalDate

/** A salary period as the dashboard shows and navigates it: [startDate, endDateExclusive). */
data class DashboardSalaryPeriod(val startDate: LocalDate, val endDateExclusive: LocalDate) {
    init { require(endDateExclusive.isAfter(startDate)) { "Period must end after it starts" } }

    /** Inclusive last calendar day shown in labels. */
    val displayEndDateInclusive: LocalDate get() = endDateExclusive.minusDays(1)

    internal fun toFinancialPeriod() = FinancialPeriod(startDate, endDateExclusive)

    companion object {
        internal fun of(period: FinancialPeriod) = DashboardSalaryPeriod(period.startDate, period.endDateExclusive)
    }
}

/** Why a salary cycle starts off the 27th. */
enum class DashboardSalaryCycleAdjustment {
    EARLY_FOR_FRIDAY,
    LATE_FOR_SATURDAY,
    ;

    internal companion object {
        fun of(adjustment: SalaryCycleStartAdjustment) = when (adjustment) {
            SalaryCycleStartAdjustment.EARLY_FOR_FRIDAY -> EARLY_FOR_FRIDAY
            SalaryCycleStartAdjustment.LATE_FOR_SATURDAY -> LATE_FOR_SATURDAY
        }
    }
}

val DashboardOverview.salaryPeriod: DashboardSalaryPeriod get() = DashboardSalaryPeriod.of(period)
val MonthlyFinancialSummary.salaryPeriod: DashboardSalaryPeriod get() = DashboardSalaryPeriod.of(period)

/** Loads the overview of a dashboard salary period. */
suspend fun DashboardOverviewLoader.loadOverview(period: DashboardSalaryPeriod): DashboardOverview =
    loadOverview(period.toFinancialPeriod())
