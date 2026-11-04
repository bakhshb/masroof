package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofIconSizes

@Composable
internal fun EntitySummaryCard(title: String, subtitle: String, icon: ImageVector, accent: Color,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
    guidance: String? = null,
    bankLabel: String? = null,
    actionLabel: String = androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.reference_details),
    content: @Composable ColumnScope.() -> Unit) {
    // The whole card opens details; actionLabel names the click for accessibility services.
    MasroofCard(modifier.then(if (onClick != null) Modifier.clickable(onClickLabel = actionLabel,
        role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
            com.baraa.masroof.presentation.common.MasroofIconTile(icon, when (accent) {
                com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.card -> com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.cardSoft
                com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.liability -> com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.liabilitySoft
                else -> com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.accountSoft
            }, accent)
            Column(Modifier.weight(1f)) {
                com.baraa.masroof.presentation.common.MasroofGuidanceLabel(title, guidance,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = com.baraa.masroof.ui.theme.MasroofFontWeights.semiBold), contentColor = MaterialTheme.colorScheme.onSurface)
            }
            bankLabel?.let { Text(it, Modifier.widthIn(max = com.baraa.masroof.ui.theme.MasroofReference.statusBadgeMaxWidth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (onClick != null) Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                Modifier.size(MasroofIconSizes.lg), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(com.baraa.masroof.ui.theme.MasroofReference.chartHeadGap))
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
