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
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.application.transaction.HistoricalMerchantRateCorrectionWorkflow
import com.baraa.masroof.domain.model.AccountType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRegistryWorkflow: SettingsRegistryWorkflow,
    private val settingsCommitmentsWorkflow: SettingsCommitmentsWorkflow,
    private val appLocaleRepository: AppLocaleRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val databaseBackupService: DatabaseBackupGateway,
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
    /** Debug builds only: writes the plan 3.2 ledger shadow comparison to a document; null hides the action. */
    private val ledgerShadowExport: (suspend (Uri) -> Unit)? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            appVersion = appVersion,
            isNightlyBuild = InstalledBuildInfo.isNightlyBuild(appVersion),
            languageTag = appLocaleRepository.getLanguageTag(),
            themeMode = themePreferencesRepository.getThemeMode(),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** The app theme alone, so the activity root does not redraw for every Settings change. */
    val themeMode: StateFlow<ThemeMode> = _uiState.map { it.themeMode }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.themeMode)
    private val updates = SettingsUpdateActions(viewModelScope, _uiState, appUpdateService, updateCheckCoordinator,
        appLogService, apkInstaller, canInstallPackages, onRequestInstallPermission)
    private val backup = SettingsBackupActions(viewModelScope, _uiState, databaseBackupService, appLogService)
    private val registry = SettingsRegistryActions(viewModelScope, _uiState, settingsRegistryWorkflow, reconcileOwnershipChange, appLogService)
    private val commitments = SettingsCommitmentActions(viewModelScope, _uiState, settingsCommitmentsWorkflow, appLogService)
    private val _logEntries = MutableStateFlow(appLogService.readAll())
    val logEntries: StateFlow<List<com.baraa.masroof.application.logging.AppLogEntry>> = _logEntries.asStateFlow()
    private val maintenance = SettingsMaintenanceActions(viewModelScope, _uiState, _logEntries, settingsRegistryWorkflow,
        reparseStoredEvents, permissionStateProvider, appLogService, historicalMerchantRateCorrectionWorkflow,
        bankSmsImportWorkflow, ledgerShadowExport)

    fun refreshLogs() = maintenance.refreshLogs()

    /** Bank display names for home screens; only the pattern catalog, not the full settings refresh. */
    fun loadBankCatalog() {
        if (_uiState.value.bankCatalog != null) return
        launchLogged(appLogService, AppLogCategories.SETTINGS, "Settings loadBankCatalog") {
            val catalog = messagePatternsWorkflow?.catalog() ?: return@launchLogged
            _uiState.update { if (it.bankCatalog == null) it.copy(bankCatalog = catalog) else it }
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
            try { updates.restorePendingUpdateState() } catch (ce: CancellationException) { throw ce } catch (_: Exception) {
                appLogService.error(com.baraa.masroof.application.logging.AppLogCategories.UPDATE, "Pending update state restore failed")
            }
            try {
                val catalog = messagePatternsWorkflow?.catalog()
                _uiState.update { it.copy(bankCatalog = catalog) }
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    _uiState,
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                    commitments = settingsCommitmentsWorkflow.listAll(),
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

    fun requestStopTracking(card: ManagedCardUi) = registry.requestStopTracking(card)
    fun requestStopAccountTracking(account: ManagedAccountUi) = registry.requestStopAccountTracking(account)
    fun dismissStopConfirm() = registry.dismissStopConfirm()
    fun openRenameCard(card: ManagedCardUi) = registry.openRenameCard(card)
    fun dismissRenameCard() = registry.dismissRenameCard()
    fun saveCardDisplayName(name: String) = registry.saveCardDisplayName(name)
    fun openRenameAccount(account: ManagedAccountUi) = registry.openRenameAccount(account)
    fun dismissRenameAccount() = registry.dismissRenameAccount()
    fun saveAccountDisplayName(name: String) = registry.saveAccountDisplayName(name)
    fun openCardNetworkPicker(card: ManagedCardUi) = registry.openCardNetworkPicker(card)
    fun dismissCardNetworkPicker() = registry.dismissCardNetworkPicker()
    fun saveCardSetup(configuration: com.baraa.masroof.domain.model.CardConfiguration) = registry.saveCardSetup(configuration)
    fun confirmStopTracking() = registry.confirmStopTracking()
    fun confirmStopAccountTracking() = registry.confirmStopAccountTracking()
    fun confirmCardOwned(card: ManagedCardUi) = registry.confirmCardOwned(card)
    fun markCardExternal(card: ManagedCardUi) = registry.markCardExternal(card)
    fun resumeTracking(card: ManagedCardUi) = registry.resumeTracking(card)
    fun confirmAccountOwned(account: ManagedAccountUi) = registry.confirmAccountOwned(account)
    fun markAccountExternal(account: ManagedAccountUi) = registry.markAccountExternal(account)
    fun resumeAccountTracking(account: ManagedAccountUi) = registry.resumeAccountTracking(account)
    fun confirmLoanOwned(loan: ManagedLoanUi) = registry.confirmLoanOwned(loan)
    fun markLoanExternal(loan: ManagedLoanUi) = registry.markLoanExternal(loan)
    fun resumeLoanTracking(loan: ManagedLoanUi) = registry.resumeLoanTracking(loan)
    fun requestStopLoanTracking(loan: ManagedLoanUi) = registry.requestStopLoanTracking(loan)
    fun confirmStopLoanTracking() = registry.confirmStopLoanTracking()
    fun requestDelete(target: StopTrackingTarget) = registry.requestDelete(target)
    fun dismissDelete() = registry.dismissDelete()
    fun stopTrackingInsteadOfDelete() = registry.stopTrackingInsteadOfDelete()
    fun confirmDelete() = registry.confirmDelete()
    fun clearError() = registry.clearError()
    fun addManualAccount(bankId: String, last4: String, name: String?, onSaved: () -> Unit) = registry.addManualAccount(bankId, last4, name, onSaved)
    fun addManualCard(bankId: String, last4: String, name: String?, configuration: com.baraa.masroof.domain.model.CardConfiguration, onSaved: () -> Unit) = registry.addManualCard(bankId, last4, name, configuration, onSaved)
    fun openAccountTypePicker(account: ManagedAccountUi) = registry.openAccountTypePicker(account)
    fun dismissAccountTypePicker() = registry.dismissAccountTypePicker()
    fun setAccountTypeFromPicker(accountType: AccountType) = registry.setAccountTypeFromPicker(accountType)
    fun setAccountType(account: ManagedAccountUi, accountType: AccountType) = registry.setAccountType(account, accountType)

    fun saveCommitment(commitmentId: String, draft: SettingsCommitmentsWorkflow.CommitmentEditorDraft) = commitments.saveCommitment(commitmentId, draft)
    fun setCommitmentsListTab(tab: CommitmentsListTab) = commitments.setCommitmentsListTab(tab)
    fun toggleCommitmentActive(commitmentId: String) = commitments.toggleCommitmentActive(commitmentId)
    fun deleteCommitment(commitmentId: String, onDeleted: () -> Unit = {}) = commitments.deleteCommitment(commitmentId, onDeleted)
    fun commitmentById(commitmentId: String): ManagedCommitmentUi? = commitments.commitmentById(commitmentId)

    fun reparseStoredMessages() = maintenance.reparseStoredMessages()
    fun refreshHistoricalMerchantRateCorrections() = maintenance.refreshHistoricalMerchantRateCorrections()
    fun requestHistoricalMerchantRateCorrection() = maintenance.requestHistoricalMerchantRateCorrection()
    fun dismissHistoricalMerchantRateCorrection() = maintenance.dismissHistoricalMerchantRateCorrection()
    fun confirmHistoricalMerchantRateCorrections() = maintenance.confirmHistoricalMerchantRateCorrections()
    fun clearScopedSmsImportResult() = maintenance.clearScopedSmsImportResult()
    fun refreshSmsPermissionState() = maintenance.refreshSmsPermissionState()
    fun importSelectedBankSms(bankIds: Set<String>, from: java.time.LocalDate, through: java.time.LocalDate) = maintenance.importSelectedBankSms(bankIds, from, through)
    fun logExportText(): String = maintenance.logExportText()
    fun exportLogs(uri: Uri) = maintenance.exportLogs(uri)
    val canExportLedgerShadow: Boolean get() = maintenance.canExportLedgerShadow
    fun exportLedgerShadow(uri: Uri) = maintenance.exportLedgerShadow(uri)
    fun clearLogs() = maintenance.clearLogs()
    fun clearLogMessage() = maintenance.clearLogMessage()

    fun clearUpdateMessage() = updates.clearUpdateMessage()
    fun saveGithubToken(token: String) = updates.saveGithubToken(token)
    fun clearGithubToken() = updates.clearGithubToken()
    fun setUpdateChannel(channel: UpdateChannel) = updates.setUpdateChannel(channel)
    fun checkForUpdates(silent: Boolean = false) = updates.checkForUpdates(silent)
    fun checkForUpdatesIfStale(silent: Boolean = true) = updates.checkForUpdatesIfStale(silent)
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
}
