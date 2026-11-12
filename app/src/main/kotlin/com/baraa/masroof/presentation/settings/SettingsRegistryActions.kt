package com.baraa.masroof.presentation.settings

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.dashboard.TransactionLocalDate
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.settings.RegistryDeleteTarget
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cards, accounts and loans registry edits for [SettingsViewModel]. It shares the Settings state, so screens keep one state object. */
internal class SettingsRegistryActions(
    private val scope: CoroutineScope,
    private val settingsState: MutableStateFlow<SettingsUiState>,
    private val settingsRegistryWorkflow: SettingsRegistryWorkflow,
    private val reconcileOwnershipChange: suspend (ReviewWorkflowService.OwnershipChange) -> Unit,
    private val appLogService: AppLogService,
) {
    fun requestStopTracking(card: ManagedCardUi) {
        settingsState.update { it.copy(stopTrackingTarget = StopTrackingTarget.Card(card)) }
    }

    fun requestStopAccountTracking(account: ManagedAccountUi) {
        settingsState.update { it.copy(stopTrackingTarget = StopTrackingTarget.Account(account)) }
    }

    fun dismissStopConfirm() {
        settingsState.update { it.copy(stopTrackingTarget = null) }
    }

    fun openRenameCard(card: ManagedCardUi) {
        settingsState.update { it.copy(renameCardTarget = card) }
    }

    fun dismissRenameCard() {
        settingsState.update { it.copy(renameCardTarget = null) }
    }

    fun saveCardDisplayName(name: String) {
        val target = settingsState.value.renameCardTarget ?: return
        dismissRenameCard()
        updateRegistry {
            settingsRegistryWorkflow.updateCardDisplayName(
                CardReference(target.bank, target.last4),
                name.trim().ifEmpty { null },
            )
        }
    }

    fun openRenameAccount(account: ManagedAccountUi) {
        settingsState.update { it.copy(renameAccountTarget = account) }
    }

    fun dismissRenameAccount() {
        settingsState.update { it.copy(renameAccountTarget = null) }
    }

    fun saveAccountDisplayName(name: String) {
        val target = settingsState.value.renameAccountTarget ?: return
        dismissRenameAccount()
        updateRegistry {
            settingsRegistryWorkflow.updateAccountDisplayName(
                AccountReference(target.bank, target.maskedNumber),
                name.trim().ifEmpty { null },
            )
        }
    }

    fun openCardNetworkPicker(card: ManagedCardUi) {
        if (settingsState.value.updating) return
        scope.launch {
            settingsState.update { it.copy(updating = true, error = null) }
            val suggestion = try {
                settingsRegistryWorkflow.suggestedCardType(CardReference(card.bank, card.last4))
            } catch (ce: CancellationException) { throw ce } catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings openCardNetworkPicker", caught); null }
            settingsState.update { it.copy(updating = false, cardNetworkTarget = card, cardTypeSuggestion = suggestion) }
        }
    }

    fun dismissCardNetworkPicker() {
        if (!settingsState.value.updating) settingsState.update { it.copy(cardNetworkTarget = null, cardTypeSuggestion = null) }
    }

    fun saveCardSetup(configuration: com.baraa.masroof.domain.model.CardConfiguration) {
        val target = settingsState.value.cardNetworkTarget ?: return
        updateRegistry(onUpdated = {
            settingsState.update { it.copy(cardNetworkTarget = null, cardTypeSuggestion = null) }
        }) {
            // Metadata correction is not a reparse, ownership change or ledger rebuild.
            settingsRegistryWorkflow.configureCard(CardReference(target.bank, target.last4), configuration)
        }
    }

    fun confirmStopTracking() {
        val target = settingsState.value.stopConfirmCardTarget ?: return
        dismissStopConfirm()
        updateCardOwnership(target, owned = false)
    }

    fun confirmStopAccountTracking() {
        val target = settingsState.value.stopConfirmAccountTarget ?: return
        dismissStopConfirm()
        updateAccountOwnership(target, owned = false)
    }

    fun confirmCardOwned(card: ManagedCardUi) {
        updateCardOwnership(card, owned = true, openSetup = true)
    }

    fun markCardExternal(card: ManagedCardUi) {
        updateCardOwnership(card, owned = false)
    }

    fun resumeTracking(card: ManagedCardUi) {
        updateCardOwnership(card, owned = true)
    }

    fun confirmAccountOwned(account: ManagedAccountUi) {
        updateAccountOwnership(account, owned = true)
    }

    fun markAccountExternal(account: ManagedAccountUi) {
        updateAccountOwnership(account, owned = false)
    }

    fun resumeAccountTracking(account: ManagedAccountUi) {
        updateAccountOwnership(account, owned = true)
    }

    fun confirmLoanOwned(loan: ManagedLoanUi) {
        updateLoanOwnership(loan, owned = true)
    }

    fun markLoanExternal(loan: ManagedLoanUi) {
        updateLoanOwnership(loan, owned = false)
    }

    fun resumeLoanTracking(loan: ManagedLoanUi) {
        updateLoanOwnership(loan, owned = true)
    }

    fun requestStopLoanTracking(loan: ManagedLoanUi) {
        settingsState.update { it.copy(stopTrackingTarget = StopTrackingTarget.Loan(loan)) }
    }

    fun confirmStopLoanTracking() {
        val target = settingsState.value.stopConfirmLoanTarget ?: return
        dismissStopConfirm()
        markLoanExternal(target)
    }

    fun requestDelete(target: StopTrackingTarget) {
        if (settingsState.value.updating) return
        scope.launchLogged(appLogService, AppLogCategories.SETTINGS, "Settings deletionPreview", onError = { settingsState.update { it.copy(error = SettingsError.UPDATE_FAILED) } }) {
            val preview = settingsRegistryWorkflow.deletionPreview(target.registryDeleteTarget())
            settingsState.update { it.copy(deleteRequest = RegistryDeleteUi(target,
                preview.linked.map { tx -> LinkedTransactionUi(tx.id, tx.merchant ?: tx.counterparty, tx.type, tx.amount,
                    TransactionLocalDate.of(tx, java.time.ZoneId.systemDefault())) },
                preview.linkedCount, preview.blocker)) }
        }
    }

    fun dismissDelete() {
        settingsState.update { it.copy(deleteRequest = null) }
    }

    /** A blocked delete offers stop tracking instead, through the usual stop confirmation. */
    fun stopTrackingInsteadOfDelete() {
        val request = settingsState.value.deleteRequest ?: return
        settingsState.update { it.copy(deleteRequest = null, stopTrackingTarget = request.target) }
    }

    fun confirmDelete() {
        val request = settingsState.value.deleteRequest?.takeIf { it.blocker == null } ?: return
        dismissDelete()
        updateRegistry { settingsRegistryWorkflow.delete(request.target.registryDeleteTarget()) }
    }

    private fun StopTrackingTarget.registryDeleteTarget(): RegistryDeleteTarget = when (this) {
        is StopTrackingTarget.Account -> RegistryDeleteTarget.Account(AccountReference(account.bank, account.maskedNumber))
        is StopTrackingTarget.Card -> RegistryDeleteTarget.Card(CardReference(card.bank, card.last4))
        is StopTrackingTarget.Loan -> RegistryDeleteTarget.Loan(LoanReference(loan.bank, loan.loanType))
    }

    private fun updateRegistry(onUpdated: () -> Unit = {}, action: suspend () -> Unit) {
        if (settingsState.value.updating) return
        // A registry write during a reparse or import would race its reconciliation: refuse it and say why.
        if (settingsState.value.reparsingStored || settingsState.value.importingSms) {
            settingsState.update { it.copy(error = SettingsError.REGISTRY_BUSY) }
            return
        }
        scope.launch {
            settingsState.update { it.copy(updating = true, error = null) }
            try {
                action()
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(settingsState, snapshot.cards, snapshot.accounts, snapshot.loans)
                settingsState.update { it.copy(updating = false) }
                onUpdated()
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings updateRegistry", caught);
                // A manual decision may already be durable even when its reconciliation needs a retry.
                try {
                    val snapshot = settingsRegistryWorkflow.loadSnapshot()
                    applyRegistries(settingsState, snapshot.cards, snapshot.accounts, snapshot.loans)
                } catch (error: CancellationException) { throw error }
                catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings updateRegistry", caught); /* Keep the prior snapshot and the visible error. */ }
                settingsState.update { it.copy(updating = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    /** A new sheet must not open showing an earlier action's failure. */
    fun clearError() = settingsState.update { it.copy(error = null) }

    fun addManualAccount(bankId: String, last4: String, name: String?, onSaved: () -> Unit) {
        if (settingsState.value.bankCatalog != null && settingsState.value.bankSummaries.none { it.bank.id == bankId }) {
            settingsState.update { it.copy(error = SettingsError.UPDATE_FAILED) }; return
        }
        updateRegistry(onSaved) {
            val ref = settingsRegistryWorkflow.addAccount(bankId, last4, name)
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(ref))
        }
    }

    fun addManualCard(bankId: String, last4: String, name: String?, configuration: com.baraa.masroof.domain.model.CardConfiguration, onSaved: () -> Unit) {
        if (settingsState.value.bankCatalog != null && settingsState.value.bankSummaries.none { it.bank.id == bankId }) {
            settingsState.update { it.copy(error = SettingsError.UPDATE_FAILED) }; return
        }
        updateRegistry(onSaved) {
            val ref = settingsRegistryWorkflow.addCard(bankId, last4, name, configuration)
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Card(ref))
        }
    }

    private fun updateCardOwnership(card: ManagedCardUi, owned: Boolean, openSetup: Boolean = false) {
        updateRegistry(onUpdated = {
            if (openSetup) settingsState.value.followedCards.firstOrNull { it.bank == card.bank && it.last4 == card.last4 }
                ?.let(::openCardNetworkPicker)
        }) {
            val ref = CardReference(card.bank, card.last4)
            if (owned) settingsRegistryWorkflow.confirmCardOwned(ref) else settingsRegistryWorkflow.markCardExternal(ref)
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Card(ref))
        }
    }

    private fun updateAccountOwnership(account: ManagedAccountUi, owned: Boolean) {
        updateRegistry {
            val ref = AccountReference(account.bank, account.maskedNumber)
            if (owned) settingsRegistryWorkflow.confirmAccountOwned(ref) else settingsRegistryWorkflow.markAccountExternal(ref)
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(ref))
        }
    }

    private fun updateLoanOwnership(loan: ManagedLoanUi, owned: Boolean) {
        updateRegistry {
            val ref = LoanReference(loan.bank, loan.loanType)
            if (owned) settingsRegistryWorkflow.confirmLoanOwned(ref) else settingsRegistryWorkflow.markLoanExternal(ref)
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Loan(ref))
        }
    }

    fun openAccountTypePicker(account: ManagedAccountUi) {
        settingsState.update { it.copy(accountTypeTarget = account) }
    }

    fun dismissAccountTypePicker() {
        settingsState.update { it.copy(accountTypeTarget = null) }
    }

    fun setAccountTypeFromPicker(accountType: AccountType) {
        val account = settingsState.value.accountTypeTarget ?: return
        setAccountType(account, accountType)
    }

    fun setAccountType(account: ManagedAccountUi, accountType: AccountType) {
        updateRegistry {
            val reference = AccountReference(account.bank, account.maskedNumber)
            settingsRegistryWorkflow.updateAccountType(reference, accountType)
            // Only this account's evidence can change; a full-history pass is not needed.
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(reference))
        }
        dismissAccountTypePicker()
    }
}
