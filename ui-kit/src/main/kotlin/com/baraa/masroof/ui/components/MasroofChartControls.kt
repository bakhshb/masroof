package com.baraa.masroof.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.ui.theme.*

/** Equal columns and complete labels in both directions, with an explicit selected color pair. */
@Composable
fun MasroofChartScopeTabs(labels: List<String>, selected: Int, enabled: Boolean = true, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MasroofShapes.small).background(MaterialTheme.colorScheme.background)
        .padding(MasroofReference.scopeTabInset).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MasroofReference.scopeTabInset)) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Box(Modifier.weight(1f).clip(RoundedCornerShape(MasroofReference.scopeTabRadius))
                .background(if (active) MasroofPalette.Primary else MaterialTheme.colorScheme.background)
                .selectable(active, enabled = enabled, role = Role.Tab, onClick = { onSelected(index) })
                .heightIn(min = MasroofSpacing.minimumTouchTarget)
                .padding(horizontal = MasroofReference.scopeTabInset, vertical = MasroofSpacing.sectionHeaderGap)
                .testTag("home-chart-scope-$index"), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(label, Modifier.fillMaxWidth(),
                    color = if (active) MasroofLightColors.OnPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    fontWeight = if (active) MasroofFontWeights.bold else MasroofFontWeights.normal)
            }
        }
    }
}

@Composable
fun MasroofChartAverageLegend(label: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
        Canvas(Modifier.width(MasroofReference.averageLegendWidth).height(MasroofSpacing.smallGap)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                MasroofReference.averageLineStroke.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(MasroofReference.averageDash.toPx(), MasroofReference.averageDash.toPx())))
        }
        androidx.compose.material3.Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
