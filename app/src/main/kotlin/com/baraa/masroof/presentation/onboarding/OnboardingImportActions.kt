package com.baraa.masroof.presentation.onboarding

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.onboarding.HistoricalImportFailure
import com.baraa.masroof.application.onboarding.HistoricalImportGateway
import com.baraa.masroof.application.onboarding.HistoricalImportResult
import com.baraa.masroof.application.onboarding.ImportDatePolicy
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** The import range, the saved setup draft and the historical import, for [OnboardingViewModel]. It shares the onboarding state. */
internal class OnboardingImportActions(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OnboardingUiState>,
    private val onboardingPrefs: OnboardingPreferencesRepository,
    private val historicalImportGateway: HistoricalImportGateway,
    private val permissionStateProvider: () -> Boolean,
    private val loadCandidatesAndCounts: suspend () -> Unit,
    private val isBusy: () -> Boolean,
    private val zoneId: ZoneId,
    private val clock: Clock,
    private val appLogService: com.baraa.masroof.application.logging.AppLogService?,
) {
    private var importJob: Job? = null

    val importActive: Boolean
        get() = importJob?.isActive == true

    fun persistDraft() {
        val state = uiState.value
        onboardingPrefs.setSetupDraft(com.baraa.masroof.application.onboarding.OnboardingSetupDraft(state.selectedBankIds,
            (state.selectedImportDate ?: LocalDate.now(clock).minusDays(30)).toString(),
            (state.importThroughDate ?: LocalDate.now(clock)).toString(), state.banksConfirmed))
    }

    fun selectDateOption(option: ImportDateOption) {
        if (isBusy()) return
        val today = LocalDate.now(clock)
        val date = when (option) {
            ImportDateOption.CURRENT_MONTH_START -> today.withDayOfMonth(1)
            ImportDateOption.LAST_30_DAYS -> today.minusDays(30)
            ImportDateOption.LAST_27TH -> ImportDatePolicy.last27th(today)
            ImportDateOption.CUSTOM -> uiState.value.selectedImportDate ?: ImportDatePolicy.last27th(today)
        }
        uiState.update { it.copy(selectedDateOption = option, selectedImportDate = date,
            importThroughDate = if (option == ImportDateOption.CUSTOM) it.importThroughDate else today, error = null) }
        persistDraft()
    }

    fun selectCustomDate(date: LocalDate) {
        if (isBusy()) return
        val today = LocalDate.now(clock)
        if (date.isAfter(today)) {
            uiState.update { it.copy(error = OnboardingError.INVALID_FUTURE_DATE) }
            return
        }
        uiState.update {
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
        if (date.isAfter(LocalDate.now(clock))) { uiState.update { it.copy(error = OnboardingError.INVALID_FUTURE_DATE) }; return }
        uiState.update { it.copy(importThroughDate = date, error = null) }
        persistDraft()
    }

    fun startImport() {
        if (importJob?.isActive == true || uiState.value.importState is ImportState.Scanning) {
            return
        }
        val selection = uiState.value
        val from = selection.selectedImportDate ?: LocalDate.now(clock).minusDays(30)
        val through = selection.importThroughDate ?: LocalDate.now(clock)
        if (!selection.banksConfirmed || selection.selectedBankIds.isEmpty() || selection.bankCatalog == null ||
            selection.selectedBankIds.any { id -> selection.bankCatalog.banks.none { it.bankId == id } } ||
            from.isAfter(through) || through.isAfter(LocalDate.now(clock))) {
            uiState.update { it.copy(error = OnboardingError.INVALID_IMPORT_SELECTION) }; return
        }
        if (!permissionStateProvider()) { uiState.update { it.copy(step = OnboardingStep.PERMISSION, permissionGranted = false, error = OnboardingError.PERMISSION_DENIED) }; return }
        persistDraft()

        importJob = scope.launchLogged(appLogService, AppLogCategories.SCAN, "Onboarding startImport", onError = {
            uiState.update {
                it.copy(
                    importState = ImportState.ProviderError(
                        HistoricalImportResult(failure = HistoricalImportFailure.ProviderError("scan_failed")),
                    ),
                    error = OnboardingError.IMPORT_FAILED,
                )
            }
        }) {
            val selectedDate = from
            val startInstant = ImportDatePolicy.toStartOfDayInstant(selectedDate, zoneId)

            onboardingPrefs.setHistoricalImportStartEpochMillis(startInstant.toEpochMilli())
            onboardingPrefs.setHistoricalImportCompleted(false)
            uiState.update { it.copy(step = OnboardingStep.IMPORTING, importState = ImportState.Scanning, importScanned = 0, error = null) }

            val result = historicalImportGateway.scan(com.baraa.masroof.application.onboarding.OnboardingImportRequest(selection.selectedBankIds, from, through,
                onProgress = { scanned -> uiState.update { it.copy(importScanned = scanned) } }))
            val state = result.toImportState()
            uiState.update { it.copy(importState = state) }

            when (state) {
                is ImportState.Completed -> {
                    try {
                        // The shared importer already completed discovery/reconciliation for this batch.
                        loadCandidatesAndCounts()
                        onboardingPrefs.setHistoricalImportCompleted(true)
                        uiState.update { it.copy(step = OnboardingStep.OWNERSHIP) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.SCAN, "Onboarding startImport", caught);
                        onboardingPrefs.setHistoricalImportCompleted(false)
                        uiState.update {
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
                    uiState.update {
                        it.copy(
                            step = OnboardingStep.PERMISSION,
                            error = OnboardingError.PERMISSION_DENIED,
                        )
                    }
                }
                is ImportState.ProviderError -> {
                    uiState.update {
                        it.copy(error = if (state.result.failure is HistoricalImportFailure.ProcessingIncomplete) {
                            OnboardingError.IMPORT_FAILED
                        } else {
                            OnboardingError.SMS_PROVIDER_ERROR
                        })
                    }
                }
                else -> {
                    uiState.update { it.copy(error = OnboardingError.IMPORT_FAILED) }
                }
            }
        }
    }
}
