
package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.presentation.common.AccountOwnershipInlinePrompt
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBankLoansScreen(
    bank: Bank,
    state: SettingsUiState,
    onBack: () -> Unit,
    onConfirmOwned: (ManagedLoanUi) -> Unit,
    onMarkExternal: (ManagedLoanUi) -> Unit,
    onRequestStopTracking: (ManagedLoanUi) -> Unit,
    onResumeTracking: (ManagedLoanUi) -> Unit,
    onDismissStopConfirm: () -> Unit,
    onConfirmStopTracking: () -> Unit,
    deleteActions: RegistryDeleteActions,
    onOpenBank: (() -> Unit)? = null,
) {
    var referenceTab by rememberSaveable { mutableStateOf("followed") }

    val bankLoans = state.loans.filter { it.bank == bank }
    val unregisteredLoans = bankLoans.filter { it.ownership == OwnershipStatus.UNKNOWN }
    val followedLoans = bankLoans.filter { it.ownership == OwnershipStatus.OWNED }
    val stoppedLoans = bankLoans.filter { it.ownership == OwnershipStatus.EXTERNAL }

    SettingsDeleteDialogHost(state, deleteActions) { (it as? StopTrackingTarget.Loan)?.loan?.let { loan -> loanDisplayLabel(loan) } }
    state.stopConfirmLoanTarget?.let { target ->
        SettingsStopConfirmDialog(
            title = stringResource(R.string.settings_stop_loan_confirm_title),
            body = stringResource(R.string.settings_stop_loan_confirm_body, loanDisplayLabel(target)),
            updating = state.updating,
            onDismiss = onDismissStopConfirm,
            onConfirm = onConfirmStopTracking,
        )
    }

    MasroofSecondaryScaffold(
        title = stringResource(R.string.reference_manage_loans),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            SettingsScreenHeader(
                bank = bank,
                hint = stringResource(R.string.settings_loans_hint),
                onOpenBank = onOpenBank,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap), verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap)) {
                listOf("followed" to R.string.reference_followed, "unconfirmed" to R.string.reference_unconfirmed,
                    "stopped" to R.string.reference_stopped).forEach { (key, label) ->
                    com.baraa.masroof.presentation.common.MasroofChoice(stringResource(label), referenceTab == key, { referenceTab = key })
                }
            }


            if (bankLoans.isEmpty()) {
                Text(
                    stringResource(R.string.settings_loans_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (referenceTab == "unconfirmed" && unregisteredLoans.isNotEmpty()) {
                SettingsGroupTitle(stringResource(R.string.settings_loans_unregistered))
                unregisteredLoans.forEach { loan ->
                    SettingsRegistryItemCard(
                        icon = MasroofIcons.moneyMovement,
                        accent = com.baraa.masroof.presentation.common.MasroofCardAccent.Liability,
                        bank = loan.bank,
                        title = loanDisplayLabel(loan),
                        showBankLabel = true,
                        footer = {
                            AccountOwnershipInlinePrompt(
                                enabled = !state.updating,
                                titleRes = R.string.settings_loans_unregistered,
                                onConfirmOwned = { onConfirmOwned(loan) },
                                onMarkExternal = { onMarkExternal(loan) },
                            )
                            SettingsDeleteAction({ deleteActions.onRequest(StopTrackingTarget.Loan(loan)) }, !state.updating,
                                Modifier.padding(top = MasroofSpacing.cardInnerGap))
                        },
                    )
                }
            }

            if (referenceTab == "followed" && followedLoans.isNotEmpty()) {

                followedLoans.forEach { loan ->
                    SettingsRegistryItemCard(
                        icon = MasroofIcons.moneyMovement,
                        accent = com.baraa.masroof.presentation.common.MasroofCardAccent.Liability,
                        bank = loan.bank,
                        title = loanDisplayLabel(loan),
                        showBankLabel = true,
                        endAction = {
                            SettingsStopTrackingButton(
                                onClick = { onRequestStopTracking(loan) },
                                enabled = !state.updating,
                                contentDescription = stringResource(R.string.settings_stop_loan_tracking),
                            )
                            Spacer(Modifier.width(MasroofSpacing.smallGap))
                            SettingsDeleteAction({ deleteActions.onRequest(StopTrackingTarget.Loan(loan)) }, !state.updating)
                        },
                    )
                }
            }

            if (referenceTab == "stopped" && stoppedLoans.isNotEmpty()) {
                SettingsGroupTitle(stringResource(R.string.settings_loans_stopped))
                stoppedLoans.forEach { loan ->
                    SettingsRegistryItemCard(
                        icon = MasroofIcons.moneyMovement,
                        accent = com.baraa.masroof.presentation.common.MasroofCardAccent.Liability,
                        bank = loan.bank,
                        title = loanDisplayLabel(loan),
                        showBankLabel = true,
                        endAction = {
                            SettingsStoppedItemActions({ onResumeTracking(loan) },
                                { deleteActions.onRequest(StopTrackingTarget.Loan(loan)) }, !state.updating)
                        },
                    )
                }
            }

            state.error?.let {
                Text(
                    stringResource(R.string.settings_update_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun loanDisplayLabel(loan: ManagedLoanUi): String {
    val custom = loan.displayName?.trim()?.takeIf { it.isNotEmpty() }
    return custom ?: loanTypeLabel(loan.loanType)
}

@Composable
internal fun loanTypeLabel(loanType: LoanType): String =
    when (loanType) {
        LoanType.PERSONAL -> stringResource(R.string.settings_loan_type_personal)
        LoanType.AUTO -> stringResource(R.string.settings_loan_type_auto)
        LoanType.MORTGAGE -> stringResource(R.string.settings_loan_type_mortgage)
    }
