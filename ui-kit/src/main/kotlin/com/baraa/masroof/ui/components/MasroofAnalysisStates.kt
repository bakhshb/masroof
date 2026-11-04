package com.baraa.masroof.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.ui.theme.*

@Composable
fun MasroofAnalysisEmptyState(title: String, description: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(MasroofSpacing.largeGap), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        Box(Modifier.size(MasroofSpacing.heroIconSize).background(MasroofThemeExtras.extendedColors.hintBackground, MasroofShapes.small), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(MasroofIconSizes.xl), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MasroofTimelineRow(title: String, subtitle: String, modifier: Modifier = Modifier, active: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        Box(Modifier.padding(top = MasroofSpacing.smallGap).size(MasroofSpacing.carouselGap)
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, MasroofBadgeShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
