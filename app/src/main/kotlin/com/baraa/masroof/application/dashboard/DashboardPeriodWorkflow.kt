package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.sms.time.InstantClock
import java.time.ZoneId

/**
 * Dashboard salary-period navigation and adjustment lookup for presentation.
 */
class DashboardPeriodWorkflow(
    private val clock: InstantClock = InstantClock.System,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun currentPeriod(): DashboardSalaryPeriod =
        DashboardSalaryPeriod.of(FinancialPeriodPolicy.periodContaining(clock.now().atZone(zoneId).toLocalDate()))

    fun previous(period: DashboardSalaryPeriod): DashboardSalaryPeriod =
        DashboardSalaryPeriod.of(FinancialPeriodPolicy.previous(period.toFinancialPeriod()))

    fun next(period: DashboardSalaryPeriod): DashboardSalaryPeriod =
        DashboardSalaryPeriod.of(FinancialPeriodPolicy.next(period.toFinancialPeriod()))

    fun salaryCycleStartAdjustment(period: DashboardSalaryPeriod): DashboardSalaryCycleAdjustment? =
        FinancialPeriodPolicy.salaryCycleStartAdjustment(period.startDate)?.let(DashboardSalaryCycleAdjustment::of)
}
