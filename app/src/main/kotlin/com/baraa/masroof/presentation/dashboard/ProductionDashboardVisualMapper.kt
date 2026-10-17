package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.locale.AppDigits
import com.baraa.masroof.ui.dashboard.DashboardHeroVisualState

/** Maps already formatted presentation values; no repository or financial calculation. */
object ProductionDashboardVisualMapper {
    fun hero(title: String, remaining: String, inflowLabel: String, inflow: String,
        outflowLabel: String, outflow: String) = DashboardHeroVisualState(
        AppDigits.display(title), AppDigits.display(remaining), AppDigits.display(inflowLabel),
        AppDigits.display(inflow), AppDigits.display(outflowLabel), AppDigits.display(outflow))
}
