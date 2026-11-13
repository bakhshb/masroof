package com.baraa.masroof.presentation.settings

import android.net.Uri
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Encrypted backup export and import for [SettingsViewModel]. It shares the Settings state, so screens keep one state object. */
internal class SettingsBackupActions(
    private val scope: CoroutineScope,
    private val settingsState: MutableStateFlow<SettingsUiState>,
    private val databaseBackupService: DatabaseBackupGateway,
    private val appLogService: AppLogService,
) {
    private var pendingImportUri: Uri? = null
    private var pendingImportGeneration: Long = 0
    private var verificationJob: kotlinx.coroutines.Job? = null
    private var pendingExportPassphrase: CharArray? = null

    fun clearBackupMessage() {
        settingsState.update { it.copy(backupMessage = null) }
    }

    fun onExportPassphraseChange(value: String) {
        settingsState.update { it.copy(exportPassphrase = value) }
    }

    fun onImportPassphraseChange(value: String) {
        settingsState.update { it.copy(importPassphrase = value) }
    }

    /**
     * Copies the export passphrase out of view state and clears the field.
     * Returns false when the field is empty. Call [abandonPreparedExport] if the
     * user dismisses the destination picker.
     */
    fun prepareExport(): Boolean {
        if (settingsState.value.exportingBackup || settingsState.value.importingBackup) return false
        val current = settingsState.value.exportPassphrase
        if (current.isEmpty()) {
            settingsState.update { it.copy(backupMessage = BackupMessage.PASSPHRASE_REQUIRED) }
            return false
        }
        abandonPreparedExport()
        pendingExportPassphrase = current.toCharArray()
        settingsState.update { it.copy(exportPassphrase = "", backupMessage = null) }
        return true
    }

    fun abandonPreparedExport() {
        pendingExportPassphrase?.fill('\u0000')
        pendingExportPassphrase = null
    }

    fun offerImport(uri: Uri) {
        if (settingsState.value.exportingBackup || settingsState.value.importingBackup) return
        pendingImportGeneration++
        scope.launchLogged(appLogService, AppLogCategories.BACKUP, "Settings offerImport", onError = {
            settingsState.update {
                it.copy(importingBackup = false, backupMessage = BackupMessage.IMPORT_FAILED)
            }
        }) {
            settingsState.update { it.copy(importingBackup = true, backupMessage = null) }
            when (databaseBackupService.inspect(uri)) {
                BackupPackageKind.ENCRYPTED -> {
                    if (settingsState.value.importPassphrase.isEmpty()) {
                        pendingImportUri = uri
                        settingsState.update {
                            it.copy(
                                importingBackup = false,
                                awaitingImportPassword = true, backupVerified = false,
                                backupMessage = null,
                            )
                        }
                    } else {
                        pendingImportUri = uri
                        settingsState.update {
                            it.copy(
                                importingBackup = false,
                                awaitingImportConfirm = true,
                            )
                        }
                    }
                }
                BackupPackageKind.UNRECOGNIZED ->
                    settingsState.update {
                        it.copy(
                            importingBackup = false,
                            backupMessage = BackupMessage.IMPORT_INVALID,
                        )
                    }
            }
        }
    }

    fun cancelPendingImport() {
        val verifying = verificationJob?.isActive == true
        pendingImportGeneration++
        verificationJob?.cancel()
        verificationJob = null
        pendingImportUri = null
        settingsState.update {
            it.copy(awaitingImportConfirm = false, awaitingImportPassword = false,
                backupVerified = false, importingBackup = if (verifying) false else it.importingBackup, importPassphrase = "")
        }
    }

    fun verifyPendingImport() {
        val uri = pendingImportUri ?: return
        val passphrase = settingsState.value.importPassphrase.toCharArray()
        if (passphrase.isEmpty() || settingsState.value.importingBackup) return
        val generation = pendingImportGeneration
        verificationJob = scope.launch {
            settingsState.update { it.copy(importingBackup = true, backupMessage = null) }
            try {
                val result = databaseBackupService.verify(uri, passphrase)
                if (generation != pendingImportGeneration) return@launch
                settingsState.update { state ->
                    when (result) {
                        com.baraa.masroof.application.backup.BackupVerificationOutcome.VERIFIED -> state.copy(
                            importingBackup = false, awaitingImportPassword = false, backupVerified = true,
                            awaitingImportConfirm = true)
                        com.baraa.masroof.application.backup.BackupVerificationOutcome.AUTHENTICATION_FAILED -> state.copy(
                            importingBackup = false, backupVerified = false, backupMessage = BackupMessage.IMPORT_AUTH_FAILED)
                        com.baraa.masroof.application.backup.BackupVerificationOutcome.INVALID_PACKAGE -> state.copy(
                            importingBackup = false, backupVerified = false, backupMessage = BackupMessage.IMPORT_INVALID)
                        com.baraa.masroof.application.backup.BackupVerificationOutcome.FAILED -> state.copy(
                            importingBackup = false, backupVerified = false, backupMessage = BackupMessage.IMPORT_FAILED)
                    }
                }
            } finally { passphrase.fill('\u0000') }
        }
    }

    fun confirmPendingImport() {
        val uri = pendingImportUri ?: return
        val passphrase = settingsState.value.importPassphrase.toCharArray()
        pendingImportUri = null
        if (passphrase.isEmpty()) {
            settingsState.update { it.copy(awaitingImportConfirm = false, backupMessage = BackupMessage.PASSPHRASE_REQUIRED) }
            return
        }
        settingsState.update { it.copy(awaitingImportConfirm = false, importPassphrase = "") }
        importBackup(uri, passphrase)
    }

    fun exportBackup(uri: Uri) {
        if (settingsState.value.exportingBackup || settingsState.value.importingBackup) return
        val passphrase = pendingExportPassphrase ?: run {
            settingsState.update { it.copy(backupMessage = BackupMessage.EXPORT_REENTER_PASSPHRASE) }
            return
        }
        pendingExportPassphrase = null
        scope.launchLogged(appLogService, AppLogCategories.BACKUP, "Settings exportBackup", onError = {
            settingsState.update {
                it.copy(exportingBackup = false, backupMessage = BackupMessage.EXPORT_FAILED)
            }
        }, onFinally = { passphrase.fill('\u0000') }) {
            settingsState.update { it.copy(exportingBackup = true, backupMessage = null, error = null) }
            databaseBackupService.exportTo(uri, passphrase).getOrThrow()
            settingsState.update {
                it.copy(exportingBackup = false, backupMessage = BackupMessage.EXPORT_SUCCESS)
            }
        }
    }

    fun importBackup(uri: Uri, passphrase: CharArray) {
        if (settingsState.value.exportingBackup || settingsState.value.importingBackup) return
        scope.launchLogged(appLogService, AppLogCategories.BACKUP, "Settings importBackup", onError = {
            settingsState.update {
                it.copy(importingBackup = false, backupMessage = BackupMessage.IMPORT_FAILED)
            }
        }, onFinally = { passphrase.fill('\u0000') }) {
            settingsState.update { it.copy(importingBackup = true, backupMessage = null, error = null) }
            when (
                databaseBackupService.importFrom(uri, passphrase)
            ) {
                BackupImportOutcome.SuccessNeedsRestart -> {
                    // Process restarts inside the service after a successful restore.
                }
                BackupImportOutcome.InvalidPackage ->
                    settingsState.update {
                        it.copy(
                            importingBackup = false,
                            backupMessage = BackupMessage.IMPORT_INVALID,
                        )
                    }
                BackupImportOutcome.Failed ->
                    settingsState.update {
                        it.copy(
                            importingBackup = false,
                            backupMessage = BackupMessage.IMPORT_FAILED,
                        )
                    }
                BackupImportOutcome.AuthenticationFailed ->
                    settingsState.update {
                        it.copy(
                            importingBackup = false,
                            backupMessage = BackupMessage.IMPORT_AUTH_FAILED,
                        )
                    }
            }
        }
    }
}
