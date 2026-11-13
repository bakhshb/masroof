package com.baraa.masroof.presentation.onboarding

import com.baraa.masroof.presentation.common.launchLogged
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.onboarding.ImportDatePolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.onboarding.HistoricalImportGateway
import com.baraa.masroof.application.onboarding.OnboardingOwnershipWorkflow
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import kotlinx.coroutines.CancellationException
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
    private val restore = OnboardingRestoreActions(viewModelScope, _uiState, databaseBackupService, appLogService)
    private val ownership: OnboardingOwnershipActions = OnboardingOwnershipActions(
        viewModelScope, _uiState, onboardingPrefs, onboardingOwnershipWorkflow,
        refreshReviewQueue, reconcileOwnershipChange, ::isBusy, appLogService,
    )
    private val historicalImport: OnboardingImportActions = OnboardingImportActions(
        viewModelScope, _uiState, onboardingPrefs, historicalImportGateway, permissionStateProvider,
        ownership::loadCandidatesAndCounts, ::isBusy, zoneId, clock, appLogService,
    )

    init {
        reloadFromCurrentState()
    }

    fun reloadFromCurrentState() {
        viewModelScope.launch {
            val permissionGranted = permissionStateProvider()
            if (historicalImport.importActive) {
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
                try { ownership.loadCandidatesAndCounts() }
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
        historicalImport.persistDraft()
    }

    fun continueFromBanks() {
        if (isBusy() || _uiState.value.selectedBankIds.isEmpty() || _uiState.value.loadingBanks || _uiState.value.bankCatalog == null) return
        val permission = permissionStateProvider()
        _uiState.update { it.copy(banksConfirmed = true, permissionGranted = permission,
            step = if (permission) OnboardingStep.IMPORT_DATE else OnboardingStep.PERMISSION, error = null) }
        historicalImport.persistDraft()
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
            historicalImport.persistDraft()
        }
    }

    private fun isBusy(): Boolean = historicalImport.importActive || _uiState.value.updatingOwnership || _uiState.value.finalizing || _uiState.value.restoringBackup

    fun clearBackupError() = restore.clearBackupError()

    fun onRestorePassphraseChange(value: String) = restore.onRestorePassphraseChange(value)

    fun restoreBackup(uri: Uri) = restore.restoreBackup(uri)

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

    fun selectDateOption(option: ImportDateOption) = historicalImport.selectDateOption(option)

    fun selectCustomDate(date: LocalDate) = historicalImport.selectCustomDate(date)

    fun selectImportThroughDate(date: LocalDate) = historicalImport.selectImportThroughDate(date)

    fun startImport() = historicalImport.startImport()

    fun setAccountOwnership(candidate: OwnershipCandidateUi, owned: Boolean) = ownership.setAccountOwnership(candidate, owned)

    fun setCardOwnership(candidate: OwnershipCandidateUi, owned: Boolean) = ownership.setCardOwnership(candidate, owned)

    fun finalizeOnboarding() = ownership.finalizeOnboarding()

    fun refreshOwnership() = ownership.refreshOwnership()

    fun enterApp() {
        if (onboardingPrefs.isOnboardingCompleted()) _uiState.update { it.copy(step = OnboardingStep.HOME, homeGuideSeen = onboardingPrefs.isHomeGuideSeen()) }
    }

    fun setHomeGuideSeen(seen: Boolean) {
        onboardingPrefs.setHomeGuideSeen(seen)
        _uiState.update { it.copy(homeGuideSeen = seen) }
    }
}
