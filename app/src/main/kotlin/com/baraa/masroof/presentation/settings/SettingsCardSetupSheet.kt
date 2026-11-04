package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.presentation.common.MasroofBottomSheet
import com.baraa.masroof.presentation.common.MasroofSheetChoices
import com.baraa.masroof.presentation.common.MasroofText
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
fun SettingsCardSetupSheet(
    target: ManagedCardUi?, suggestion: CardType?, cards: List<ManagedCardUi>, accounts: List<ManagedAccountUi>,
    busy: Boolean, failed: Boolean, onDismiss: () -> Unit, onSave: (CardConfiguration) -> Unit,
    failureRes: Int = R.string.settings_update_failed,
) {
    target ?: return
    key(target.bank.id, target.last4) {
        var networkName by rememberSaveable { mutableStateOf(target.cardNetwork?.name ?: CardNetwork.UNKNOWN.name) }
        var typeName by rememberSaveable { mutableStateOf(target.cardType?.name ?: suggestion?.name) }
        var roleName by rememberSaveable { mutableStateOf(target.cardRole?.name ?: CardRole.STANDALONE.name) }
        var primary by rememberSaveable { mutableStateOf(target.parentCardLast4) }
        var account by rememberSaveable { mutableStateOf(target.linkedAccountMaskedNumber) }
        val network = CardNetwork.valueOf(networkName)
        val type = typeName?.let(CardType::valueOf)
        val role = CardRole.valueOf(roleName)
        val sameBankCards = cards.filter { it.bank == target.bank }
        val parents = sameBankCards.filter { it.last4 != target.last4 && it.cardType == CardType.CREDIT && it.cardRole == CardRole.PRIMARY && it.ownership == OwnershipStatus.OWNED }
        val availableAccounts = accounts.filter { it.bank == target.bank && it.ownership == OwnershipStatus.OWNED }
        val hasDependents = sameBankCards.any { it.parentCardLast4 == target.last4 }
        val blocksDependents = hasDependents && (type != CardType.CREDIT || role != CardRole.PRIMARY)
        val valid = type != null && !blocksDependents && (network != CardNetwork.MADA || type == CardType.DEBIT) &&
            (type != CardType.CREDIT || role != CardRole.SUPPLEMENTARY || parents.any { it.last4 == primary }) &&
            (type != CardType.DEBIT || account == null || availableAccounts.any { it.maskedNumber == account })
        MasroofBottomSheet(title = stringResource(R.string.settings_card_setup),
            subtitle = stringResource(R.string.dashboard_credit_card_last4, com.baraa.masroof.application.locale.AppDigits.display(target.last4)),
            busy = busy, canApply = valid,
            applyLabel = stringResource(R.string.settings_save), onDismiss = onDismiss,
            onApply = {
                type?.let { chosen -> onSave(CardConfiguration(
                    network.takeUnless { it == CardNetwork.UNKNOWN }, chosen,
                    if (chosen == CardType.CREDIT) role else null,
                    if (chosen == CardType.CREDIT && role == CardRole.SUPPLEMENTARY) primary else null,
                    if (chosen == CardType.DEBIT) account?.let { AccountReference(target.bank, it) } else null,
                )) }
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                MasroofText(stringResource(R.string.settings_card_network_title), style = MaterialTheme.typography.titleSmall)
                MasroofSheetChoices(CardNetwork.entries, setOf(network), { cardNetworkLabel(it) }, {
                    networkName = it.name
                    if (it == CardNetwork.MADA) typeName = CardType.DEBIT.name
                }, { "card-network-${it.name}" })
                MasroofText(stringResource(R.string.settings_card_type_title), style = MaterialTheme.typography.titleSmall)
                suggestion?.let { MasroofText(stringResource(if (it == CardType.CREDIT) R.string.settings_card_credit_evidence else R.string.settings_card_debit_evidence)) }
                val types = if (network == CardNetwork.MADA) listOf(CardType.DEBIT) else CardType.entries
                MasroofSheetChoices(types, setOfNotNull(type), {
                    stringResource(if (it == CardType.CREDIT) R.string.settings_card_type_credit else R.string.settings_card_type_debit)
                }, { typeName = it.name }, { "card-type-${it.name}" })
                if (type == CardType.CREDIT) {
                    MasroofText(stringResource(R.string.settings_card_role_title), style = MaterialTheme.typography.titleSmall)
                    MasroofSheetChoices(CardRole.entries, setOf(role), { cardRoleLabel(it) }, { roleName = it.name }, { "card-role-${it.name}" })
                    if (role == CardRole.SUPPLEMENTARY) {
                        MasroofText(stringResource(R.string.settings_card_primary_title))
                        if (parents.isEmpty()) MasroofText(stringResource(R.string.settings_card_no_primary))
                        MasroofSheetChoices(parents.map { it.last4 }, setOfNotNull(primary), { last4 -> registryLabel(parents.single { it.last4 == last4 }) },
                            { primary = it }, { "card-primary-$it" })
                    }
                }
                if (type == CardType.DEBIT) {
                    MasroofText(stringResource(R.string.settings_link_debit_title), style = MaterialTheme.typography.titleSmall)
                    val options = listOf("") + availableAccounts.map { it.maskedNumber }
                    MasroofSheetChoices(options, setOf(account ?: ""), { masked ->
                        if (masked.isEmpty()) stringResource(R.string.settings_card_no_account)
                        else registryLabel(availableAccounts.single { it.maskedNumber == masked })
                    }, { account = it.ifEmpty { null } }, { "card-account-$it" })
                }
                if (blocksDependents) MasroofText(stringResource(R.string.settings_card_dependents_block), color = MaterialTheme.colorScheme.error)
                if (failed) MasroofText(stringResource(failureRes), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
