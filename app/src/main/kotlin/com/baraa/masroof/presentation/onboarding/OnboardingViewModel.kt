package com.baraa.masroof.presentation.onboarding

import com.baraa.masroof.presentation.common.launchLogged
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.onboarding.ImportDatePolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.onboarding.HistoricalImportGateway
import com.baraa.masroof.application.onboarding.HistoricalImportFailure
import com.baraa.masroof.application.onboarding.HistoricalImportResult
import com.baraa.masroof.application.onboarding.OnboardingOwnershipWorkflow
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.CardReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class OnboardingViewModel(
    private val onboardingPrefs: OnboardingPreferencesRepository,
    private val historicalImportGateway: HistoricalImportGateway,
    private val onboardingOwnershipWorkflow: OnboardingOwnershipWorkflow,
    private val refreshReviewQueue: suspend () -> Unit,
    private val reconcileOwnershipChange: suspend (ReviewWorkflowService.OwnershipChange) -> Unit = {},
    private val databaseBackupService: DatabaseBackupGateway,
    private val permissionStateProvider: () -> Boolean,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
    val messagePatternsWorkflow: com.baraa.masroof.application.patterns.MessagePatternsWorkflow? = null,
    private val loadBankCatalog: suspend () -> com.baraa.masroof.application.patterns.PatternCatalog = {
        messagePatternsWorkflow?.catalog() ?: com.baraa.masroof.application.patterns.PatternCatalog(0, emptyList())
    },
    private val appLogService: com.baraa.masroof.application.logging.AppLogService? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()
    private var importJob: Job? = null
    private var pendingRestoreUri: Uri? = null

    init {
        reloadFromCurrentState()
    }

    fun reloadFromCurrentState() {
        viewModelScope.launch {
            val permissionGranted = permissionStateProvider()
            if (importJob?.isActive == true) {
                _uiState.update {
                    it.copy(
                        permissionGranted = permissionGranted,
                        step = OnboardingStep.IMPORTING,
                        importState = ImportState.Scanning,
                    )
                }
                return@launch
            }
            if (_uiState.value.updatingOwnership || _uiState.value.finalizing || _uiState.value.restoringBackup) {
                _uiState.update { it.copy(permissionGranted = permissionGranted) }
                return@launch
            }
            val catalog = try { loadBankCatalog() } catch (error: CancellationException) { throw error }
            catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Onboarding reloadFromCurrentState", caught);
                val completed = onboardingPrefs.isOnboardingCompleted()
                _uiState.update { it.copy(loadingBanks = false, error = OnboardingError.BANKS_LOAD_FAILED,
                    step = if (completed) OnboardingStep.HOME else it.step, onboardingCompleted = completed) }
                return@launch
            }
            // An import, finalize or restore may have started while the catalog was loading.
            if (isBusy()) {
                _uiState.update { it.copy(permissionGranted = permissionGranted) }
                return@launch
            }
            val started = onboardingPrefs.isOnboardingStarted()
            val completed = onboardingPrefs.isOnboardingCompleted()
            val savedEpoch = onboardingPrefs.getHistoricalImportStartEpochMillis()
            val savedDate = savedEpoch?.let {
                Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
            }
            val importCompleted = onboardingPrefs.isHistoricalImportCompleted()
            val draft = onboardingPrefs.getSetupDraft()
            val selected = draft?.bankIds.orEmpty().filter { id -> catalog.banks.any { it.bankId == id } }.toSet()
            val confirmed = draft?.banksConfirmed == true && selected.isNotEmpty() && selected == draft.bankIds
            val from = draft?.from?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val through = draft?.through?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val today = LocalDate.now(clock)
            val selectedDate = from ?: savedDate ?: today.minusDays(30)

            val step = when {
                completed -> OnboardingStep.HOME
                !started -> OnboardingStep.WELCOME
                !confirmed -> OnboardingStep.BANKS
                importCompleted -> OnboardingStep.OWNERSHIP
                !permissionGranted -> OnboardingStep.PERMISSION
                else -> OnboardingStep.IMPORT_DATE
            }

            _uiState.update {
                it.copy(
                    permissionGranted = permissionGranted,
                    onboardingCompleted = completed,
                    homeGuideSeen = onboardingPrefs.isHomeGuideSeen(),
                    selectedImportDate = selectedDate,
                    importThroughDate = through ?: today,
                    bankCatalog = catalog, selectedBankIds = selected, banksConfirmed = confirmed, loadingBanks = false,
                    selectedDateOption = when {
                        through != null && through != today -> ImportDateOption.CUSTOM
                        selectedDate == today.withDayOfMonth(1) -> ImportDateOption.CURRENT_MONTH_START
                        selectedDate == today.minusDays(30) -> ImportDateOption.LAST_30_DAYS
                        selectedDate == ImportDatePolicy.last27th(today) -> ImportDateOption.LAST_27TH
                        else -> ImportDateOption.CUSTOM
                    },
                    step = step,
                    importState = if (step == OnboardingStep.IMPORTING) ImportState.Scanning else ImportState.Idle,
                )
            }

            if (step == OnboardingStep.OWNERSHIP || step == OnboardingStep.HOME) {
                try { loadCandidatesAndCounts() }
                catch (error: CancellationException) { throw error }
                catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.SETTINGS, "Onboarding reloadFromCurrentState", caught); _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) } }
            }
        }
    }

    fun onStartClicked() {
        if (isBusy()) return
        onboardingPrefs.setOnboardingStarted(true)
        val permissionGranted = permissionStateProvider()
        _uiState.update {
            it.copy(
                permissionGranted = permissionGranted,
                step = OnboardingStep.BANKS,
                error = null,
            )
        }
    }

    fun toggleBank(bankId: String) {
        if (isBusy() || _uiState.value.bankCatalog?.banks?.none { it.bankId == bankId } != false) return
        _uiState.update { it.copy(selectedBankIds = if (bankId in it.selectedBankIds) it.selectedBankIds - bankId else it.selectedBankIds + bankId,
            banksConfirmed = false, error = null) }
        onboardingPrefs.setHistoricalImportCompleted(false)
        persistDraft()
    }

    fun continueFromBanks() {
        if (isBusy() || _uiState.value.selectedBankIds.isEmpty() || _uiState.value.loadingBanks || _uiState.value.bankCatalog == null) return
        val permission = permissionStateProvider()
        _uiState.update { it.copy(banksConfirmed = true, permissionGranted = permission,
            step = if (permission) OnboardingStep.IMPORT_DATE else OnboardingStep.PERMISSION, error = null) }
        persistDraft()
    }

    fun onBankAdded(bankId: String) {
        launchLogged(appLogService, AppLogCategories.SETTINGS, "Onboarding onBankAdded", onError = { _uiState.update { it.copy(loadingBanks = false, error = OnboardingError.BANKS_LOAD_FAILED) } }) {
            val catalog = loadBankCatalog()
            _uiState.update { it.copy(bankCatalog = catalog, loadingBanks = false, error = null) }
            if (bankId !in _uiState.value.selectedBankIds) toggleBank(bankId)
        }
    }

    fun goBack() {
        if (isBusy()) return
        if (_uiState.value.step == OnboardingStep.OWNERSHIP) onboardingPrefs.setHistoricalImportCompleted(false)
        _uiState.update { it.copy(step = when (it.step) {
            OnboardingStep.BANKS -> OnboardingStep.WELCOME
            OnboardingStep.PERMISSION -> OnboardingStep.BANKS
            OnboardingStep.IMPORT_DATE -> if (it.permissionGranted) OnboardingStep.BANKS else OnboardingStep.PERMISSION
            OnboardingStep.OWNERSHIP, OnboardingStep.IMPORTING -> OnboardingStep.IMPORT_DATE
            OnboardingStep.FINALIZE -> OnboardingStep.HOME
            else -> it.step
        }, error = null) }
        if (_uiState.value.step == OnboardingStep.BANKS) {
            _uiState.update { it.copy(banksConfirmed = false) }
            persistDraft()
        }
    }

    private fun isBusy() = importJob?.isActive == true || _uiState.value.updatingOwnership || _uiState.value.finalizing || _uiState.value.restoringBackup

    private fun persistDraft() {
        val state = _uiState.value
        onboardingPrefs.setSetupDraft(com.baraa.masroof.application.onboarding.OnboardingSetupDraft(state.selectedBankIds,
            (state.selectedImportDate ?: LocalDate.now(clock).minusDays(30)).toString(),
            (state.importThroughDate ?: LocalDate.now(clock)).toString(), state.banksConfirmed))
    }

    fun clearBackupError() {
        _uiState.update {
            if (it.error == OnboardingError.BACKUP_RESTORE_FAILED ||
                it.error == OnboardingError.BACKUP_RESTORE_INVALID ||
                it.error == OnboardingError.BACKUP_PASSPHRASE_REQUIRED
            ) {
                it.copy(error = null)
            } else {
                it
            }
        }
    }

    fun onRestorePassphraseChange(value: String) {
        _uiState.update { it.copy(restorePassphrase = value) }
    }

    fun restoreBackup(uri: Uri) {
        if (_uiState.value.restoringBackup) return
        launchLogged(appLogService, AppLogCategories.BACKUP, "Onboarding restoreBackup", onError = { _uiState.update { it.copy(error = OnboardingError.BACKUP_RESTORE_FAILED) } }) {
            when (databaseBackupService.inspect(uri)) {
                BackupPackageKind.LEGACY_PLAINTEXT -> {
                    pendingRestoreUri = uri
                    _uiState.update {
                        it.copy(awaitingLegacyRestoreConfirm = true, error = null)
                    }
                }
                BackupPackageKind.ENCRYPTED -> {
                    val passphrase = _uiState.value.restorePassphrase
                    if (passphrase.isEmpty()) {
                        _uiState.update { it.copy(error = OnboardingError.BACKUP_PASSPHRASE_REQUIRED) }
                    } else {
                        val secret = passphrase.toCharArray()
                        _uiState.update { it.copy(restorePassphrase = "") }
                        importRestore(uri, secret, confirmLegacyPlaintext = false)
                    }
                }
                BackupPackageKind.UNRECOGNIZED ->
                    _uiState.update { it.copy(error = OnboardingError.BACKUP_RESTORE_INVALID) }
            }
        }
    }

    fun confirmLegacyRestore() {
        val uri = pendingRestoreUri ?: return
        pendingRestoreUri = null
        _uiState.update { it.copy(awaitingLegacyRestoreConfirm = false) }
        importRestore(uri, CharArray(0), confirmLegacyPlaintext = true)
    }

    fun cancelLegacyRestore() {
        pendingRestoreUri = null
        _uiState.update { it.copy(awaitingLegacyRestoreConfirm = false) }
    }

    private fun importRestore(
        uri: Uri,
        passphrase: CharArray,
        confirmLegacyPlaintext: Boolean,
    ) {
        launchLogged(appLogService, AppLogCategories.BACKUP, "Onboarding importRestore", onError = {
            _uiState.update {
                it.copy(
                    restoringBackup = false,
                    error = OnboardingError.BACKUP_RESTORE_FAILED,
                )
            }
        }, onFinally = { passphrase.fill('\u0000') }) {
            _uiState.update { it.copy(restoringBackup = true, error = null) }
            when (
                databaseBackupService.importFrom(
                    source = uri,
                    passphrase = passphrase,
                    confirmLegacyPlaintext = confirmLegacyPlaintext,
                )
            ) {
                BackupImportOutcome.SuccessNeedsRestart -> Unit
                BackupImportOutcome.InvalidPackage ->
                    _uiState.update {
                        it.copy(
                            restoringBackup = false,
                            error = OnboardingError.BACKUP_RESTORE_INVALID,
                        )
                    }
                BackupImportOutcome.Failed ->
                    _uiState.update {
                        it.copy(
                            restoringBackup = false,
                            error = OnboardingError.BACKUP_RESTORE_FAILED,
                        )
                    }
                BackupImportOutcome.LegacyConfirmationRequired -> {
                    pendingRestoreUri = uri
                    _uiState.update {
                        it.copy(
                            restoringBackup = false,
                            awaitingLegacyRestoreConfirm = true,
                        )
                    }
                }
                BackupImportOutcome.AuthenticationFailed ->
                    _uiState.update {
                        it.copy(
                            restoringBackup = false,
                            error = OnboardingError.BACKUP_RESTORE_FAILED,
                        )
                    }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update {
            val nextStep = when (it.step) {
                OnboardingStep.PERMISSION -> if (granted) OnboardingStep.IMPORT_DATE else OnboardingStep.PERMISSION
                OnboardingStep.IMPORT_DATE,
                OnboardingStep.IMPORTING,
                -> if (granted) it.step else OnboardingStep.PERMISSION
                OnboardingStep.WELCOME,
                OnboardingStep.BANKS,
                OnboardingStep.OWNERSHIP,
                OnboardingStep.FINALIZE,
                OnboardingStep.HOME,
                -> it.step
            }
            it.copy(
                permissionGranted = granted,
                step = nextStep,
                error = if (!granted && nextStep == OnboardingStep.PERMISSION) {
                    OnboardingError.PERMISSION_DENIED
                } else if (granted && it.error == OnboardingError.PERMISSION_DENIED) {
                    null
                } else {
                    it.error
                },
            )
        }
    }

    fun selectDateOption(option: ImportDateOption) {
        if (isBusy()) return
        val today = LocalDate.now(clock)
        val date = when (option) {
            ImportDateOption.CURRENT_MONTH_START -> today.withDayOfMonth(1)
            ImportDateOption.LAST_30_DAYS -> today.minusDays(30)
            ImportDateOption.LAST_27TH -> ImportDatePolicy.last27th(today)
            ImportDateOption.CUSTOM -> _uiState.value.selectedImportDate ?: ImportDatePolicy.last27th(today)
        }
        _uiState.update { it.copy(selectedDateOption = option, selectedImportDate = date,
            importThroughDate = if (option == ImportDateOption.CUSTOM) it.importThroughDate else today, error = null) }
        persistDraft()
    }

    fun selectCustomDate(date: LocalDate) {
        if (isBusy()) return
        val today = LocalDate.now(clock)
        if (date.isAfter(today)) {
            _uiState.update { it.copy(error = OnboardingError.INVALID_FUTURE_DATE) }
            return
        }
        _uiState.update {
            it.copy(
                selectedDateOption = ImportDateOption.CUSTOM,
                selectedImportDate = date,
                error = null,
            )
        }
        persistDraft()
    }

    fun selectImportThroughDate(date: LocalDate) {
        if (isBusy()) return
        if (date.isAfter(LocalDate.now(clock))) { _uiState.update { it.copy(error = OnboardingError.INVALID_FUTURE_DATE) }; return }
        _uiState.update { it.copy(importThroughDate = date, error = null) }
        persistDraft()
    }

    fun startImport() {
        if (importJob?.isActive == true || _uiState.value.importState is ImportState.Scanning) {
            return
        }
        val selection = _uiState.value
        val from = selection.selectedImportDate ?: LocalDate.now(clock).minusDays(30)
        val through = selection.importThroughDate ?: LocalDate.now(clock)
        if (!selection.banksConfirmed || selection.selectedBankIds.isEmpty() || selection.bankCatalog == null ||
            selection.selectedBankIds.any { id -> selection.bankCatalog.banks.none { it.bankId == id } } ||
            from.isAfter(through) || through.isAfter(LocalDate.now(clock))) {
            _uiState.update { it.copy(error = OnboardingError.INVALID_IMPORT_SELECTION) }; return
        }
        if (!permissionStateProvider()) { _uiState.update { it.copy(step = OnboardingStep.PERMISSION, permissionGranted = false, error = OnboardingError.PERMISSION_DENIED) }; return }
        persistDraft()

        importJob = viewModelScope.launch {
            try {
                val selectedDate = from
                val startInstant = ImportDatePolicy.toStartOfDayInstant(selectedDate, zoneId)

                onboardingPrefs.setHistoricalImportStartEpochMillis(startInstant.toEpochMilli())
                onboardingPrefs.setHistoricalImportCompleted(false)
                _uiState.update { it.copy(step = OnboardingStep.IMPORTING, importState = ImportState.Scanning, importScanned = 0, error = null) }

                val result = historicalImportGateway.scan(com.baraa.masroof.application.onboarding.OnboardingImportRequest(selection.selectedBankIds, from, through,
                    onProgress = { scanned -> _uiState.update { it.copy(importScanned = scanned) } }))
                val state = result.toImportState()
                _uiState.update { it.copy(importState = state) }

                when (state) {
                    is ImportState.Completed -> {
                        try {
                            // The shared importer already completed discovery/reconciliation for this batch.
                            loadCandidatesAndCounts()
                            onboardingPrefs.setHistoricalImportCompleted(true)
                            _uiState.update { it.copy(step = OnboardingStep.OWNERSHIP) }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.SCAN, "Onboarding startImport", caught);
                            onboardingPrefs.setHistoricalImportCompleted(false)
                            _uiState.update {
                                it.copy(
                                    importState = ImportState.ProviderError(
                                        HistoricalImportResult(failure = HistoricalImportFailure.ProviderError("post_scan_setup_failed")),
                                    ),
                                    error = OnboardingError.IMPORT_FAILED,
                                )
                            }
                        }
                    }
                    is ImportState.PermissionError -> {
                        _uiState.update {
                            it.copy(
                                step = OnboardingStep.PERMISSION,
                                error = OnboardingError.PERMISSION_DENIED,
                            )
                        }
                    }
                    is ImportState.ProviderError -> {
                        _uiState.update {
                            it.copy(error = if (state.result.failure is HistoricalImportFailure.ProcessingIncomplete) {
                                OnboardingError.IMPORT_FAILED
                            } else {
                                OnboardingError.SMS_PROVIDER_ERROR
                            })
                        }
                    }
                    else -> {
                        _uiState.update { it.copy(error = OnboardingError.IMPORT_FAILED) }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.SCAN, "Onboarding startImport", caught);
                _uiState.update {
                    it.copy(
                                importState = ImportState.ProviderError(
                                    HistoricalImportResult(failure = HistoricalImportFailure.ProviderError("scan_failed")),
                                ),
                        error = OnboardingError.IMPORT_FAILED,
                    )
                }
            }
        }
    }

    fun setAccountOwnership(candidate: OwnershipCandidateUi, owned: Boolean) {
        if (isBusy() || candidate.bank.id !in _uiState.value.selectedBankIds) return
        _uiState.update { it.copy(updatingOwnership = true, error = null) }
        launchLogged(appLogService, AppLogCategories.OWNERSHIP, "Onboarding setAccountOwnership", onError = {
            reloadAfterOwnershipFailure()
            _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
        }, onFinally = { _uiState.update { it.copy(updatingOwnership = false) } }) {
            val ref = AccountReference(candidate.bank, candidate.suffix)
            if (owned) {
                onboardingOwnershipWorkflow.confirmAccountOwned(ref)
            } else {
                onboardingOwnershipWorkflow.markAccountExternal(ref)
            }
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(ref))
            loadCandidatesAndCounts()
        }
    }

    fun setCardOwnership(candidate: OwnershipCandidateUi, owned: Boolean) {
        if (isBusy() || candidate.bank.id !in _uiState.value.selectedBankIds) return
        _uiState.update { it.copy(updatingOwnership = true, error = null) }
        launchLogged(appLogService, AppLogCategories.OWNERSHIP, "Onboarding setCardOwnership", onError = {
            reloadAfterOwnershipFailure()
            _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
        }, onFinally = { _uiState.update { it.copy(updatingOwnership = false) } }) {
            val ref = CardReference(candidate.bank, candidate.suffix)
            if (owned) {
                onboardingOwnershipWorkflow.confirmCardOwned(ref)
            } else {
                onboardingOwnershipWorkflow.markCardExternal(ref)
            }
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Card(ref))
            loadCandidatesAndCounts()
        }
    }

    fun finalizeOnboarding() {
        if (isBusy()) return
        _uiState.update { it.copy(finalizing = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = onboardingOwnershipWorkflow.loadSnapshot(_uiState.value.selectedBankIds)
                if (!_uiState.value.banksConfirmed || snapshot.hasUnknownCandidates || snapshot.cards.any { it.ownership == com.baraa.masroof.domain.model.OwnershipStatus.OWNED && it.cardType == null }) {
                    _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
                    return@launch
                }
                refreshReviewQueue()
                loadCandidatesAndCounts()
                onboardingPrefs.setOnboardingCompleted(true)
                _uiState.update {
                    it.copy(
                        onboardingCompleted = true,
                        finalizing = false,
                        step = OnboardingStep.FINALIZE,
                    )
                }
            } catch (error: CancellationException) { throw error }
            catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.OWNERSHIP, "Onboarding finalizeOnboarding", caught);
                _uiState.update { it.copy(finalizing = false, error = OnboardingError.FINALIZATION_FAILED) }
            } finally { _uiState.update { it.copy(finalizing = false) } }
        }
    }

    fun enterApp() {
        if (onboardingPrefs.isOnboardingCompleted()) _uiState.update { it.copy(step = OnboardingStep.HOME, homeGuideSeen = onboardingPrefs.isHomeGuideSeen()) }
    }

    fun setHomeGuideSeen(seen: Boolean) {
        onboardingPrefs.setHomeGuideSeen(seen)
        _uiState.update { it.copy(homeGuideSeen = seen) }
    }

    private suspend fun loadCandidatesAndCounts() {
        val snapshot = onboardingOwnershipWorkflow.loadSnapshot(_uiState.value.selectedBankIds)
        _uiState.update {
            it.copy(
                accounts = snapshot.accounts.map(::toOwnershipCandidateUi),
                cards = snapshot.cards.map(::toOwnershipCandidateUi),
                ownedAccountsCount = snapshot.ownedAccountsCount,
                ownedCardsCount = snapshot.ownedCardsCount,
                reviewRequiredCount = snapshot.reviewRequiredCount,
            )
        }
    }

    private suspend fun reloadAfterOwnershipFailure() {
        try { loadCandidatesAndCounts() }
        catch (error: CancellationException) { throw error }
        catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.OWNERSHIP, "Onboarding reloadAfterOwnershipFailure", caught); /* Preserve the durable decision and show the write/retry failure. */ }
    }

    fun refreshOwnership() {
        if (_uiState.value.step != OnboardingStep.OWNERSHIP || isBusy()) return
        launchLogged(appLogService, AppLogCategories.OWNERSHIP, "Onboarding refreshOwnership", onError = { _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) } }) {
            loadCandidatesAndCounts()
        }
    }

    private fun toOwnershipCandidateUi(
        candidate: OnboardingOwnershipWorkflow.OwnershipCandidate,
    ): OwnershipCandidateUi =
        OwnershipCandidateUi(
            kind = when (candidate.kind) {
                OnboardingOwnershipWorkflow.CandidateKind.ACCOUNT ->
                    OwnershipCandidateUi.CandidateKind.ACCOUNT
                OnboardingOwnershipWorkflow.CandidateKind.CARD ->
                    OwnershipCandidateUi.CandidateKind.CARD
            },
            bank = candidate.bank,
            suffix = candidate.suffix,
            ownership = candidate.ownership,
            cardType = candidate.cardType,
            discoveredFromSms = candidate.discoveredFromSms,
        )
}
