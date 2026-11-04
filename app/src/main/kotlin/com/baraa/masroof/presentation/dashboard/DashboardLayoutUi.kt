package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofOpacity
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofComponentShapes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baraa.masroof.application.dashboard.DashboardSectionSize
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@Composable
fun Modifier.dashboardSectionFrame(
    size: DashboardSectionSize,
    editing: Boolean,
): Modifier {
    if (!editing) return this
    val extended = MasroofThemeExtras.extendedColors
    val padding = if (size == DashboardSectionSize.LARGE) MasroofSpacing.hairlineGap else MasroofSpacing.none
    return this
        .padding(vertical = padding)
        .border(MasroofSpacing.borderWidth, extended.inflow.copy(alpha = MasroofOpacity.subtle), MasroofComponentShapes.radius16)
}
