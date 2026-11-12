package com.baraa.masroof.presentation.settings

import android.net.Uri
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.application.transaction.HistoricalMerchantRateCorrectionReport
import com.baraa.masroof.application.transaction.HistoricalMerchantRateCorrectionWorkflow
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reparse, rate corrections, scoped SMS import and logs for [SettingsViewModel]. It shares the Settings state, so screens keep one state object. */
internal class SettingsMaintenanceActions(
    private val scope: CoroutineScope,
    private val settingsState: MutableStateFlow<SettingsUiState>,
    private val logEntries: MutableStateFlow<List<com.baraa.masroof.application.logging.AppLogEntry>>,
    private val settingsRegistryWorkflow: SettingsRegistryWorkflow,
    private val reparseStoredEvents: suspend () -> com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult,
    private val permissionStateProvider: () -> Boolean,
    private val appLogService: AppLogService,
    private val historicalMerchantRateCorrectionWorkflow: HistoricalMerchantRateCorrectionWorkflow?,
    private val bankSmsImportWorkflow: com.baraa.masroof.application.sms.BankSmsImportWorkflow?,
    private val ledgerShadowExport: (suspend (Uri) -> Unit)?,
) {
    private var updatableCorrectionIds: List<String> = emptyList()

    fun refreshLogs() {
        logEntries.value = appLogService.readAll()
    }

    fun reparseStoredMessages() {
        if (settingsState.value.reparsingStored || settingsState.value.updating || settingsState.value.importingSms) return
        scope.launch {
            settingsState.update { it.copy(reparsingStored = true, error = null) }
            try {
                val reparsed = reparseStoredEvents()
                check(reparsed.succeeded) { "Manual reparse incomplete: ${reparsed.failedCount} messages failed" }
                val count = reparsed.refreshedCount
                val snapshot = settingsRegistryWorkflow.loadSnapshot()
                applyRegistries(
                    settingsState,
                    cards = snapshot.cards,
                    accounts = snapshot.accounts,
                    loans = snapshot.loans,
                )
                appLogService.info(AppLogCategories.SETTINGS, "Manual reparse finished: $count events refreshed")
                settingsState.update { it.copy(reparsingStored = false, error = it.error.withoutBusyRefusal()) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                appLogService.error(
                    AppLogCategories.SETTINGS,
                    "Manual reparse failed: ${error.message ?: error::class.java.simpleName}",
                )
                settingsState.update { it.copy(reparsingStored = false, error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    fun refreshHistoricalMerchantRateCorrections() {
        val workflow = historicalMerchantRateCorrectionWorkflow ?: return
        scope.launch {
            try {
                applyCorrectionReport(workflow.listCandidates())
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                appLogService.error(
                    AppLogCategories.SETTINGS,
                    "Historical merchant rate review failed: ${error.message ?: error::class.java.simpleName}",
                )
                settingsState.update { it.copy(error = SettingsError.UPDATE_FAILED) }
            }
        }
    }

    fun requestHistoricalMerchantRateCorrection() {
        if (updatableCorrectionIds.isEmpty() || settingsState.value.fxRateCorrectionRunning) return
        settingsState.update { it.copy(fxRateCorrectionAwaitingConfirm = true) }
    }

    fun dismissHistoricalMerchantRateCorrection() {
        settingsState.update { it.copy(fxRateCorrectionAwaitingConfirm = false) }
    }

    fun confirmHistoricalMerchantRateCorrections() {
        val workflow = historicalMerchantRateCorrectionWorkflow ?: return
        val ids = updatableCorrectionIds
        if (ids.isEmpty() || settingsState.value.fxRateCorrectionRunning) return
        scope.launch {
            settingsState.update {
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
                settingsState.update { it.copy(fxRateCorrectionRunning = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                appLogService.error(
                    AppLogCategories.SETTINGS,
                    "Historical merchant rate correction failed: ${error.message ?: error::class.java.simpleName}",
                )
                settingsState.update {
                    it.copy(fxRateCorrectionRunning = false, error = SettingsError.UPDATE_FAILED)
                }
            }
        }
    }

    private fun applyCorrectionReport(report: HistoricalMerchantRateCorrectionReport) {
        updatableCorrectionIds = report.candidates
            .filterNot { it.requiresManualFollowUp }
            .map { it.transactionId }
        settingsState.update { it.copy(fxRateCorrectionUpdatable = updatableCorrectionIds.size) }
    }

    fun clearScopedSmsImportResult() {
        if (!settingsState.value.importingSms) settingsState.update { it.copy(scopedSmsImportResult = null, scopedSmsImportFailed = false) }
    }

    fun refreshSmsPermissionState() {
        settingsState.update { it.copy(smsPermissionGranted = permissionStateProvider()) }
    }

    fun importSelectedBankSms(bankIds: Set<String>, from: java.time.LocalDate, through: java.time.LocalDate) {
        val workflow = bankSmsImportWorkflow ?: return
        if (settingsState.value.importingSms || settingsState.value.reparsingStored || settingsState.value.updating) return
        if (!permissionStateProvider()) {
            settingsState.update { it.copy(smsPermissionGranted = false, scopedSmsImportFailed = true) }
            return
        }
        scope.launchLogged(appLogService, AppLogCategories.SCAN, "Settings importSelectedBankSms", onError = { settingsState.update { it.copy(scopedSmsImportFailed = true) } }, onFinally = { settingsState.update { it.copy(importingSms = false, error = it.error.withoutBusyRefusal()) } }) {
            settingsState.update { it.copy(importingSms = true, importScanned = 0, scopedSmsImportResult = null, scopedSmsImportFailed = false) }
            val result = workflow.import(bankIds, from, through) { scanned -> settingsState.update { it.copy(importScanned = scanned) } }
            settingsState.update { it.copy(scopedSmsImportResult = result) }
            val snapshot = settingsRegistryWorkflow.loadSnapshot()
            applyRegistries(settingsState, snapshot.cards, snapshot.accounts, snapshot.loans)
        }
    }

    fun logExportText(): String = appLogService.exportText()

    fun exportLogs(uri: Uri) = runExport("Log export") { appLogService.exportTo(uri).getOrThrow() }

    val canExportLedgerShadow: Boolean get() = ledgerShadowExport != null

    fun exportLedgerShadow(uri: Uri) {
        val export = ledgerShadowExport ?: return
        runExport("Ledger comparison export") { export(uri) }
    }

    private fun runExport(action: String, write: suspend () -> Unit) {
        scope.launchLogged(appLogService, AppLogCategories.LOGS, action, onError = {
            settingsState.update { it.copy(exportingLogs = false, logMessage = LogMessage.EXPORT_FAILED) }
        }) {
            settingsState.update { it.copy(exportingLogs = true, logMessage = null) }
            withContext(Dispatchers.IO) { write() }
            settingsState.update { it.copy(exportingLogs = false, logMessage = LogMessage.EXPORT_SUCCESS) }
        }
    }

    fun clearLogs() {
        appLogService.clear()
        refreshLogs()
        settingsState.update { it.copy(logMessage = LogMessage.CLEARED) }
    }

    fun clearLogMessage() {
        settingsState.update { it.copy(logMessage = null) }
    }
}
