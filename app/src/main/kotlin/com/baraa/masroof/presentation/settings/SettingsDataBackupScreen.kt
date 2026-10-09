package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.SmsPermissionNotice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataBackupScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onReparseStored: () -> Unit,
    onImportSms: () -> Unit,
    onClearSmsImportMessage: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestExport: () -> Unit,
    onRequestImport: () -> Unit,
    onConfirmPendingImport: () -> Unit,
    onCancelPendingImport: () -> Unit,
    onClearBackupMessage: () -> Unit,
    onRequestFxRateCorrection: () -> Unit,
    onConfirmFxRateCorrection: () -> Unit,
    onDismissFxRateCorrection: () -> Unit,
) {
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
    if (state.awaitingImportConfirm) {
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
            BackupMessage.EXPORT_SUCCESS -> stringResource(R.string.settings_export_success)
            BackupMessage.EXPORT_FAILED -> stringResource(R.string.settings_export_failed)
            BackupMessage.IMPORT_FAILED -> stringResource(R.string.settings_import_failed)
            BackupMessage.IMPORT_INVALID -> stringResource(R.string.settings_import_invalid)
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
    state.smsImportMessage?.let { message ->
        val text = when (message) {
            SmsImportMessage.OK -> stringResource(R.string.dashboard_rescan_ok)
            SmsImportMessage.ALREADY_UP_TO_DATE -> stringResource(R.string.dashboard_rescan_already_up_to_date)
            SmsImportMessage.NEEDS_REVIEW -> stringResource(R.string.dashboard_rescan_needs_review)
            SmsImportMessage.PERMISSION_DENIED -> stringResource(R.string.settings_import_sms_permission_denied)
            SmsImportMessage.NO_MESSAGES -> stringResource(R.string.dashboard_rescan_no_messages)
            SmsImportMessage.NO_BANK_SMS -> stringResource(R.string.dashboard_rescan_no_bank_sms)
            SmsImportMessage.NO_TRANSACTIONS -> stringResource(R.string.dashboard_rescan_no_transactions)
            SmsImportMessage.FAILED -> stringResource(R.string.dashboard_rescan_failed)
        }
        AlertDialog(
            onDismissRequest = onClearSmsImportMessage,
            title = { Text(stringResource(R.string.settings_import_sms_title)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    if (message == SmsImportMessage.PERMISSION_DENIED) {
                        onRequestSmsPermission()
                    }
                    onClearSmsImportMessage()
                }) {
                    Text(
                        if (message == SmsImportMessage.PERMISSION_DENIED) {
                            stringResource(R.string.dashboard_sms_permission_grant)
                        } else {
                            stringResource(R.string.settings_cancel)
                        },
                    )
                }
            },
            dismissButton = {
                if (message == SmsImportMessage.PERMISSION_DENIED) {
                    TextButton(onClick = onClearSmsImportMessage) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                }
            },
        )
    }

    MasroofSecondaryScaffold(
        title = stringResource(R.string.settings_data_backup_section),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            if (!state.smsPermissionGranted) {
                SmsPermissionNotice(
                    onRequestPermission = onRequestSmsPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }

            SettingsReparseRow(
                title = stringResource(R.string.settings_import_sms_title),
                subtitle = stringResource(R.string.settings_import_sms_subtitle),
                detail = null,
                icon = MasroofIcons.externalIn,
                actionIcon = MasroofIcons.rescan,
                running = state.importingSms,
                enabled = !state.importingSms && !state.reparsingStored && !state.updating &&
                    !state.exportingBackup && !state.importingBackup,
                onRefresh = onImportSms,
            )

            SettingsReparseRow(
                title = stringResource(R.string.settings_reparse_title),
                subtitle = stringResource(R.string.settings_reparse_stored_hint),
                detail = stringResource(R.string.settings_reparse_stored_example),
                icon = MasroofIcons.rescan,
                actionIcon = MasroofIcons.retry,
                running = state.reparsingStored,
                enabled = !state.reparsingStored && !state.updating &&
                    !state.exportingBackup && !state.importingBackup,
                onRefresh = onReparseStored,
            )

            SettingsNavRow(
                icon = MasroofIcons.export,
                title = stringResource(R.string.settings_export_title),
                subtitle = stringResource(R.string.settings_export_subtitle),
                onClick = onRequestExport,
                enabled = !state.exportingBackup && !state.importingBackup && !state.reparsingStored,
            )

            SettingsNavRow(
                icon = MasroofIcons.importBackup,
                title = stringResource(R.string.settings_import_title),
                subtitle = stringResource(R.string.settings_import_subtitle),
                onClick = onRequestImport,
                enabled = !state.exportingBackup && !state.importingBackup && !state.reparsingStored,
            )

            SettingsReparseRow(
                title = stringResource(R.string.settings_fx_rate_correction_title),
                subtitle = if (state.fxRateCorrectionUpdatable == 0 && state.fxRateCorrectionManualFollowUp == 0) {
                    stringResource(R.string.settings_fx_rate_correction_none)
                } else {
                    stringResource(
                        R.string.settings_fx_rate_correction_subtitle,
                        state.fxRateCorrectionUpdatable,
                        state.fxRateCorrectionManualFollowUp,
                    )
                },
                icon = MasroofIcons.retry,
                actionIcon = MasroofIcons.retry,
                running = state.fxRateCorrectionRunning,
                enabled = state.fxRateCorrectionUpdatable > 0 &&
                    !state.fxRateCorrectionRunning &&
                    !state.reparsingStored &&
                    !state.importingSms,
                onRefresh = onRequestFxRateCorrection,
            )

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
