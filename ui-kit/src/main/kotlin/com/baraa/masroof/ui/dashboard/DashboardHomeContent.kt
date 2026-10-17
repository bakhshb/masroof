package com.baraa.masroof.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.ui.components.MasroofHeroCard
import com.baraa.masroof.ui.theme.MasroofReference

/** Exact production home footer geometry; callbacks navigate, never calculate money. */
@Composable
fun DashboardHomeHero(state: DashboardHeroVisualState, icon: ImageVector,
    onIncome: () -> Unit, onOutflow: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    MasroofHeroCard(title = state.title, value = state.remaining, icon = icon, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.clickable(enabled = enabled, onClick = onIncome), verticalArrangement = Arrangement.spacedBy(MasroofReference.heroFooterGap)) {
                Text(state.inflowLabel, style = MaterialTheme.typography.bodySmall)
                Text(state.inflow, style = MasroofReference.heroFooterValue)
            }
            Column(Modifier.clickable(enabled = enabled, onClick = onOutflow), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(MasroofReference.heroFooterGap)) {
                Text(state.outflowLabel, style = MaterialTheme.typography.bodySmall)
                Text(state.outflow, style = MasroofReference.heroFooterValue)
            }
        }
    }
}
