package com.baraa.masroof.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

/** Production Home geometry with display-only slots; no app models or financial policies. */
@Composable
fun DashboardPeriodHeader(label: String, scope: @Composable RowScope.() -> Unit,
    previousDescription: String, nextDescription: String, onPrevious: () -> Unit, onNext: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, rangeTag: String = "period-range",
    previousTag: String = "period-previous", nextTag: String = "period-next",
    customize: (@Composable () -> Unit)? = null, rangeModifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Surface(modifier.padding(bottom = MasroofSpacing.inlineGap).fillMaxWidth(), shape = MasroofShapes.small,
        border = BorderStroke(MasroofSpacing.borderWidth, MasroofThemeExtras.extendedColors.cardBorder)) {
        Column(Modifier.padding(MasroofSpacing.sectionHeaderGap)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = MasroofReference.periodHeaderHorizontal),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
                    Icon(MasroofOutlineIcons.CalendarDays, null, Modifier.size(MasroofReference.icon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    scope()
                }
                customize?.invoke()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = enabled,
                    modifier = Modifier.size(MasroofSpacing.minimumTouchTarget).testTag(previousTag)) {
                    Icon(if (rtl) MasroofOutlineIcons.ChevronRight else MasroofOutlineIcons.ChevronLeft,
                        previousDescription, Modifier.size(MasroofReference.icon))
                }
                Text(label, Modifier.weight(1f).then(rangeModifier).testTag(rangeTag), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
                IconButton(onClick = onNext, enabled = enabled,
                    modifier = Modifier.size(MasroofSpacing.minimumTouchTarget).testTag(nextTag)) {
                    Icon(if (rtl) MasroofOutlineIcons.ChevronLeft else MasroofOutlineIcons.ChevronRight,
                        nextDescription, Modifier.size(MasroofReference.icon))
                }
            }
        }
    }
}

@Composable
fun DashboardMetric(value: String, color: Color, modifier: Modifier = Modifier,
    label: @Composable () -> Unit) {
    Surface(modifier, shape = MasroofComponentShapes.radius16,
        color = MasroofThemeExtras.extendedColors.cardSurface,
        border = BorderStroke(MasroofSpacing.borderWidth, MasroofThemeExtras.extendedColors.cardBorder)) {
        // A taller sibling tile stretches this one; the amount stays at the bottom so both line up.
        Column(Modifier.fillMaxWidth().padding(MasroofSpacing.compactCardContentPadding + MasroofSpacing.borderWidth),
            verticalArrangement = Arrangement.SpaceBetween) {
            label()
            Column {
                Spacer(Modifier.height(MasroofReference.shortcutGap))
                MasroofReferenceMoney(value, style = MasroofReference.metricValue, color = color)
                Spacer(Modifier.height(MasroofSpacing.hairlineGap))
            }
        }
    }
}

@Composable
fun DashboardSectionHeading(title: String, icon: ImageVector, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            Icon(icon, null, Modifier.size(MasroofIconSizes.sectionHeader), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        if (onAction != null && actionLabel != null) MasroofTextButton(onClick = onAction) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun DashboardCommitmentBand(title: String, waitingLabel: String, pendingLabels: List<String>,
    modifier: Modifier = Modifier, details: (@Composable () -> Unit)? = null) {
    val colors = MasroofThemeExtras.extendedColors
    Column(modifier.padding(top = MasroofReference.sectionHeadingTop - MasroofSpacing.sectionGap),
        verticalArrangement = Arrangement.spacedBy(MasroofReference.sectionHeadingBottom)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Surface(color = colors.liabilitySoft, shape = MasroofComponentShapes.radius14) {
            Row(Modifier.fillMaxWidth().padding(MasroofReference.contextualBandPadding),
                horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap), verticalAlignment = Alignment.Top) {
                Icon(MasroofOutlineIcons.Clock3, null, Modifier.size(MasroofReference.icon), tint = colors.liability)
                Column(Modifier.weight(1f)) {
                    Text(waitingLabel, style = MaterialTheme.typography.bodySmall.copy(fontWeight = MasroofFontWeights.semiBold))
                    pendingLabels.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                    details?.invoke()
                }
            }
        }
    }
}

@Composable
fun DashboardTransactionRow(title: String, subtitle: String, amount: String, dateAndCurrency: String,
    icon: ImageVector, color: Color, background: Color, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(vertical = MasroofReference.rowVertical),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofReference.rowGap)) {
            Surface(shape = MasroofShapes.small, color = background) {
                Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(MasroofReference.icon), tint = color)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.hairlineGap)) {
                Text(title, style = MasroofReference.pair.copy(fontWeight = MasroofFontWeights.semiBold))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, style = MasroofReference.rowAmount, color = color)
                Text(dateAndCurrency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MasroofThemeExtras.extendedColors.cardBorder)
    }
}

/** Same selected-day/average composition used by the production line chart. */
@Composable
fun DashboardTrendHead(date: String, amount: String, modifier: Modifier = Modifier,
    average: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = MasroofReference.chartHeadGap), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.none)) {
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MasroofReferenceMoney(amount, style = MasroofReference.selectedAmount)
        }
        average?.invoke(this)
    }
}

/** Original production chart menu glyph (Material MoreHoriz, 24 x 24). */
val DashboardChartMenuIcon: ImageVector by lazy {
    ImageVector.Builder("MoreHoriz", androidx.compose.ui.unit.Dp(24f), androidx.compose.ui.unit.Dp(24f), 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
        ).toNodes(), fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
    }.build()
}
