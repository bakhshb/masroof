// Frozen pre-extraction reference from main 5e9ec27. Test-only; never a runtime dependency.
package com.baraa.masroof.instrumentation.m2reference

import com.baraa.masroof.presentation.common.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.baraa.masroof.ui.theme.*

data class MasroofShortcutItem(val label: String, val icon: ImageVector,
    val accent: MasroofCardAccent, val onClick: () -> Unit)

/** A single shared row; narrow windows scroll instead of breaking translated labels. */
@Composable
fun MasroofShortcutStrip(items: List<MasroofShortcutItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val colors = MasroofThemeExtras.extendedColors
    Surface(modifier.fillMaxWidth(), shape = MasroofCardShape, color = colors.cardSurface,
        border = BorderStroke(MasroofSpacing.borderWidth, colors.cardBorder)) {
        BoxWithConstraints {
            val available = maxWidth - MasroofReference.shortcutHorizontal * 2 - MasroofSpacing.borderWidth * (items.size - 1)
            val minimum = MasroofReference.shortcutMinWidth * androidx.compose.ui.platform.LocalDensity.current.fontScale
            val cellWidth = maxOf(minimum, available / items.size)
            Row(Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = MasroofReference.shortcutHorizontal), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { index, item ->
                    val tint = when (item.accent) {
                        MasroofCardAccent.Account -> colors.account
                        MasroofCardAccent.Credit -> colors.card
                        else -> colors.liability
                    }
                    val background = when (item.accent) {
                        MasroofCardAccent.Account -> colors.accountSoft
                        MasroofCardAccent.Credit -> colors.cardSoft
                        else -> colors.liabilitySoft
                    }
                    Column(Modifier.width(cellWidth).testTag("dashboard-shortcut-$index")
                        .clickable(role = Role.Button, onClick = item.onClick)
                        .padding(vertical = MasroofReference.shortcutVertical + MasroofSpacing.borderWidth),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MasroofReference.shortcutGap)) {
                        Surface(shape = MasroofShapes.small, color = background) {
                            Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                                Icon(item.icon, null, Modifier.size(MasroofReference.icon), tint = tint)
                            }
                        }
                        MasroofText(item.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false)
                    }
                    if (index != items.lastIndex) VerticalDivider(
                        Modifier.height(MasroofReference.shortcutSeparatorHeight),
                        thickness = MasroofSpacing.borderWidth, color = colors.cardBorder)
                }
            }
        }
    }
}
