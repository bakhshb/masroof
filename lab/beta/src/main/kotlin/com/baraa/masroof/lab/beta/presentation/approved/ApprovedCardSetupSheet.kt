package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.*
import androidx.compose.foundation.layout.ColumnScope
import com.baraa.masroof.lab.beta.presentation.MasroofSheetChoices
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
internal fun ApprovedCardSetupSheet(
    target: BetaManagedCardUi?, suggestion: AssetKind?, cards: List<BetaManagedCardUi>, accounts: List<BetaManagedAccountUi>,
    busy: Boolean, failed: Boolean, onDismiss: () -> Unit, onSave: (BetaCardConfiguration) -> Unit,
    additionalValid: Boolean = true,extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    target ?: return
    key(target.bank.value, target.id.localId) {
        var networkName by rememberSaveable { mutableStateOf(target.cardNetwork?.name ?: BetaCardNetwork.UNKNOWN.name) }
        var typeName by rememberSaveable { mutableStateOf(target.cardType?.name ?: suggestion?.name) }
        var roleName by rememberSaveable { mutableStateOf(target.cardRole?.name ?: CreditCardRole.STANDALONE.name) }
        var primary by rememberSaveable { mutableStateOf(target.parentCardId?.localId) }
        var account by rememberSaveable { mutableStateOf(target.linkedAccountId?.localId) }
        val network = BetaCardNetwork.valueOf(networkName)
        val type = typeName?.let(AssetKind::valueOf)
        val role = CreditCardRole.valueOf(roleName)
        val sameBankCards = cards.filter { it.bank == target.bank }
        val parents = sameBankCards.filter { it.id != target.id && it.cardType == AssetKind.CREDIT_CARD && it.cardRole == CreditCardRole.PRIMARY }
        val availableAccounts = accounts.filter { it.bank == target.bank }
        val hasDependents = sameBankCards.any { it.parentCardId == target.id }
        val blocksDependents = hasDependents && (type != AssetKind.CREDIT_CARD || role != CreditCardRole.PRIMARY)
        val valid = type != null && !blocksDependents && (network != BetaCardNetwork.MADA || type == AssetKind.DEBIT_CARD) &&
            (type != AssetKind.CREDIT_CARD || role != CreditCardRole.SUPPLEMENTARY || parents.any { it.id.localId == primary }) &&
            (type != AssetKind.DEBIT_CARD || account == null || availableAccounts.any { it.id.localId == account })
        ApprovedBottomSheet(title = stringResource(R.string.beta_approved_settings_card_setup),
            subtitle = stringResource(R.string.beta_approved_dashboard_credit_card_last4, BetaDisplayDigits.display(target.last4)),
            busy = busy, canApply = valid && additionalValid,sheetTag="dash-sheet",bodyTag="dash-sheet-body",applyTag="asset-save",cancelTag="asset-cancel",
            applyLabel = stringResource(R.string.beta_approved_settings_save), onDismiss = onDismiss,
            onApply = {
                type?.let { chosen -> onSave(BetaCardConfiguration(
                    network.takeUnless { it == BetaCardNetwork.UNKNOWN }, chosen,
                    if (chosen == AssetKind.CREDIT_CARD) role else null,
                    if (chosen == AssetKind.CREDIT_CARD && role == CreditCardRole.SUPPLEMENTARY) parents.singleOrNull {it.id.localId==primary}?.id else null,
                    if (chosen == AssetKind.DEBIT_CARD) availableAccounts.singleOrNull {it.id.localId==account}?.id else null,
                )) }
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                extraContent()
                ApprovedText(stringResource(R.string.beta_approved_settings_card_network_title), style = MaterialTheme.typography.titleSmall)
                MasroofSheetChoices(BetaCardNetwork.entries, setOf(network), { approvedCardNetworkLabel(it) }, {
                    networkName = it.name
                    if (it == BetaCardNetwork.MADA) typeName = AssetKind.DEBIT_CARD.name
                }, { "asset-network-${it.name}" },enabled=!busy)
                ApprovedText(stringResource(R.string.beta_approved_settings_card_type_title), style = MaterialTheme.typography.titleSmall)
                suggestion?.let { ApprovedText(stringResource(if (it == AssetKind.CREDIT_CARD) R.string.beta_approved_settings_card_credit_evidence else R.string.beta_approved_settings_card_debit_evidence)) }
                val types = if (network == BetaCardNetwork.MADA) listOf(AssetKind.DEBIT_CARD) else listOf(AssetKind.DEBIT_CARD,AssetKind.CREDIT_CARD)
                MasroofSheetChoices(types, setOfNotNull(type), {
                    stringResource(if (it == AssetKind.CREDIT_CARD) R.string.beta_approved_settings_card_type_credit else R.string.beta_approved_settings_card_type_debit)
                }, { typeName = it.name }, { "asset-kind-${it.name}" },enabled=!busy)
                if (type == AssetKind.CREDIT_CARD) {
                    ApprovedText(stringResource(R.string.beta_approved_settings_card_role_title), style = MaterialTheme.typography.titleSmall)
                    MasroofSheetChoices(CreditCardRole.entries, setOf(role), { approvedCardRoleLabel(it) }, { roleName = it.name }, { "asset-role-${it.name}" },enabled=!busy)
                    if (role == CreditCardRole.SUPPLEMENTARY) {
                        ApprovedText(stringResource(R.string.beta_approved_settings_card_primary_title))
                        if (parents.isEmpty()) ApprovedText(stringResource(R.string.beta_approved_settings_card_no_primary))
                        MasroofSheetChoices(parents.map { it.id.localId }, setOfNotNull(primary), { last4 -> approvedRegistryLabel(parents.single { it.id.localId == last4 }) },
                            { primary = it }, { "asset-primary-$it" },enabled=!busy)
                    }
                }
                if (type == AssetKind.DEBIT_CARD) {
                    ApprovedText(stringResource(R.string.beta_approved_settings_link_debit_title), style = MaterialTheme.typography.titleSmall)
                    val options = listOf("") + availableAccounts.map { it.id.localId }
                    MasroofSheetChoices(options, setOf(account ?: ""), { masked ->
                        if (masked.isEmpty()) stringResource(R.string.beta_approved_settings_card_no_account)
                        else approvedRegistryLabel(availableAccounts.single { it.id.localId == masked })
                    }, { account = it.ifEmpty { null } }, { "asset-parent-${it.ifEmpty {"none"}}" },enabled=!busy)
                }
                if (blocksDependents) ApprovedText(stringResource(R.string.beta_approved_settings_card_dependents_block), color = MaterialTheme.colorScheme.error)
                if (failed) ApprovedText(stringResource(R.string.beta_approved_settings_update_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable private fun approvedCardNetworkLabel(network: BetaCardNetwork): String = stringResource(when(network) {
    BetaCardNetwork.MADA->R.string.beta_approved_card_network_mada;BetaCardNetwork.VISA->R.string.beta_approved_card_network_visa
    BetaCardNetwork.MASTERCARD->R.string.beta_approved_card_network_mastercard;BetaCardNetwork.AMEX->R.string.beta_approved_card_network_amex
    BetaCardNetwork.UNKNOWN->R.string.beta_approved_card_network_unknown
})
@Composable private fun approvedCardRoleLabel(role: CreditCardRole): String = stringResource(when(role) {
    CreditCardRole.PRIMARY->R.string.beta_approved_settings_card_role_primary;CreditCardRole.SUPPLEMENTARY->R.string.beta_approved_settings_card_role_additional
    CreditCardRole.STANDALONE->R.string.beta_approved_settings_card_role_standalone
})
@Composable private fun approvedRegistryLabel(card: BetaManagedCardUi): String =
    card.displayName ?: stringResource(R.string.beta_approved_dashboard_credit_card_last4,card.last4)
@Composable private fun approvedRegistryLabel(account: BetaManagedAccountUi): String =
    account.displayName ?: stringResource(R.string.beta_approved_onboarding_account_suffix,account.last4)
