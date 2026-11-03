package com.baraa.masroof.presentation.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofFormField as OutlinedTextField
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.presentation.common.formatCardLast4

@Composable
fun SettingsRenameCardDialog(
    target: ManagedCardUi?,
    updating: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    target ?: return
    SettingsRenameDialog(stringResource(R.string.settings_rename_card_title), target.last4, target.displayName, updating, onDismiss, onSave)
}

@Composable
fun SettingsRenameAccountDialog(
    target: ManagedAccountUi?,
    updating: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    target ?: return
    SettingsRenameDialog(
        stringResource(R.string.settings_rename_account_title), target.maskedNumber, target.displayName, updating, onDismiss, onSave,
    )
}

@Composable
private fun SettingsRenameDialog(
    title: String,
    key: String,
    displayName: String?,
    updating: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(key) { mutableStateOf(displayName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                visualTransformation = com.baraa.masroof.presentation.locale.AppDigitsVisualTransformation,
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_display_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = !updating) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
fun cardNetworkLabel(network: CardNetwork): String =
    when (network) {
        CardNetwork.MADA -> stringResource(R.string.card_network_mada)
        CardNetwork.VISA -> stringResource(R.string.card_network_visa)
        CardNetwork.MASTERCARD -> stringResource(R.string.card_network_mastercard)
        CardNetwork.AMEX -> stringResource(R.string.card_network_amex)
        CardNetwork.UNKNOWN -> stringResource(R.string.card_network_unknown)
    }

@Composable
fun cardRoleLabel(role: CardRole?): String =
    when (role) {
        CardRole.PRIMARY -> stringResource(R.string.settings_card_role_primary)
        CardRole.SUPPLEMENTARY -> stringResource(R.string.settings_card_role_additional)
        CardRole.STANDALONE, null -> stringResource(R.string.settings_card_role_standalone)
    }
