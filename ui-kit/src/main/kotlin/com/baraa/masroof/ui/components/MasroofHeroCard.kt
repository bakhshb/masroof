package com.baraa.masroof.ui.components

import com.baraa.masroof.ui.theme.MasroofOpacity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.baraa.masroof.ui.theme.MasroofCardShape
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.approvedHeroColors
import com.baraa.masroof.ui.theme.MasroofReference
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.vector.ImageVector

/** Shared hero geometry; formatted text and optional title interaction come from the host. */

@Composable
fun MasroofHeroCard(
    title: String,
    value: String,
    subtitle: String? = null,
    role: MasroofHeroRole = MasroofHeroRole.Brand,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    titleContent: (@Composable (Modifier, Color) -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val (background, foreground) = approvedHeroColors(role)
    Surface(modifier.fillMaxWidth(), shape = MasroofCardShape, color = background, contentColor = foreground) {
        Column(Modifier.padding(MasroofReference.heroPadding)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                if (titleContent != null) titleContent(Modifier.weight(1f), foreground)
                else Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MasroofReference.pair, color = foreground)
                }
                Icon(icon,
                    null, Modifier.size(MasroofReference.icon), tint = foreground)
            }
            Spacer(Modifier.height(MasroofSpacing.carouselGap))
            MasroofReferenceMoney(value, style = MaterialTheme.typography.displayMedium, color = foreground)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(MasroofSpacing.inlineGap))
                Text(it, style = MaterialTheme.typography.labelSmall, color = foreground)
            }
            if (footer != null) {
                Spacer(Modifier.height(MasroofSpacing.cardInnerPadding))
                HorizontalDivider(color = foreground.copy(alpha = MasroofOpacity.divider))
                Spacer(Modifier.height(MasroofReference.shortcutVertical))
                footer()
            }
        }
    }
}
