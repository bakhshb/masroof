package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofFormField as OutlinedTextField
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold

enum class BackupPage { Hub, Export, Restore, Tools }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataBackupScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onReparseStored: () -> Unit,
    onExportPassphraseChange: (String) -> Unit,
    onImportPassphraseChange: (String) -> Unit,
    onRequestExport: () -> Unit,
    onRequestImport: () -> Unit,
    onConfirmPendingImport: () -> Unit,
    onCancelPendingImport: () -> Unit,
    onClearBackupMessage: () -> Unit,
    onRequestFxRateCorrection: () -> Unit,
    onConfirmFxRateCorrection: () -> Unit,
    onDismissFxRateCorrection: () -> Unit,
    page: BackupPage = BackupPage.Hub,
    onOpenPage: (BackupPage) -> Unit = {},
    onVerifyPendingImport: () -> Unit = {},
) {
    var showRestoreConfirm by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var exportConfirmation by com.baraa.masroof.presentation.common.rememberSensitiveInput()
    val cancelOnLeave by androidx.compose.runtime.rememberUpdatedState(onCancelPendingImport)
    androidx.compose.runtime.DisposableEffect(page) {
        onDispose { if (page == BackupPage.Restore) cancelOnLeave() }
    }
    if (state.fxRateCorrectionAwaitingConfirm) {
        AlertDialog(
            onDismissRequest = onDismissFxRateCorrection,
            title = { Text(stringResource(R.string.settings_fx_rate_correction_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_fx_rate_correction_confirm_body,
                        state.fxRateCorrectionUpdatable,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmFxRateCorrection) {
                    Text(stringResource(R.string.settings_fx_rate_correction_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissFxRateCorrection) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    if (state.awaitingLegacyImportConfirm) {
        AlertDialog(
            onDismissRequest = onCancelPendingImport,
            title = { Text(stringResource(R.string.settings_import_legacy_title)) },
            text = { Text(stringResource(R.string.settings_import_legacy_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmPendingImport) {
                    Text(stringResource(R.string.settings_import_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelPendingImport) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    if (state.awaitingImportConfirm && (showRestoreConfirm || !state.backupVerified)) {
        AlertDialog(
            onDismissRequest = onCancelPendingImport,
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmPendingImport) {
                    Text(stringResource(R.string.settings_import_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelPendingImport) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    state.backupMessage?.let { message ->
        val text = when (message) {
            BackupMessage.EXPORT_REENTER_PASSPHRASE -> stringResource(R.string.settings_export_reenter_passphrase)
            BackupMessage.EXPORT_SUCCESS -> stringResource(R.string.settings_export_success)
            BackupMessage.EXPORT_FAILED -> stringResource(R.string.settings_export_failed)
            BackupMessage.IMPORT_FAILED -> stringResource(R.string.settings_import_failed)
            BackupMessage.IMPORT_INVALID -> stringResource(R.string.settings_import_invalid)
            BackupMessage.PASSPHRASE_REQUIRED -> stringResource(R.string.settings_backup_passphrase_required)
            BackupMessage.IMPORT_AUTH_FAILED -> stringResource(R.string.settings_import_auth_failed)
        }
        AlertDialog(
            onDismissRequest = onClearBackupMessage,
            title = { Text(stringResource(R.string.settings_data_backup_section)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = onClearBackupMessage) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    MasroofSecondaryScaffold(
        title = stringResource(when (page) {
            BackupPage.Hub -> R.string.settings_data_backup_section
            BackupPage.Export -> R.string.reference_export_title
            BackupPage.Restore -> R.string.reference_restore_title
            BackupPage.Tools -> R.string.settings_data_tools_title
        }),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            if (page == BackupPage.Hub) {
                com.baraa.masroof.presentation.common.MasroofCard {
                    com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.reference_export_title),
                        stringResource(R.string.settings_export_subtitle), MasroofIcons.export, { onOpenPage(BackupPage.Export) })
                    com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.reference_restore_title),
                        stringResource(R.string.settings_import_subtitle), MasroofIcons.importBackup, { onOpenPage(BackupPage.Restore) })
                }
                com.baraa.masroof.presentation.common.MasroofCard {
                    com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_data_tools_title),
                        stringResource(R.string.settings_data_tools_hint), MasroofIcons.sms, { onOpenPage(BackupPage.Tools) })
                }
                return@Column
            }
            if (page == BackupPage.Tools) {
                com.baraa.masroof.presentation.common.MasroofCard {
                    Text(stringResource(R.string.settings_data_tools_title), style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap)) {
                        com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.settings_reparse_title),
                            stringResource(R.string.settings_reparse_stored_hint) + "\n\n" + stringResource(R.string.settings_reparse_stored_example))
                        com.baraa.masroof.presentation.common.MasroofOutlinedButton(onReparseStored, Modifier.fillMaxWidth(), enabled = !state.reparsingStored && !state.importingSms) {
                            Text(stringResource(R.string.settings_reparse_title))
                        }
                        com.baraa.masroof.presentation.common.MasroofOutlinedButton(onRequestFxRateCorrection, Modifier.fillMaxWidth(),
                            enabled = state.fxRateCorrectionUpdatable > 0 && !state.fxRateCorrectionRunning && !state.reparsingStored && !state.importingSms) {
                            Text(stringResource(R.string.settings_fx_rate_correction_title))
                        }
                    }
                }
            }
            if (page == BackupPage.Export) {
            com.baraa.masroof.presentation.common.MasroofCard {
                Text(stringResource(R.string.reference_export_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.exportPassphrase,
                onValueChange = onExportPassphraseChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_export_passphrase_label)) },
                trailingIcon = {
                    com.baraa.masroof.presentation.common.MasroofGuidanceButton(title = stringResource(R.string.settings_export_passphrase_label), message = stringResource(R.string.settings_export_passphrase_hint))
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !state.exportingBackup && !state.importingBackup,
            )

            OutlinedTextField(value = exportConfirmation, onValueChange = { exportConfirmation = it },
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.reference_confirm_password)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            com.baraa.masroof.presentation.common.MasroofButton(
                onClick = { onRequestExport(); exportConfirmation = "" }, modifier = Modifier.fillMaxWidth(),
                enabled = !state.exportingBackup && !state.importingBackup && !state.reparsingStored &&
                    state.exportPassphrase.isNotEmpty() && state.exportPassphrase == exportConfirmation) {
                Text(stringResource(R.string.reference_choose_save_location))
            }

            }
            }
            if (page == BackupPage.Restore) {
                Text(stringResource(R.string.reference_restore_steps), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.baraa.masroof.presentation.common.MasroofCard {
                    Text(stringResource(R.string.reference_restore_title), style = MaterialTheme.typography.titleMedium)
                    when {
                        state.backupVerified -> {
                            com.baraa.masroof.presentation.common.MasroofBadge(stringResource(R.string.reference_verified_backup),
                                com.baraa.masroof.presentation.common.MasroofCardAccent.Inflow)
                            com.baraa.masroof.presentation.common.MasroofButton({ showRestoreConfirm = true }, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.reference_continue_restore))
                            }
                        }
                        state.awaitingImportPassword -> {
                            OutlinedTextField(state.importPassphrase, onImportPassphraseChange, Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.settings_import_passphrase_label)) },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            com.baraa.masroof.presentation.common.MasroofButton(onVerifyPendingImport, Modifier.fillMaxWidth(),
                                enabled = state.importPassphrase.isNotEmpty() && !state.importingBackup) {
                                Text(stringResource(R.string.reference_verify_backup))
                            }
                        }
                        else -> com.baraa.masroof.presentation.common.MasroofButton(onRequestImport, Modifier.fillMaxWidth(),
                            enabled = !state.importingBackup) { Text(stringResource(R.string.reference_choose_backup)) }
                    }
                    if (state.awaitingImportPassword || state.backupVerified) {
                        TextButton(onClick = { onCancelPendingImport(); onRequestImport() }, enabled = !state.importingBackup) {
                            Text(stringResource(R.string.reference_choose_another_backup))
                        }
                    }
                }
            }
            if (state.exportingBackup || state.importingBackup) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
