package com.baraa.masroof.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

import com.baraa.masroof.ui.theme.MasroofGuidanceStyle
import com.baraa.masroof.ui.theme.MasroofSpacing

/** Positions below its subject, flips above when needed and stays inside the window. */
class MasroofGuidancePositionProvider(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val marginX = margin.coerceAtMost(maxX / 2)
        val marginY = margin.coerceAtMost(maxY / 2)
        val preferredX = if (layoutDirection == LayoutDirection.Rtl) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val below = anchorBounds.bottom + gap
        val preferredY = if (below <= maxY - marginY) below else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(preferredX.coerceIn(marginX, maxX - marginX), preferredY.coerceIn(marginY, maxY - marginY))
    }
}

/** Contextual help, never an alert: dismisses on outside press or Back. */
@Composable
fun MasroofGuidanceButton(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    description: String = title,
) {
    if (message.isBlank()) return
    // Not keyed on the text: a refresh that rewrites the help must not close it while it is being read.
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val position = remember(density) {
        with(density) { MasroofGuidancePositionProvider(MasroofGuidanceStyle.anchorGap.roundToPx(), MasroofGuidanceStyle.windowMargin.roundToPx()) }
    }
    Box(modifier.size(MasroofGuidanceStyle.iconSize)) {
        // requiredSize overflows the icon slot evenly, so the touch target grows without moving the row.
        Box(modifier = Modifier.requiredSize(MasroofSpacing.minimumTouchTarget).clickable(role = Role.Button) { open = !open },
            contentAlignment = Alignment.Center) {
            Icon(MasroofOutlineIcons.Info, description,
                tint = contentColor, modifier = Modifier.size(MasroofGuidanceStyle.iconSize))
        }
        if (open) {
            Popup(popupPositionProvider = position, onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)) {
                Surface(modifier = Modifier.widthIn(max = MasroofGuidanceStyle.maxWidth).testTag("guidance-popup"),
                    shape = MasroofGuidanceStyle.shape, color = MasroofGuidanceStyle.backgroundColor,
                    border = BorderStroke(MasroofSpacing.borderWidth, MasroofGuidanceStyle.borderColor),
                    contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = MasroofGuidanceStyle.elevation) {
                    Column(Modifier.padding(MasroofGuidanceStyle.contentPadding),
                        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                        androidx.compose.material3.Text(title, style = MaterialTheme.typography.titleSmall)
                        androidx.compose.material3.Text(message, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

