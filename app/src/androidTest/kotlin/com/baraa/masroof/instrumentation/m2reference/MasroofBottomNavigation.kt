// Frozen pre-extraction reference from main 5e9ec27. Test-only; never a runtime dependency.
package com.baraa.masroof.instrumentation.m2reference

import com.baraa.masroof.presentation.common.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.baraa.masroof.ui.theme.MasroofReference
import com.baraa.masroof.ui.theme.MasroofShapes
import com.baraa.masroof.ui.theme.MasroofThemeExtras

data class MasroofNavigationItem(val label: String, val icon: ImageVector,
    val selected: Boolean, val description: String = label)

@Composable
fun MasroofBottomNavigation(items: List<MasroofNavigationItem>, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MasroofThemeExtras.extendedColors.cardBorder)
            Row(Modifier.fillMaxWidth().padding(start = MasroofReference.navigationHorizontal,
                end = MasroofReference.navigationHorizontal, top = MasroofReference.navigationTop,
                bottom = MasroofReference.navigationBottom),
                horizontalArrangement = Arrangement.spacedBy(MasroofReference.navigationGap)) {
                items.forEachIndexed { index, item ->
                    val color = if (item.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(modifier = Modifier.weight(1f).semantics {
                        contentDescription = item.description; selected = item.selected
                    }, color = if (item.selected) MasroofReference.selectedNavigationColor() else Color.Transparent,
                        shape = MasroofShapes.small) {
                        Column(Modifier.clickable { onSelect(index) }.padding(vertical = MasroofReference.navigationItemPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MasroofReference.navigationGap)) {
                            Icon(item.icon, null, Modifier.size(MasroofReference.icon), tint = color)
                            MasroofText(item.label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (item.selected) com.baraa.masroof.ui.theme.MasroofFontWeights.semiBold else com.baraa.masroof.ui.theme.MasroofFontWeights.normal), color = color)
                        }
                    }
                }
            }
        }
    }
}
