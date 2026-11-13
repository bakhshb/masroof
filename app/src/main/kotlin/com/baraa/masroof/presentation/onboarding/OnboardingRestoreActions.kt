package com.baraa.masroof.presentation.onboarding

import android.net.Uri
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Restoring a backup from the welcome step, for [OnboardingViewModel]. It shares the onboarding state. */
internal class OnboardingRestoreActions(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OnboardingUiState>,
    private val databaseBackupService: DatabaseBackupGateway,
    private val appLogService: com.baraa.masroof.application.logging.AppLogService?,
) {

    fun clearBackupError() {
        uiState.update {
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
        uiState.update { it.copy(restorePassphrase = value) }
    }

    fun restoreBackup(uri: Uri) {
        if (uiState.value.restoringBackup) return
        scope.launchLogged(appLogService, AppLogCategories.BACKUP, "Onboarding restoreBackup", onError = { uiState.update { it.copy(error = OnboardingError.BACKUP_RESTORE_FAILED) } }) {
            when (databaseBackupService.inspect(uri)) {
                BackupPackageKind.ENCRYPTED -> {
                    val passphrase = uiState.value.restorePassphrase
                    if (passphrase.isEmpty()) {
                        uiState.update { it.copy(error = OnboardingError.BACKUP_PASSPHRASE_REQUIRED) }
                    } else {
                        val secret = passphrase.toCharArray()
                        uiState.update { it.copy(restorePassphrase = "") }
                        importRestore(uri, secret)
                    }
                }
                BackupPackageKind.UNRECOGNIZED ->
                    uiState.update { it.copy(error = OnboardingError.BACKUP_RESTORE_INVALID) }
            }
        }
    }

    private fun importRestore(uri: Uri, passphrase: CharArray) {
        scope.launchLogged(appLogService, AppLogCategories.BACKUP, "Onboarding importRestore", onError = {
            uiState.update {
                it.copy(
                    restoringBackup = false,
                    error = OnboardingError.BACKUP_RESTORE_FAILED,
                )
            }
        }, onFinally = { passphrase.fill('\u0000') }) {
            uiState.update { it.copy(restoringBackup = true, error = null) }
            when (
databaseBackupService.importFrom(uri, passphrase)
            ) {
                BackupImportOutcome.SuccessNeedsRestart -> Unit
                BackupImportOutcome.InvalidPackage ->
                    uiState.update {
                        it.copy(
                            restoringBackup = false,
                            error = OnboardingError.BACKUP_RESTORE_INVALID,
                        )
                    }
                BackupImportOutcome.Failed ->
                    uiState.update {
                        it.copy(
                            restoringBackup = false,
                            error = OnboardingError.BACKUP_RESTORE_FAILED,
                        )
                    }
                BackupImportOutcome.AuthenticationFailed ->
                    uiState.update {
                        it.copy(
                            restoringBackup = false,
                            error = OnboardingError.BACKUP_RESTORE_FAILED,
                        )
                    }
            }
        }
    }
}
