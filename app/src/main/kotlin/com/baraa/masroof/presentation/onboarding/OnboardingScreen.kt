package com.baraa.masroof.presentation.onboarding

import com.baraa.masroof.presentation.theme.MasroofIconSizes
import com.baraa.masroof.presentation.theme.MasroofSpacing

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.presentation.common.IconTextButton
import com.baraa.masroof.presentation.common.IconTextButtonOutlined
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofCardAccent
import com.baraa.masroof.presentation.common.MasroofHintBox
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.common.MasroofLogo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestRestoreBackup: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    OnboardingScreen(
        state = state,
        onStart = viewModel::onStartClicked,
        onRequestPermissions = onRequestPermissions,
        onOpenAppSettings = onOpenAppSettings,
        onRequestRestoreBackup = onRequestRestoreBackup,
        onRestorePassphraseChange = viewModel::onRestorePassphraseChange,
        onConfirmLegacyRestore = viewModel::confirmLegacyRestore,
        onCancelLegacyRestore = viewModel::cancelLegacyRestore,
        onClearBackupError = viewModel::clearBackupError,
        onSelectDateOption = viewModel::selectDateOption,
        onSelectCustomDate = viewModel::selectCustomDate,
        onStartImport = viewModel::startImport,
        onSetAccountOwned = { c -> viewModel.setAccountOwnership(c, true) },
        onSetAccountExternal = { c -> viewModel.setAccountOwnership(c, false) },
        onSetCardOwned = { c -> viewModel.setCardOwnership(c, true) },
        onSetCardExternal = { c -> viewModel.setCardOwnership(c, false) },
        onFinalize = viewModel::finalizeOnboarding,
        onEnterApp = viewModel::enterApp,
    )
}

@Composable
private fun OnboardingScreen(
    state: OnboardingUiState,
    onStart: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestRestoreBackup: () -> Unit,
    onRestorePassphraseChange: (String) -> Unit,
    onConfirmLegacyRestore: () -> Unit,
    onCancelLegacyRestore: () -> Unit,
    onClearBackupError: () -> Unit,
    onSelectDateOption: (ImportDateOption) -> Unit,
    onSelectCustomDate: (LocalDate) -> Unit,
    onStartImport: () -> Unit,
    onSetAccountOwned: (OwnershipCandidateUi) -> Unit,
    onSetAccountExternal: (OwnershipCandidateUi) -> Unit,
    onSetCardOwned: (OwnershipCandidateUi) -> Unit,
    onSetCardExternal: (OwnershipCandidateUi) -> Unit,
    onFinalize: () -> Unit,
    onEnterApp: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        when (state.step) {
            OnboardingStep.WELCOME -> WelcomeStep(
                modifier = Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
                restoringBackup = state.restoringBackup,
                restorePassphrase = state.restorePassphrase,
                awaitingLegacyRestoreConfirm = state.awaitingLegacyRestoreConfirm,
                error = state.error,
                onStart = onStart,
                onRequestRestoreBackup = onRequestRestoreBackup,
                onRestorePassphraseChange = onRestorePassphraseChange,
                onConfirmLegacyRestore = onConfirmLegacyRestore,
                onCancelLegacyRestore = onCancelLegacyRestore,
                onClearBackupError = onClearBackupError,
            )
            OnboardingStep.PERMISSION -> PermissionStep(
                modifier = Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
                denied = state.error == OnboardingError.PERMISSION_DENIED,
                onRequestPermissions = onRequestPermissions,
                onOpenSettings = onOpenAppSettings,
            )
            OnboardingStep.IMPORT_DATE -> ImportDateStep(
                modifier = Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
                state = state,
                onSelectDateOption = onSelectDateOption,
                onSelectCustomDate = onSelectCustomDate,
                onContinue = onStartImport,
            )
            OnboardingStep.IMPORTING -> ImportingStep(Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge), state, onStartImport)
            OnboardingStep.OWNERSHIP -> OwnershipStep(
                modifier = Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
                state = state,
                onSetAccountOwned = onSetAccountOwned,
                onSetAccountExternal = onSetAccountExternal,
                onSetCardOwned = onSetCardOwned,
                onSetCardExternal = onSetCardExternal,
                onFinalize = onFinalize,
            )
            OnboardingStep.FINALIZE -> CompletionStep(Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge), state, onEnterApp)
            OnboardingStep.HOME -> HomePlaceholder(Modifier.fillMaxSize(), state)
        }
    }
}

@Composable
private fun WelcomeStep(
    modifier: Modifier,
    restoringBackup: Boolean,
    restorePassphrase: String,
    awaitingLegacyRestoreConfirm: Boolean,
    error: OnboardingError?,
    onStart: () -> Unit,
    onRequestRestoreBackup: () -> Unit,
    onRestorePassphraseChange: (String) -> Unit,
    onConfirmLegacyRestore: () -> Unit,
    onCancelLegacyRestore: () -> Unit,
    onClearBackupError: () -> Unit,
) {
    if (awaitingLegacyRestoreConfirm) {
        AlertDialog(
            onDismissRequest = onCancelLegacyRestore,
            title = { Text(stringResource(R.string.settings_import_legacy_title)) },
            text = { Text(stringResource(R.string.settings_import_legacy_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmLegacyRestore) {
                    Text(stringResource(R.string.settings_import_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelLegacyRestore) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    if (error == OnboardingError.BACKUP_RESTORE_FAILED ||
        error == OnboardingError.BACKUP_RESTORE_INVALID ||
        error == OnboardingError.BACKUP_PASSPHRASE_REQUIRED
    ) {
        val message = when (error) {
            OnboardingError.BACKUP_RESTORE_INVALID -> stringResource(R.string.onboarding_restore_invalid)
            OnboardingError.BACKUP_PASSPHRASE_REQUIRED ->
                stringResource(R.string.settings_backup_passphrase_required)
            else -> stringResource(R.string.onboarding_restore_failed)
        }
        AlertDialog(
            onDismissRequest = onClearBackupError,
            title = { Text(stringResource(R.string.onboarding_restore_backup)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearBackupError) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    Column(
        modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MasroofCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
            ) {
                MasroofLogo(
                    size = MasroofIconSizes.onboardingLogo,
                    contentDescription = null,
                )
                Text(
                    stringResource(R.string.onboarding_welcome_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.onboarding_welcome_body),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                MasroofHintBox(
                    text = stringResource(R.string.onboarding_welcome_local_only),
                )
            }
        }
        Spacer(Modifier.height(MasroofSpacing.screenPaddingLarge))
        if (restoringBackup) {
            CircularProgressIndicator()
            Spacer(Modifier.height(MasroofSpacing.screenVertical))
        }
        IconTextButton(
            onClick = onStart,
            brandedLogo = true,
            text = stringResource(R.string.onboarding_start),
            modifier = Modifier.fillMaxWidth(),
            enabled = !restoringBackup,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_restore_backup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MasroofSpacing.sectionHeaderGap))
        OutlinedTextField(
            value = restorePassphrase,
            onValueChange = onRestorePassphraseChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_import_passphrase_label)) },
            supportingText = {
                Text(
                    stringResource(R.string.settings_import_passphrase_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = !restoringBackup,
        )
        Spacer(Modifier.height(MasroofSpacing.sectionHeaderGap))
        IconTextButtonOutlined(
            onClick = onRequestRestoreBackup,
            icon = MasroofIcons.importBackup,
            text = stringResource(R.string.onboarding_restore_backup),
            modifier = Modifier.fillMaxWidth(),
            enabled = !restoringBackup,
        )
    }
}

@Composable
private fun PermissionStep(
    modifier: Modifier,
    denied: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = MasroofIcons.sms,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(MasroofSpacing.screenVertical))
        Text(stringResource(R.string.onboarding_permission_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_permission_body))
        Spacer(Modifier.height(MasroofSpacing.screenPaddingLarge))
        IconTextButton(
            onClick = onRequestPermissions,
            icon = MasroofIcons.sms,
            text = stringResource(R.string.onboarding_allow),
            modifier = Modifier.fillMaxWidth(),
        )
        if (denied) {
            Spacer(Modifier.height(12.dp))
            IconTextButtonOutlined(
                onClick = onOpenSettings,
                icon = MasroofIcons.warning,
                text = stringResource(R.string.onboarding_open_settings),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ImportDateStep(
    modifier: Modifier,
    state: OnboardingUiState,
    onSelectDateOption: (ImportDateOption) -> Unit,
    onSelectCustomDate: (LocalDate) -> Unit,
    onContinue: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val selected = state.selectedImportDate ?: LocalDate.now()

    Column(modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge)) {
        MasroofSectionHeader(
            title = stringResource(R.string.onboarding_import_date_title),
            icon = MasroofIcons.calendar,
        )
        Spacer(Modifier.height(MasroofSpacing.screenVertical))
        DateOptionRow(state.selectedDateOption == ImportDateOption.CURRENT_MONTH_START, stringResource(R.string.onboarding_date_current_month)) {
            onSelectDateOption(ImportDateOption.CURRENT_MONTH_START)
        }
        DateOptionRow(state.selectedDateOption == ImportDateOption.LAST_30_DAYS, stringResource(R.string.onboarding_date_last_30)) {
            onSelectDateOption(ImportDateOption.LAST_30_DAYS)
        }
        DateOptionRow(state.selectedDateOption == ImportDateOption.LAST_27TH, stringResource(R.string.onboarding_date_last_27)) {
            onSelectDateOption(ImportDateOption.LAST_27TH)
        }
        DateOptionRow(state.selectedDateOption == ImportDateOption.CUSTOM, stringResource(R.string.onboarding_date_custom)) {
            onSelectDateOption(ImportDateOption.CUSTOM)
            showDatePicker = true
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_selected_date, selected.toString()))
        Spacer(Modifier.height(20.dp))
        IconTextButton(
            onClick = onContinue,
            enabled = state.importState !is ImportState.Scanning,
            icon = MasroofIcons.rescan,
            text = stringResource(R.string.onboarding_start_import),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selected.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        onSelectCustomDate(date)
                    }
                    showDatePicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun DateOptionRow(selected: Boolean, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Icon(
            imageVector = MasroofIcons.calendar,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MasroofIconSizes.moneyRowLeading),
        )
        Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
        Text(title, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ImportingStep(modifier: Modifier, state: OnboardingUiState, onRetry: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val importState = state.importState) {
            is ImportState.Scanning, ImportState.Idle -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MasroofIcons.rescan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
                    Text(stringResource(R.string.onboarding_import_scanning))
                }
            }
            is ImportState.Completed -> {
                Icon(
                    imageVector = MasroofIcons.success,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MasroofIconSizes.hero),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.onboarding_import_done))
                Spacer(Modifier.height(MasroofSpacing.sectionHeaderGap))
                Text(stringResource(R.string.onboarding_import_counts, importState.result.scanned, importState.result.parsed, importState.result.duplicates, importState.result.failed, importState.result.notRelevant))
            }
            is ImportState.PermissionError -> {
                Icon(
                    imageVector = MasroofIcons.warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MasroofIconSizes.hero),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.onboarding_permission_denied_short))
                Spacer(Modifier.height(12.dp))
                IconTextButton(onClick = onRetry, icon = MasroofIcons.retry, text = stringResource(R.string.onboarding_retry))
            }
            is ImportState.ProviderError -> {
                Icon(
                    imageVector = MasroofIcons.error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MasroofIconSizes.hero),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.onboarding_provider_error))
                Spacer(Modifier.height(12.dp))
                IconTextButton(onClick = onRetry, icon = MasroofIcons.retry, text = stringResource(R.string.onboarding_retry))
            }
        }
    }
}

@Composable
private fun OwnershipStep(
    modifier: Modifier,
    state: OnboardingUiState,
    onSetAccountOwned: (OwnershipCandidateUi) -> Unit,
    onSetAccountExternal: (OwnershipCandidateUi) -> Unit,
    onSetCardOwned: (OwnershipCandidateUi) -> Unit,
    onSetCardExternal: (OwnershipCandidateUi) -> Unit,
    onFinalize: () -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        item {
            MasroofSectionHeader(
                title = stringResource(R.string.onboarding_ownership_title),
                icon = MasroofIcons.ownership,
            )
            if (state.hasUnknownCandidates) {
                Text(stringResource(R.string.onboarding_ownership_must_resolve))
            }
            val importResult = (state.importState as? ImportState.Completed)?.result
            if (importResult != null) {
                Spacer(Modifier.height(MasroofSpacing.sectionHeaderGap))
                Text(
                    stringResource(
                        R.string.onboarding_import_counts,
                        importResult.scanned,
                        importResult.parsed,
                        importResult.duplicates,
                        importResult.failed,
                        importResult.notRelevant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
                if (importResult.parsed == 0) {
                    Text(
                        if (importResult.scanned == 0) {
                            stringResource(R.string.onboarding_import_empty_inbox)
                        } else if (importResult.notRelevant == importResult.scanned) {
                            stringResource(R.string.onboarding_import_no_bank_sms)
                        } else {
                            stringResource(R.string.onboarding_import_no_transactions)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (importResult.distinctSenders.isNotEmpty()) {
                        Spacer(Modifier.height(MasroofSpacing.sectionHeaderGap))
                        Text(
                            stringResource(R.string.onboarding_import_senders_seen),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                        importResult.distinctSenders.forEach { sender ->
                            Text(
                                "• $sender",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            }
        }
        item { MasroofSectionHeader(title = stringResource(R.string.onboarding_accounts_section), icon = MasroofIcons.externalIn) }
        items(state.accounts) { candidate ->
            CandidateCard(
                candidate = candidate,
                ownedLabel = stringResource(R.string.onboarding_is_mine_account),
                externalLabel = stringResource(R.string.onboarding_not_mine),
                onOwned = { onSetAccountOwned(candidate) },
                onExternal = { onSetAccountExternal(candidate) },
            )
        }
        item { MasroofSectionHeader(title = stringResource(R.string.onboarding_cards_section), icon = MasroofIcons.cardPayment) }
        items(state.cards) { candidate ->
            CandidateCard(
                candidate = candidate,
                ownedLabel = stringResource(R.string.onboarding_is_mine_card),
                externalLabel = stringResource(R.string.onboarding_not_mine),
                onOwned = { onSetCardOwned(candidate) },
                onExternal = { onSetCardExternal(candidate) },
            )
        }
        item {
            IconTextButton(
                onClick = onFinalize,
                enabled = !state.hasUnknownCandidates && !state.finalizing,
                icon = MasroofIcons.success,
                text = stringResource(R.string.onboarding_finalize),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: OwnershipCandidateUi,
    ownedLabel: String,
    externalLabel: String,
    onOwned: () -> Unit,
    onExternal: () -> Unit,
) {
    MasroofCard(
        accent = if (candidate.kind == OwnershipCandidateUi.CandidateKind.ACCOUNT) {
            MasroofCardAccent.Account
        } else {
            MasroofCardAccent.Credit
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (candidate.kind == OwnershipCandidateUi.CandidateKind.ACCOUNT) {
                        MasroofIcons.externalIn
                    } else {
                        MasroofIcons.cardPayment
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
                Text(
                    if (candidate.bank == Bank.BANK_ALJAZIRA) stringResource(R.string.bank_aljazira)
                    else stringResource(R.string.bank_unknown),
                )
            }
            val label = if (candidate.kind == OwnershipCandidateUi.CandidateKind.ACCOUNT) {
                stringResource(R.string.onboarding_account_suffix, candidate.suffix)
            } else {
                stringResource(R.string.onboarding_card_suffix, candidate.suffix)
            }
            Text(label)
            Row(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                IconTextButton(onClick = onOwned, icon = MasroofIcons.success, text = ownedLabel)
                IconTextButton(onClick = onExternal, icon = MasroofIcons.warning, text = externalLabel)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusIcon = when (candidate.ownership) {
                    OwnershipStatus.OWNED -> MasroofIcons.success
                    OwnershipStatus.EXTERNAL -> MasroofIcons.warning
                    OwnershipStatus.UNKNOWN -> MasroofIcons.error
                }
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(MasroofIconSizes.sm),
                    tint = when (candidate.ownership) {
                        OwnershipStatus.OWNED -> MaterialTheme.colorScheme.primary
                        OwnershipStatus.EXTERNAL -> MaterialTheme.colorScheme.onSurfaceVariant
                        OwnershipStatus.UNKNOWN -> MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    when (candidate.ownership) {
                        OwnershipStatus.OWNED -> stringResource(R.string.onboarding_status_owned)
                        OwnershipStatus.EXTERNAL -> stringResource(R.string.onboarding_status_external)
                        OwnershipStatus.UNKNOWN -> stringResource(R.string.onboarding_status_unknown)
                    },
                )
            }
        }
    }
}

@Composable
private fun CompletionStep(modifier: Modifier, state: OnboardingUiState, onEnterApp: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = MasroofIcons.success,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(MasroofSpacing.screenVertical))
        Text(stringResource(R.string.onboarding_done_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_done_counts, state.ownedAccountsCount, state.ownedCardsCount, state.reviewRequiredCount))
        Spacer(Modifier.height(20.dp))
        IconTextButton(
            onClick = onEnterApp,
            brandedLogo = true,
            text = stringResource(R.string.onboarding_enter_app),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HomePlaceholder(modifier: Modifier, state: OnboardingUiState) {
    Column(
        modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MasroofLogo(
            size = MasroofIconSizes.hero,
            contentDescription = null,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(MasroofSpacing.sectionHeaderGap))
        Text(stringResource(R.string.home_setup_complete))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_done_counts, state.ownedAccountsCount, state.ownedCardsCount, state.reviewRequiredCount))
    }
}
