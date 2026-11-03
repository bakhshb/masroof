package com.baraa.masroof.presentation.settings

import com.baraa.masroof.ui.theme.MasroofOpacity
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofSizes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofOutlinedButton as OutlinedButton
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    endAction: (@Composable () -> Unit)? = null,
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
        endAction?.let { action -> Row(Modifier.padding(top = MasroofSpacing.cardInnerGap)) { action() } }
    }
}

@Composable
fun SettingsStopTrackingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String,
) {
    com.baraa.masroof.presentation.common.MasroofManagementAction(stringResource(R.string.reference_stop_tracking),
        onClick, modifier.semantics { this.contentDescription = contentDescription }, enabled, warning = true)
}

@Composable
fun SettingsResumeTrackingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(
            imageVector = MasroofIcons.success,
            contentDescription = null,
            modifier = Modifier.size(MasroofSpacing.screenHorizontal),
        )
        Spacer(Modifier.width(MasroofSpacing.inlineGap))
        Text(
            stringResource(R.string.settings_action_resume_short),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun settingsBankLabel(bank: Bank): String =
    LocalSettingsBankNames.current[bank.id] ?: if (bank == Bank.BANK_ALJAZIRA) {
        stringResource(R.string.bank_aljazira)
    } else if (bank == Bank.UNKNOWN) {
        stringResource(R.string.bank_unknown)
    } else bank.id

internal val LocalSettingsBankNames = androidx.compose.runtime.staticCompositionLocalOf<Map<String, String>> { emptyMap() }
