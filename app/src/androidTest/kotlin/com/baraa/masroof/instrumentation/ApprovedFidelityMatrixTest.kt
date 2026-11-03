package com.baraa.masroof.instrumentation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.*
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.update.*
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.settings.*
import com.baraa.masroof.presentation.review.*
import com.baraa.masroof.presentation.onboarding.*
import com.baraa.masroof.presentation.notification.*
import com.baraa.masroof.presentation.statement.*
import com.baraa.masroof.ui.theme.MasroofTheme
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.domain.model.Bank
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Capture real production components with the approved synthetic financial facts. */
@RunWith(AndroidJUnit4::class)
class ApprovedFidelityMatrixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val pageIds = listOf("home", "transactions", "accounts", "accountDetail", "cards", "cardDetail", "loans", "loanDetail", "commitments", "merchants", "daily", "review", "settings", "preferences", "about", "updates", "logs", "backup", "exportBackup", "restoreBackup", "dataTools", "notifications", "flowIn", "flowOut", "commitmentEditor", "statement", "onboarding", "bankRegistry", "myAccounts", "myCards", "myLoans", "myCommitments")

    @Test fun captureProductionPages_ar_light() = captureProductionPages("ar", false)
    @Test fun captureProductionPages_ar_dark() = captureProductionPages("ar", true)
    @Test fun captureProductionPages_en_light() = captureProductionPages("en", false)
    @Test fun captureProductionPages_en_dark() = captureProductionPages("en", true)

    private fun captureProductionPages(lang: String, night: Boolean) {
        val screen = mutableStateOf("home")
        val language = mutableStateOf("ar")
        val dark = mutableStateOf(false)
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        key(screen.value, language.value, dark.value) {
                            Column(Modifier.fillMaxSize()) {
                                MasroofAppBar(androidx.compose.ui.res.stringResource(R.string.app_name)) {
                                    if (screen.value != "onboarding") ReviewNotificationIconButton(3, {})
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) { ProductionPage(screen.value, language.value, dark.value) }
                                if (screen.value != "onboarding") {
                                    val selected = when (screen.value) {
                                        "home" -> 0; "transactions" -> 1; "review" -> 2
                                        in settingsPages -> 3
                                        else -> -1
                                    }
                                    val labels = listOf(R.string.approved_nav_home, R.string.approved_nav_transactions, R.string.approved_nav_review, R.string.approved_nav_settings)
                                    val icons = listOf(MasroofIcons.dashboard, MasroofIcons.recentTransactions, MasroofIcons.reviewQueue, MasroofIcons.settings)
                                    MasroofBottomNavigation(labels.mapIndexed { i, label -> MasroofNavigationItem(androidx.compose.ui.res.stringResource(label), icons[i], selected == i) }, {})
                                }
                            }
                        }
                    }
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "fidelity-matrix-final")
        directory.mkdirs()
        val capturePages = InstrumentationRegistry.getArguments().getString("capturePages")?.split(",") ?: pageIds
        for (page in capturePages) {
            compose.runOnIdle { language.value = lang; dark.value = night; screen.value = page }
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            // Compose idleness precedes the SurfaceFlinger frame. Wait for the displayed frame too.
            Thread.sleep(250)
            compose.waitForIdle()
            val texts = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
            assertTrue("Production page $page must render content", texts.size > 4)
            assertTrue("No Eastern digits on $page/$lang/$night", texts.none { Regex("[\u0660-\u0669\u06F0-\u06F9]").containsMatchIn(it) })
            if (page == "home") {
                val cells = (0..3).map { compose.onNodeWithTag("dashboard-shortcut-$it").fetchSemanticsNode().boundsInRoot }
                fun sameRow(a: Int, b: Int) = kotlin.math.abs(cells[a].center.y - cells[b].center.y) < 1f
                // Small phones wrap the strip into two rows of two; wider windows keep one row.
                assertTrue("Shortcuts form one row or two rows of two",
                    (1..3).all { sameRow(0, it) } || (sameRow(0, 1) && sameRow(2, 3) && cells[2].center.y > cells[0].center.y))
                assertTrue("Shortcut order follows language", if (lang == "ar") cells.first().center.x > cells.last().center.x else cells.first().center.x < cells.last().center.x)
                val label = AppLocaleContext.wrap(instrumentation.targetContext, lang).getString(R.string.approved_commitments)
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Full commitment label fits on one line: $lang/$night ${layouts.single().size} ${layouts.single().layoutInput.constraints} width=${layouts.single().didOverflowWidth} height=${layouts.single().didOverflowHeight}", layouts.single().lineCount == 1 && !layouts.single().hasVisualOverflow)
            }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            FileOutputStream(File(directory, "$page-$lang-${if (night) "dark" else "light"}.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            if (compose.onAllNodes(vertical).fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodes(vertical)[0].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                Thread.sleep(250)
            }
            val bottom = compose.onRoot().captureToImage().asAndroidBitmap()
            FileOutputStream(File(directory, "$page-$lang-${if (night) "dark" else "light"}-bottom.png")).use { bottom.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bottom.recycle()
        }
        assertTrue(File(directory, "${capturePages.last()}-$lang-${if (night) "dark" else "light"}.png").isFile)
    }

    @Composable
    private fun ProductionPage(id: String, language: String, dark: Boolean) {
        val financial = FidelityFixtures.financial()
        val settingsState = FidelityFixtures.settings(language)
        val reviewState = FidelityFixtures.review()
        val notificationState = FidelityFixtures.notifications()
        val logEntries = FidelityFixtures.logs()
        val updateState = settingsState.updateState
        val periodLabel = FinancialPeriodUiFormatter.formatRange(LocalContext.current, financial.period!!)
        when (id) {
            "home" -> DashboardScreen(
                state = financial,
                onPrevious = {},
                onNext = {},
                onCurrent = {},
                onRetry = {},
                onRescan = {},
                onOpenAllTransactions = {},
                onOpenTransaction = {},
                onOpenCommitments = {},
                onManageCardsInSettings = {},
                onOpenAccountsSummary = {},
                onOpenCardsSummary = {},
                onOpenLoansSummary = {},
                onOpenMerchantsSummary = {},
                onOpenLoanDetail = {},
                onOpenCardDetail = {},
                onOpenDebitDetail = {},
                onRequestSmsPermission = {},
                onOpenAppSettings = {},
                onOpenCustomize = {},
                onDismissCustomize = {},
                onSaveCustomize = {},
                onToggleCustomizeSection = {},
                onMoveCustomizeSection = { _, _ -> },
                onToggleCustomizeQuickExpense = {},
                onToggleCustomizeQuickIncome = {}
            )
            "transactions" -> TransactionListScreen(
                periodLabel = periodLabel,
                transactions = financial.allTransactions,
                projection = TransactionListPresentationMapper.project(financial.allTransactions, TransactionListFilterState()),
                onFilterChanged = {},
                onOpenTransaction = {},
                ownedCards = financial.ownedCards,
                ownedAccounts = financial.ownedAccounts
            )
            "accounts" -> AccountsSummaryScreen(
                state = financial,
                onBack = {},
                onOpenAccount = {}
            )
            "debitDetail" -> DebitCardDetailScreen(
                debit = financial.creditFacilities!!.debitCards.first(), state = financial,
                cardNetwork = com.baraa.masroof.domain.model.CardNetwork.MADA, onBack = {}, onOpenTransaction = {},
            )
            "accountDetail" -> AccountDetailScreen(
                account = financial.ownedAccounts.first(),
                state = financial,
                onBack = {},
                onOpenTransaction = {}
            )
            "cards" -> CardsSummaryScreen(
                state = financial,
                onBack = {},
                onOpenCard = {},
                onOpenDebit = {},
                cardNetworksByLast4 = financial.ownedCards.associate { CardOwnershipKey.of(it) to it.cardNetwork }
            )
            "cardDetail" -> CardDetailScreen(
                row = financial.creditFacilities!!.facilities.first().supplementaries.first(),
                state = financial,
                onBack = {},
                onOpenTransaction = {}
            )
            "loans" -> LoansSummaryScreen(
                state = financial,
                onBack = {},
                onOpenLoan = {}
            )
            "loanDetail" -> LoanDetailScreen(
                loan = financial.loansOverview!!.loans.first(),
                state = financial,
                onBack = {},
                onOpenTransaction = {}
            )
            "commitments" -> CommitmentsSummaryScreen(
                state = financial,
                onBack = {},
                onOpenCommitment = {}
            )
            "merchants" -> MerchantSpendingSummaryScreen(
                state = financial,
                onBack = {},
                onOpenMerchant = {}
            )
            "daily" -> DailyExpensesScreen(
                state = financial,
                onBack = {},
                onOpenTransaction = {}
            )
            "review" -> ReviewListScreen(
                state = reviewState,
                onRefresh = {},
                onOpen = {},
                onDismissAllInformational = {},
                onListModeChange = {}
            )
            "settings" -> SettingsHubScreen(
                state = settingsState,
                onOpenBanks = {},
                onOpenCommitments = {},
                onOpenApp = {},
                onOpenDataBackup = {},
                onOpenAbout = {}
            )
            "preferences" -> SettingsAppScreen(
                languageTag = language,
                themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                onBack = {},
                onSelectLanguage = {},
                onSelectTheme = {}
            )
            "about" -> SettingsAboutScreen(
                appVersion = "1.0.0",
                isNightlyBuild = false,
                updateChannel = UpdateChannel.STABLE,
                githubTokenConfigured = false,
                updateState = updateState,
                updateMessage = null,
                onBack = {},
                onOpenLogs = {},
                onSaveGithubToken = {},
                onClearGithubToken = {},
                onSelectUpdateChannel = {},
                onCheckForUpdates = {},
                onDownloadUpdate = {},
                onInstallUpdate = {},
                onClearUpdateMessage = {}
            )
            "updates" -> SettingsUpdatesScreen(
                updateChannel = UpdateChannel.STABLE,
                githubTokenConfigured = false,
                updateState = updateState,
                updateMessage = null,
                onBack = {},
                onSelectUpdateChannel = {},
                onSaveGithubToken = {},
                onClearGithubToken = {},
                onCheckForUpdates = {},
                onDownloadUpdate = {},
                onInstallUpdate = {},
                onClearUpdateMessage = {}
            )
            "logs" -> SettingsLogsContent(
                state = settingsState,
                entries = logEntries,
                onBack = {},
                onRequestExport = {},
                onClearLogs = {},
                onClearMessage = {}
            )
            "backup" -> SettingsDataBackupScreen(
                state = settingsState,
                onBack = {},
                onReparseStored = {},
                onExportPassphraseChange = {},
                onImportPassphraseChange = {},
                onRequestExport = {},
                onRequestImport = {},
                onConfirmPendingImport = {},
                onCancelPendingImport = {},
                onClearBackupMessage = {},
                onRequestFxRateCorrection = {},
                onConfirmFxRateCorrection = {},
                onDismissFxRateCorrection = {},
                page = BackupPage.Hub
            )
            "exportBackup" -> SettingsDataBackupScreen(
                state = settingsState,
                onBack = {},
                onReparseStored = {},
                onExportPassphraseChange = {},
                onImportPassphraseChange = {},
                onRequestExport = {},
                onRequestImport = {},
                onConfirmPendingImport = {},
                onCancelPendingImport = {},
                onClearBackupMessage = {},
                onRequestFxRateCorrection = {},
                onConfirmFxRateCorrection = {},
                onDismissFxRateCorrection = {},
                page = BackupPage.Export
            )
            "restoreBackup" -> SettingsDataBackupScreen(
                state = settingsState,
                onBack = {},
                onReparseStored = {},
                onExportPassphraseChange = {},
                onImportPassphraseChange = {},
                onRequestExport = {},
                onRequestImport = {},
                onConfirmPendingImport = {},
                onCancelPendingImport = {},
                onClearBackupMessage = {},
                onRequestFxRateCorrection = {},
                onConfirmFxRateCorrection = {},
                onDismissFxRateCorrection = {},
                page = BackupPage.Restore
            )
            "dataTools" -> SettingsDataBackupScreen(
                state = settingsState,
                onBack = {},
                onReparseStored = {},
                onExportPassphraseChange = {},
                onImportPassphraseChange = {},
                onRequestExport = {},
                onRequestImport = {},
                onConfirmPendingImport = {},
                onCancelPendingImport = {},
                onClearBackupMessage = {},
                onRequestFxRateCorrection = {},
                onConfirmFxRateCorrection = {},
                onDismissFxRateCorrection = {},
                page = BackupPage.Tools
            )
            "notifications" -> NotificationCenterScreen(
                state = notificationState,
                onBack = {},
                onOpenNotification = {}
            )
            "flowIn" -> DashboardFlowDetailScreen(
                mode = DashboardFlowDetailMode.Income,
                summary = financial.currentAccount!!,
                state = financial,
                transactions = financial.allTransactions,
                grouping = financial.flowDetailGrouping ?: CurrentAccountFlowDetailGrouping.empty(),
                onBack = {},
                onOpenTransaction = {}
            )
            "flowOut" -> DashboardFlowDetailScreen(
                mode = DashboardFlowDetailMode.Expense,
                summary = financial.currentAccount!!,
                state = financial,
                transactions = financial.allTransactions,
                grouping = financial.flowDetailGrouping ?: CurrentAccountFlowDetailGrouping.empty(),
                onBack = {},
                onOpenTransaction = {}
            )
            "commitmentEditor" -> SettingsCommitmentEditorScreen(
                commitment = settingsState.activeCommitments.first(),
                saving = false,
                onBack = {},
                onSave = {},
                onToggleActive = {},
                onDelete = {}
            )
            "statement" -> StatementReconciliationScreen(
                state = StatementReconciliationUiState(),
                onBack = {},
                onPickFile = {}
            )
            "onboarding" -> OnboardingScreen(
                state = OnboardingUiState(),
                onStart = {},
                onRequestPermissions = {},
                onOpenAppSettings = {},
                onRequestRestoreBackup = {},
                onRestorePassphraseChange = {},
                onConfirmLegacyRestore = {},
                onCancelLegacyRestore = {},
                onClearBackupError = {},
                onSelectDateOption = {},
                onSelectCustomDate = {},
                onStartImport = {},
                onSetAccountOwned = {},
                onSetAccountExternal = {},
                onSetCardOwned = {},
                onSetCardExternal = {},
                onFinalize = {},
                onEnterApp = {}
            )
            "bankRegistry" -> SettingsBankScreen(
                bank = Bank.BANK_ALJAZIRA,
                summary = settingsState.bankSummaries.first(),
                onBack = {},
                onOpenAccounts = {},
                onOpenCards = {},
                onOpenLoans = {}
            )
            "myAccounts" -> SettingsBankAccountsScreen(
                bank = Bank.BANK_ALJAZIRA,
                state = settingsState,
                onBack = {},
                onConfirmOwned = {},
                onMarkExternal = {},
                onRequestStopTracking = {},
                onResumeTracking = {},
                onDismissStopConfirm = {},
                onConfirmStopTracking = {},
                deleteActions = RegistryDeleteActions({}, {}, {}, {}),
                onRenameAccount = {},
                onDismissRenameAccount = {},
                onSaveAccountName = {},
                onPickAccountType = {},
                onDismissAccountType = {},
                onSelectAccountType = {}
            )
            "myCards" -> SettingsBankCardsScreen(
                bank = Bank.BANK_ALJAZIRA,
                state = settingsState,
                onBack = {},
                onConfirmOwned = {},
                onMarkExternal = {},
                onRequestStopTracking = {},
                onResumeTracking = {},
                onDismissStopConfirm = {},
                onConfirmStopTracking = {},
                deleteActions = RegistryDeleteActions({}, {}, {}, {}),
                onRenameCard = {},
                onDismissRenameCard = {},
                onSaveCardName = {},
                onPickCardNetwork = {},
                onDismissCardNetwork = {},
            )
            "myLoans" -> SettingsBankLoansScreen(
                bank = Bank.BANK_ALJAZIRA,
                state = settingsState,
                onBack = {},
                onConfirmOwned = {},
                onMarkExternal = {},
                onRequestStopTracking = {},
                onResumeTracking = {},
                onDismissStopConfirm = {},
                onConfirmStopTracking = {},
                deleteActions = RegistryDeleteActions({}, {}, {}, {}),
            )
            "myCommitments" -> SettingsCommitmentsScreen(
                state = settingsState,
                onBack = {},
                onOpenCommitment = {}
            )
        }
    }

    private val settingsPages = setOf("settings", "preferences", "about", "updates", "logs", "backup", "exportBackup",
        "restoreBackup", "dataTools", "commitmentEditor", "statement", "bankRegistry", "myAccounts", "myCards", "myLoans", "myCommitments")
}
