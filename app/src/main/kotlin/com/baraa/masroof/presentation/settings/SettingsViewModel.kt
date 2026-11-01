package com.baraa.masroof.presentation.settings

import com.baraa.masroof.presentation.common.launchLogged
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.theme.ThemePreferencesRepository
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.update.AppUpdateService
import com.baraa.masroof.application.update.ApkInstaller
import com.baraa.masroof.application.update.InstalledBuildInfo
import com.baraa.masroof.application.update.UpdateChannel
import com.baraa.masroof.application.update.UpdateCheckCoordinator
import com.baraa.masroof.application.settings.CommitmentHistoryBuilder
import com.baraa.masroof.application.settings.CommitmentRecordBuilder
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.settings.RegistryDeleteTarget
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.application.transaction.HistoricalMerchantRateCorrectionReport
import com.baraa.masroof.application.transaction.HistoricalMerchantRateCorrectionWorkflow
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.OwnershipStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val settingsRegistryWorkflow: SettingsRegistryWorkflow,
    private val settingsCommitmentsWorkflow: SettingsCommitmentsWorkflow,
    private val appLocaleRepository: AppLocaleRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val databaseBackupService: DatabaseBackupGateway,
    private val refreshReviewQueue: suspend () -> Unit,
    private val reconcileOwnershipChange: suspend (ReviewWorkflowService.OwnershipChange) -> Unit = {},
    private val reparseStoredEvents: suspend () -> com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult,
    private val permissionStateProvider: () -> Boolean,
    private val appVersion: String,
    private val appUpdateService: AppUpdateService,
    private val updateCheckCoordinator: UpdateCheckCoordinator,
    private val appLogService: AppLogService,
    private val apkInstaller: ApkInstaller,
    private val canInstallPackages: () -> Boolean,
    private val onRequestInstallPermission: () -> Unit = {},
    val messagePatternsWorkflow: com.baraa.masroof.application.patterns.MessagePatternsWorkflow? = null,
    private val historicalMerchantRateCorrectionWorkflow: HistoricalMerchantRateCorrectionWorkflow? = null,
    private val bankSmsImportWorkflow: com.baraa.masroof.application.sms.BankSmsImportWorkflow? = null,
) : ViewModel() {
    private var updatableCorrectionIds: List<String> = emptyList()
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            appVersion = appVersion,
            isNightlyBuild = InstalledBuildInfo.isNightlyBuild(appVersion),
            languageTag = appLocaleRepository.getLanguageTag(),
            themeMode = themePreferencesRepository.getThemeMode(),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val updates = SettingsUpdateActions(viewModelScope, _uiState, appUpdateService, updateCheckCoordinator,
        appLogService, apkInstaller, canInstallPackages, onRequestInstallPermission)
    private val backup = SettingsBackupActions(viewModelScope, _uiState, databaseBackupService, appLogService)
    private val _logEntries = MutableStateFlow(appLogService.readAll())
    val logEntries: StateFlow<List<com.baraa.masroof.application.logging.AppLogEntry>> = _logEntries.asStateFlow()

    fun refreshLogs() {
        _logEntries.value = appLogService.readAll()
    }

    /** Bank display names for home screens; only the pattern catalog, not the full settings refresh. */
    fun loadBankCatalog() {
        if (_uiState.value.bankCatalog != null) return
        viewModelScope.launch {
            try {
                val catalog = messagePatternsWorkflow?.catalog() ?: return@launch
                _uiState.update { if (it.bankCatalog == null) it.copy(bankCatalog = catalog) else it }
            } catch (ce: CancellationException) { throw ce } catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings loadBankCatalog", caught); }
        }
    }

    fun refresh() {
        refreshLogs()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    languageTag = appLocaleRepository.getLanguageTag(),
                    themeMode = themePreferencesRepository.getThemeMode(),
                    githubTokenConfigured = appUpdateService.hasConfiguredToken(),
                    updateChannel = appUpdateService.getUpdateChannel(),
                    isNightlyBuild = InstalledBuildInfo.isNightlyBuild(appVersion),
                    smsPermissionGranted = permissionStateProvider(),
                )
            }
            // An update-state failure must not leave `loading` stuck (it disables onboarding actions).
            try { updates.restorePendingUpdateState() } catch (_: Exception) {
                appLogService.error(com.baraa.masroof.application.logging.AppLogCategories.UPDATE, "Pending update state restore failed")
            }
            try {
                val catalog = messagePatternsWorkflow?.catalog()
                _uiState.update { it.copy(bankCatalog = catalog) }
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                val commitments = settingsCommitmentsWorkflow.listAll()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                    commitments = commitments,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings refresh", caught);
                _uiState.update {
                    it.copy(
                        loading = false,
                        commitmentsLoaded = true,
                        error = SettingsError.UPDATE_FAILED,
                    )
                }
            }
        }
    }

    fun requestStopTracking(card: ManagedCardUi) {
        _uiState.update { it.copy(stopTrackingTarget = StopTrackingTarget.Card(card)) }
    }

    fun requestStopAccountTracking(account: ManagedAccountUi) {
        _uiState.update { it.copy(stopTrackingTarget = StopTrackingTarget.Account(account)) }
    }

    fun dismissStopConfirm() {
        _uiState.update { it.copy(stopTrackingTarget = null) }
    }

    fun openRenameCard(card: ManagedCardUi) {
        _uiState.update { it.copy(renameCardTarget = card) }
    }

    fun dismissRenameCard() {
        _uiState.update { it.copy(renameCardTarget = null) }
    }

    fun saveCardDisplayName(name: String) {
        val target = _uiState.value.renameCardTarget ?: return
        dismissRenameCard()
        updateRegistry {
            settingsRegistryWorkflow.updateCardDisplayName(
                CardReference(target.bank, target.last4),
                name.trim().ifEmpty { null },
            )
        }
    }

    fun openRenameAccount(account: ManagedAccountUi) {
        _uiState.update { it.copy(renameAccountTarget = account) }
    }

    fun dismissRenameAccount() {
        _uiState.update { it.copy(renameAccountTarget = null) }
    }

    fun saveAccountDisplayName(name: String) {
        val target = _uiState.value.renameAccountTarget ?: return
        dismissRenameAccount()
        updateRegistry {
            settingsRegistryWorkflow.updateAccountDisplayName(
                AccountReference(target.bank, target.maskedNumber),
                name.trim().ifEmpty { null },
            )
        }
    }

    fun openCardNetworkPicker(card: ManagedCardUi) {
        if (_uiState.value.updating) return
        viewModelScope.launch {
            _uiState.update { it.copy(updating = true, error = null) }
            val suggestion = try {
                settingsRegistryWorkflow.suggestedCardType(CardReference(card.bank, card.last4))
            } catch (ce: CancellationException) { throw ce } catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings openCardNetworkPicker", caught); null }
            _uiState.update { it.copy(updating = false, cardNetworkTarget = card, cardTypeSuggestion = suggestion) }
        }
    }

    fun dismissCardNetworkPicker() {
        if (!_uiState.value.updating) _uiState.update { it.copy(cardNetworkTarget = null, cardTypeSuggestion = null) }
    }

    fun saveCardSetup(configuration: com.baraa.masroof.domain.model.CardConfiguration) {
        val target = _uiState.value.cardNetworkTarget ?: return
        updateRegistry(onUpdated = {
            _uiState.update { it.copy(cardNetworkTarget = null, cardTypeSuggestion = null) }
        }) {
            // Metadata correction is not a reparse, ownership change or ledger rebuild.
            settingsRegistryWorkflow.configureCard(CardReference(target.bank, target.last4), configuration)
        }
    }

    fun confirmStopTracking() {
        val target = _uiState.value.stopConfirmCardTarget ?: return
        dismissStopConfirm()
        updateCardOwnership(target, owned = false)
    }

    fun confirmStopAccountTracking() {
        val target = _uiState.value.stopConfirmAccountTarget ?: return
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
        _uiState.update { it.copy(stopTrackingTarget = StopTrackingTarget.Loan(loan)) }
    }

    fun confirmStopLoanTracking() {
        val target = _uiState.value.stopConfirmLoanTarget ?: return
        dismissStopConfirm()
        markLoanExternal(target)
    }

    fun requestDelete(target: StopTrackingTarget) {
        if (_uiState.value.updating) return
        launchLogged(appLogService, AppLogCategories.SETTINGS, "Settings deletionPreview", onError = { _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) } }) {
            val preview = settingsRegistryWorkflow.deletionPreview(target.registryDeleteTarget())
            _uiState.update { it.copy(deleteRequest = RegistryDeleteUi(target,
                preview.linked.map { tx -> LinkedTransactionUi(tx.id, tx.merchant ?: tx.counterparty, tx.type, tx.amount,
                    tx.occurredAt.atZone(tx.occurredAtZone?.let(java.time.ZoneId::of) ?: java.time.ZoneId.systemDefault()).toLocalDate()) },
                preview.linkedCount, preview.blocker)) }
        }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteRequest = null) }
    }

    /** A blocked delete offers stop tracking instead, through the usual stop confirmation. */
    fun stopTrackingInsteadOfDelete() {
        val request = _uiState.value.deleteRequest ?: return
        _uiState.update { it.copy(deleteRequest = null, stopTrackingTarget = request.target) }
    }

    fun confirmDelete() {
        val request = _uiState.value.deleteRequest?.takeIf { it.blocker == null } ?: return
        dismissDelete()
        updateRegistry { settingsRegistryWorkflow.delete(request.target.registryDeleteTarget()) }
    }

    private fun StopTrackingTarget.registryDeleteTarget(): RegistryDeleteTarget = when (this) {
        is StopTrackingTarget.Account -> RegistryDeleteTarget.Account(AccountReference(account.bank, account.maskedNumber))
        is StopTrackingTarget.Card -> RegistryDeleteTarget.Card(CardReference(card.bank, card.last4))
        is StopTrackingTarget.Loan -> RegistryDeleteTarget.Loan(LoanReference(loan.bank, loan.loanType))
    }

    fun reparseStoredMessages() {
        if (_uiState.value.reparsingStored || _uiState.value.updating || _uiState.value.importingSms) return
        viewModelScope.launch {
            _uiState.update { it.copy(reparsingStored = true, error = null) }
            try {
                val reparsed = reparseStoredEvents()
                check(reparsed.succeeded) { "Manual reparse incomplete: ${reparsed.failedCount} messages failed" }
                val count = reparsed.refreshedCount
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                appLogService.info(AppLogCategories.SETTINGS, "Manual reparse finished: $count events refreshed")
                _uiState.update { it.copy(reparsingStored = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                appLogService.error(
                    AppLogCategories.SETTINGS,
                    "Manual reparse failed: ${error.message ?: error::class.java.simpleName}",
                )
                _uiState.update { it.copy(reparsingStored = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    fun refreshHistoricalMerchantRateCorrections() {
        val workflow = historicalMerchantRateCorrectionWorkflow ?: return
        viewModelScope.launch {
            try {
                applyCorrectionReport(workflow.listCandidates())
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                appLogService.error(
                    AppLogCategories.SETTINGS,
                    "Historical merchant rate review failed: ${error.message ?: error::class.java.simpleName}",
                )
                _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    fun requestHistoricalMerchantRateCorrection() {
        if (updatableCorrectionIds.isEmpty() || _uiState.value.fxRateCorrectionRunning) return
        _uiState.update { it.copy(fxRateCorrectionAwaitingConfirm = true) }
    }

    fun dismissHistoricalMerchantRateCorrection() {
        _uiState.update { it.copy(fxRateCorrectionAwaitingConfirm = false) }
    }

    fun confirmHistoricalMerchantRateCorrections() {
        val workflow = historicalMerchantRateCorrectionWorkflow ?: return
        val ids = updatableCorrectionIds
        if (ids.isEmpty() || _uiState.value.fxRateCorrectionRunning) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    fxRateCorrectionAwaitingConfirm = false,
                    fxRateCorrectionRunning = true,
                    error = null,
                )
            }
            try {
                val result = workflow.confirm(ids)
                appLogService.info(
                    AppLogCategories.SETTINGS,
                    "Confirmed historical merchant rate correction updated=${result.updated.size} " +
                        "manual_follow_up=${result.manualFollowUp.size}",
                )
                applyCorrectionReport(workflow.listCandidates())
                _uiState.update { it.copy(fxRateCorrectionRunning = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                appLogService.error(
                    AppLogCategories.SETTINGS,
                    "Historical merchant rate correction failed: ${error.message ?: error::class.java.simpleName}",
                )
                _uiState.update {
                    it.copy(fxRateCorrectionRunning = false, error = SettingsError.UPDATE_FAILED)
                }
            }
        }
    }

    private fun applyCorrectionReport(report: HistoricalMerchantRateCorrectionReport) {
        updatableCorrectionIds = report.candidates
            .filterNot { it.requiresManualFollowUp }
            .map { it.transactionId }
        _uiState.update {
            it.copy(
                fxRateCorrectionUpdatable = updatableCorrectionIds.size,
                fxRateCorrectionManualFollowUp = report.candidates.count { candidate ->
                    candidate.requiresManualFollowUp
                },
            )
        }
    }

    fun clearScopedSmsImportResult() {
        if (!_uiState.value.importingSms) _uiState.update { it.copy(scopedSmsImportResult = null, scopedSmsImportFailed = false) }
    }

    fun refreshSmsPermissionState() {
        _uiState.update { it.copy(smsPermissionGranted = permissionStateProvider()) }
    }

    fun importSelectedBankSms(bankIds: Set<String>, from: java.time.LocalDate, through: java.time.LocalDate) {
        val workflow = bankSmsImportWorkflow ?: return
        if (_uiState.value.importingSms || _uiState.value.reparsingStored || _uiState.value.updating) return
        if (!permissionStateProvider()) {
            _uiState.update { it.copy(smsPermissionGranted = false, scopedSmsImportFailed = true) }
            return
        }
        launchLogged(appLogService, AppLogCategories.SCAN, "Settings importSelectedBankSms", onError = { _uiState.update { it.copy(scopedSmsImportFailed = true) } }, onFinally = { _uiState.update { it.copy(importingSms = false) } }) {
            _uiState.update { it.copy(importingSms = true, importScanned = 0, scopedSmsImportResult = null, scopedSmsImportFailed = false) }
            val result = workflow.import(bankIds, from, through) { scanned -> _uiState.update { it.copy(importScanned = scanned) } }
            _uiState.update { it.copy(scopedSmsImportResult = result) }
            val snapshot = settingsRegistryWorkflow.loadSnapshot()
            applyRegistries(snapshot.cards, snapshot.accounts, snapshot.loans)
        }
    }

    fun clearUpdateMessage() = updates.clearUpdateMessage()
    fun saveGithubToken(token: String) = updates.saveGithubToken(token)
    fun clearGithubToken() = updates.clearGithubToken()
    fun setUpdateChannel(channel: UpdateChannel) = updates.setUpdateChannel(channel)
    fun checkForUpdates(silent: Boolean = false) = updates.checkForUpdates(silent)
    fun checkForUpdatesIfStale(silent: Boolean = true) = updates.checkForUpdatesIfStale(silent)
    fun logExportText(): String = appLogService.exportText()

    fun exportLogs(uri: Uri) {
        launchLogged(appLogService, AppLogCategories.LOGS, "Log export", onError = {
            _uiState.update {
                it.copy(exportingLogs = false, logMessage = LogMessage.EXPORT_FAILED)
            }
        }) {
            _uiState.update { it.copy(exportingLogs = true, logMessage = null) }
            withContext(Dispatchers.IO) {
                appLogService.exportTo(uri).getOrThrow()
            }
            _uiState.update {
                it.copy(exportingLogs = false, logMessage = LogMessage.EXPORT_SUCCESS)
            }
        }
    }

    fun clearLogs() {
        appLogService.clear()
        refreshLogs()
        _uiState.update { it.copy(logMessage = LogMessage.CLEARED) }
    }

    fun clearLogMessage() {
        _uiState.update { it.copy(logMessage = null) }
    }

    fun downloadUpdate() = updates.downloadUpdate()
    fun installPendingUpdate() = updates.installPendingUpdate()
    fun retryInstallAfterPermissionGranted() = updates.retryInstallAfterPermissionGranted()
    fun setLanguageTag(languageTag: String, onApplied: () -> Unit) {
        val normalized = when (languageTag) {
            AppLocale.TAG_EN -> AppLocale.TAG_EN
            else -> AppLocale.TAG_AR
        }
        if (normalized == appLocaleRepository.getLanguageTag()) return
        appLocaleRepository.setLanguageTag(normalized)
        onApplied()
    }

    fun setThemeMode(mode: ThemeMode) {
        if (mode == themePreferencesRepository.getThemeMode()) return
        themePreferencesRepository.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun clearBackupMessage() = backup.clearBackupMessage()
    fun onExportPassphraseChange(value: String) = backup.onExportPassphraseChange(value)
    fun onImportPassphraseChange(value: String) = backup.onImportPassphraseChange(value)
    fun prepareExport(): Boolean = backup.prepareExport()
    fun abandonPreparedExport() = backup.abandonPreparedExport()
    fun offerImport(uri: Uri) = backup.offerImport(uri)
    fun cancelPendingImport() = backup.cancelPendingImport()
    fun verifyPendingImport() = backup.verifyPendingImport()
    fun confirmPendingImport() = backup.confirmPendingImport()
    fun exportBackup(uri: Uri) = backup.exportBackup(uri)
    fun importBackup(uri: Uri, passphrase: CharArray = CharArray(0), confirmLegacyPlaintext: Boolean = false) = backup.importBackup(uri, passphrase, confirmLegacyPlaintext)
    private fun updateRegistry(onUpdated: () -> Unit = {}, action: suspend () -> Unit) {
        if (_uiState.value.updating) return
        viewModelScope.launch {
            _uiState.update { it.copy(updating = true, error = null) }
            try {
                action()
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(snapshot.cards, snapshot.accounts, snapshot.loans)
                _uiState.update { it.copy(updating = false) }
                onUpdated()
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings updateRegistry", caught);
                // A manual decision may already be durable even when its reconciliation needs a retry.
                try {
                    val snapshot = settingsRegistryWorkflow.loadSnapshot()
                    applyRegistries(snapshot.cards, snapshot.accounts, snapshot.loans)
                } catch (error: CancellationException) { throw error }
                catch (caught: Exception) { appLogService.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Settings updateRegistry", caught); /* Keep the prior snapshot and the visible error. */ }
                _uiState.update { it.copy(updating = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    fun addManualAccount(bankId: String, last4: String, name: String?, onSaved: () -> Unit) {
        if (_uiState.value.bankCatalog != null && _uiState.value.bankSummaries.none { it.bank.id == bankId }) {
            _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) }; return
        }
        updateRegistry(onSaved) {
            val ref = settingsRegistryWorkflow.addAccount(bankId, last4, name)
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(ref))
        }
    }

    fun addManualCard(bankId: String, last4: String, name: String?, configuration: com.baraa.masroof.domain.model.CardConfiguration, onSaved: () -> Unit) {
        if (_uiState.value.bankCatalog != null && _uiState.value.bankSummaries.none { it.bank.id == bankId }) {
            _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) }; return
        }
        updateRegistry(onSaved) {
            val ref = settingsRegistryWorkflow.addCard(bankId, last4, name, configuration)
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Card(ref))
        }
    }

    private fun updateCardOwnership(card: ManagedCardUi, owned: Boolean, openSetup: Boolean = false) {
        updateRegistry(onUpdated = {
            if (openSetup) _uiState.value.followedCards.firstOrNull { it.bank == card.bank && it.last4 == card.last4 }
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

    fun saveCommitment(commitmentId: String, draft: SettingsCommitmentsWorkflow.CommitmentEditorDraft) {
        launchLogged(appLogService, AppLogCategories.SETTINGS, "Settings saveCommitment", onError = { _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) } }, onFinally = { _uiState.update { it.copy(savingCommitment = false) } }) {
            _uiState.update { it.copy(savingCommitment = true) }
            settingsCommitmentsWorkflow.update(commitmentId, draft)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
        }
    }

    fun setCommitmentsListTab(tab: CommitmentsListTab) {
        _uiState.update { it.copy(commitmentsListTab = tab) }
    }

    fun toggleCommitmentActive(commitmentId: String) {
        launchLogged(appLogService, AppLogCategories.SETTINGS, "Settings toggleCommitmentActive", onError = { _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) } }) {
            settingsCommitmentsWorkflow.toggleActive(commitmentId)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
        }
    }

    fun deleteCommitment(commitmentId: String, onDeleted: () -> Unit = {}) {
        launchLogged(appLogService, AppLogCategories.SETTINGS, "Settings deleteCommitment", onError = { _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) } }) {
            settingsCommitmentsWorkflow.delete(commitmentId)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
            onDeleted()
        }
    }

    fun commitmentById(commitmentId: String): ManagedCommitmentUi? =
        (_uiState.value.activeCommitments + _uiState.value.disabledCommitments)
            .find { it.id == commitmentId }

    private fun applyCommitments(commitments: List<Commitment>) {
        _uiState.update { it.withCommitments(commitments) }
    }

    private fun SettingsUiState.withCommitments(commitments: List<Commitment>): SettingsUiState {
        val zoneId = java.time.ZoneId.systemDefault()
        val items = commitments.map { commitment -> toManagedCommitmentUi(commitment, zoneId) }
        val disabledItems = CommitmentHistoryBuilder.disabledTabItems(commitments, zoneId)
            .map { historyItem ->
                items.first { it.id == historyItem.commitmentId }
            }
        val recordEntries = CommitmentRecordBuilder.recordTabItems(commitments)
            .map { entry ->
                CommitmentRecordEntryUi(
                    commitmentId = entry.commitmentId,
                    commitmentName = entry.commitmentName,
                    event = entry.event,
                    at = entry.at.atZone(zoneId).toLocalDate(),
                )
            }
        return copy(
            activeCommitments = items.filter { item -> item.active },
            disabledCommitments = disabledItems,
            commitmentRecordEntries = recordEntries,
            commitmentsLoaded = true,
        )
    }

    private fun toManagedCommitmentUi(
        commitment: Commitment,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): ManagedCommitmentUi =
        ManagedCommitmentUi(
            id = commitment.id,
            name = commitment.name,
            amount = commitment.amount,
            transactionDate = commitment.transactionDate,
            recurrence = commitment.recurrence,
            dueDate = commitment.dueDate,
            active = commitment.active,
            sourceTransactionId = commitment.sourceTransactionId,
            pauseIntervals = CommitmentHistoryBuilder.intervalSummaries(commitment, zoneId),
        )

    private fun applyRegistries(
        cards: List<com.baraa.masroof.domain.model.CardRegistryEntry>,
        accounts: List<com.baraa.masroof.domain.model.AccountRegistryEntry>,
        loans: List<com.baraa.masroof.domain.model.LoanRegistryEntry>,
        commitments: List<Commitment>? = null,
    ) {
        val cardItems = cards
            .filter { it.bank != Bank.UNKNOWN }
            .map {
                ManagedCardUi(
                    id = it.id,
                    bank = it.bank,
                    last4 = it.last4,
                    ownership = it.ownership,
                    displayName = it.displayName,
                    cardNetwork = it.cardNetwork,
                    cardType = it.cardType,
                    cardRole = it.cardRole,
                    parentCardLast4 = it.parentCardLast4,
                    linkedAccountMaskedNumber = it.linkedAccountMaskedNumber,
                )
            }
            .sortedBy { it.last4 }
        val accountItems = accounts
            .filter { it.bank != Bank.UNKNOWN }
            .map {
                ManagedAccountUi(
                    id = it.id,
                    bank = it.bank,
                    maskedNumber = it.maskedNumber,
                    ownership = it.ownership,
                    displayName = it.displayName,
                    accountType = it.accountType,
                )
            }
            .sortedBy { it.maskedNumber }
        val loanItems = loans
            .filter { it.bank != Bank.UNKNOWN }
            .map {
                ManagedLoanUi(
                    id = it.id,
                    bank = it.bank,
                    loanType = it.loanType,
                    ownership = it.ownership,
                    displayName = it.displayName,
                )
            }
        val followedCards = cardItems.filter { card -> card.ownership == OwnershipStatus.OWNED }
        val followedAccounts = accountItems.filter { account -> account.ownership == OwnershipStatus.OWNED }
        _uiState.update {
            it.copy(
                loading = false,
                followedCards = followedCards,
                unregisteredCards = cardItems.filter { card -> card.ownership == OwnershipStatus.UNKNOWN },
                stoppedCards = cardItems.filter { card -> card.ownership == OwnershipStatus.EXTERNAL },
                followedAccounts = followedAccounts,
                unregisteredAccounts = accountItems.filter { account -> account.ownership == OwnershipStatus.UNKNOWN },
                stoppedAccounts = accountItems.filter { account -> account.ownership == OwnershipStatus.EXTERNAL },
                loans = loanItems,
                bankTrees = buildBankTrees(
                    cards = followedCards,
                    accounts = followedAccounts,
                    loans = loanItems,
                ),
                bankSummaries = buildBankSummaries(
                    accounts = accountItems,
                    cards = cardItems,
                    loans = loanItems,
                ),
                error = null,
            ).let { state -> if (commitments == null) state else state.withCommitments(commitments) }
        }
    }

    private fun buildBankSummaries(
        accounts: List<ManagedAccountUi>,
        cards: List<ManagedCardUi>,
        loans: List<ManagedLoanUi>,
    ): List<SettingsBankSummaryUi> {
        val bankIds = (
            accounts.map { it.bank.id } +
                cards.map { it.bank.id } +
                loans.map { it.bank.id } + _uiState.value.bankCatalog?.banks.orEmpty().map { it.bankId }
        ).distinct().sorted()
        return bankIds.mapNotNull { bankId ->
            val bankAccounts = accounts.filter { it.bank.id == bankId }
            val bankCards = cards.filter { it.bank.id == bankId }
            val bankLoans = loans.filter { it.bank.id == bankId }
            SettingsBankSummaryUi(
                bank = Bank(bankId),
                followedAccountCount = bankAccounts.count { it.ownership == OwnershipStatus.OWNED },
                unregisteredAccountCount = bankAccounts.count { it.ownership == OwnershipStatus.UNKNOWN },
                stoppedAccountCount = bankAccounts.count { it.ownership == OwnershipStatus.EXTERNAL },
                followedCardCount = bankCards.count { it.ownership == OwnershipStatus.OWNED },
                unregisteredCardCount = bankCards.count { it.ownership == OwnershipStatus.UNKNOWN },
                stoppedCardCount = bankCards.count { it.ownership == OwnershipStatus.EXTERNAL },
                loanCount = bankLoans.size,
            )
        }
    }

    private fun buildBankTrees(
        cards: List<ManagedCardUi>,
        accounts: List<ManagedAccountUi>,
        loans: List<ManagedLoanUi>,
    ): List<SettingsBankTreeUi> {
        val bankIds = (
            cards.map { it.bank.id } +
                accounts.map { it.bank.id } +
                loans.map { it.bank.id }
        ).distinct().sorted()
        return bankIds.map { bankId ->
            val bank = Bank(bankId)
            val bankCards = cards.filter { it.bank.id == bankId }
            val bankAccounts = accounts.filter { it.bank.id == bankId }
            val currentAccounts = bankAccounts.filter { it.accountType == AccountType.CURRENT }
            val debitCards = bankCards.filter { it.isDebitRegistryCard() }
            val assignedDebitLast4s = mutableSetOf<String>()
            val currentNodes = currentAccounts.map { account ->
                val matchedDebit = debitCards.filter { debit ->
                    debit.last4 !in assignedDebitLast4s &&
                        com.baraa.masroof.application.dashboard.debitLinkMatchesAccount(debit.linkedAccountMaskedNumber, account.maskedNumber)
                }
                matchedDebit.forEach { assignedDebitLast4s.add(it.last4) }
                SettingsCurrentAccountNodeUi(account = account, debitCards = matchedDebit)
            }
            val unlinkedDebit = debitCards.filter { it.last4 !in assignedDebitLast4s }
            SettingsBankTreeUi(
                bank = bank,
                currentAccountNodes = currentNodes,
                savingsAccounts = bankAccounts.filter { it.accountType == AccountType.SAVINGS },
                walletAccounts = bankAccounts.filter { it.accountType == AccountType.WALLET },
                creditCards = bankCards.filter { !it.isDebitRegistryCard() },
                unlinkedDebitCards = unlinkedDebit,
                loans = loans.filter { it.bank.id == bankId },
            )
        }.filter { tree ->
            tree.currentAccountNodes.isNotEmpty() ||
                tree.savingsAccounts.isNotEmpty() ||
                tree.walletAccounts.isNotEmpty() ||
                tree.creditCards.isNotEmpty() ||
                tree.unlinkedDebitCards.isNotEmpty() ||
                tree.loans.isNotEmpty()
        }
    }

    private fun ManagedCardUi.isDebitRegistryCard(): Boolean =
        when (cardType) {
            CardType.DEBIT -> true
            CardType.CREDIT -> false
            null -> cardNetwork == CardNetwork.MADA || linkedAccountMaskedNumber != null
        }

    fun openAccountTypePicker(account: ManagedAccountUi) {
        _uiState.update { it.copy(accountTypeTarget = account) }
    }

    fun dismissAccountTypePicker() {
        _uiState.update { it.copy(accountTypeTarget = null) }
    }

    fun setAccountTypeFromPicker(accountType: AccountType) {
        val account = _uiState.value.accountTypeTarget ?: return
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
