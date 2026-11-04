package com.baraa.masroof.presentation.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.debug.DesignCatalogScreen
import com.baraa.masroof.presentation.navigation.SettingsDestination
import com.baraa.masroof.presentation.navigation.SettingsLaunchRequest
import com.baraa.masroof.presentation.navigation.decodeSettingsDestination
import com.baraa.masroof.presentation.navigation.encode
import com.baraa.masroof.presentation.navigation.popSettingsStack
import com.baraa.masroof.presentation.navigation.pushSettingsDestination
import com.baraa.masroof.presentation.navigation.replaceSettingsStack
import com.baraa.masroof.presentation.navigation.replaceSettingsTop
import com.baraa.masroof.presentation.statement.StatementReconciliationRoute
import com.baraa.masroof.presentation.statement.StatementReconciliationViewModel

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    statementReconciliationViewModel: StatementReconciliationViewModel,
    reviewRequiredCount: Int,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    /** The hub shows a back arrow when Settings was opened above another screen, not from its tab. */
    showHubBack: Boolean = false,
    pendingLaunch: SettingsLaunchRequest? = null,
    onPendingLaunchConsumed: () -> Unit = {},
    onLocaleChanged: () -> Unit,
    onRequestExport: () -> Unit,
    onRequestImport: () -> Unit,
    onRequestExportLogs: () -> Unit,
    onRequestSmsPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onOpenReviewExample: (String) -> Unit = { onOpenReview() },
    onShowGuideAgain: () -> Unit = {},
    onOpenAccounts: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    val state by viewModel.uiState.collectAsState()
    val deleteActions = remember(viewModel) {
        RegistryDeleteActions(viewModel::requestDelete, viewModel::dismissDelete, viewModel::confirmDelete, viewModel::stopTrackingInsteadOfDelete)
    }
    var manualBankId by rememberSaveable { mutableStateOf<String?>(null) }
    var manualCard by rememberSaveable { mutableStateOf(false) }
    var destinationStack by rememberSaveable {
        mutableStateOf(listOf(SettingsDestination.Hub.encode()))
    }
    val destination = decodeSettingsDestination(
        destinationStack.lastOrNull() ?: SettingsDestination.Hub.encode(),
    )

    fun navigateTo(next: SettingsDestination) {
        destinationStack = pushSettingsDestination(destinationStack, next)
    }

    fun popOrExit() {
        if (state.importingSms) return
        val popped = popSettingsStack(destinationStack)
        if (popped == null) {
            onBack()
        } else {
            destinationStack = popped
        }
    }

    fun openAt(next: SettingsDestination) {
        destinationStack = replaceSettingsStack(next)
    }

    LaunchedEffect(pendingLaunch, state.loading, state.bankSummaries) {
        val launch = pendingLaunch ?: return@LaunchedEffect
        if (launch.destination.needsBankSummaries() && state.loading) return@LaunchedEffect
        openAt(launch.destination)
        onPendingLaunchConsumed()
    }

    LaunchedEffect(destination) {
        if (destination == SettingsDestination.Banks || destination is SettingsDestination.BankHub || destination == SettingsDestination.About) viewModel.refresh()
        if (destination == SettingsDestination.About || destination == SettingsDestination.Logs) {
            viewModel.refreshLogs()
        }
        if (destination == SettingsDestination.DataBackup) {
            viewModel.refreshHistoricalMerchantRateCorrections()
        }
    }

    BackHandler {
        popOrExit()
    }


    CompositionLocalProvider(LocalSettingsBankNames provides state.bankCatalog?.banks.orEmpty().mapNotNull { bank ->
        bank.displayName?.let { bank.bankId to it }
    }.toMap()) {
    when (val current = destination) {
        SettingsDestination.Hub -> SettingsHubScreen(
            state = state,
            onBack = if (showHubBack) ({ popOrExit() }) else null,
            onOpenBanks = { navigateTo(SettingsDestination.Banks) },
            onOpenCommitments = { navigateTo(SettingsDestination.MyCommitments) },
            onOpenApp = { navigateTo(SettingsDestination.App) },
            onOpenDataBackup = { navigateTo(SettingsDestination.DataBackup) },
            onOpenAbout = { navigateTo(SettingsDestination.About) },
            onOpenLearning = { navigateTo(SettingsDestination.MessageLearning) },
        )

        SettingsDestination.MyAccounts -> SettingsRegistryCategoryScreen(
            category = SettingsRegistryCategory.Accounts,
            state = state,
            onBack = { popOrExit() },
            onOpenBank = { summary ->
                navigateTo(SettingsDestination.BankAccounts(summary.bank.id))
            },
        )

        SettingsDestination.MyCards -> SettingsRegistryCategoryScreen(
            category = SettingsRegistryCategory.Cards,
            state = state,
            onBack = { popOrExit() },
            onOpenBank = { summary ->
                navigateTo(SettingsDestination.BankCards(summary.bank.id))
            },
        )

        SettingsDestination.MyLoans -> SettingsRegistryCategoryScreen(
            category = SettingsRegistryCategory.Loans,
            state = state,
            onBack = { popOrExit() },
            onOpenBank = { summary ->
                navigateTo(SettingsDestination.BankLoans(summary.bank.id))
            },
        )

        SettingsDestination.MyCommitments -> SettingsCommitmentsScreen(
            state = state,
            onBack = { popOrExit() },
            onOpenCommitment = { commitment ->
                navigateTo(SettingsDestination.CommitmentDetail(commitment.id))
            },
            onOpenCommitmentById = { commitmentId ->
                navigateTo(SettingsDestination.CommitmentDetail(commitmentId))
            },
            onListTabChange = viewModel::setCommitmentsListTab,
            onToggle = { viewModel.toggleCommitmentActive(it.id) },
            onDelete = { viewModel.deleteCommitment(it.id) },
        )

        is SettingsDestination.CommitmentDetail -> {
            val commitment = viewModel.commitmentById(current.commitmentId)
            when {
                commitment != null -> SettingsCommitmentEditorScreen(
                    commitment = commitment,
                    saving = state.savingCommitment,
                    onBack = { popOrExit() },
                    onSave = { draft ->
                        viewModel.saveCommitment(current.commitmentId, draft)
                    },
                    onToggleActive = {
                        viewModel.toggleCommitmentActive(current.commitmentId)
                    },
                    onDelete = {
                        viewModel.deleteCommitment(current.commitmentId) { popOrExit() }
                    },
                )

                state.loading -> SettingsCommitmentsLoadingScreen(onBack = { popOrExit() })

                state.commitmentsLoaded -> {
                    LaunchedEffect(current.commitmentId) {
                        destinationStack = replaceSettingsTop(destinationStack, SettingsDestination.MyCommitments)
                    }
                    SettingsCommitmentsLoadingScreen(onBack = { popOrExit() })
                }

                else -> SettingsCommitmentsLoadingScreen(onBack = { popOrExit() })
            }
        }

        SettingsDestination.Banks -> SettingsBanksScreen(state, { popOrExit() },
            onOpenBank = { navigateTo(SettingsDestination.BankHub(it)) },
            onAddBank = { navigateTo(SettingsDestination.BankEditor()) },
            onImport = { viewModel.clearScopedSmsImportResult(); navigateTo(SettingsDestination.SmsImport()) })

        is SettingsDestination.BankEditor -> {
            val catalog = state.bankCatalog
            val workflow = viewModel.messagePatternsWorkflow
            if (catalog != null && workflow != null) SettingsBankEditorScreen(catalog, current.bankId, workflow,
                onBack = { popOrExit() }, onSaved = { id ->
                    viewModel.refresh()
                    destinationStack = replaceSettingsTop(destinationStack, SettingsDestination.BankHub(id))
                })
            else SettingsBankHubLoadingScreen(Bank(current.bankId ?: Bank.UNKNOWN.id), { popOrExit() },
                failed = !state.loading, onRetry = viewModel::refresh)
        }
        is SettingsDestination.SmsImport -> SettingsSmsImportScreen(state, current.bankId,
            onBack = { popOrExit() }, onImport = viewModel::importSelectedBankSms,
            onClearResult = viewModel::clearScopedSmsImportResult, onRequestPermission = onRequestSmsPermission,
            onRetry = viewModel::refresh,
            onOpenAppSettings = onOpenAppSettings, onOpenBank = {
                destinationStack = replaceSettingsTop(destinationStack, SettingsDestination.BankHub(it))
            }, onOpenPatterns = { destinationStack = replaceSettingsTop(destinationStack, SettingsDestination.MessagePatterns(it)) })

        is SettingsDestination.BankHub -> {
            val bank = Bank(current.bankId)
            val summary = state.bankSummary(current.bankId)
            when {
                summary != null -> SettingsBankScreen(
                    bank = bank,
                    summary = summary,
                    onBack = { popOrExit() },
                    onOpenAccounts = { navigateTo(SettingsDestination.BankAccounts(current.bankId)) },
                    onOpenCards = { navigateTo(SettingsDestination.BankCards(current.bankId)) },
                    onOpenLoans = { navigateTo(SettingsDestination.BankLoans(current.bankId)) },
                    onOpenPatterns = { navigateTo(SettingsDestination.MessagePatterns(current.bankId)) },
                    definition = state.bankCatalog?.banks?.find { it.bankId == current.bankId },
                    onImport = { viewModel.clearScopedSmsImportResult(); navigateTo(SettingsDestination.SmsImport(current.bankId)) },
                    onStatement = { navigateTo(SettingsDestination.BankStatement(current.bankId)) },
                    onEditBank = { navigateTo(SettingsDestination.BankEditor(current.bankId)) },
                )

                state.loading -> SettingsBankHubLoadingScreen(
                    bank = bank,
                    onBack = { popOrExit() },
                )

                else -> {
                    LaunchedEffect(current.bankId) {
                        destinationStack = replaceSettingsTop(
                            destinationStack,
                            SettingsDestination.Banks,
                        )
                    }
                    SettingsBankHubLoadingScreen(
                        bank = bank,
                        onBack = { popOrExit() },
                    )
                }
            }
        }

        is SettingsDestination.BankAccounts -> SettingsBankAccountsScreen(
            bank = Bank(current.bankId),
            state = state,
            onBack = { popOrExit() },
            onOpenBank = { navigateTo(SettingsDestination.BankHub(current.bankId)) },
            onAddManual = { viewModel.clearError(); manualBankId = current.bankId; manualCard = false },
            onConfirmOwned = viewModel::confirmAccountOwned,
            onMarkExternal = viewModel::markAccountExternal,
            onRequestStopTracking = viewModel::requestStopAccountTracking,
            onResumeTracking = viewModel::resumeAccountTracking,
            onDismissStopConfirm = viewModel::dismissStopConfirm,
            onConfirmStopTracking = viewModel::confirmStopAccountTracking,
            deleteActions = deleteActions,
            onRenameAccount = viewModel::openRenameAccount,
            onDismissRenameAccount = viewModel::dismissRenameAccount,
            onSaveAccountName = viewModel::saveAccountDisplayName,
            onPickAccountType = viewModel::openAccountTypePicker,
            onDismissAccountType = viewModel::dismissAccountTypePicker,
            onSelectAccountType = viewModel::setAccountTypeFromPicker,
        )

        is SettingsDestination.BankCards -> SettingsBankCardsScreen(
            bank = Bank(current.bankId),
            state = state,
            onBack = { popOrExit() },
            onOpenBank = { navigateTo(SettingsDestination.BankHub(current.bankId)) },
            onAddManual = { viewModel.clearError(); manualBankId = current.bankId; manualCard = true },
            onConfirmOwned = viewModel::confirmCardOwned,
            onMarkExternal = viewModel::markCardExternal,
            onRequestStopTracking = viewModel::requestStopTracking,
            onResumeTracking = viewModel::resumeTracking,
            onDismissStopConfirm = viewModel::dismissStopConfirm,
            onConfirmStopTracking = viewModel::confirmStopTracking,
            deleteActions = deleteActions,
            onRenameCard = viewModel::openRenameCard,
            onDismissRenameCard = viewModel::dismissRenameCard,
            onSaveCardName = viewModel::saveCardDisplayName,
            onPickCardNetwork = viewModel::openCardNetworkPicker,
            onDismissCardNetwork = viewModel::dismissCardNetworkPicker,
            onSaveCardSetup = viewModel::saveCardSetup,
        )

        is SettingsDestination.BankLoans -> SettingsBankLoansScreen(
            bank = Bank(current.bankId),
            state = state,
            onBack = { popOrExit() },
            onOpenBank = { navigateTo(SettingsDestination.BankHub(current.bankId)) },
            onConfirmOwned = viewModel::confirmLoanOwned,
            onMarkExternal = viewModel::markLoanExternal,
            onRequestStopTracking = viewModel::requestStopLoanTracking,
            onResumeTracking = viewModel::resumeLoanTracking,
            onDismissStopConfirm = viewModel::dismissStopConfirm,
            onConfirmStopTracking = viewModel::confirmStopLoanTracking,
            deleteActions = deleteActions,
        )

        SettingsDestination.App -> SettingsAppScreen(
            languageTag = state.languageTag,
            themeMode = state.themeMode,
            onBack = { popOrExit() },
            onSelectLanguage = { tag ->
                viewModel.setLanguageTag(tag, onLocaleChanged)
            },
            onSelectTheme = viewModel::setThemeMode,
        )

        SettingsDestination.StatementReconciliation -> SettingsBanksScreen(state, { popOrExit() },
            { navigateTo(SettingsDestination.BankStatement(it)) }, title = stringResource(R.string.settings_statement_reconciliation_title))
        is SettingsDestination.BankStatement -> StatementReconciliationRoute(
            viewModel = statementReconciliationViewModel, bankId = current.bankId, bankLabel = settingsBankLabel(Bank(current.bankId)), onBack = { popOrExit() },
        )

        SettingsDestination.DataBackup, SettingsDestination.ExportBackup, SettingsDestination.RestoreBackup, SettingsDestination.DataTools -> SettingsDataBackupScreen(
            state = state,
            onBack = { popOrExit() },
            page = when (current) {
                SettingsDestination.ExportBackup -> BackupPage.Export
                SettingsDestination.RestoreBackup -> BackupPage.Restore
                SettingsDestination.DataTools -> BackupPage.Tools
                else -> BackupPage.Hub
            },
            onOpenPage = { page -> navigateTo(when (page) {
                BackupPage.Export -> SettingsDestination.ExportBackup
                BackupPage.Restore -> SettingsDestination.RestoreBackup
                BackupPage.Tools -> SettingsDestination.DataTools
                BackupPage.Hub -> SettingsDestination.DataBackup
            }) },
            onReparseStored = viewModel::reparseStoredMessages,
            onExportPassphraseChange = viewModel::onExportPassphraseChange,
            onImportPassphraseChange = viewModel::onImportPassphraseChange,
            onRequestExport = onRequestExport,
            onRequestImport = onRequestImport,
            onConfirmPendingImport = viewModel::confirmPendingImport,
            onVerifyPendingImport = viewModel::verifyPendingImport,
            onCancelPendingImport = viewModel::cancelPendingImport,
            onClearBackupMessage = viewModel::clearBackupMessage,
            onRequestFxRateCorrection = viewModel::requestHistoricalMerchantRateCorrection,
            onConfirmFxRateCorrection = viewModel::confirmHistoricalMerchantRateCorrections,
            onDismissFxRateCorrection = viewModel::dismissHistoricalMerchantRateCorrection,
        )

        SettingsDestination.About -> SettingsAboutScreen(
            appVersion = state.appVersion,
            onShowGuideAgain = onShowGuideAgain,
            bankCatalog = state.bankCatalog,
            onOpenBanks = { navigateTo(SettingsDestination.Banks) },
            isNightlyBuild = state.isNightlyBuild,
            updateMessage = state.updateMessage,
            onBack = { popOrExit() },
            onOpenLogs = { navigateTo(SettingsDestination.Logs) },
            onOpenUpdates = { navigateTo(SettingsDestination.Updates) },
            onOpenDesignCatalog = { navigateTo(SettingsDestination.DesignCatalog) },
            onClearUpdateMessage = viewModel::clearUpdateMessage,
        )

        SettingsDestination.Updates -> SettingsUpdatesScreen(
            appVersion = state.appVersion,
            updateChannel = state.updateChannel, githubTokenConfigured = state.githubTokenConfigured,
            updateState = state.updateState, updateMessage = state.updateMessage,
            onBack = { popOrExit() }, onSelectUpdateChannel = viewModel::setUpdateChannel,
            onSaveGithubToken = viewModel::saveGithubToken, onClearGithubToken = viewModel::clearGithubToken,
            onCheckForUpdates = { viewModel.checkForUpdates(silent = false) },
            onDownloadUpdate = viewModel::downloadUpdate, onInstallUpdate = viewModel::installPendingUpdate,
            onClearUpdateMessage = viewModel::clearUpdateMessage)

        SettingsDestination.Logs -> SettingsLogsScreen(
            viewModel = viewModel,
            onBack = { popOrExit() },
            onRequestExport = onRequestExportLogs,
        )

        is SettingsDestination.MessagePatterns -> viewModel.messagePatternsWorkflow?.let { workflow ->
            if (current.bankId == null) SettingsBanksScreen(state, { popOrExit() },
                { navigateTo(SettingsDestination.MessagePatterns(it)) }, title = stringResource(R.string.settings_bank_teaching))
            else com.baraa.masroof.presentation.patterns.MessagePatternsScreen(workflow, current.bankId,
                onOpenReview = onOpenReviewExample, onBack = { popOrExit() },
                onImportBank = { navigateTo(SettingsDestination.SmsImport(it)) }, onOpenAccounts = onOpenAccounts)
        }
        SettingsDestination.MessageLearning -> com.baraa.masroof.presentation.analytics.MessageLearningSettingsScreen(
            onBack = { popOrExit() }, onOpenExample = onOpenReviewExample,
            onOpenPatterns = { navigateTo(SettingsDestination.MessagePatterns()) })
        SettingsDestination.DesignCatalog -> DesignCatalogScreen(
            onBack = { popOrExit() },
        )

    }
    manualBankId?.let { bankId -> SettingsManualRegistrySheet(bankId, manualCard, state,
        onDismiss = { if (!state.updating) manualBankId = null },
        onSaveAccount = { last4, name -> viewModel.addManualAccount(bankId, last4, name) { manualBankId = null } },
        onSaveCard = { last4, name, configuration -> viewModel.addManualCard(bankId, last4, name, configuration) { manualBankId = null } }) }
    }
}

private fun SettingsDestination.needsBankSummaries(): Boolean =
    when (this) {
        SettingsDestination.MyAccounts,
        SettingsDestination.MyCards,
        SettingsDestination.MyLoans,
        SettingsDestination.Banks,
        is SettingsDestination.BankAccounts,
        is SettingsDestination.BankCards,
        is SettingsDestination.BankLoans,
        is SettingsDestination.BankHub,
        -> true

        else -> false
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBankHubLoadingScreen(
    bank: Bank,
    onBack: () -> Unit,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
) {
    MasroofSecondaryScaffold(
        title = settingsBankLabel(bank),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed) {
                Text(stringResource(R.string.settings_update_failed), color = MaterialTheme.colorScheme.error)
                com.baraa.masroof.presentation.common.MasroofOutlinedButton(onRetry) { Text(stringResource(R.string.dashboard_retry)) }
            } else CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHubScreen(
    state: SettingsUiState,
    onOpenBanks: () -> Unit,
    onOpenCommitments: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenDataBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLearning: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    MasroofSecondaryScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        backContentDescription = onBack?.let { stringResource(R.string.settings_back) },
    ) { contentModifier ->
        if (state.loading) {
            Column(contentModifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
            return@MasroofSecondaryScaffold
        }
        Column(contentModifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap)) {
            com.baraa.masroof.presentation.common.MasroofCard {
                Text(stringResource(R.string.reference_manage_title), style = MaterialTheme.typography.titleMedium)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_banks_title),
                    stringResource(R.string.settings_banks_subtitle), MasroofIcons.moneyMovement, onOpenBanks)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_commitments_section),
                    stringResource(R.string.settings_commitments_hub_subtitle, state.activeCommitments.size, state.disabledCommitments.size), MasroofIcons.calendar, onOpenCommitments, divider = false)
            }
            com.baraa.masroof.presentation.common.MasroofCard {
                Text(stringResource(R.string.settings_app_section), style = MaterialTheme.typography.titleMedium)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.reference_preferences_title),
                    appHubSubtitle(state.languageTag, state.themeMode), MasroofIcons.palette, onOpenApp)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_data_backup_section),
                    stringResource(R.string.settings_data_backup_hub_subtitle), MasroofIcons.backup, onOpenDataBackup)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.analysis_learning_settings),
                    stringResource(R.string.analysis_learning_settings_hint), MasroofIcons.analysis, onOpenLearning)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_about_section),
                    stringResource(R.string.settings_about_subtitle, state.appVersion), MasroofIcons.periodHint, onOpenAbout, divider = false)
            }
            state.error?.let { Text(stringResource(R.string.settings_update_failed), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun appHubSubtitle(languageTag: String, themeMode: ThemeMode): String =
    stringResource(
        R.string.settings_app_hub_subtitle,
        if (AppLocale.isEnglish(languageTag)) {
            stringResource(R.string.settings_language_english)
        } else {
            stringResource(R.string.settings_language_arabic)
        },
        when (themeMode) {
            ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
            ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
            ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
        },
    )
