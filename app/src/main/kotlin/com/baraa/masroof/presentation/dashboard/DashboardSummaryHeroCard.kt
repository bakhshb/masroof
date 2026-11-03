package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import com.baraa.masroof.presentation.common.MasroofHeroCard
import com.baraa.masroof.presentation.common.MasroofDetailRow
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.ui.theme.MasroofSpacing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofCardAccent

data class DashboardSummaryHeroSpec(
    val accent: MasroofCardAccent,
    val primary: DashboardSummaryMetricItem,
    val secondary: List<DashboardSummaryMetricItem> = emptyList(),
    val footerHint: String? = null,
    val subtitle: String? = null,
)

@Composable
fun DashboardSummaryHeroCard(spec: DashboardSummaryHeroSpec, modifier: Modifier = Modifier) {
    val role = when (spec.accent) {
        MasroofCardAccent.Account -> MasroofHeroRole.Account
        MasroofCardAccent.Credit -> MasroofHeroRole.Card
        MasroofCardAccent.Liability -> MasroofHeroRole.Liability
        MasroofCardAccent.Inflow -> MasroofHeroRole.Inflow
        MasroofCardAccent.Outflow -> MasroofHeroRole.Outflow
        MasroofCardAccent.None -> MasroofHeroRole.Brand
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofHeroCard(title = spec.primary.title, value = spec.primary.amount, role = role, subtitle = spec.subtitle,
            guidance = listOfNotNull(spec.primary.hint, spec.footerHint).distinct().joinToString("\n\n"))
        if (spec.secondary.isNotEmpty()) {
            com.baraa.masroof.presentation.common.MasroofMetricRow { tile ->
                spec.secondary.forEach { metric -> com.baraa.masroof.presentation.common.MasroofMetric(metric.title, metric.amount,
                    resolveMetricToneColor(metric.tone, metric.signedAmount), tile) }
            }
        }
    }
}
