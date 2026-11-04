package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.presentation.common.MasroofTextStyles

object SettingsSpacing {
    val sectionGap = MasroofSpacing.sectionGap
    val groupGap = MasroofSpacing.listItemGap
}

@Composable
fun SettingsGroupTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        modifier = modifier,
        style = MasroofTextStyles.sectionTitle,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun SettingsScreenHeader(
    bank: Bank,
    hint: String,
    modifier: Modifier = Modifier,
    onOpenBank: (() -> Unit)? = null,
) {
    com.baraa.masroof.presentation.common.MasroofCard(modifier.then(if (onOpenBank != null) Modifier.clickable(onClick = onOpenBank) else Modifier)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            com.baraa.masroof.presentation.common.MasroofIconTile(com.baraa.masroof.presentation.common.MasroofIcons.moneyMovement,
                com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.accountSoft,
                com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.account)
            com.baraa.masroof.presentation.common.MasroofGuidanceLabel(settingsBankLabel(bank), hint,
                style = MasroofTextStyles.screenLabel, contentColor = MaterialTheme.colorScheme.onSurface)
        }
    }
}
