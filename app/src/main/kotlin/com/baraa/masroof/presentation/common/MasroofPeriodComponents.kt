package com.baraa.masroof.presentation.common

import com.baraa.masroof.ui.components.MasroofOutlineIcons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.R
import com.baraa.masroof.ui.theme.*

@Composable
fun MasroofPeriodPill(label: String, onPrevious: () -> Unit, onNext: () -> Unit,
    modifier: Modifier = Modifier, previousContentDescription: String? = null,
    nextContentDescription: String? = null, onCustomize: (() -> Unit)? = null,
    customizeLabel: String? = null, guidance: String? = null) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val previous = previousContentDescription ?: stringResource(R.string.dashboard_period_previous)
    val next = nextContentDescription ?: stringResource(R.string.dashboard_period_next)
    Surface(modifier = modifier.padding(bottom = MasroofSpacing.inlineGap).fillMaxWidth(), shape = MasroofShapes.small,
        border = BorderStroke(MasroofSpacing.borderWidth, MasroofThemeExtras.extendedColors.cardBorder)) {
        Column(Modifier.padding(MasroofSpacing.sectionHeaderGap)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = MasroofReference.periodHeaderHorizontal),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
                    Icon(MasroofOutlineIcons.CalendarDays, null, Modifier.size(MasroofReference.icon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    MasroofText(stringResource(R.string.dashboard_salary_period_short),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    guidance?.let { MasroofGuidanceButton(label, it) }
                }
                if (onCustomize != null && customizeLabel != null) TextButton(onClick = onCustomize,
                    modifier = Modifier.heightIn(min = MasroofSpacing.minimumTouchTarget).testTag("dashboard-customize")) {
                    Icon(MasroofIcons.customize, null, Modifier.size(MasroofReference.icon))
                    MasroofText(customizeLabel, Modifier.padding(start = MasroofSpacing.smallGap),
                        style = MaterialTheme.typography.titleSmall)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(MasroofSpacing.minimumTouchTarget).testTag("period-previous")) {
                    Icon(if (rtl) MasroofOutlineIcons.ChevronRight else MasroofOutlineIcons.ChevronLeft, previous,
                        Modifier.size(MasroofReference.icon))
                }
                MasroofText(label, Modifier.weight(1f).testTag("period-range"), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
                IconButton(onClick = onNext, modifier = Modifier.size(MasroofSpacing.minimumTouchTarget).testTag("period-next")) {
                    Icon(if (rtl) MasroofOutlineIcons.ChevronLeft else MasroofOutlineIcons.ChevronRight, next,
                        Modifier.size(MasroofReference.icon))
                }
            }
        }
    }
}

@Composable
fun MasroofPeriodDisplay(label: String, modifier: Modifier = Modifier, guidance: String? = null) {
    Surface(modifier.padding(bottom = MasroofSpacing.inlineGap).fillMaxWidth(), shape = MasroofShapes.small,
        border = BorderStroke(MasroofSpacing.borderWidth, MasroofThemeExtras.extendedColors.cardBorder)) {
        ReferencePeriodContent(label, guidance)
    }
}

@Composable
private fun ReferencePeriodContent(label: String, guidance: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = MasroofSpacing.periodDisplayHorizontalPadding + MasroofSpacing.borderWidth,
        vertical = MasroofReference.navigationItemVertical + MasroofSpacing.borderWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Icon(MasroofOutlineIcons.CalendarDays, null, Modifier.size(MasroofReference.icon))
        // Reserve the trailing label before measuring a long translated date.
        // Otherwise it can receive zero width and make the entire toolbar screen-height.
        MasroofText(label, modifier = Modifier.weight(1f).padding(horizontal = MasroofSpacing.inlineGap),
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MasroofText(stringResource(R.string.dashboard_salary_period_short), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            guidance?.let { MasroofGuidanceButton(label, it) }
        }
    }
}
