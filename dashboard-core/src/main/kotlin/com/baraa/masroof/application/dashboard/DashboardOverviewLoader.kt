package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.period.FinancialPeriod

/**
 * Small loader abstraction so dashboard ViewModel loads can be faked in unit tests.
 */
fun interface DashboardOverviewLoader {
    suspend fun loadOverview(period: FinancialPeriod): DashboardOverview
}
