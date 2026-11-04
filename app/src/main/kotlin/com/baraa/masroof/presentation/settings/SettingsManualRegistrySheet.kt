package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardConfiguration
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.ui.theme.MasroofSpacing

/** Both steps are drafts. Only the final Save may create an owned registry entry. */
@Composable
internal fun SettingsManualRegistrySheet(bankId: String, card: Boolean, state: SettingsUiState,
    onDismiss: () -> Unit, onSaveAccount: (String, String?) -> Unit,
    onSaveCard: (String, String?, CardConfiguration) -> Unit) {
    var last4 by rememberSaveable(bankId, card) { mutableStateOf("") }
    var name by rememberSaveable(bankId, card) { mutableStateOf("") }
    var owned by rememberSaveable(bankId, card) { mutableStateOf(false) }
    var configuringCard by rememberSaveable(bankId, card) { mutableStateOf(false) }
    if (configuringCard) {
        SettingsCardSetupSheet(ManagedCardUi("", Bank(bankId), last4, OwnershipStatus.OWNED, name), null,
            state.followedCards, state.followedAccounts, state.updating, state.error != null, onDismiss,
            { configuration -> onSaveCard(last4, name, configuration) }, settingsErrorRes(state.error))
        return
    }
    MasroofBottomSheet(stringResource(if (card) R.string.settings_add_card_manual else R.string.settings_add_account_manual),
        onDismiss, { if (card) configuringCard = true else onSaveAccount(last4, name) },
        stringResource(if (card) R.string.teaching_next else R.string.settings_save),
        subtitle = settingsBankLabel(Bank(bankId)), canApply = owned && last4.length == 4, busy = state.updating) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofFormField(last4, { value -> last4 = value.filter(Char::isDigit).take(4) },
                label = { MasroofText(stringResource(R.string.settings_manual_last4)) }, enabled = !state.updating,
                trailingIcon = { MasroofGuidanceButton(stringResource(if (card) R.string.settings_add_card_manual else R.string.settings_add_account_manual),
                    stringResource(R.string.settings_manual_registry_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manual-last4"), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            MasroofFormField(name, { name = it }, label = { MasroofText(stringResource(R.string.settings_manual_name)) },
                enabled = !state.updating, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manual-name"))
            Row(Modifier.fillMaxWidth().toggleable(owned, enabled = !state.updating, role = Role.Checkbox) { owned = it }) {
                Checkbox(owned, null, enabled = !state.updating)
                MasroofText(stringResource(if (card) R.string.settings_manual_card_owned else R.string.pattern_financial_account_owned))
            }
            if (state.error != null) MasroofText(stringResource(settingsErrorRes(state.error, R.string.settings_manual_registry_failed)), color = MaterialTheme.colorScheme.error)
        }
    }
}
