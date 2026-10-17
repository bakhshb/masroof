package com.baraa.masroof.presentation.common

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
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.baraa.masroof.ui.theme.MasroofCardShape
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.approvedHeroColors
import com.baraa.masroof.ui.theme.MasroofReference
import androidx.compose.ui.graphics.Color

/** Shared hero, amount rows and spacing for every account/card/loan detail. */
@Composable
fun MasroofHeroCard(
    title: String,
    value: String,
    subtitle: String? = null,
    role: MasroofHeroRole = MasroofHeroRole.Brand,
    modifier: Modifier = Modifier,
    guidance: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    com.baraa.masroof.ui.components.MasroofHeroCard(
        title = com.baraa.masroof.application.locale.AppDigits.display(title),
        value = com.baraa.masroof.application.locale.AppDigits.display(value),
        subtitle = subtitle?.let(com.baraa.masroof.application.locale.AppDigits::display),
        role = role, modifier = modifier,
        icon = if (role == MasroofHeroRole.Card) MasroofIcons.cardPayment else MasroofIcons.appLogo,
        titleContent = { titleModifier, foreground ->
            MasroofGuidanceLabel(title, guidance, titleModifier, style = MasroofReference.pair, contentColor = foreground)
        }, footer = footer)
}

@Composable
fun MasroofDetailRow(label: String, value: String, modifier: Modifier = Modifier, guidance: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface, dense: Boolean = false, divider: Boolean = true) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = if (dense) MasroofReference.rowGap else MasroofReference.rowVertical,
                bottom = if (dense) MasroofSpacing.none else MasroofReference.rowVertical),
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MasroofGuidanceLabel(label, guidance, Modifier.weight(1f), style = if (dense) MaterialTheme.typography.bodySmall else MasroofReference.pair,
                contentColor = MaterialTheme.colorScheme.onSurface)
            Text(value, Modifier.weight(1f), style = (if (dense) MaterialTheme.typography.bodySmall else MasroofReference.pair).copy(fontWeight = com.baraa.masroof.ui.theme.MasroofFontWeights.semiBold),
                textAlign = androidx.compose.ui.text.style.TextAlign.End, color = valueColor)
        }
        if (divider && !dense) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
