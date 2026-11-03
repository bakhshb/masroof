package com.baraa.masroof.presentation.common

import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.ui.theme.*

@Composable
fun MasroofMetric(label: String, value: String, color: Color,
    modifier: Modifier = Modifier, guidance: String? = null) {
    com.baraa.masroof.ui.dashboard.DashboardMetric(value, color, modifier) {
        MasroofGuidanceLabel(label, guidance)
    }
}

/** Side-by-side tiles share one height, so their amounts line up when one label wraps. */
@Composable
fun MasroofMetricRow(modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp = MasroofReference.metricGap,
    content: @Composable RowScope.(tile: Modifier) -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
        content(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
fun MasroofAverageBand(value: String, periodHint: String, guidance: String?,
    modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MasroofReference.selectedNavigationColor(),
        shape = MasroofShapes.small) {
        Row(Modifier.padding(MasroofReference.contextualBandPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                MasroofGuidanceLabel(androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.approved_daily_average),
                    guidance, style = MaterialTheme.typography.bodySmall.copy(fontWeight = MasroofFontWeights.semiBold),
                    contentColor = MaterialTheme.colorScheme.onSurface)
                MasroofText(periodHint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MasroofText(value.substringBeforeLast(' '), style = MasroofReference.averageValue)
                MasroofText(androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.reference_average_unit,
                    value.substringAfterLast(' ')), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MasroofReferenceNavigationRow(title: String, subtitle: String, icon: ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true,
    status: String? = null, showChevron: Boolean = true, badges: List<Pair<String, MasroofCardAccent>> = emptyList(),
    subtitleColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
    // Narrow rows (card width, not screen width) stack the badge under the subtitle so neither text is squeezed.
    val stackStatus = maxWidth < MasroofReference.inlineStatusMinWidth
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(top = MasroofReference.rowVertical,
            bottom = if (divider) MasroofReference.rowVertical else MasroofReference.lastRowBottom),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofReference.rowGap)) {
            Surface(shape = MasroofShapes.small, color = MasroofThemeExtras.extendedColors.accountSoft) {
                Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(MasroofReference.icon), tint = MasroofThemeExtras.extendedColors.account)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofReference.metadataGap)) {
                MasroofText(title, style = MasroofReference.pair.copy(fontWeight = MasroofFontWeights.semiBold))
                MasroofText(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant })
                if (stackStatus) status?.let { MasroofBadge(it, MasroofCardAccent.Liability) }
                // Several states wrap under the subtitle; side by side they would squeeze the title.
                if (badges.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap),
                    verticalArrangement = Arrangement.spacedBy(MasroofSpacing.hairlineGap)) {
                    badges.forEach { (text, accent) -> MasroofBadge(text, accent) }
                }
            }
            if (!stackStatus) status?.let { MasroofBadge(it, MasroofCardAccent.Liability, Modifier.widthIn(max = MasroofReference.statusBadgeMaxWidth)) }
            if (showChevron) Icon(if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl)
                MasroofIcons.periodPrevious else MasroofIcons.periodNext,
                null, Modifier.size(MasroofReference.icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(color = MasroofThemeExtras.extendedColors.cardBorder)
    }
    }
}
