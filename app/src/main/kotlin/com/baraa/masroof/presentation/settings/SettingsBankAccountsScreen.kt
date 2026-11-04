
package com.baraa.masroof.presentation.settings

import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.OwnershipInlinePrompt
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBankAccountsScreen(
    bank: Bank,
    state: SettingsUiState,
    onBack: () -> Unit,
    onConfirmOwned: (ManagedAccountUi) -> Unit,
    onMarkExternal: (ManagedAccountUi) -> Unit,
    onRequestStopTracking: (ManagedAccountUi) -> Unit,
    onResumeTracking: (ManagedAccountUi) -> Unit,
    onDismissStopConfirm: () -> Unit,
    onConfirmStopTracking: () -> Unit,
    onRenameAccount: (ManagedAccountUi) -> Unit,
    onDismissRenameAccount: () -> Unit,
    onSaveAccountName: (String) -> Unit,
    onPickAccountType: (ManagedAccountUi) -> Unit,
    onDismissAccountType: () -> Unit,
    onSelectAccountType: (com.baraa.masroof.domain.model.AccountType) -> Unit,
    deleteActions: RegistryDeleteActions,
    onOpenBank: (() -> Unit)? = null,
    onAddManual: (() -> Unit)? = null,
) {
    var referenceTab by rememberSaveable { mutableStateOf("followed") }

    val unregisteredAccounts = state.unregisteredAccounts.filter { it.bank == bank }
    val followedAccounts = state.followedAccounts.filter { it.bank == bank }
    val stoppedAccounts = state.stoppedAccounts.filter { it.bank == bank }
    val bankTree = state.bankTree(bank.id)

    state.stopConfirmAccountTarget?.let { target ->
        SettingsStopConfirmDialog(
            title = stringResource(R.string.settings_stop_account_confirm_title),
            body = stringResource(R.string.settings_stop_account_confirm_body, target.maskedNumber),
            updating = state.updating,
            onDismiss = onDismissStopConfirm,
            onConfirm = onConfirmStopTracking,
        )
    }
    SettingsDeleteDialogHost(state, deleteActions) { (it as? StopTrackingTarget.Account)?.account?.let { account -> registryLabel(account) } }
    SettingsRenameAccountDialog(
        target = state.renameAccountTarget,
        updating = state.updating,
        onDismiss = onDismissRenameAccount,
        onSave = onSaveAccountName,
    )
    SettingsAccountTypeDialog(
        target = state.accountTypeTarget,
        updating = state.updating,
        onDismiss = onDismissAccountType,
        onSelect = onSelectAccountType,
    )

    MasroofSecondaryScaffold(
        title = stringResource(R.string.reference_manage_accounts),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            onAddManual?.let { action -> com.baraa.masroof.presentation.common.MasroofOutlinedButton(action,
                enabled = !state.updating, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_add_account_manual))
            } }
            SettingsScreenHeader(
                bank = bank,
                hint = stringResource(R.string.settings_accounts_hint),
                onOpenBank = onOpenBank,
            )
            SettingsRegistryTabs(referenceTab) { referenceTab = it }


            if (followedAccounts.isEmpty() && unregisteredAccounts.isEmpty() && stoppedAccounts.isEmpty()) {
                Text(
                    stringResource(R.string.settings_accounts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (referenceTab == "unconfirmed" && unregisteredAccounts.isNotEmpty()) {
                SettingsGroupTitle(stringResource(R.string.settings_accounts_unregistered))
                unregisteredAccounts.forEach { account ->
                    SettingsRegistryItemCard(
                        icon = MasroofIcons.moneyMovement,
                        bank = account.bank,
                        title = registryLabel(account),
                        showBankLabel = false,
                        headerAction = {
                            SettingsDeleteOnlyMenu(registryLabel(account), !state.updating) {
                                deleteActions.onRequest(StopTrackingTarget.Account(account))
                            }
                        },
                        footer = {
                            OwnershipInlinePrompt(
                                titleRes = R.string.settings_accounts_unregistered,
                                confirmRes = R.string.onboarding_is_mine_account,
                                enabled = !state.updating,
                                onConfirmOwned = { onConfirmOwned(account) },
                                onMarkExternal = { onMarkExternal(account) },
                            )
                        },
                    )
                }
            }

            if (referenceTab == "followed" && followedAccounts.isNotEmpty()) {

                val rows = bankTree?.let { tree ->
                    tree.currentAccountNodes.map { it.account to it.debitCards } +
                        (tree.savingsAccounts + tree.walletAccounts).map { it to emptyList() }
                } ?: followedAccounts.map { it to emptyList() }
                rows.forEach { (account, debitCards) ->
                    SettingsRegistryAccountRow(
                        account = account,
                        debitCards = debitCards,
                        updating = state.updating,
                        onRequestStopTracking = onRequestStopTracking,
                        onRenameAccount = onRenameAccount,
                        onPickAccountType = onPickAccountType,
                        onRequestDelete = { deleteActions.onRequest(StopTrackingTarget.Account(it)) },
                    )
                }
            }

            if (referenceTab == "stopped" && stoppedAccounts.isNotEmpty()) {
                stoppedAccounts.forEach { account ->
                    SettingsRegistryItemCard(
                        icon = MasroofIcons.moneyMovement,
                        bank = account.bank,
                        title = registryLabel(account),
                        showBankLabel = false,
                        headerAction = {
                            SettingsStoppedItemMenu(registryLabel(account), !state.updating, { onResumeTracking(account) },
                                { deleteActions.onRequest(StopTrackingTarget.Account(account)) })
                        },
                    )
                }
            }

            state.error?.let { SettingsUpdateFailedText() }
        }
    }
}

@Composable
internal fun SettingsRegistryAccountRow(
    account: ManagedAccountUi,
    debitCards: List<ManagedCardUi> = emptyList(),
    updating: Boolean,
    onRequestStopTracking: (ManagedAccountUi) -> Unit,
    onRenameAccount: (ManagedAccountUi) -> Unit,
    onPickAccountType: (ManagedAccountUi) -> Unit,
    onRequestDelete: (ManagedAccountUi) -> Unit,
) {
    val title = registryLabel(account)
    SettingsRegistryItemCard(MasroofIcons.moneyMovement, account.bank, title, showBankLabel = false,
        onTitleClick = { onRenameAccount(account) },
        headerAction = {
            SettingsRegistryMoreMenu(stringResource(R.string.settings_account_more_actions, title), !updating, listOf(
                SettingsRowAction(stringResource(R.string.settings_action_rename)) { onRenameAccount(account) },
                SettingsRowAction(stringResource(R.string.settings_account_type_title)) { onPickAccountType(account) },
                SettingsRowAction(stringResource(R.string.reference_stop_tracking), "registry-stop-tracking-action") { onRequestStopTracking(account) },
                SettingsRowAction(stringResource(R.string.registry_delete), "registry-delete-action", danger = true) { onRequestDelete(account) },
            ))
        },
        footer = {
            // An unnamed account's title already is its type.
            if (!account.displayName.isNullOrBlank()) Text(accountTypeLabel(account.accountType), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = MasroofSpacing.inlineGap))
            debitCards.forEach { debit -> Text(stringResource(R.string.dashboard_account_linked_mada_label, registryLabel(debit)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MasroofSpacing.inlineGap)) }
        })
}
