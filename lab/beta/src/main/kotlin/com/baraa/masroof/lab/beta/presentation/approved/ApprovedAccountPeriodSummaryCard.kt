package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.MasroofCard
import com.baraa.masroof.ui.theme.*

@Composable
internal fun ApprovedAccountPeriodSummaryCard(
    title: String, subtitle: String, remaining: String, inflow: String, outflow: String,
    externalIn: List<Pair<String,String>>, externalOut: List<Pair<String,String>>, internalTransfers: List<Pair<String,String>>,
    modifier: Modifier = Modifier, trend: @Composable () -> Unit,
) {
    val colors = MasroofThemeExtras.extendedColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedHeroCard(title, remaining, subtitle,
            role = MasroofHeroRole.Account, guidance = stringResource(R.string.beta_approved_dashboard_account_remaining_calculated_hint))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofReference.metricGap)) {
            ApprovedAccountMetric(stringResource(R.string.beta_approved_dashboard_total_inflow), inflow,
                colors.inflow, Modifier.weight(1f))
            ApprovedAccountMetric(stringResource(R.string.beta_approved_dashboard_total_spent), outflow,
                colors.outflow, Modifier.weight(1f))
        }
        trend()
        ApprovedAccountReferenceGroup(stringResource(R.string.beta_approved_reference_inflow_external), externalIn, colors.inflow)
        ApprovedAccountReferenceGroup(stringResource(R.string.beta_approved_reference_outflow_external), externalOut, colors.outflow)
        ApprovedAccountReferenceGroup(stringResource(R.string.beta_approved_dashboard_self_transfers), internalTransfers, colors.account,
            stringResource(R.string.beta_approved_dashboard_account_remaining_calculated_hint))
    }
}

@Composable
private fun ApprovedAccountMetric(label: String,value: String,color: androidx.compose.ui.graphics.Color,modifier: Modifier) {
    com.baraa.masroof.ui.dashboard.DashboardMetric(value,color,modifier) {
        ApprovedGuidanceLabel(label,null)
    }
}

@Composable
private fun ApprovedAccountReferenceGroup(title: String, rows: List<Pair<String,String>>, color: androidx.compose.ui.graphics.Color,
    guidance: String? = null) {
    if (rows.isEmpty()) return
    MasroofCard {
        ApprovedGuidanceLabel(title, guidance, style = MaterialTheme.typography.titleMedium,
            contentColor = MaterialTheme.colorScheme.onSurface)
        rows.forEach { (label, value) ->
            ApprovedDetailRow(label, value, valueColor = color)
        }
    }
}
