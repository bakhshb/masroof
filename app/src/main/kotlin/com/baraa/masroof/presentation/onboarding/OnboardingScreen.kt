
package com.baraa.masroof.presentation.onboarding

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.ui.theme.MasroofSizes
import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofSpacing

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofFormField as OutlinedTextField
import androidx.compose.material3.RadioButton
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.baraa.masroof.presentation.settings.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.presentation.common.IconTextButton
import com.baraa.masroof.presentation.common.IconTextButtonOutlined
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.common.MasroofLogo
import java.time.LocalDate

@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestRestoreBackup: () -> Unit,
    settingsViewModel: SettingsViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val registry by settingsViewModel.uiState.collectAsState()
    var editingBank by rememberSaveable { mutableStateOf(false) }
    var choosingManual by rememberSaveable { mutableStateOf(false) }
    var manualChoiceBank by rememberSaveable { mutableStateOf<String?>(null) }
    var manualBankId by rememberSaveable { mutableStateOf<String?>(null) }
    var manualCard by rememberSaveable { mutableStateOf(false) }
    val busy = registry.updating || state.updatingOwnership || state.finalizing || state.restoringBackup || state.importState is ImportState.Scanning
    val back = { if (!busy) viewModel.goBack() }
    BackHandler(enabled = state.step != OnboardingStep.WELCOME || busy, onBack = back)
    LaunchedEffect(state.step, state.updatingOwnership, state.accounts, state.cards) {
        if (state.step == OnboardingStep.OWNERSHIP && !state.updatingOwnership) settingsViewModel.refresh()
    }
    // Fast writes can coalesce busy transitions; the persisted registry facts drive refreshes too.
    LaunchedEffect(state.step, registry.updating, registry.followedCards, registry.followedAccounts) {
        if (!registry.updating) viewModel.refreshOwnership()
    }
    CompositionLocalProvider(LocalSettingsBankNames provides state.bankCatalog?.banks.orEmpty().mapNotNull { bank ->
        bank.displayName?.let { bank.bankId to it }
    }.toMap()) {
    val workflow = viewModel.messagePatternsWorkflow
    val catalog = state.bankCatalog
    if (editingBank && catalog != null && workflow != null) {
        SettingsBankEditorScreen(catalog, null, workflow, { editingBank = false }, { id ->
            editingBank = false; viewModel.onBankAdded(id)
        })
        return@CompositionLocalProvider
    }
    OnboardingScreen(
        state = state,
        onStart = viewModel::onStartClicked,
        onRequestPermissions = onRequestPermissions,
        onOpenAppSettings = onOpenAppSettings,
        onRequestRestoreBackup = onRequestRestoreBackup,
        onRestorePassphraseChange = viewModel::onRestorePassphraseChange,
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
        onBack = back, onToggleBank = viewModel::toggleBank, onContinueBanks = viewModel::continueFromBanks,
        onAddBank = { editingBank = true }, onRetryBanks = viewModel::reloadFromCurrentState,
        onSelectThroughDate = viewModel::selectImportThroughDate,
        onAddManual = { manualChoiceBank = state.selectedBankIds.singleOrNull(); manualCard = false; choosingManual = true }, registryBusy = registry.updating || registry.loading,
        registryError = registry.error != null, onRetryRegistry = settingsViewModel::refresh,
        onConfigureCard = { candidate ->
            val card = (registry.followedCards + registry.unregisteredCards + registry.stoppedCards)
                .firstOrNull { it.bank == candidate.bank && it.last4 == candidate.suffix }
            if (card != null) settingsViewModel.openCardNetworkPicker(card)
        },
    )
    if (choosingManual) com.baraa.masroof.presentation.common.MasroofBottomSheet(
        stringResource(R.string.onboarding_add_manual), { choosingManual = false }, { manualBankId = manualChoiceBank; choosingManual = false },
        stringResource(R.string.teaching_next), canApply = manualChoiceBank in state.selectedBankIds) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            com.baraa.masroof.presentation.common.MasroofSheetChoices(state.selectedBankIds.toList(), setOfNotNull(manualChoiceBank),
                { settingsBankLabel(Bank(it)) }, { manualChoiceBank = it }, { "manual-bank-$it" })
            com.baraa.masroof.presentation.common.MasroofSheetChoices(listOf(false, true), setOf(manualCard),
                { stringResource(if (it) R.string.settings_add_card_manual else R.string.settings_add_account_manual) },
                { manualCard = it }, { "manual-kind-$it" })
        }
    }
    manualBankId?.let { id -> SettingsManualRegistrySheet(id, manualCard, registry,
        { if (!registry.updating) manualBankId = null },
        { last4, name -> settingsViewModel.addManualAccount(id, last4, name) { manualBankId = null; viewModel.refreshOwnership() } },
        { last4, name, configuration -> settingsViewModel.addManualCard(id, last4, name, configuration) { manualBankId = null; viewModel.refreshOwnership() } }) }
    SettingsCardSetupSheet(registry.cardNetworkTarget, registry.cardTypeSuggestion, registry.followedCards, registry.followedAccounts,
        registry.updating, registry.error != null, { settingsViewModel.dismissCardNetworkPicker() }, { settingsViewModel.saveCardSetup(it) })
    }
}

@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    onStart: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestRestoreBackup: () -> Unit,
    onRestorePassphraseChange: (String) -> Unit,
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
    onBack: () -> Unit = {}, onToggleBank: (String) -> Unit = {}, onContinueBanks: () -> Unit = {},
    onAddBank: () -> Unit = {}, onRetryBanks: () -> Unit = {},
    onSelectThroughDate: (LocalDate) -> Unit = {}, onAddManual: () -> Unit = {},
    onConfigureCard: (OwnershipCandidateUi) -> Unit = {}, registryBusy: Boolean = false,
    registryError: Boolean = false, onRetryRegistry: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    com.baraa.masroof.presentation.common.MasroofSecondaryScaffold(stringResource(R.string.reference_initial_setup),
        onBack = { if (state.step == OnboardingStep.WELCOME) context.onboardingActivity()?.onBackPressedDispatcher?.onBackPressed() else onBack() },
        backContentDescription = stringResource(R.string.settings_back)) { contentModifier ->
    Column(modifier = contentModifier.fillMaxSize()) {
        Text(stringResource(R.string.reference_setup_progress, when (state.step) {
            OnboardingStep.WELCOME -> 1; OnboardingStep.BANKS -> 2; OnboardingStep.PERMISSION -> 3
            OnboardingStep.IMPORT_DATE, OnboardingStep.IMPORTING -> 4; OnboardingStep.OWNERSHIP -> 5
            OnboardingStep.FINALIZE, OnboardingStep.HOME -> 6
        }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        when (state.step) {
            OnboardingStep.WELCOME -> WelcomeStep(
                modifier = Modifier.fillMaxSize(),
                restoringBackup = state.restoringBackup,
                restorePassphrase = state.restorePassphrase,
                error = state.error,
                onStart = onStart,
                onRequestRestoreBackup = onRequestRestoreBackup,
                onRestorePassphraseChange = onRestorePassphraseChange,
                onClearBackupError = onClearBackupError,
            )
            OnboardingStep.BANKS -> BanksStep(state, onToggleBank, onContinueBanks, onAddBank, onRetryBanks)
            OnboardingStep.PERMISSION -> PermissionStep(
                modifier = Modifier.fillMaxSize(),
                denied = state.error == OnboardingError.PERMISSION_DENIED,
                onRequestPermissions = onRequestPermissions,
                onOpenSettings = onOpenAppSettings,
            )
            OnboardingStep.IMPORT_DATE -> ImportDateStep(
                modifier = Modifier.fillMaxSize(),
                state = state,
                onSelectDateOption = onSelectDateOption,
                onSelectCustomDate = onSelectCustomDate,
                onContinue = onStartImport,
                onSelectThroughDate = onSelectThroughDate,
            )
            OnboardingStep.IMPORTING -> ImportingStep(Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge), state, onStartImport)
            OnboardingStep.OWNERSHIP -> OwnershipStep(
                modifier = Modifier.fillMaxSize(),
                state = state,
                onSetAccountOwned = onSetAccountOwned,
                onSetAccountExternal = onSetAccountExternal,
                onSetCardOwned = onSetCardOwned,
                onSetCardExternal = onSetCardExternal,
                onFinalize = onFinalize,
                onAddManual = onAddManual, onConfigureCard = onConfigureCard, registryBusy = registryBusy,
                registryError = registryError, onRetryRegistry = onRetryRegistry,
            )
            OnboardingStep.FINALIZE -> CompletionStep(Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge), state, onEnterApp)
            OnboardingStep.HOME -> HomePlaceholder(Modifier.fillMaxSize(), state)
        }
    }
}
}

@Composable
private fun WelcomeStep(
    modifier: Modifier,
    restoringBackup: Boolean,
    restorePassphrase: String,
    error: OnboardingError?,
    onStart: () -> Unit,
    onRequestRestoreBackup: () -> Unit,
    onRestorePassphraseChange: (String) -> Unit,
    onClearBackupError: () -> Unit,
) {
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
    var restoreExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = MasroofSpacing.periodDisplayHorizontalPadding + MasroofSpacing.borderWidth,
            vertical = com.baraa.masroof.ui.theme.MasroofReference.headerTop + MasroofSpacing.borderWidth)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                MasroofLogo(size = com.baraa.masroof.ui.theme.MasroofReference.aboutLogo, contentDescription = null)
                Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
                Text(stringResource(R.string.onboarding_banks_welcome), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.onboarding_welcome_local_only),
                    stringResource(R.string.settings_about_privacy_body))
                com.baraa.masroof.presentation.common.MasroofButton(onStart, Modifier.fillMaxWidth(), enabled = !restoringBackup) {
                    Text(stringResource(R.string.reference_start_setup))
                }
                com.baraa.masroof.presentation.common.MasroofOutlinedButton({ restoreExpanded = !restoreExpanded },
                    Modifier.fillMaxWidth(), enabled = !restoringBackup) { Text(stringResource(R.string.onboarding_restore_backup)) }
                if (restoreExpanded) {
                    OutlinedTextField(restorePassphrase, onRestorePassphraseChange, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_import_passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                    com.baraa.masroof.presentation.common.MasroofButton(onRequestRestoreBackup, Modifier.fillMaxWidth(),
                        enabled = !restoringBackup && restorePassphrase.isNotEmpty()) { Text(stringResource(R.string.onboarding_restore_backup)) }
                }
                if (restoringBackup) CircularProgressIndicator()
            }
        }
    }
}

private tailrec fun android.content.Context.onboardingActivity(): androidx.activity.ComponentActivity? = when (this) {
    is androidx.activity.ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.onboardingActivity()
    else -> null
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
            modifier = Modifier.size(MasroofSizes.size56),
        )
        Spacer(Modifier.height(MasroofSpacing.screenVertical))
        Text(stringResource(R.string.onboarding_permission_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        Text(stringResource(R.string.onboarding_permission_body))
        Spacer(Modifier.height(MasroofSpacing.screenPaddingLarge))
        IconTextButton(
            onClick = onRequestPermissions,
            icon = MasroofIcons.sms,
            text = stringResource(R.string.onboarding_allow),
            modifier = Modifier.fillMaxWidth(),
        )
        if (denied) {
            Spacer(Modifier.height(MasroofSpacing.sectionGap))
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
private fun BanksStep(state: OnboardingUiState, onToggle: (String) -> Unit, onContinue: () -> Unit,
    onAdd: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MasroofSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.onboarding_select_banks),
            stringResource(R.string.onboarding_select_banks_hint), style = MaterialTheme.typography.titleMedium)
        if (state.loadingBanks) CircularProgressIndicator()
        else if (state.error == OnboardingError.BANKS_LOAD_FAILED || state.bankCatalog == null) {
            Text(stringResource(R.string.settings_update_failed), color = MaterialTheme.colorScheme.error)
            com.baraa.masroof.presentation.common.MasroofOutlinedButton(onRetry) { Text(stringResource(R.string.dashboard_retry)) }
        } else {
            SettingsImportBankChoices(state.bankCatalog.banks, state.selectedBankIds, true, onToggle)
            com.baraa.masroof.presentation.common.MasroofOutlinedButton(onAdd, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_add_bank)) }
            com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.settings_bank_support_title),
                stringResource(R.string.settings_bank_support_hint))
        }
        IconTextButton(onContinue, icon = MasroofIcons.success, text = stringResource(R.string.teaching_next),
            enabled = !state.loadingBanks && state.bankCatalog != null && state.selectedBankIds.isNotEmpty() && state.error != OnboardingError.BANKS_LOAD_FAILED,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ImportDateStep(modifier: Modifier, state: OnboardingUiState,
    onSelectDateOption: (ImportDateOption) -> Unit, onSelectCustomDate: (LocalDate) -> Unit,
    onContinue: () -> Unit, onSelectThroughDate: (LocalDate) -> Unit) {
    val from = state.selectedImportDate ?: LocalDate.now()
    val through = state.importThroughDate ?: LocalDate.now()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MasroofSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofSectionHeader(stringResource(R.string.settings_import_period), icon = MasroofIcons.calendar)
        MasroofCard {
            com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.settings_import_banks),
                stringResource(R.string.settings_import_scope_hint), style = MaterialTheme.typography.titleMedium)
            state.selectedBankIds.forEach { Text(settingsBankLabel(Bank(it))) }
        }
        SettingsImportPeriod(from, through, true, onSelectCustomDate, onSelectThroughDate)
        DateOptionRow(state.selectedDateOption == ImportDateOption.CURRENT_MONTH_START, stringResource(R.string.onboarding_date_current_month)) { onSelectDateOption(ImportDateOption.CURRENT_MONTH_START) }
        DateOptionRow(state.selectedDateOption == ImportDateOption.LAST_30_DAYS, stringResource(R.string.onboarding_date_last_30)) { onSelectDateOption(ImportDateOption.LAST_30_DAYS) }
        DateOptionRow(state.selectedDateOption == ImportDateOption.LAST_27TH, stringResource(R.string.onboarding_date_last_27)) { onSelectDateOption(ImportDateOption.LAST_27TH) }
        IconTextButton(onContinue, icon = MasroofIcons.rescan, text = stringResource(R.string.onboarding_start_import),
            enabled = !from.isAfter(through) && !through.isAfter(LocalDate.now()) && state.selectedBankIds.isNotEmpty(), modifier = Modifier.fillMaxWidth())
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
        Text(title, modifier = Modifier.padding(top = MasroofSpacing.hairlineGap))
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
                Spacer(Modifier.height(MasroofSpacing.sectionGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MasroofIcons.rescan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
                    Text(if (state.importScanned > 0) stringResource(R.string.onboarding_import_scanning_count, state.importScanned)
                        else stringResource(R.string.onboarding_import_scanning))
                }
            }
            is ImportState.Completed -> {
                Icon(
                    imageVector = MasroofIcons.success,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MasroofIconSizes.hero),
                )
                Spacer(Modifier.height(MasroofSpacing.sectionGap))
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
                Spacer(Modifier.height(MasroofSpacing.sectionGap))
                Text(stringResource(R.string.onboarding_permission_denied_short))
                Spacer(Modifier.height(MasroofSpacing.sectionGap))
                IconTextButton(onClick = onRetry, icon = MasroofIcons.retry, text = stringResource(R.string.onboarding_retry))
            }
            is ImportState.ProviderError -> {
                Icon(
                    imageVector = MasroofIcons.error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MasroofIconSizes.hero),
                )
                Spacer(Modifier.height(MasroofSpacing.sectionGap))
                Text(stringResource(R.string.onboarding_provider_error))
                Spacer(Modifier.height(MasroofSpacing.sectionGap))
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
    onAddManual: () -> Unit, onConfigureCard: (OwnershipCandidateUi) -> Unit, registryBusy: Boolean,
    registryError: Boolean, onRetryRegistry: () -> Unit,
) {
    val busy = state.updatingOwnership || state.finalizing || registryBusy
    LazyColumn(modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        item {
            MasroofSectionHeader(
                title = stringResource(R.string.onboarding_ownership_title),
                icon = MasroofIcons.ownership,
            )
            if (state.hasUnknownCandidates) {
                Text(stringResource(R.string.onboarding_ownership_must_resolve))
            }
            if (state.hasUnconfiguredCards) com.baraa.masroof.presentation.common.MasroofGuidanceLabel(
                stringResource(R.string.settings_card_setup), stringResource(R.string.onboarding_configure_cards_hint))
            if (registryError) {
                Text(stringResource(R.string.settings_update_failed), color = MaterialTheme.colorScheme.error)
                com.baraa.masroof.presentation.common.MasroofOutlinedButton(onRetryRegistry, enabled = !busy) { Text(stringResource(R.string.dashboard_retry)) }
            }
            if (state.error == OnboardingError.OWNERSHIP_UPDATE_FAILED || state.error == OnboardingError.FINALIZATION_FAILED) {
                Text(stringResource(R.string.settings_update_failed), color = MaterialTheme.colorScheme.error)
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        item { MasroofSectionHeader(title = stringResource(R.string.settings_bank_accounts_title), icon = MasroofIcons.externalIn) }
        items(state.accounts, key = { "${it.bank.id}:${it.suffix}" }) { candidate ->
            CandidateCard(
                candidate = candidate,
                ownedLabel = stringResource(R.string.onboarding_is_mine_account),
                externalLabel = stringResource(R.string.onboarding_not_mine),
                onOwned = { onSetAccountOwned(candidate) },
                onExternal = { onSetAccountExternal(candidate) },
                busy = busy,
            )
        }
        item { MasroofSectionHeader(title = stringResource(R.string.settings_bank_cards_title), icon = MasroofIcons.cardPayment) }
        items(state.cards, key = { "${it.bank.id}:${it.suffix}" }) { candidate ->
            CandidateCard(
                candidate = candidate,
                ownedLabel = stringResource(R.string.onboarding_is_mine_card),
                externalLabel = stringResource(R.string.onboarding_not_mine),
                onOwned = { onSetCardOwned(candidate) },
                onExternal = { onSetCardExternal(candidate) },
                busy = busy,
            )
            if (candidate.ownership == OwnershipStatus.OWNED) com.baraa.masroof.presentation.common.MasroofOutlinedButton(
                { onConfigureCard(candidate) }, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(stringResource(R.string.settings_card_setup))
            }
        }
        item {
            if (state.accounts.isEmpty() && state.cards.isEmpty()) Text(stringResource(R.string.onboarding_no_candidates))
            state.bankCatalog?.banks.orEmpty().filter { it.bankId in state.selectedBankIds && it.currentPatterns.none { pattern -> pattern.canApply } }.forEach { bank ->
                MasroofCard {
                    Text(settingsBankLabel(Bank(bank.bankId)), style = MaterialTheme.typography.titleMedium)
                    Text(bankSupportLabel(bank).first)
                    Text(stringResource(R.string.onboarding_bank_teach_later), style = MaterialTheme.typography.bodySmall)
                }
            }
            com.baraa.masroof.presentation.common.MasroofOutlinedButton(onAddManual, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(stringResource(R.string.onboarding_add_manual))
            }
        }
        item {
            IconTextButton(
                onClick = onFinalize,
                enabled = !state.hasUnknownCandidates && !state.hasUnconfiguredCards && !busy,
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
    busy: Boolean,
) {
    MasroofCard(
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
                    modifier = Modifier.size(MasroofSizes.size22),
                )
                Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
                Text(
                    settingsBankLabel(candidate.bank),
                )
            }
            val label = if (candidate.kind == OwnershipCandidateUi.CandidateKind.ACCOUNT) {
                stringResource(R.string.onboarding_account_suffix, candidate.suffix)
            } else {
                stringResource(R.string.onboarding_card_suffix, candidate.suffix)
            }
            Text(label)
            Text(stringResource(if (candidate.discoveredFromSms) R.string.onboarding_discovered_entry else R.string.onboarding_manual_entry),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            candidate.cardType?.let { type -> Text(stringResource(if (type == com.baraa.masroof.domain.model.CardType.CREDIT)
                R.string.settings_card_type_credit else R.string.settings_card_type_debit), style = MaterialTheme.typography.bodySmall) }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                IconTextButton(onClick = onOwned, icon = MasroofIcons.success, text = ownedLabel, enabled = !busy)
                IconTextButton(onClick = onExternal, icon = MasroofIcons.warning, text = externalLabel, enabled = !busy)
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
                Spacer(Modifier.size(MasroofSpacing.smallGap))
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
            modifier = Modifier.size(MasroofSizes.size64),
        )
        Spacer(Modifier.height(MasroofSpacing.screenVertical))
        Text(stringResource(R.string.onboarding_done_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        Text(stringResource(R.string.onboarding_done_counts, state.ownedAccountsCount, state.ownedCardsCount, state.reviewRequiredCount))
        if (state.bankCatalog?.banks.orEmpty().any { it.bankId in state.selectedBankIds && it.currentPatterns.none { pattern -> pattern.canApply } }) {
            Spacer(Modifier.height(MasroofSpacing.sectionGap))
            Text(stringResource(R.string.onboarding_bank_teach_later), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(MasroofSpacing.largeGap))
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
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(MasroofSpacing.sectionHeaderGap))
        Text(stringResource(R.string.home_setup_complete))
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        Text(stringResource(R.string.onboarding_done_counts, state.ownedAccountsCount, state.ownedCardsCount, state.reviewRequiredCount))
    }
}
