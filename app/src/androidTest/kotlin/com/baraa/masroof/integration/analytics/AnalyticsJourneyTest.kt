package com.baraa.masroof.integration.analytics

import com.baraa.masroof.presentation.navigation.AnalyticsNavigation

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.R
import com.baraa.masroof.testsupport.*
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.data.room.entity.*
import com.baraa.masroof.presentation.analytics.*
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AnalyticsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val host = instrument.targetContext
    private val fixtureToday = LocalDate.of(2026, 10, 10)
    private val fixtureClock = Clock.fixed(fixtureToday.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
    @Test fun publishedComparisonShowsItsActualLateOriginAndContextualHelpInBothLanguages() {
        val start=LocalDate.of(2026,10,1).toEpochDay()
        val origin=start+10
        val pair=com.baraa.masroof.data.analytics.ForecastComparisonRow("late",start,start+30,
            LocalDate.ofEpochDay(origin).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            "a".repeat(64),com.baraa.masroof.analytics.AnalyticsVersion.MODEL,
            ForecastPerformancePolicy.VERSION,20000,21000,originDay=origin)
        var language by mutableStateOf("ar")
        var dark by mutableStateOf(false)
        compose.setContent {
            val config=Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag(language));fontScale=1.3f }
            CompositionLocalProvider(LocalContext provides host.createConfigurationContext(config),LocalConfiguration provides config) {
                MasroofTheme(darkTheme=dark) { MasroofScreenBackground {
                    Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                        ForecastPerformanceDetails(AnalysisUiState(forecastPerformance=ForecastPerformancePolicy.evaluate(listOf(pair)),forecastComparisons=listOf(pair)))
                    }
                } }
            }
        }
        for(locale in listOf("ar","en")) for(isDark in listOf(false,true)) {
            compose.runOnUiThread { language=locale;dark=isDark }
            val context=host.createConfigurationContext(Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            val date=java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy",Locale.forLanguageTag(locale)).format(LocalDate.ofEpochDay(origin))
            val label=com.baraa.masroof.application.locale.AppDigits.display(context.getString(R.string.learning_forecast_origin,date,20))
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.learning_forecast_hint,locale)).assertDoesNotExist()
            val about=context.getString(R.string.guidance_about,context.getString(R.string.learning_forecast_title))
            compose.onNodeWithContentDescription(about).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.learning_forecast_hint,locale)).assertIsDisplayed()
            screen("$locale-${if(isDark) "dark" else "light"}-late-origin-help")
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
        }
    }
    @Test fun forecastsKeepLedgerParityAcrossCorrectionsAndReopenAndUiDraftsOnlySaveExplicitly() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
        runBlocking { TestRuntime.reset(host) }
        val container = TestRuntime.container(host)
        val workflow = AnalyticsWorkflow(container, TestStorageContext(host), fixtureClock)
        var showing by mutableStateOf(true)
        try {
            runBlocking { container.maintenance.awaitStartupMaintenance(); container.maintenance.awaitPostStartupBackgroundWorkForTesting(); container.stopAnalytics(); seed(container, fixtureToday); workflow.start() }
            runBlocking { workflow.refresh() }
            awaitAnalysis(workflow) { it.snapshot?.status == "READY" }
            runBlocking {
                val overview = container.dashboard.dashboardService.loadOverview(com.baraa.masroof.domain.period.FinancialPeriodPolicy.periodContaining(fixtureToday))
                val expected = overview.spendingTrends!!.purchases.totalSpending.amount.movePointRight(2).longValueExact()
                assertEquals("Purchase scope exactly matches the current graph", expected, workflow.state.value.snapshot!!.actual)
                val snapshot = workflow.state.value.snapshot!!
                assertEquals(snapshot.actual, snapshot.insights!!.components.sumOf { it.amount })
                assertNotNull(snapshot.insights.comparison)
                assertTrue(snapshot.insights.merchants.isNotEmpty())
                assertEquals(com.baraa.masroof.analytics.AnalyticsVersion.EVALUATION, snapshot.evaluationVersion)
                for (past in workflow.state.value.purchasePeriods.filter { it.actual != null }) {
                    val period = com.baraa.masroof.domain.period.FinancialPeriod(LocalDate.ofEpochDay(past.start), LocalDate.ofEpochDay(past.end))
                    val actual = container.dashboard.dashboardService.loadOverview(period).spendingTrends!!.purchases.totalSpending.amount.movePointRight(2).longValueExact()
                    assertEquals("Historical periods use the existing purchases scope", actual, past.actual)
                }
                assertEquals(workflow.state.value.snapshot!!.total, workflow.state.value.snapshot!!.chart().sumOf { it.amount }.movePointRight(2).longValueExact())
                val rows = container.storage.financialTransactionRepository.listAll()
                val changed = rows.first { it.id == "cash" }
                val priorHash = workflow.state.value.snapshot!!.sourceHash
                container.storage.financialTransactionRepository.update(changed.copy(amount = com.baraa.masroof.core.money.Money.of(java.math.BigDecimal("70.00"), changed.amount.currency)))
                workflow.request()
                withTimeout(90_000) { while (workflow.state.value.updating || workflow.state.value.snapshot?.sourceHash == priorHash) delay(100) }
                assertEquals(expected + 2000, workflow.state.value.snapshot!!.actual)
                assertEquals(expected + 2000, workflow.state.value.snapshot!!.insights!!.components.sumOf { it.amount })
                val afterCorrection = workflow.state.value.snapshot!!.sourceHash
                container.storage.database.openHelper.writableDatabase.execSQL("DELETE FROM financial_transaction WHERE id='cash'")
                workflow.request()
                withTimeout(90_000) { while (workflow.state.value.updating || workflow.state.value.snapshot?.sourceHash == afterCorrection) delay(100) }
                assertEquals(expected - 5000, workflow.state.value.snapshot!!.actual)
                assertEquals(expected - 5000, workflow.state.value.snapshot!!.insights!!.components.sumOf { it.amount })
                assertEquals(rows.size - 1, container.storage.financialTransactionRepository.listAll().size)
            }
            var locale by mutableStateOf("ar")
            var dark by mutableStateOf(false)
            var open by mutableStateOf(false)
            compose.setContent { if (showing) {
                val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
                val config = Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)); fontScale = if (locale == "en") 1.3f else 1f }
                val context = host.createConfigurationContext(config)
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config, LocalAnalyticsWorkflow provides workflow,
                    LocalActivityResultRegistryOwner provides registryOwner) {
                    MasroofTheme(darkTheme = dark) { MasroofScreenBackground {
                        if (open) AnalyticsRoute(onBack = { open = false }, onOpenTransaction = {}) else ForecastCard { open = true }
                    } }
                }
            } }
            for (language in listOf("ar", "en")) for (isDark in listOf(false, true)) {
                compose.runOnUiThread { locale = language; dark = isDark; open = false }
                compose.onNodeWithTag("lab-forecast-entry").assertIsDisplayed().performClick()
                compose.onNodeWithText(text(R.string.lab_analysis_title, language)).assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-purchases")
                compose.onNodeWithText(text(R.string.lab_analysis_need_data, language)).assertDoesNotExist()
                compose.onNodeWithText(text(R.string.analysis_forecasts_tab, language)).performClick()
                val about = text(R.string.guidance_about, language).format(text(R.string.lab_analysis_initial, language))
                compose.onNodeWithContentDescription(about).performClick()
                compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
                compose.onNodeWithText(text(R.string.lab_analysis_forecast_hint, language)).assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-forecast-help")
                compose.waitForIdle(); instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle()
                compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
                compose.onNodeWithText(text(R.string.lab_analysis_title, language)).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.lab_analysis_spending_tab, language)).performClick()
                compose.onNodeWithTag("analysis-spending-explanation").performScrollTo().assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-spending-explanation")
                compose.onNodeWithText(text(R.string.analysis_merchants, language)).performScrollTo().assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-merchant-bars")
                compose.onNodeWithText(text(R.string.analysis_change_title, language)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(text(R.string.analysis_decrease_axis, language)).performScrollTo().assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-change-bars")
                compose.onNodeWithTag("analysis-quality-entry").assertDoesNotExist()
                compose.onNodeWithText(text(R.string.analysis_forecasts_tab, language)).performClick()
                compose.onNodeWithTag("analysis-quality-entry").performScrollTo().performClick()
                compose.onNodeWithText(text(R.string.lab_analysis_show_data, language)).performScrollTo().performClick()
                compose.onNodeWithText(text(R.string.lab_analysis_scope_title, language)).performScrollTo().assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-data-details")
                compose.onNodeWithTag("learning-insights").assertDoesNotExist()
                compose.onNodeWithContentDescription(text(R.string.lab_analysis_back, language)).performClick()
                compose.onNodeWithText(text(R.string.analysis_forecasts_tab, language)).performClick()
                compose.onNodeWithTag("analysis-daily-bars").performScrollTo().assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-daily-bars")
                compose.onNodeWithTag("analysis-performance-entry").performScrollTo().performClick()
                compose.onNodeWithTag("forecast-performance").performScrollTo().assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-forecast-performance")
                compose.onNodeWithContentDescription(text(R.string.lab_analysis_back, language)).performClick()
                compose.onNodeWithTag("lab-analysis-settings").performClick()
                screen("$language-${if (isDark) "dark" else "light"}-settings")
                compose.onNodeWithText(text(R.string.lab_analysis_cancel, language)).performScrollTo().performClick()
                compose.onNodeWithText(text(R.string.lab_analysis_commitments, language), useUnmergedTree = true).performClick()
                screen("$language-${if (isDark) "dark" else "light"}-commitments")
                compose.onNodeWithText(text(R.string.lab_analysis_spending_tab, language), useUnmergedTree = true).performClick()
                compose.onNodeWithTag("lab-history-3").performScrollTo().performClick()
                compose.onNodeWithText(text(R.string.lab_analysis_history_purchases, language)).performScrollTo().assertIsDisplayed()
                screen("$language-${if (isDark) "dark" else "light"}-history")
                compose.onNodeWithTag("lab-history-0").performScrollTo().performClick()
            }
            compose.runOnUiThread { locale = "en"; dark = false }
            compose.onNodeWithTag("lab-analysis-settings").performClick()
            val budget = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lab-analysis-budget")))
            budget.performTextReplacement("1200")
            compose.onNodeWithText(text(R.string.lab_analysis_cancel, "en")).performScrollTo().performClick()
            assertNull(workflow.state.value.preferences.budget)
            compose.onNodeWithTag("lab-analysis-settings").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lab-analysis-budget"))).performTextReplacement("1200")
            compose.onNodeWithText(text(R.string.lab_analysis_save, "en")).performScrollTo().performClick()
            awaitAnalysis(workflow) { it.preferences.budget == 120000L }
            val candidate = workflow.state.value.snapshot!!.candidates.first()
            val originalCommitments = runBlocking { container.storage.commitmentRepository.listAll() }
            compose.onNodeWithText(text(R.string.lab_analysis_commitments, "en"), useUnmergedTree = true).performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_review_candidate, "en")).performScrollTo().performClick()
            screen("en-light-commitment-editor")
            compose.onNodeWithContentDescription(text(R.string.lab_analysis_back, "en")).performClick()
            assertEquals(originalCommitments, runBlocking { container.storage.commitmentRepository.listAll() })
            compose.onNodeWithText(text(R.string.lab_analysis_review_candidate, "en")).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_commitment_save, "en")).performScrollTo().performClick()
            compose.waitUntil(30_000) { runBlocking { container.storage.commitmentRepository.listAll().size == originalCommitments.size + 1 } }
            // The Save click's coroutine runs on the v2 test dispatcher; let it finish and release the workflow lock.
            compose.waitForIdle()
            runBlocking {
                // Repeating a Save after a crash cannot insert a second financial commitment.
                workflow.acceptCandidate(candidate.key, workflow.editorDraft(candidate))
                assertEquals(originalCommitments.size + 1, container.storage.commitmentRepository.listAll().size)
                val archive = AnalyticsBackup.capture(TestStorageContext(host))!!
                AnalyticsBackup.validate(archive)
                val ledger = container.storage.financialTransactionRepository.listAll()
                assertEquals(ledger.size, container.storage.database.financialTransactionDao().listAll().size)
                container.storage.database.openHelper.readableDatabase.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(com.baraa.masroof.data.room.MasroofDatabase.VERSION, it.getInt(0)) }
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, host.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
                    workflow.updatePreferences(workflow.state.value.preferences.copy(budget = 1, phone = true))
                    workflow.refresh()
                    withTimeout(30_000) { while (workflow.state.value.updating || workflow.state.value.preferences.budget != 1L) delay(100) }
                    assertTrue(workflow.state.value.alerts.any { it.kind == "ACTUAL" })
                    assertTrue(host.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.tag == "lab-analysis" })
                    val alertId = workflow.state.value.alerts.first { it.kind == "ACTUAL" }.id
                    compose.runOnUiThread { AnalyticsNavigation.open(alertId) }
                    compose.waitForIdle()
                    compose.onNodeWithText(text(R.string.lab_analysis_spending_tab, "en")).assertIsSelected()
                    workflow.updatePreferences(workflow.state.value.preferences.copy(budget = 120000, phone = false))
                    workflow.refresh()
                    withTimeout(30_000) { while (workflow.state.value.updating || workflow.state.value.preferences.budget != 120000L) delay(100) }
                }
            }
            runBlocking {
                container.storage.database.financialTransactionDao().insertTransactionIfAbsent(FinancialTransactionEntity(
                    "large-refund", "REFUND", "5000.00", "SAR", fixtureClock.millis(),
                    "card:BANK_ALJAZIRA:1234", null, "استرداد اختبار", null, null))
                workflow.request(); workflow.refresh()
            }
            awaitAnalysis(workflow) { (it.snapshot?.actual ?: 0) < 0 }
            screen("en-light-negative-net")
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            val beforeReopen = runBlocking { container.storage.financialTransactionRepository.listAll() }
            val period = com.baraa.masroof.domain.period.FinancialPeriodPolicy.periodContaining(fixtureToday)
            val detached = runBlocking { FinancialAnalysisReader.capture(container, fixtureToday.minusYears(1).atStartOfDay(ZoneId.systemDefault()).toInstant(), period.endDateExclusive.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()) }
            workflow.close()
            TestRuntime.close()
            runBlocking { assertTrue(detached.service.loadOverview(period).spendingTrends!!.purchases.points.isNotEmpty()) }
            val reopened = TestRuntime.container(host)
            runBlocking {
                reopened.maintenance.awaitStartupMaintenance(); reopened.maintenance.awaitPostStartupBackgroundWorkForTesting()
                assertEquals(beforeReopen, reopened.storage.financialTransactionRepository.listAll())
                reopened.analytics.refresh()
                assertEquals(120000L, reopened.analytics.state.value.preferences.budget)
                assertTrue(reopened.analytics.state.value.history.isNotEmpty())
            }
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            workflow.close()
            runBlocking { TestRuntime.reset(host) }
        }
    }
    @Test fun usabilityShowsActualRowsClearSettingsAndAlertsInTheExistingCenter() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
        runBlocking { TestRuntime.reset(host) }
        val c = TestRuntime.container(host)
        var showing by mutableStateOf(true)
        var center by mutableStateOf(false)
        var language by mutableStateOf("en")
        var dark by mutableStateOf(false)
        var openedTransaction: String? = null
        try {
            runBlocking { c.maintenance.awaitStartupMaintenance(); c.maintenance.awaitPostStartupBackgroundWorkForTesting(); seed(c); c.analytics.refresh() }
            val workflow = c.analytics
            awaitAnalysis(workflow) { it.snapshot != null }
            compose.setContent { if (showing) {
                val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
                val config = Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = if (language == "en") 1.3f else 1f }
                CompositionLocalProvider(LocalContext provides host.createConfigurationContext(config), LocalConfiguration provides config, LocalAnalyticsWorkflow provides workflow, LocalActivityResultRegistryOwner provides registryOwner) {
                    MasroofTheme(darkTheme = dark) { MasroofScreenBackground {
                        if (center) {
                            val target by AnalyticsNavigation.target.collectAsState()
                            LaunchedEffect(target) { if (target != null) center = false }
                            com.baraa.masroof.presentation.notification.NotificationCenterScreen(
                                com.baraa.masroof.presentation.notification.NotificationCenterUiState(loading = false,
                                    items = listOf(com.baraa.masroof.application.notification.NotificationItem("review_required",
                                        com.baraa.masroof.application.notification.NotificationType.REVIEW_REQUIRED, count = 2,
                                        action = com.baraa.masroof.application.notification.NotificationAction.OPEN_REVIEW)), unreadCount = 1), {}, {})
                        } else AnalyticsRoute({}, { openedTransaction = it })
                    } }
                }
            } }
            compose.onNodeWithText(text(R.string.analysis_forecasts_tab, "en")).performClick()
            compose.onNodeWithTag("analysis-quality-entry").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_show_data, "en")).performScrollTo().performClick()
            val reason = text(R.string.lab_analysis_excluded_scope, "en")
            compose.onNode(hasText(reason, substring = true) and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("lab-evidence-settlement").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lab-evidence-settlement").performScrollTo().assertIsDisplayed().performClick()
            assertEquals("settlement", openedTransaction)
            screen("en-light-quality-transactions")
            compose.onNodeWithContentDescription(text(R.string.lab_analysis_back, "en")).performClick()
            compose.onNodeWithTag("lab-analysis-settings").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lab-analysis-budget"))).performTextReplacement("invalid")
            compose.onNodeWithText(text(R.string.lab_analysis_save, "en")).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_budget_invalid, "en")).performScrollTo().assertIsDisplayed()
            assertNull(workflow.state.value.preferences.budget)
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lab-analysis-budget"))).performScrollTo().performTextReplacement("١٢٠٠٫٥٠")
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lab-analysis-reminder"))).performScrollTo().performTextReplacement("٣")
            compose.onNodeWithText(text(R.string.lab_analysis_save, "en")).performScrollTo().performClick()
            awaitAnalysis(workflow) { it.preferences.budget == 120050L }
            runBlocking { workflow.updatePreferences(workflow.state.value.preferences.copy(budget = 1)); workflow.refresh() }
            awaitAnalysis(workflow) { it.alerts.any { alert -> alert.kind == "ACTUAL" } }
            val alert = workflow.state.value.alerts.first { it.kind == "ACTUAL" }
            compose.runOnUiThread { center = true }
            for (locale in listOf("ar", "en")) for (isDark in listOf(false, true)) {
                compose.runOnUiThread { language = locale; dark = isDark }
                compose.onNodeWithTag("lab-alert-${alert.id}").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(text(R.string.settings_hub_review_title, locale)).assertExists()
                screen("$locale-${if (isDark) "dark" else "light"}-unified-alerts")
            }
            compose.runOnUiThread { language = "en"; dark = false }
            compose.onNodeWithTag("lab-alert-${alert.id}").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_spending_tab, "en")).assertIsSelected()
            awaitAnalysis(workflow) { it.alerts.first { row -> row.id == alert.id }.read }
            compose.runOnUiThread { center = true }
            compose.onNode(hasContentDescription(text(R.string.lab_analysis_actions, "en")) and hasAnyAncestor(hasTestTag("lab-alert-container-${alert.id}"))).performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_snooze, "en")).performClick()
            awaitAnalysis(workflow) { it.alerts.first { row -> row.id == alert.id }.snoozedUntil > 0 }
            compose.onNodeWithTag("lab-alert-${alert.id}").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.lab_analysis_settings, "en")).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_budget_section, "en")).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            runBlocking { TestRuntime.reset(host) }
        }
    }
    @Test fun chartRowsOpenScopedTransactionsAndBackRestoresTheirSource() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
        runBlocking { TestRuntime.reset(host) }
        val c = TestRuntime.container(host)
        val workflow = AnalyticsWorkflow(c, TestStorageContext(host), fixtureClock)
        var showing by mutableStateOf(true)
        var opened: String? = null
        try {
            runBlocking { c.maintenance.awaitStartupMaintenance(); c.maintenance.awaitPostStartupBackgroundWorkForTesting(); c.stopAnalytics(); seed(c, fixtureToday); workflow.start(); workflow.refresh() }
            awaitAnalysis(workflow) { it.snapshot != null }
            val before = runBlocking { c.storage.financialTransactionRepository.listAll() }
            val snapshot = requireNotNull(workflow.state.value.snapshot)
            val insights = requireNotNull(snapshot.insights)
            val config = Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag("en")); fontScale = 1.3f }
            compose.setContent { if (showing) {
                CompositionLocalProvider(LocalContext provides host.createConfigurationContext(config), LocalConfiguration provides config,
                    LocalAnalyticsWorkflow provides workflow) {
                    MasroofTheme { MasroofScreenBackground { AnalyticsRoute({}, { opened = it }) } }
                }
            } }
            fun assertEvidence(vararg groups: List<String>) {
                val ids = groups.flatMap { it }
                compose.onNodeWithTag("analysis-transactions").assertIsDisplayed()
                groups.filter { it.isNotEmpty() }.forEach { group ->
                    compose.waitUntil(30_000) {
                        compose.onAllNodesWithTag("lab-evidence-${group.first()}").fetchSemanticsNodes().isNotEmpty()
                    }
                }
                while (compose.onAllNodesWithText(text(R.string.lab_analysis_more, "en")).fetchSemanticsNodes().isNotEmpty()) {
                    val loaded = ids.count { compose.onAllNodesWithTag("lab-evidence-$it").fetchSemanticsNodes().isNotEmpty() }
                    compose.onAllNodesWithText(text(R.string.lab_analysis_more, "en"))[0].performScrollTo().performClick()
                    compose.waitUntil(30_000) {
                        ids.count { compose.onAllNodesWithTag("lab-evidence-$it").fetchSemanticsNodes().isNotEmpty() } > loaded
                    }
                }
                ids.forEach { compose.onNodeWithTag("lab-evidence-$it").assertExists() }
            }
            fun back() { compose.onNodeWithContentDescription(text(R.string.lab_analysis_back, "en")).performClick() }
            compose.onNodeWithTag("analysis-quality-entry").assertDoesNotExist()
            compose.onNodeWithTag("analysis-expand-insights").assertDoesNotExist()
            compose.onNodeWithText("Show comparison transactions").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.lab_analysis_comparison_current, "en")).assert(hasClickAction().not())
            compose.onNodeWithText(text(R.string.lab_analysis_comparison_previous, "en")).assert(hasClickAction().not())
            insights.components.forEach { group ->
                compose.onNodeWithTag("analysis-component-${group.key}").performScrollTo().performClick()
                assertEvidence(group.ids)
                if (group.key == "TRANSFERS") {
                    compose.onNodeWithTag("lab-evidence-external").performScrollTo().performClick()
                    assertEquals("external", opened)
                    compose.onNodeWithTag("lab-evidence-self").assertDoesNotExist()
                    compose.onNodeWithTag("lab-evidence-cash").assertDoesNotExist()
                    screen("en-light-external-transfer-transactions")
                }
                back()
                compose.onNodeWithTag("analysis-component-${group.key}").assertIsDisplayed()
                compose.onNodeWithTag("lab-evidence-external").assertDoesNotExist()
            }
            val merchant = insights.merchants.first()
            compose.onNodeWithTag("analysis-merchant-${merchant.key}").performScrollTo().performClick()
            assertEvidence(merchant.ids)
            compose.onNodeWithTag("lab-evidence-external").assertDoesNotExist()
            back()
            compose.onNodeWithText(text(R.string.dashboard_merchants_view_all, "en")).performScrollTo().performClick()
            compose.onNodeWithTag("analysis-merchant-${merchant.key}").performScrollTo().performClick()
            assertEvidence(merchant.ids)
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.onNodeWithTag("analysis-merchant-${merchant.key}").assertIsDisplayed()
            back()
            val comparison = requireNotNull(insights.comparison)
            for (change in comparison.changes) {
                compose.onNodeWithTag("analysis-change-${change.group.key}").performScrollTo().performClick()
                assertEvidence(change.group.ids, change.previousIds)
                compose.onNodeWithText(text(R.string.lab_analysis_comparison_current, "en")).assertExists()
                compose.onNodeWithText(text(R.string.lab_analysis_comparison_previous, "en")).assertExists()
                compose.onNodeWithTag("lab-evidence-today-spend").assertDoesNotExist()
                screen("en-light-change-${change.group.key.replace(':', '-')}")
                back()
            }
            compose.onNodeWithText(text(R.string.analysis_forecasts_tab, "en")).performClick()
            compose.onNodeWithTag("analysis-quality-entry").performScrollTo().assertIsDisplayed()
            assertEquals(before, runBlocking { c.storage.financialTransactionRepository.listAll() })
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            workflow.close()
            runBlocking { TestRuntime.reset(host) }
        }
    }
    @Test fun qualityActionsAndSpendingEvidenceAreReachableWithoutChangingFinancialData() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
        runBlocking { TestRuntime.reset(host) }
        val c = TestRuntime.container(host)
        var showing by mutableStateOf(true)
        var insufficient by mutableStateOf(true)
        var selection by mutableStateOf<String?>(null)
        var opened: String? = null
        var review = false; var permission = false; var rescan = false
        try {
            runBlocking { c.maintenance.awaitStartupMaintenance(); c.maintenance.awaitPostStartupBackgroundWorkForTesting(); seed(c); c.analytics.refresh() }
            val workflow = c.analytics
            awaitAnalysis(workflow) { it.snapshot != null }
            val original = runBlocking { c.storage.financialTransactionRepository.listAll() }
            val snapshot = requireNotNull(workflow.state.value.snapshot).let { it.copy(quality = it.quality.copy(
                pendingReviews = 1, blocked = true, reasons = listOf("SMS_PERMISSION", "PROCESSING_RETRY"))) }
            val config = Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag("en")) }
            compose.setContent { if (showing) {
                CompositionLocalProvider(LocalContext provides host.createConfigurationContext(config), LocalConfiguration provides config) {
                    MasroofTheme { if (selection != null) AnalysisTransactionsScreen(snapshot, selection!!, workflow, { selection = null }, { opened = it })
                    else Column(Modifier.verticalScroll(rememberScrollState())) {
                        AnalysisSpendingExplanation(snapshot, { selection = it })
                        val displayed = if (insufficient) snapshot.copy(status = "INSUFFICIENT_DATA", method = "NONE",
                            quality = snapshot.quality.copy(blocked = false, pendingReviews = 0, reasons = emptyList())) else snapshot
                        AnalysisDataQuality(displayed, workflow, { opened = it }, { review = true }, { permission = true }, { rescan = true })
                    } }
                }
            } }
            compose.onNodeWithText(text(R.string.lab_analysis_need_data, "en")).assertDoesNotExist()
            val aboutInsufficient = text(R.string.guidance_about, "en").format(text(R.string.lab_analysis_quality_insufficient, "en"))
            compose.onNodeWithContentDescription(aboutInsufficient).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_need_data, "en")).assertIsDisplayed()
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.lab_analysis_need_data, "en")).assertDoesNotExist()
            compose.runOnUiThread { insufficient = false }
            compose.onNodeWithTag("analysis-component-PURCHASES").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("lab-evidence-today-spend").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lab-evidence-today-spend").performScrollTo().performClick()
            assertEquals("today-spend", opened)
            compose.onNodeWithContentDescription(text(R.string.lab_analysis_back, "en")).performClick()
            compose.onNodeWithTag("analysis-open-review").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_quality_permission_action, "en")).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_quality_retry_action, "en")).performScrollTo().performClick()
            assertTrue(review); assertTrue(permission); assertTrue(rescan)
            assertEquals(original, runBlocking { c.storage.financialTransactionRepository.listAll() })
            screen("en-light-quality-actions")
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            runBlocking { TestRuntime.reset(host) }
        }
    }
    @Test fun learningIsReachableFromSettingsAndSharedChartsAreInTheDesignCatalog() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
        runBlocking { TestRuntime.reset(host) }
        val c = TestRuntime.container(host)
        var mode by mutableIntStateOf(0)
        var showing by mutableStateOf(true)
        try {
            runBlocking { c.maintenance.awaitStartupMaintenance(); c.maintenance.awaitPostStartupBackgroundWorkForTesting(); seed(c); c.analytics.refresh() }
            awaitAnalysis(c.analytics) { true }
            val before = runBlocking { c.storage.financialTransactionRepository.listAll() }
            val config = Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag("en")); fontScale = 1.3f }
            compose.setContent { if (showing) {
                CompositionLocalProvider(LocalContext provides host.createConfigurationContext(config), LocalConfiguration provides config, LocalAnalyticsWorkflow provides c.analytics) {
                    MasroofTheme { MasroofScreenBackground {
                        when (mode) {
                            0 -> com.baraa.masroof.presentation.settings.SettingsHubScreen(
                                com.baraa.masroof.presentation.settings.SettingsUiState(loading = false),
                                {}, {}, {}, {}, {}, onOpenLearning = { mode = 1 })
                            1 -> MessageLearningSettingsScreen { mode = 0 }
                            else -> com.baraa.masroof.presentation.debug.DesignCatalogScreen { mode = 0 }
                        }
                    } }
                }
            } }
            compose.onNodeWithText(text(R.string.analysis_learning_settings, "en")).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_corrections_scope, "en")).assertDoesNotExist()
            val learningHelp = text(R.string.guidance_about, "en").format(text(R.string.analysis_learning_settings, "en"))
            compose.onNodeWithContentDescription(learningHelp).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_corrections_scope, "en")).assertIsDisplayed()
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.settings_corrections_scope, "en")).assertDoesNotExist()
            compose.onNodeWithTag("learning-insights").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("forecast-performance").assertDoesNotExist()
            screen("en-learning-settings-large-font")
            compose.runOnUiThread { mode = 2 }
            compose.onNodeWithText(text(R.string.analysis_shared_charts, "en")).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Published forecast").performScrollTo().assertIsDisplayed()
            screen("en-shared-analytics-catalog-large-font")
            assertEquals(before, runBlocking { c.storage.financialTransactionRepository.listAll() })
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            runBlocking { TestRuntime.reset(host) }
        }
    }

    @Test fun recurringCorrectionsTeachOnlyExplicitLabelsAndCanBeUndone() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
        runBlocking { TestRuntime.reset(host) }
        val c = TestRuntime.container(host)
        var showing by mutableStateOf(true)
        try {
            runBlocking { c.maintenance.awaitPostStartupBackgroundWorkForTesting(); seed(c) }
            val candidate = awaitAnalysis(c.analytics) { it.snapshot?.candidates?.isNotEmpty() == true }.snapshot!!.candidates.first()
            val before = runBlocking { c.storage.financialTransactionRepository.listAll() }
            compose.setContent { if (showing) {
                CompositionLocalProvider(LocalAnalyticsWorkflow provides c.analytics) {
                    MasroofTheme { MasroofScreenBackground { AnalyticsRoute({}, {}) } }
                }
            } }
            compose.onNodeWithTag("analysis-tab-2").performClick()
            compose.onNodeWithTag("recurring-not-${candidate.key}").performScrollTo().performClick()
            awaitAnalysis(c.analytics) { state -> state.recurringCorrections.any {
                it.seriesKey == candidate.seriesKey && it.kind == "NOT_RECURRING"
            } && state.snapshot!!.candidates.none { it.key == candidate.key } }
            compose.onNodeWithTag("recurring-undo-${candidate.seriesKey}").performScrollTo().performClick()
            awaitAnalysis(c.analytics) { state -> state.snapshot!!.candidates.any { it.key == candidate.key } &&
                state.recurringCorrections.none { it.seriesKey == candidate.seriesKey } }
            compose.onNodeWithTag("recurring-dismiss-${candidate.key}").performScrollTo().performClick()
            awaitAnalysis(c.analytics) { state -> state.recurringCorrections.any {
                it.seriesKey == candidate.seriesKey && it.kind == "DISMISSED"
            } }
            // Plan 5.2: each answer, the undo included, is one appended row of user_decision.
            assertEquals(listOf("NOT_RECURRING", "UNDONE", "DISMISSED"), runBlocking {
                c.storage.database.userDecisionDao().listRecurring().filter { it.subjectKey == candidate.seriesKey }.map { it.answer }
            })
            assertEquals(before, runBlocking { c.storage.financialTransactionRepository.listAll() })
            screen("recurring-explicit-feedback")
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            runBlocking { TestRuntime.reset(host) }
        }
    }

    @Test fun personalSpendingExplainsPriorEvidenceAndForecastMethodWithoutChangingFinancialData() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
        runBlocking { TestRuntime.reset(host) }
        val c = TestRuntime.container(host)
        var showing by mutableStateOf(true)
        var language by mutableStateOf("ar")
        var dark by mutableStateOf(false)
        var opened: String? = null
        try {
            runBlocking {
                c.maintenance.awaitStartupMaintenance(); c.maintenance.awaitPostStartupBackgroundWorkForTesting(); seed(c)
                val today = LocalDate.now()
                for (index in 0..6) c.storage.database.financialTransactionDao().insertTransactionIfAbsent(FinancialTransactionEntity(
                    "observed-$index", "EXPENSE", if (index == 0) "90.00" else "40.00", "SAR",
                    today.minusDays(index * 7L).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    "card:BANK_ALJAZIRA:1234", null, "Observation merchant", null, null))
                c.analytics.request(); c.analytics.refresh()
            }
            val workflow = c.analytics
            awaitAnalysis(workflow) { it.observations.any { row -> row.id == "observed-0" } }
            val observation = workflow.state.value.observations.first { it.id == "observed-0" }
            assertEquals((1..6).map { "observed-$it" }.reversed(), observation.referenceIds)
            val before = runBlocking { c.storage.financialTransactionRepository.listAll() }
            compose.setContent { if (showing) {
                val config = Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = 1.3f }
                CompositionLocalProvider(LocalContext provides host.createConfigurationContext(config), LocalConfiguration provides config,
                    LocalAnalyticsWorkflow provides workflow) {
                    MasroofTheme(darkTheme = dark) { MasroofScreenBackground { AnalyticsRoute({}, { opened = it }) } }
                }
            } }
            for (locale in listOf("ar", "en")) for (isDark in listOf(false, true)) {
                compose.runOnUiThread { language = locale; dark = isDark }
                compose.onNodeWithTag("analysis-tab-0").performClick()
                compose.onNodeWithText(text(R.string.lab_analysis_scope_short, locale), substring = true).assertDoesNotExist()
                compose.onNodeWithText(text(R.string.analysis_merchants_scope, locale)).assertDoesNotExist()
                val aboutMerchants = text(R.string.guidance_about, locale).format(text(R.string.analysis_merchants, locale))
                compose.onNodeWithContentDescription(aboutMerchants).performScrollTo().performClick()
                compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
                compose.onNodeWithText(text(R.string.analysis_merchants_scope, locale)).assertIsDisplayed()
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("guidance-popup").fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("spending-observation-chart-observed-0").fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("spending-observation-chart-observed-0").performScrollTo().assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithTag("spending-observation-current-observed-0", useUnmergedTree = true).assert(hasAnyDescendant(hasText(
                    com.baraa.masroof.application.locale.AppDigits.display(text(R.string.personal_spending_payment, locale)))))
                compose.onNodeWithTag("spending-observation-reference-observed-0", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.personal_spending_evidence, locale)).assertDoesNotExist()
                compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
                compose.onNodeWithTag("masroof-bottom-sheet").assertDoesNotExist()
                val aboutObservation = text(R.string.guidance_about, locale).format(text(R.string.personal_spending_attention, locale))
                compose.onNodeWithContentDescription(aboutObservation).performScrollTo().performClick()
                compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
                compose.onNodeWithTag("analysis-transactions").assertDoesNotExist()
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("guidance-popup").fetchSemanticsNodes().isEmpty() }
                screen("$locale-${if (isDark) "dark" else "light"}-personal-spending-chart")
                compose.onNodeWithTag("spending-observation-chart-observed-0").performScrollTo().performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("lab-evidence-observed-0").fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("lab-evidence-observed-0").performScrollTo().assertIsDisplayed().performClick()
                assertEquals("observed-0", opened)
                compose.onNodeWithTag("lab-evidence-observed-6").performScrollTo().assertIsDisplayed()
                screen("$locale-${if (isDark) "dark" else "light"}-personal-spending-evidence")
                compose.onNodeWithContentDescription(text(R.string.lab_analysis_back, locale)).performClick()
                compose.onNodeWithTag("analysis-tab-1").performClick()
                compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
                compose.onNodeWithText(text(R.string.personal_forecast_qualification, locale), substring = true).assertDoesNotExist()
                compose.onNodeWithTag("forecast-explanation-open").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(text(R.string.lab_analysis_remaining, locale)).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.lab_analysis_scope_short, locale), substring = true).assertDoesNotExist()
                screen("$locale-${if (isDark) "dark" else "light"}-forecast-summary")
                val aboutForecast = text(R.string.guidance_about, locale).format(text(R.string.personal_forecast_why, locale))
                compose.onNodeWithContentDescription(aboutForecast).performClick()
                compose.onNodeWithTag("masroof-bottom-sheet").assertDoesNotExist()
                compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
                compose.onNodeWithText(com.baraa.masroof.application.locale.AppDigits.display(
                    text(R.string.personal_forecast_qualification, locale)), substring = true).assertIsDisplayed()
                screen("$locale-${if (isDark) "dark" else "light"}-forecast-explanation")
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("guidance-popup").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
                compose.onNodeWithTag("analysis-daily-bars").performScrollTo().assertIsDisplayed()
            }
            assertEquals(before, runBlocking { c.storage.financialTransactionRepository.listAll() })
            runBlocking {
                val transaction = c.storage.financialTransactionRepository.getById("observed-0")!!
                c.storage.financialTransactionRepository.update(transaction.copy(amount = com.baraa.masroof.core.money.Money.of(
                    java.math.BigDecimal("40.00"), transaction.amount.currency)))
                workflow.request(); workflow.refresh()
            }
            awaitAnalysis(workflow) { it.observations.none { row -> row.id == "observed-0" } }
            compose.onNodeWithTag("analysis-tab-0").performClick()
            compose.onNodeWithTag("spending-observation-chart-observed-0").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            runBlocking { TestRuntime.reset(host) }
        }
    }

    @Test fun optionalMessageReviewShowsDistinctExamplesAndCancelDoesNotCreateDecisions() {
        runBlocking { TestRuntime.reset(host) }
        val c = TestRuntime.container(host)
        var showing by mutableStateOf(true)
        var opened: String? = null
        try {
            runBlocking {
                c.maintenance.awaitStartupMaintenance(); c.maintenance.awaitPostStartupBackgroundWorkForTesting()
                val loan = "سداد تمويل خاص\nمن: 3001\nالقسط: SAR 20\nالمبلغ المتبقي: SAR 300\nلـ: تمويل شخصي\nفي: 2026-10-06 09:00"
                listOf(loan, loan.replace("SAR 20", "SAR 21"), "صيغة اختبار جديدة\nمبلغ: SAR 20", "صيغة اختبار أخرى\nمبلغ: SAR 30").forEachIndexed { index, body ->
                    c.sms.processRawSmsUseCase.ingest(com.baraa.masroof.domain.model.RawSms("review-pattern-$index", "AlJazira", body,
                        Instant.now(), "review-pattern-$index", com.baraa.masroof.sms.hash.SmsBodyHasher.sha256Hex(body)))
                }
            }
            val examples = runBlocking { c.patterns.messageReviewWorkflow.load() }
            assertEquals(3, examples.size)
            val before = runBlocking { c.review.reviewWorkflowService.listRequiredReviews() }
            val financial = runBlocking { c.storage.financialTransactionRepository.listAll() }
            compose.setContent { if (showing) {
                CompositionLocalProvider(LocalAnalyticsWorkflow provides c.analytics) {
                    MasroofTheme { MasroofScreenBackground { MessageLearningSettingsScreen(onBack = {}, onOpenExample = { opened = it }) } }
                }
            } }
            fun openReviewSession() {
                // Analytics emissions reload examples outside Compose idling, including after Cancel.
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("masroof-bottom-sheet").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithTag("learning-review-session").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("learning-review-session").performScrollTo().performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("learning-example-${examples.first().reviewId}", useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
            }
            openReviewSession()
            // The modal can merge button semantics while its body reloads; target the actual tagged action.
            compose.onNodeWithTag("learning-example-${examples.first().reviewId}", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("sheet-cancel").performClick()
            assertNull(opened)
            assertEquals(before, runBlocking { c.review.reviewWorkflowService.listRequiredReviews() })
            assertEquals(financial, runBlocking { c.storage.financialTransactionRepository.listAll() })
            openReviewSession()
            compose.onNodeWithTag("learning-example-${examples.first().reviewId}", useUnmergedTree = true).assertHasClickAction().assertIsEnabled().performClick()
            assertEquals(examples.first().reviewId, opened)
            compose.onNodeWithTag("masroof-bottom-sheet").assertDoesNotExist()
            screen("optional-distinct-message-review")
        } finally {
            compose.runOnUiThread { showing = false }; compose.waitForIdle()
            runBlocking { TestRuntime.reset(host) }
        }
    }

    private suspend fun seed(c: AppContainer, today: LocalDate = LocalDate.now()) {
        c.storage.onboardingPreferencesRepository.setOnboardingStarted(true)
        c.storage.onboardingPreferencesRepository.setOnboardingCompleted(true)
        c.storage.onboardingPreferencesRepository.setHistoricalImportStartEpochMillis(today.minusDays(150).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        c.storage.onboardingPreferencesRepository.setHistoricalImportCompleted(true)
        c.storage.database.withTransaction {
            c.storage.database.cardRegistryDao().insertFacilityIfAbsent("test-facility", "BANK_ALJAZIRA", "1234")
            c.storage.database.cardRegistryDao().insertIfAbsent(CardRegistryEntity("test-card", "BANK_ALJAZIRA", "1234", "OWNED", cardType = "CREDIT", cardRole = "PRIMARY", creditFacilityId = "test-facility", firstSeenRawSmsId = null, lastSeenRawSmsId = null))
            fun entity(id: String, type: String, amount: String, date: LocalDate, merchant: String = "متجر الاختبار", currency: String = "SAR", rate: String? = null) =
                FinancialTransactionEntity(id, type, amount, currency, date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    "card:BANK_ALJAZIRA:1234", null, merchant, null, null, rate, if (rate != null) "SMS" else null)
            val dao = c.storage.database.financialTransactionDao()
            for (ago in 1..150) dao.insertTransactionIfAbsent(entity("daily-$ago", "EXPENSE", "${20 + today.minusDays(ago.toLong()).dayOfWeek.value * 5}.00", today.minusDays(ago.toLong())))
            for (month in 0..3) dao.insertTransactionIfAbsent(entity("utility-$month", "BILL_PAYMENT", "${if (month == 0) 150 else 100}.00", today.minusMonths(month.toLong()).minusDays(2), "شركة الخدمات"))
            // Keep today net-positive so budget alerts are exercised even on salary-period day one.
            listOf(entity("cash", "CASH_WITHDRAWAL", "50.00", today.minusDays(1)), entity("external", "EXTERNAL_TRANSFER_OUT", "40.00", today.minusDays(1)),
                entity("refund", "REFUND", "20.00", today.minusDays(1)), entity("settlement", "CREDIT_CARD_PAYMENT", "500.00", today.minusDays(1)),
                entity("today-spend", "EXPENSE", "50.00", today), entity("today-refund", "REFUND", "20.00", today),
                entity("loan", "LOAN_REPAYMENT", "100.00", today.minusDays(1)), entity("self", "SELF_TRANSFER", "1000.00", today.minusDays(1)),
                entity("fx", "EXPENSE", "6.00", today.minusDays(1), currency = "USD", rate = "3.75"))
                .forEach { dao.insertTransactionIfAbsent(it) }
        }
        c.analytics.request()
    }
    // Workflow completion is external to Compose; waiting must not pump its virtual frame clock.
    // compose.waitUntil keeps the v2 test dispatcher running, so work launched from a click can finish.
    private fun awaitAnalysis(workflow: AnalyticsWorkflow, condition: (AnalysisUiState) -> Boolean): AnalysisUiState {
        var matched: AnalysisUiState? = null
        compose.waitUntil(90_000) { workflow.state.value.takeIf { !it.updating && condition(it) }?.also { matched = it } != null }
        return matched!!
    }

    private fun text(id: Int, language: String): String = host.createConfigurationContext(Configuration(host.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }).getString(id)
    private fun screen(name: String) {
        compose.waitForIdle()
        val directory = File(host.getExternalFilesDir(null), "analytics-screens").apply { mkdirs() }
        instrument.waitForIdleSync()
        // Capture the composed display without drawing AndroidComposeView on the test thread.
        android.os.SystemClock.sleep(200)
        val bitmap = requireNotNull(instrument.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
