package com.baraa.masroof.presentation.settings

import com.baraa.masroof.ui.theme.MasroofOpacity
import com.baraa.masroof.ui.theme.MasroofSizes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import com.baraa.masroof.ui.theme.MasroofIconSizes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofCardAccent
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofTextStyles

/**
 * Registry row for Arabic RTL: identity on the right (start), action on the left (end).
 */
@Composable
fun SettingsRegistryItemCard(
    icon: ImageVector,
    bank: Bank,
    title: String,
    modifier: Modifier = Modifier,
    showBankLabel: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    headerAction: (@Composable () -> Unit)? = null,
    accent: com.baraa.masroof.presentation.common.MasroofCardAccent = if (icon == MasroofIcons.cardPayment)
        com.baraa.masroof.presentation.common.MasroofCardAccent.Credit else com.baraa.masroof.presentation.common.MasroofCardAccent.Account,
) {
    val colors = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors
    val foreground = when (accent) {
        com.baraa.masroof.presentation.common.MasroofCardAccent.Credit -> colors.card
        com.baraa.masroof.presentation.common.MasroofCardAccent.Liability -> colors.liability
        else -> colors.account
    }
    val background = when (accent) {
        com.baraa.masroof.presentation.common.MasroofCardAccent.Credit -> colors.cardSoft
        com.baraa.masroof.presentation.common.MasroofCardAccent.Liability -> colors.liabilitySoft
        else -> colors.accountSoft
    }
    MasroofCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.rowGap)) {
            androidx.compose.material3.Surface(shape = com.baraa.masroof.ui.theme.MasroofShapes.small,
                color = background) {
                Box(Modifier.size(com.baraa.masroof.ui.theme.MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = foreground,
                        modifier = Modifier.size(com.baraa.masroof.ui.theme.MasroofReference.icon))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, modifier = if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier, style = MasroofTextStyles.cardTitle)
                if (showBankLabel) Text(settingsBankLabel(bank), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            headerAction?.invoke()
        }
        footer?.invoke()
    }
}

internal class SettingsRowAction(val label: String, val tag: String? = null, val danger: Boolean = false, val onClick: () -> Unit)

/** The ⋮ menu every registry row uses, so accounts, cards and loans look and act the same. */
@Composable
internal fun SettingsRegistryMoreMenu(contentDescription: String, enabled: Boolean, actions: List<SettingsRowAction>) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("registry-more-actions")) {
            Icon(MasroofIcons.more, contentDescription, Modifier.size(MasroofIconSizes.md))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label, color = if (action.danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
                    onClick = { menu = false; action.onClick() },
                    enabled = enabled,
                    modifier = action.tag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        }
    }
}

/** Track again and Delete, for a row on the Stopped tab. */
@Composable
internal fun SettingsStoppedItemMenu(title: String, enabled: Boolean, onResume: () -> Unit, onDelete: () -> Unit) {
    SettingsRegistryMoreMenu(stringResource(R.string.settings_item_more_actions, title), enabled, listOf(
        SettingsRowAction(stringResource(R.string.settings_action_resume_short), "registry-resume-action", onClick = onResume),
        SettingsRowAction(stringResource(R.string.registry_delete), "registry-delete-action", danger = true, onClick = onDelete),
    ))
}

/** Delete only, for a row on the Unconfirmed tab whose main question stays in the card. */
@Composable
internal fun SettingsDeleteOnlyMenu(title: String, enabled: Boolean, onDelete: () -> Unit) {
    SettingsRegistryMoreMenu(stringResource(R.string.settings_item_more_actions, title), enabled, listOf(
        SettingsRowAction(stringResource(R.string.registry_delete), "registry-delete-action", danger = true, onClick = onDelete),
    ))
}

@Composable
fun settingsBankLabel(bank: Bank): String =
    LocalSettingsBankNames.current[bank.id] ?: if (bank == Bank.BANK_ALJAZIRA) {
        stringResource(R.string.bank_aljazira)
    } else if (bank == Bank.UNKNOWN) {
        stringResource(R.string.bank_unknown)
    } else bank.id

internal val LocalSettingsBankNames = androidx.compose.runtime.staticCompositionLocalOf<Map<String, String>> { emptyMap() }
