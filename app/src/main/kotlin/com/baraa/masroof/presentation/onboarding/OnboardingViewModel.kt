package com.baraa.masroof.presentation.onboarding

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
    private val discoverFromStoredEvents: suspend () -> Int,
    private val refreshReviewQueue: suspend () -> Unit,
    private val reconcileOwnershipChange: suspend (ReviewWorkflowService.OwnershipChange) -> Unit = {},
    private val databaseBackupService: DatabaseBackupGateway,
    private val permissionStateProvider: () -> Boolean,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
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
                        step = if (permissionGranted) OnboardingStep.IMPORTING else OnboardingStep.PERMISSION,
                        importState = ImportState.Scanning,
                    )
                }
                return@launch
            }
            val started = onboardingPrefs.isOnboardingStarted()
            val completed = onboardingPrefs.isOnboardingCompleted()
            val savedEpoch = onboardingPrefs.getHistoricalImportStartEpochMillis()
            val savedDate = savedEpoch?.let {
                Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
            }
            val importCompleted = onboardingPrefs.isHistoricalImportCompleted()

            val step = when {
                completed -> OnboardingStep.HOME
                !started -> OnboardingStep.WELCOME
                !permissionGranted -> OnboardingStep.PERMISSION
                !importCompleted -> OnboardingStep.IMPORT_DATE
                else -> OnboardingStep.OWNERSHIP
            }

            _uiState.update {
                it.copy(
                    permissionGranted = permissionGranted,
                    onboardingCompleted = completed,
                    selectedImportDate = savedDate ?: ImportDatePolicy.last27th(LocalDate.now(clock)),
                    selectedDateOption = if (savedDate == null) ImportDateOption.LAST_27TH else it.selectedDateOption,
                    step = step,
                    importState = if (step == OnboardingStep.IMPORTING) ImportState.Scanning else ImportState.Idle,
                )
            }

            if (step == OnboardingStep.OWNERSHIP || step == OnboardingStep.HOME) {
                loadCandidatesAndCounts()
            }
        }
    }

    fun onStartClicked() {
        onboardingPrefs.setOnboardingStarted(true)
        val permissionGranted = permissionStateProvider()
        _uiState.update {
            it.copy(
                permissionGranted = permissionGranted,
                step = if (permissionGranted) OnboardingStep.IMPORT_DATE else OnboardingStep.PERMISSION,
                error = if (permissionGranted) null else OnboardingError.PERMISSION_DENIED,
            )
        }
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
        viewModelScope.launch {
            try {
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(error = OnboardingError.BACKUP_RESTORE_FAILED) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(restoringBackup = true, error = null) }
            try {
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        restoringBackup = false,
                        error = OnboardingError.BACKUP_RESTORE_FAILED,
                    )
                }
            } finally {
                passphrase.fill('\u0000')
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
        val today = LocalDate.now(clock)
        val date = when (option) {
            ImportDateOption.CURRENT_MONTH_START -> today.withDayOfMonth(1)
            ImportDateOption.LAST_30_DAYS -> today.minusDays(30)
            ImportDateOption.LAST_27TH -> ImportDatePolicy.last27th(today)
            ImportDateOption.CUSTOM -> _uiState.value.selectedImportDate ?: ImportDatePolicy.last27th(today)
        }
        _uiState.update { it.copy(selectedDateOption = option, selectedImportDate = date, error = null) }
    }

    fun selectCustomDate(date: LocalDate) {
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
    }

    fun startImport() {
        if (importJob?.isActive == true || _uiState.value.importState is ImportState.Scanning) {
            return
        }

        importJob = viewModelScope.launch {
            try {
                val selectedDate = _uiState.value.selectedImportDate ?: ImportDatePolicy.last27th(LocalDate.now(clock))
                val startInstant = ImportDatePolicy.toStartOfDayInstant(selectedDate, zoneId)

                onboardingPrefs.setHistoricalImportStartEpochMillis(startInstant.toEpochMilli())
                onboardingPrefs.setHistoricalImportCompleted(false)
                _uiState.update { it.copy(step = OnboardingStep.IMPORTING, importState = ImportState.Scanning, error = null) }

                val result = historicalImportGateway.scan(receivedAfter = startInstant)
                val state = result.toImportState()
                _uiState.update { it.copy(importState = state) }

                when (state) {
                    is ImportState.Completed -> {
                        try {
                            discoverFromStoredEvents()
                            refreshReviewQueue()
                            loadCandidatesAndCounts()
                            onboardingPrefs.setHistoricalImportCompleted(true)
                            _uiState.update { it.copy(step = OnboardingStep.OWNERSHIP) }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
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
                        _uiState.update { it.copy(error = OnboardingError.SMS_PROVIDER_ERROR) }
                    }
                    else -> {
                        _uiState.update { it.copy(error = OnboardingError.IMPORT_FAILED) }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
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
        viewModelScope.launch {
            try {
                val ref = AccountReference(candidate.bank, candidate.suffix)
                if (owned) {
                    onboardingOwnershipWorkflow.confirmAccountOwned(ref)
                } else {
                    onboardingOwnershipWorkflow.markAccountExternal(ref)
                }
                reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(ref))
                loadCandidatesAndCounts()
            } catch (_: Exception) {
                _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
            }
        }
    }

    fun setCardOwnership(candidate: OwnershipCandidateUi, owned: Boolean) {
        viewModelScope.launch {
            try {
                val ref = CardReference(candidate.bank, candidate.suffix)
                if (owned) {
                    onboardingOwnershipWorkflow.confirmCardOwned(ref)
                } else {
                    onboardingOwnershipWorkflow.markCardExternal(ref)
                }
                reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Card(ref))
                loadCandidatesAndCounts()
            } catch (_: Exception) {
                _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
            }
        }
    }

    fun finalizeOnboarding() {
        viewModelScope.launch {
            if (onboardingOwnershipWorkflow.loadSnapshot().hasUnknownCandidates) {
                _uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
                return@launch
            }
            _uiState.update { it.copy(finalizing = true, error = null) }
            try {
                refreshReviewQueue()
                onboardingPrefs.setOnboardingCompleted(true)
                loadCandidatesAndCounts()
                _uiState.update {
                    it.copy(
                        onboardingCompleted = true,
                        finalizing = false,
                        step = OnboardingStep.FINALIZE,
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(finalizing = false, error = OnboardingError.FINALIZATION_FAILED) }
            }
        }
    }

    fun enterApp() {
        _uiState.update { it.copy(step = OnboardingStep.HOME) }
    }

    private suspend fun loadCandidatesAndCounts() {
        val snapshot = onboardingOwnershipWorkflow.loadSnapshot()
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
        )
}
