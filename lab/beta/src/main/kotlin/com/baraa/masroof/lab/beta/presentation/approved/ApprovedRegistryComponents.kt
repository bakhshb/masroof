package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

@Composable
internal fun ApprovedRegistryItemCard(
    icon: ImageVector,
    bankLabel: String,
    title: String,
    modifier: Modifier = Modifier,
    showBankLabel: Boolean = true,
    endAction: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    accent: MasroofCardAccent = if (icon == MasroofOutlineIcons.CreditCard)
        MasroofCardAccent.Credit else MasroofCardAccent.Account,
) {
    val colors = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors
    val foreground = when (accent) {
        MasroofCardAccent.Credit -> colors.card
        MasroofCardAccent.Liability -> colors.liability
        else -> colors.account
    }
    val background = when (accent) {
        MasroofCardAccent.Credit -> colors.cardSoft
        MasroofCardAccent.Liability -> colors.liabilitySoft
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
                ApprovedText(title, modifier = if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = MasroofFontWeights.semiBold))
                if (showBankLabel) ApprovedText(bankLabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        footer?.invoke()
        endAction?.let { action -> Row(Modifier.padding(top = MasroofSpacing.cardInnerGap)) { action() } }
    }
}

@Composable
internal fun ApprovedManagementAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, danger: Boolean = false, warning: Boolean = false) {
    Surface(onClick = onClick, modifier = modifier, enabled = enabled, shape = MasroofShapes.extraSmall,
        border = androidx.compose.foundation.BorderStroke(MasroofSpacing.borderWidth, MasroofThemeExtras.extendedColors.cardBorder),
        contentColor = when {
            danger -> MasroofThemeExtras.extendedColors.outflow
            warning -> MasroofThemeExtras.extendedColors.liability
            else -> MaterialTheme.colorScheme.primary
        }) {
        ApprovedText(label, Modifier.padding(horizontal = MasroofSpacing.rowHorizontalPadding,
            vertical = MasroofSpacing.rowVerticalPadding), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
internal fun ApprovedRenameAssetDialog(
    targetKey: String?, initialName: String, title: String,
    updating: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    failed: Boolean = false,
) {
    targetKey ?: return
    var name by rememberSaveable(targetKey) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = { if(!updating) onDismiss() },
        title = { ApprovedText(title) },
        text = {
            Column {
            ApprovedFormField(
                visualTransformation = ApprovedDigitsVisualTransformation,
                value = name, enabled = !updating,
                onValueChange = { if(it.length<=120) name = it },
                label = { ApprovedText(stringResource(R.string.beta_approved_settings_display_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("asset-rename-name"),
            )
            if(failed) ApprovedText(stringResource(R.string.beta_asset_save_failed),color=MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            ApprovedTextButton(onClick = { onSave(name) }, enabled = !updating, modifier=Modifier.testTag("asset-rename-save")) {
                ApprovedText(stringResource(R.string.beta_approved_settings_save))
            }
        },
        dismissButton = {
            ApprovedTextButton(onClick = onDismiss, enabled = !updating) {
                ApprovedText(stringResource(R.string.beta_approved_settings_cancel))
            }
        },
    )
}
