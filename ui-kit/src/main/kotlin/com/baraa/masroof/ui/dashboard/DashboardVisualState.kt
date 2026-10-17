package com.baraa.masroof.ui.dashboard

/** Formatted display values only. Unknown values are supplied as localized unavailable text. */
data class DashboardHeroVisualState(
    val title: String,
    val remaining: String,
    val inflowLabel: String,
    val inflow: String,
    val outflowLabel: String,
    val outflow: String,
)
