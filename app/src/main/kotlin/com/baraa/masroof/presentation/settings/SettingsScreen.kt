package com.baraa.masroof.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.baraa.masroof.presentation.navigation.resolvePendingDestination

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    reviewRequiredCount: Int,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    pendingLaunch: SettingsLaunchRequest? = null,
    onPendingLaunchConsumed: () -> Unit = {},
    onLocaleChanged: () -> Unit,
    onRequestExport: () -> Unit,
    onRequestImport: () -> Unit,
    onRequestExportLogs: () -> Unit,
    onRequestSmsPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    val state by viewModel.uiState.collectAsState()
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

    fun openRegistryCategory(category: SettingsRegistryCategory) {
        category.singleBankDirectDestination(state)?.let { direct ->
            navigateTo(direct)
            return
        }
        navigateTo(category.listDestination())
    }

    LaunchedEffect(pendingLaunch, state.loading, state.bankSummaries) {
        val launch = pendingLaunch ?: return@LaunchedEffect
        if (launch.destination.needsBankSummaries() && state.loading) return@LaunchedEffect
        openAt(resolvePendingDestination(launch.destination, state))
        onPendingLaunchConsumed()
    }

    LaunchedEffect(destination) {
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

    val logEntries by viewModel.logEntries.collectAsState()
    val logErrorCount = logEntries.count { it.level == com.baraa.masroof.application.logging.AppLogLevel.ERROR }

    when (val current = destination) {
        SettingsDestination.Hub -> SettingsHubScreen(
            state = state,
            reviewRequiredCount = reviewRequiredCount,
            onBack = { popOrExit() },
            onOpenAccounts = { openRegistryCategory(SettingsRegistryCategory.Accounts) },
            onOpenCards = { openRegistryCategory(SettingsRegistryCategory.Cards) },
            onOpenLoans = { openRegistryCategory(SettingsRegistryCategory.Loans) },
            onOpenCommitments = { navigateTo(SettingsDestination.MyCommitments) },
            onOpenReview = onOpenReview,
            onOpenApp = { navigateTo(SettingsDestination.App) },
            onOpenDataBackup = { navigateTo(SettingsDestination.DataBackup) },
            onOpenAbout = { navigateTo(SettingsDestination.About) },
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

        SettingsDestination.Banks -> SettingsRegistryCategoryScreen(
            category = SettingsRegistryCategory.Accounts,
            state = state,
            onBack = { popOrExit() },
            onOpenBank = { summary ->
                navigateTo(SettingsDestination.BankAccounts(summary.bank.id))
            },
        )

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
                )

                state.loading -> SettingsBankHubLoadingScreen(
                    bank = bank,
                    onBack = { popOrExit() },
                )

                else -> {
                    LaunchedEffect(current.bankId) {
                        destinationStack = replaceSettingsTop(
                            destinationStack,
                            SettingsDestination.MyAccounts,
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
            onConfirmOwned = viewModel::confirmAccountOwned,
            onMarkExternal = viewModel::markAccountExternal,
            onRequestStopTracking = viewModel::requestStopAccountTracking,
            onResumeTracking = viewModel::resumeAccountTracking,
            onDismissStopConfirm = viewModel::dismissStopConfirm,
            onConfirmStopTracking = viewModel::confirmStopAccountTracking,
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
            onConfirmOwned = viewModel::confirmCardOwned,
            onMarkExternal = viewModel::markCardExternal,
            onRequestStopTracking = viewModel::requestStopTracking,
            onResumeTracking = viewModel::resumeTracking,
            onDismissStopConfirm = viewModel::dismissStopConfirm,
            onConfirmStopTracking = viewModel::confirmStopTracking,
            onRenameCard = viewModel::openRenameCard,
            onDismissRenameCard = viewModel::dismissRenameCard,
            onSaveCardName = viewModel::saveCardDisplayName,
            onPickCardNetwork = viewModel::openCardNetworkPicker,
            onDismissCardNetwork = viewModel::dismissCardNetworkPicker,
            onSelectCardNetwork = viewModel::setCardNetwork,
            onPickCardRole = viewModel::openCardRolePicker,
            onDismissCardRole = viewModel::dismissCardRolePicker,
            onSetPrimaryCard = viewModel::setPrimaryCard,
            onSetSupplementaryCard = viewModel::setSupplementaryCard,
            onClearCardRole = viewModel::clearCardRole,
            onLinkDebitCard = viewModel::openLinkDebitCard,
            onDismissLinkDebit = viewModel::dismissLinkDebitCard,
            onConfirmLinkDebit = viewModel::linkDebitToAccount,
            onMarkDebit = viewModel::markCardAsDebit,
        )

        is SettingsDestination.BankLoans -> SettingsBankLoansScreen(
            bank = Bank(current.bankId),
            state = state,
            onBack = { popOrExit() },
            onConfirmOwned = viewModel::confirmLoanOwned,
            onMarkExternal = viewModel::markLoanExternal,
            onRequestStopTracking = viewModel::requestStopLoanTracking,
            onResumeTracking = viewModel::resumeLoanTracking,
            onDismissStopConfirm = viewModel::dismissStopConfirm,
            onConfirmStopTracking = viewModel::confirmStopLoanTracking,
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

        SettingsDestination.DataBackup -> SettingsDataBackupScreen(
            state = state,
            onBack = { popOrExit() },
            onReparseStored = viewModel::reparseStoredMessages,
            onImportSms = viewModel::importSmsFromPhone,
            onClearSmsImportMessage = viewModel::clearSmsImportMessage,
            onRequestSmsPermission = onRequestSmsPermission,
            onOpenAppSettings = onOpenAppSettings,
            onRequestExport = onRequestExport,
            onRequestImport = onRequestImport,
            onConfirmPendingImport = viewModel::confirmPendingImport,
            onCancelPendingImport = viewModel::cancelPendingImport,
            onClearBackupMessage = viewModel::clearBackupMessage,
            onRequestFxRateCorrection = viewModel::requestHistoricalMerchantRateCorrection,
            onConfirmFxRateCorrection = viewModel::confirmHistoricalMerchantRateCorrections,
            onDismissFxRateCorrection = viewModel::dismissHistoricalMerchantRateCorrection,
        )

        SettingsDestination.About -> SettingsAboutScreen(
            appVersion = state.appVersion,
            isNightlyBuild = state.isNightlyBuild,
            updateChannel = state.updateChannel,
            githubTokenConfigured = state.githubTokenConfigured,
            updateState = state.updateState,
            updateMessage = state.updateMessage,
            logErrorCount = logErrorCount,
            onBack = { popOrExit() },
            onOpenLogs = { navigateTo(SettingsDestination.Logs) },
            onOpenDesignCatalog = { navigateTo(SettingsDestination.DesignCatalog) },
            onSaveGithubToken = viewModel::saveGithubToken,
            onClearGithubToken = viewModel::clearGithubToken,
            onSelectUpdateChannel = viewModel::setUpdateChannel,
            onCheckForUpdates = { viewModel.checkForUpdates(silent = false) },
            onDownloadUpdate = viewModel::downloadUpdate,
            onInstallUpdate = viewModel::installPendingUpdate,
            onClearUpdateMessage = viewModel::clearUpdateMessage,
        )

        SettingsDestination.Logs -> SettingsLogsScreen(
            viewModel = viewModel,
            onBack = { popOrExit() },
            onRequestExport = onRequestExportLogs,
        )

        SettingsDestination.DesignCatalog -> DesignCatalogScreen(
            onBack = { popOrExit() },
        )
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
            CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubScreen(
    state: SettingsUiState,
    reviewRequiredCount: Int,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenLoans: () -> Unit,
    onOpenCommitments: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenDataBackup: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    MasroofSecondaryScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        if (state.loading) {
            Column(
                modifier = contentModifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@MasroofSecondaryScaffold
        }

        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            SettingsGroupTitle(stringResource(R.string.settings_my_finances_section))

            SettingsNavRow(
                icon = MasroofIcons.externalIn,
                title = stringResource(R.string.settings_accounts_section),
                subtitle = SettingsRegistryCategory.Accounts.hubSubtitle(state),
                onClick = onOpenAccounts,
            )

            SettingsNavRow(
                icon = MasroofIcons.cardPayment,
                title = stringResource(R.string.settings_cards_section),
                subtitle = SettingsRegistryCategory.Cards.hubSubtitle(state),
                onClick = onOpenCards,
            )

            SettingsNavRow(
                icon = MasroofIcons.moneyMovement,
                title = stringResource(R.string.settings_loans_followed),
                subtitle = SettingsRegistryCategory.Loans.hubSubtitle(state),
                onClick = onOpenLoans,
            )

            SettingsNavRow(
                icon = MasroofIcons.calendar,
                title = stringResource(R.string.settings_commitments_section),
                subtitle = stringResource(
                    R.string.settings_commitments_hub_subtitle,
                    state.activeCommitments.size,
                    state.disabledCommitments.size,
                ),
                onClick = onOpenCommitments,
            )

            SettingsNavRow(
                icon = MasroofIcons.notifications,
                title = stringResource(R.string.settings_hub_review_title),
                subtitle = reviewHubSubtitle(reviewRequiredCount),
                badgeCount = reviewRequiredCount.takeIf { it > 0 },
                onClick = onOpenReview,
            )

            SettingsGroupTitle(stringResource(R.string.settings_app_section))

            SettingsNavRow(
                icon = MasroofIcons.theme,
                title = stringResource(R.string.settings_app_section),
                subtitle = appHubSubtitle(state.languageTag, state.themeMode),
                onClick = onOpenApp,
            )

            SettingsGroupTitle(stringResource(R.string.settings_data_backup_section))

            SettingsNavRow(
                icon = MasroofIcons.rescan,
                title = stringResource(R.string.settings_data_backup_section),
                subtitle = stringResource(R.string.settings_data_backup_hub_subtitle),
                onClick = onOpenDataBackup,
            )

            SettingsNavRow(
                icon = MasroofIcons.periodHint,
                title = stringResource(R.string.settings_about_section),
                subtitle = stringResource(R.string.settings_about_subtitle, state.appVersion),
                onClick = onOpenAbout,
            )

            state.error?.let {
                Text(
                    stringResource(R.string.settings_update_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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

@Composable
private fun reviewHubSubtitle(reviewRequiredCount: Int): String =
    if (reviewRequiredCount > 0) {
        stringResource(R.string.settings_hub_review_subtitle_count, reviewRequiredCount)
    } else {
        stringResource(R.string.settings_hub_review_subtitle_none)
    }
