package com.baraa.masroof.presentation.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.theme.ThemePreferencesRepository
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.update.AppUpdateService
import com.baraa.masroof.application.update.ApkInstaller
import com.baraa.masroof.application.update.InstalledBuildInfo
import com.baraa.masroof.application.update.PrivateRepoRequiresTokenException
import com.baraa.masroof.application.update.UpdateChannel
import com.baraa.masroof.application.update.UpdateCheckCoordinator
import com.baraa.masroof.application.update.UpdateCheckResult
import com.baraa.masroof.application.update.UpdateManifest
import com.baraa.masroof.application.onboarding.HistoricalImportResult
import com.baraa.masroof.application.onboarding.HistoricalImportUserOutcome
import com.baraa.masroof.application.onboarding.userOutcome
import com.baraa.masroof.application.settings.CommitmentHistoryBuilder
import com.baraa.masroof.application.settings.CommitmentRecordBuilder
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
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
    private val reparseStoredEvents: suspend () -> Int,
    private val importSmsFromInbox: suspend () -> HistoricalImportResult,
    private val permissionStateProvider: () -> Boolean,
    private val appVersion: String,
    private val appUpdateService: AppUpdateService,
    private val updateCheckCoordinator: UpdateCheckCoordinator,
    private val appLogService: AppLogService,
    private val apkInstaller: ApkInstaller,
    private val canInstallPackages: () -> Boolean,
    private val onRequestInstallPermission: () -> Unit = {},
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
    private val _logEntries = MutableStateFlow(appLogService.readAll())
    val logEntries: StateFlow<List<com.baraa.masroof.application.logging.AppLogEntry>> = _logEntries.asStateFlow()
    private var pendingImportUri: Uri? = null

    fun refreshLogs() {
        _logEntries.value = appLogService.readAll()
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
            restorePendingUpdateState()
            try {
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
            } catch (_: Exception) {
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
        _uiState.update {
            it.copy(
                stopConfirmCardTarget = card,
                stopConfirmAccountTarget = null,
                stopConfirmLoanTarget = null,
            )
        }
    }

    fun requestStopAccountTracking(account: ManagedAccountUi) {
        _uiState.update {
            it.copy(
                stopConfirmAccountTarget = account,
                stopConfirmCardTarget = null,
                stopConfirmLoanTarget = null,
            )
        }
    }

    fun dismissStopConfirm() {
        _uiState.update {
            it.copy(
                stopConfirmCardTarget = null,
                stopConfirmAccountTarget = null,
                stopConfirmLoanTarget = null,
            )
        }
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
        updateCardMetadata(target) {
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
        updateAccountMetadata(target) {
            settingsRegistryWorkflow.updateAccountDisplayName(
                AccountReference(target.bank, target.maskedNumber),
                name.trim().ifEmpty { null },
            )
        }
    }

    fun openCardNetworkPicker(card: ManagedCardUi) {
        _uiState.update { it.copy(cardNetworkTarget = card) }
    }

    fun dismissCardNetworkPicker() {
        _uiState.update { it.copy(cardNetworkTarget = null) }
    }

    fun setCardNetwork(network: CardNetwork?) {
        val target = _uiState.value.cardNetworkTarget ?: return
        dismissCardNetworkPicker()
        updateCardMetadata(target) {
            settingsRegistryWorkflow.updateCardNetwork(
                CardReference(target.bank, target.last4),
                network,
            )
        }
    }

    fun openCardRolePicker(card: ManagedCardUi) {
        _uiState.update { it.copy(cardRoleTarget = card) }
    }

    fun dismissCardRolePicker() {
        _uiState.update { it.copy(cardRoleTarget = null) }
    }

    fun setPrimaryCard(card: ManagedCardUi) {
        dismissCardRolePicker()
        updateCardMetadata(card) {
            settingsRegistryWorkflow.setPrimaryCard(CardReference(card.bank, card.last4))
        }
    }

    fun setSupplementaryCard(card: ManagedCardUi, primaryLast4: String) {
        dismissCardRolePicker()
        updateCardMetadata(card) {
            settingsRegistryWorkflow.setSupplementaryCard(
                CardReference(card.bank, card.last4),
                primaryLast4,
            )
        }
    }

    fun clearCardRole(card: ManagedCardUi) {
        dismissCardRolePicker()
        updateCardMetadata(card) {
            settingsRegistryWorkflow.clearCardRole(CardReference(card.bank, card.last4))
        }
    }

    fun openLinkDebitCard(card: ManagedCardUi) {
        _uiState.update { it.copy(linkDebitTarget = card) }
    }

    fun dismissLinkDebitCard() {
        _uiState.update { it.copy(linkDebitTarget = null) }
    }

    fun linkDebitToAccount(card: ManagedCardUi, account: ManagedAccountUi) {
        dismissLinkDebitCard()
        updateCardMetadata(card) {
            settingsRegistryWorkflow.linkDebitToAccount(
                CardReference(card.bank, card.last4),
                AccountReference(account.bank, account.maskedNumber),
            )
        }
    }

    fun markCardAsDebit(card: ManagedCardUi) {
        updateCardMetadata(card) {
            settingsRegistryWorkflow.markCardAsDebit(CardReference(card.bank, card.last4))
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
        updateCardOwnership(card, owned = true)
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
        _uiState.update {
            it.copy(
                stopConfirmLoanTarget = loan,
                stopConfirmCardTarget = null,
                stopConfirmAccountTarget = null,
            )
        }
    }

    fun confirmStopLoanTracking() {
        val target = _uiState.value.stopConfirmLoanTarget ?: return
        dismissStopConfirm()
        markLoanExternal(target)
    }

    fun reparseStoredMessages() {
        if (_uiState.value.reparsingStored || _uiState.value.updating || _uiState.value.importingSms) return
        viewModelScope.launch {
            _uiState.update { it.copy(reparsingStored = true, error = null) }
            try {
                val count = reparseStoredEvents()
                refreshReviewQueue()
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

    fun importSmsFromPhone() {
        if (_uiState.value.importingSms || _uiState.value.reparsingStored) return
        if (!permissionStateProvider()) {
            _uiState.update { it.copy(smsImportMessage = SmsImportMessage.PERMISSION_DENIED) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(importingSms = true, smsImportMessage = null, error = null) }
            try {
                val result = importSmsFromInbox()
                refreshReviewQueue()
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                appLogService.info(
                    AppLogCategories.SETTINGS,
                    "SMS import finished: scanned=${result.scanned}, inserted=${result.inserted}, " +
                        "parsed=${result.parsed}, failed=${result.failed}",
                )
                _uiState.update {
                    it.copy(
                        importingSms = false,
                        smsImportMessage = mapSmsImportMessage(result),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                appLogService.error(
                    AppLogCategories.SETTINGS,
                    "SMS import failed: ${error.message ?: error::class.java.simpleName}",
                )
                _uiState.update {
                    it.copy(importingSms = false, smsImportMessage = SmsImportMessage.FAILED)
                }
            }
        }
    }

    fun clearSmsImportMessage() {
        _uiState.update { it.copy(smsImportMessage = null) }
    }

    private fun mapSmsImportMessage(result: HistoricalImportResult): SmsImportMessage =
        when (result.userOutcome()) {
            HistoricalImportUserOutcome.PERMISSION_DENIED -> SmsImportMessage.PERMISSION_DENIED
            HistoricalImportUserOutcome.FAILED -> SmsImportMessage.FAILED
            HistoricalImportUserOutcome.NO_MESSAGES -> SmsImportMessage.NO_MESSAGES
            HistoricalImportUserOutcome.NO_BANK_SMS -> SmsImportMessage.NO_BANK_SMS
            HistoricalImportUserOutcome.OK -> SmsImportMessage.OK
            HistoricalImportUserOutcome.ALREADY_UP_TO_DATE -> SmsImportMessage.ALREADY_UP_TO_DATE
            HistoricalImportUserOutcome.NEEDS_REVIEW -> SmsImportMessage.NEEDS_REVIEW
            HistoricalImportUserOutcome.NO_NEW_TRANSACTIONS -> SmsImportMessage.NO_TRANSACTIONS
        }

    fun clearUpdateMessage() {
        _uiState.update { it.copy(updateMessage = null) }
    }

    fun saveGithubToken(token: String) {
        if (token.isBlank()) return
        appUpdateService.saveToken(token)
        _uiState.update {
            it.copy(
                githubTokenConfigured = true,
                updateMessage = AppUpdateMessage.TOKEN_SAVED,
            )
        }
    }

    fun clearGithubToken() {
        appUpdateService.clearDownloadCache()
        appUpdateService.clearToken()
        updateCheckCoordinator.clearPendingUpdate()
        _uiState.update {
            it.copy(
                githubTokenConfigured = false,
                updateState = AppUpdateUiState.Idle,
                updateMessage = null,
            )
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        if (channel == appUpdateService.getUpdateChannel()) return
        appUpdateService.setUpdateChannel(channel)
        updateCheckCoordinator.clearPendingUpdate()
        _uiState.update {
            it.copy(
                updateChannel = channel,
                updateState = AppUpdateUiState.Idle,
                updateMessage = null,
            )
        }
        checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (_uiState.value.updateState is AppUpdateUiState.Checking ||
            _uiState.value.updateState is AppUpdateUiState.Downloading
        ) {
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    updateState = AppUpdateUiState.Checking,
                    updateMessage = null,
                    githubTokenConfigured = appUpdateService.hasConfiguredToken(),
                )
            }
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        updateCheckCoordinator.checkForUpdate(
                            if (silent) "background" else "manual",
                        ).getOrThrow()
                    }
                when (result) {
                    UpdateCheckResult.UpToDate ->
                        _uiState.update {
                            it.copy(
                                updateState = AppUpdateUiState.UpToDate,
                                updateMessage = if (silent) null else AppUpdateMessage.UP_TO_DATE,
                            )
                        }

                    is UpdateCheckResult.UpdateAvailable ->
                        applyAvailableUpdate(result.manifest, silent)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                if (!silent) {
                    val message =
                        when {
                            error is PrivateRepoRequiresTokenException -> AppUpdateMessage.TOKEN_REQUIRED
                            error.message?.contains("authentication failed", ignoreCase = true) == true ->
                                AppUpdateMessage.AUTH_FAILED
                            else -> AppUpdateMessage.CHECK_FAILED
                        }
                    _uiState.update {
                        it.copy(updateState = AppUpdateUiState.Idle, updateMessage = message)
                    }
                    restorePendingUpdateState()
                } else {
                    _uiState.update { current ->
                        if (current.updateState is AppUpdateUiState.Available ||
                            current.updateState is AppUpdateUiState.ReadyToInstall ||
                            current.updateState is AppUpdateUiState.UpToDate
                        ) {
                            current
                        } else {
                            current.copy(updateState = AppUpdateUiState.Idle)
                        }
                    }
                    restorePendingUpdateState()
                }
            }
        }
    }

    fun checkForUpdatesIfStale(silent: Boolean = true) {
        if (!updateCheckCoordinator.shouldCheckNow()) {
            restorePendingUpdateState()
            return
        }
        checkForUpdates(silent = silent)
    }

    fun exportLogs(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(exportingLogs = true, logMessage = null) }
            try {
                withContext(Dispatchers.IO) {
                    appLogService.exportTo(uri).getOrThrow()
                }
                _uiState.update {
                    it.copy(exportingLogs = false, logMessage = LogMessage.EXPORT_SUCCESS)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(exportingLogs = false, logMessage = LogMessage.EXPORT_FAILED)
                }
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

    fun downloadUpdate() {
        val state = _uiState.value.updateState
        val manifest =
            when (state) {
                is AppUpdateUiState.Available -> state.manifest
                is AppUpdateUiState.ReadyToInstall -> state.manifest
                else -> return
            }
        if (_uiState.value.updateState is AppUpdateUiState.Downloading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    updateState = AppUpdateUiState.Downloading(manifest, 0L, 0L),
                    updateMessage = null,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    appUpdateService
                        .downloadUpdate(
                            manifest = manifest,
                            onProgress = { bytesRead, totalBytes ->
                                _uiState.update {
                                    it.copy(
                                        updateState = AppUpdateUiState.Downloading(
                                            manifest = manifest,
                                            bytesRead = bytesRead,
                                            totalBytes = totalBytes,
                                        ),
                                    )
                                }
                            },
                        )
                        .getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        updateState = AppUpdateUiState.ReadyToInstall(manifest),
                        updateMessage = AppUpdateMessage.DOWNLOAD_SUCCESS,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        updateState = AppUpdateUiState.Available(manifest),
                        updateMessage = AppUpdateMessage.DOWNLOAD_FAILED,
                    )
                }
            }
        }
    }

    fun installPendingUpdate() {
        val state = _uiState.value.updateState
        if (state !is AppUpdateUiState.ReadyToInstall) return

        if (!canInstallPackages()) {
            _uiState.update { it.copy(updateMessage = AppUpdateMessage.INSTALL_PERMISSION_REQUIRED) }
            onRequestInstallPermission()
            return
        }

        val apkFile = appUpdateService.updateApkFile(state.manifest)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    apkInstaller.install(apkFile).getOrThrow()
                }
                dismissUpdateUiWhileInstalling()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(updateMessage = AppUpdateMessage.INSTALL_FAILED) }
            }
        }
    }

    fun retryInstallAfterPermissionGranted() {
        if (!canInstallPackages()) return
        installPendingUpdate()
    }

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

    fun clearBackupMessage() {
        _uiState.update { it.copy(backupMessage = null) }
    }

    fun offerImport(uri: Uri) {
        if (_uiState.value.exportingBackup || _uiState.value.importingBackup) return
        pendingImportUri = uri
        _uiState.update { it.copy(awaitingImportConfirm = true, backupMessage = null) }
    }

    fun cancelPendingImport() {
        pendingImportUri = null
        _uiState.update { it.copy(awaitingImportConfirm = false) }
    }

    fun confirmPendingImport() {
        val uri = pendingImportUri ?: return
        pendingImportUri = null
        _uiState.update { it.copy(awaitingImportConfirm = false) }
        importBackup(uri)
    }

    fun exportBackup(uri: Uri) {
        if (_uiState.value.exportingBackup || _uiState.value.importingBackup) return
        viewModelScope.launch {
            _uiState.update { it.copy(exportingBackup = true, backupMessage = null, error = null) }
            try {
                databaseBackupService.exportTo(uri).getOrThrow()
                _uiState.update {
                    it.copy(exportingBackup = false, backupMessage = BackupMessage.EXPORT_SUCCESS)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(exportingBackup = false, backupMessage = BackupMessage.EXPORT_FAILED)
                }
            }
        }
    }

    fun importBackup(uri: Uri) {
        if (_uiState.value.exportingBackup || _uiState.value.importingBackup) return
        viewModelScope.launch {
            _uiState.update { it.copy(importingBackup = true, backupMessage = null, error = null) }
            try {
                when (databaseBackupService.importFrom(uri)) {
                    BackupImportOutcome.SuccessNeedsRestart -> {
                        // Process restarts inside the service after a successful restore.
                    }
                    BackupImportOutcome.InvalidPackage ->
                        _uiState.update {
                            it.copy(
                                importingBackup = false,
                                backupMessage = BackupMessage.IMPORT_INVALID,
                            )
                        }
                    BackupImportOutcome.Failed ->
                        _uiState.update {
                            it.copy(
                                importingBackup = false,
                                backupMessage = BackupMessage.IMPORT_FAILED,
                            )
                        }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(importingBackup = false, backupMessage = BackupMessage.IMPORT_FAILED)
                }
            }
        }
    }

    private fun updateCardMetadata(_card: ManagedCardUi, action: suspend () -> Unit) {
        if (_uiState.value.updating) return
        viewModelScope.launch {
            _uiState.update { it.copy(updating = true, error = null) }
            try {
                action()
                refreshReviewQueue()
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                _uiState.update { it.copy(updating = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(updating = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    private fun updateAccountMetadata(_account: ManagedAccountUi, action: suspend () -> Unit) {
        if (_uiState.value.updating) return
        viewModelScope.launch {
            _uiState.update { it.copy(updating = true, error = null) }
            try {
                action()
                refreshReviewQueue()
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                _uiState.update { it.copy(updating = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(updating = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    private fun updateCardOwnership(card: ManagedCardUi, owned: Boolean) {
        if (_uiState.value.updating) return
        viewModelScope.launch {
            _uiState.update { it.copy(updating = true, error = null) }
            try {
                val ref = CardReference(card.bank, card.last4)
                if (owned) {
                    settingsRegistryWorkflow.confirmCardOwned(ref)
                } else {
                    settingsRegistryWorkflow.markCardExternal(ref)
                }
                reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Card(ref))
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                _uiState.update { it.copy(updating = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(updating = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    private fun updateAccountOwnership(account: ManagedAccountUi, owned: Boolean) {
        if (_uiState.value.updating) return
        viewModelScope.launch {
            _uiState.update { it.copy(updating = true, error = null) }
            try {
                val ref = AccountReference(account.bank, account.maskedNumber)
                if (owned) {
                    settingsRegistryWorkflow.confirmAccountOwned(ref)
                } else {
                    settingsRegistryWorkflow.markAccountExternal(ref)
                }
                reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(ref))
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                _uiState.update { it.copy(updating = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(updating = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    private fun updateLoanOwnership(loan: ManagedLoanUi, owned: Boolean) {
        if (_uiState.value.updating) return
        viewModelScope.launch {
            _uiState.update { it.copy(updating = true, error = null) }
            try {
                val ref = LoanReference(loan.bank, loan.loanType)
                if (owned) {
                    settingsRegistryWorkflow.confirmLoanOwned(ref)
                } else {
                    settingsRegistryWorkflow.markLoanExternal(ref)
                }
                reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Loan(ref))
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                _uiState.update { it.copy(updating = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(updating = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    private fun restorePendingUpdateState() {
        if (_uiState.value.updateState is AppUpdateUiState.Checking) {
            return
        }
        if (_uiState.value.updateState is AppUpdateUiState.Downloading) {
            val downloading = _uiState.value.updateState as AppUpdateUiState.Downloading
            clearStaleUpdateUiIfInstalled(downloading.manifest)
            return
        }
        val manifest = updateCheckCoordinator.restorePendingUpdate()
        if (manifest == null) {
            clearStaleUpdateUiIfInstalled()
            return
        }
        applyAvailableUpdate(manifest, silent = true)
    }

    private fun dismissUpdateUiWhileInstalling() {
        _uiState.update { it.copy(updateState = AppUpdateUiState.Idle, updateMessage = null) }
    }

    private fun clearStaleUpdateUiIfInstalled(manifest: UpdateManifest? = null) {
        val resolvedManifest =
            manifest ?: when (val current = _uiState.value.updateState) {
                is AppUpdateUiState.Available -> current.manifest
                is AppUpdateUiState.ReadyToInstall -> current.manifest
                is AppUpdateUiState.Downloading -> current.manifest
                else -> return
            }
        if (!appUpdateService.isUpdateStillNeeded(resolvedManifest)) {
            appUpdateService.clearDownloadedApk(resolvedManifest)
            updateCheckCoordinator.clearPendingUpdate()
            _uiState.update { it.copy(updateState = AppUpdateUiState.Idle, updateMessage = null) }
        }
    }

    private fun applyAvailableUpdate(
        manifest: com.baraa.masroof.application.update.UpdateManifest,
        silent: Boolean,
    ) {
        val downloaded = appUpdateService.updateApkFile(manifest)
        val nextState =
            if (
                downloaded.exists() &&
                com.baraa.masroof.application.update.ApkIntegrityVerifier.matches(downloaded, manifest.sha256)
            ) {
                AppUpdateUiState.ReadyToInstall(manifest)
            } else {
                if (downloaded.exists()) downloaded.delete()
                AppUpdateUiState.Available(manifest)
            }
        _uiState.update {
            it.copy(
                updateState = nextState,
                updateMessage = if (silent) null else AppUpdateMessage.UPDATE_AVAILABLE,
            )
        }
    }

    fun saveCommitment(commitmentId: String, draft: SettingsCommitmentsWorkflow.CommitmentEditorDraft) {
        viewModelScope.launch {
            _uiState.update { it.copy(savingCommitment = true) }
            try {
                settingsCommitmentsWorkflow.update(commitmentId, draft)
                applyCommitments(settingsCommitmentsWorkflow.listAll())
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(error = SettingsError.UPDATE_FAILED) }
            } finally {
                _uiState.update { it.copy(savingCommitment = false) }
            }
        }
    }

    fun setCommitmentsListTab(tab: CommitmentsListTab) {
        _uiState.update { it.copy(commitmentsListTab = tab) }
    }

    fun toggleCommitmentActive(commitmentId: String) {
        viewModelScope.launch {
            settingsCommitmentsWorkflow.toggleActive(commitmentId)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
        }
    }

    fun deleteCommitment(commitmentId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            settingsCommitmentsWorkflow.delete(commitmentId)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
            onDeleted()
        }
    }

    fun commitmentById(commitmentId: String): ManagedCommitmentUi? =
        (_uiState.value.activeCommitments + _uiState.value.disabledCommitments)
            .find { it.id == commitmentId }

    private fun applyCommitments(commitments: List<Commitment>) {
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
        _uiState.update {
            it.copy(
                activeCommitments = items.filter { item -> item.active },
                disabledCommitments = disabledItems,
                commitmentRecordEntries = recordEntries,
                commitmentsLoaded = true,
            )
        }
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
        val commitmentItems = commitments?.map { commitment ->
            toManagedCommitmentUi(commitment, java.time.ZoneId.systemDefault())
        }
        val disabledItems = commitments?.let { list ->
            CommitmentHistoryBuilder.disabledTabItems(list, java.time.ZoneId.systemDefault())
                .mapNotNull { historyItem ->
                    commitmentItems?.firstOrNull { it.id == historyItem.commitmentId }
                }
        }
        val recordEntries = commitments?.let { list ->
            CommitmentRecordBuilder.recordTabItems(list)
                .map { entry ->
                    CommitmentRecordEntryUi(
                        commitmentId = entry.commitmentId,
                        commitmentName = entry.commitmentName,
                        event = entry.event,
                        at = entry.at.atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                    )
                }
        }
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
                activeCommitments = commitmentItems?.filter { item -> item.active } ?: it.activeCommitments,
                disabledCommitments = disabledItems ?: it.disabledCommitments,
                commitmentRecordEntries = recordEntries ?: it.commitmentRecordEntries,
                commitmentsLoaded = commitments?.let { true } ?: it.commitmentsLoaded,
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
            )
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
                loans.map { it.bank.id }
        ).distinct().sorted()
        return bankIds.mapNotNull { bankId ->
            val bankAccounts = accounts.filter { it.bank.id == bankId }
            val bankCards = cards.filter { it.bank.id == bankId }
            val bankLoans = loans.filter { it.bank.id == bankId }
            if (bankAccounts.isEmpty() && bankCards.isEmpty() && bankLoans.isEmpty()) {
                return@mapNotNull null
            }
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
                    debit.last4 !in assignedDebitLast4s && debitMatchesAccount(debit, account)
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

    private fun debitMatchesAccount(debit: ManagedCardUi, account: ManagedAccountUi): Boolean {
        val linked = debit.linkedAccountMaskedNumber
        if (linked != null) {
            return account.maskedNumber == linked ||
                account.maskedNumber.endsWith(linked) ||
                linked.endsWith(account.maskedNumber.takeLast(4))
        }
        return false
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
        updateAccountMetadata(account) {
            settingsRegistryWorkflow.updateAccountType(
                AccountReference(account.bank, account.maskedNumber),
                accountType,
            )
        }
        dismissAccountTypePicker()
    }
}
