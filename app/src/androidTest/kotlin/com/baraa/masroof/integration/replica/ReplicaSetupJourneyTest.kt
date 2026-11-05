package com.baraa.masroof.integration.replica

import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.R
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.presentation.onboarding.*
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.review.*
import com.baraa.masroof.presentation.settings.*
import com.baraa.masroof.presentation.statement.*
import com.baraa.masroof.presentation.notification.*
import com.baraa.masroof.presentation.navigation.MasroofRoot
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.runtime.*
import com.baraa.masroof.presentation.analytics.LocalLabAnalyticsCardsEnabled

/** Real source UI + real ViewModels + scanner + Room; only the device inbox is a fixture. */
@RunWith(AndroidJUnit4::class)
class ReplicaSetupJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val host = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun setupProvidesFinishButtonThenDisplaysFinancialDataAndOriginalNavigation() {
        require(host.packageName=="com.baraa.masroof")
        shell("rm -rf /sdcard/Download/replica-journey")
        runBlocking { TestRuntime.reset(host) }
        val container = AppContainer(TestStorageContext(host),smsDataSourceOverride = ReferenceHistory.source(host))
        container.appLocaleRepository.setLanguageTag("en")
        container.runStartupMaintenance()
        runBlocking { container.awaitStartupMaintenance() }
        val store = ViewModelStore()
        lateinit var onboarding: OnboardingViewModel
        lateinit var dashboard: DashboardViewModel
        lateinit var review: ReviewViewModel
        lateinit var settings: SettingsViewModel
        lateinit var statement: StatementReconciliationViewModel
        lateinit var notifications: NotificationCenterViewModel
        var analyticsVisible by mutableStateOf(true)
        try {
            compose.runOnUiThread {
                onboarding = ViewModelProvider(store,onboardingViewModelFactory(container,container.onboardingPreferencesRepository){true})[OnboardingViewModel::class.java]
                dashboard = ViewModelProvider(store,dashboardViewModelFactory(container,{true}))[DashboardViewModel::class.java]
                review = ViewModelProvider(store,reviewViewModelFactory(container))[ReviewViewModel::class.java]
                settings = ViewModelProvider(store,settingsViewModelFactory(container,"test",{true}))[SettingsViewModel::class.java]
                statement = ViewModelProvider(store,statementReconciliationViewModelFactory(container.statementReconciliationWorkflow))[StatementReconciliationViewModel::class.java]
                notifications = ViewModelProvider(store,notificationCenterViewModelFactory(container,{true}))[NotificationCenterViewModel::class.java]
            }
            compose.setContent { CompositionLocalProvider(LocalLabAnalyticsCardsEnabled provides analyticsVisible) { MasroofTheme {
                MasroofRoot(onboarding,dashboard,review,settings,statement,notifications,
                    onRequestPermissions = {},onOpenAppSettings = {},onLocaleChanged = {},onRequestExport = {},onRequestImport = {},onRequestExportLogs = {},onRequestRestoreBackup = {})
            } } }
            click(R.string.reference_start_setup)
            compose.waitUntil(20_000) { onboarding.uiState.value.bankCatalog != null }
            compose.onNodeWithTag("import-bank-${com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA.id}").performScrollTo().performClick()
            click(R.string.teaching_next)
            click(R.string.onboarding_start_import)
            compose.waitUntil(30_000) { onboarding.uiState.value.step == OnboardingStep.OWNERSHIP }
            screenshot("01-discovered-ownership")
            val accounts = onboarding.uiState.value.accounts
            for ((index,candidate) in accounts.withIndex()) {
                selectOwnership(host.getString(R.string.onboarding_account_suffix,candidate.suffix),R.string.onboarding_is_mine_account)
                compose.waitUntil(20_000) { onboarding.uiState.value.accounts.first { it.bank==candidate.bank && it.suffix==candidate.suffix }.ownership == OwnershipStatus.OWNED }
            }
            val cards = onboarding.uiState.value.cards
            for ((index,candidate) in cards.withIndex()) {
                selectOwnership(host.getString(R.string.onboarding_card_suffix,candidate.suffix),R.string.onboarding_is_mine_card)
                compose.waitUntil(20_000) { onboarding.uiState.value.cards.first { it.bank==candidate.bank && it.suffix==candidate.suffix }.ownership == OwnershipStatus.OWNED }
                compose.waitUntil(20_000) { !settings.uiState.value.loading && settings.uiState.value.followedCards.any { it.bank == candidate.bank && it.last4 == candidate.suffix } }
                compose.runOnUiThread { settings.openCardNetworkPicker(settings.uiState.value.followedCards.single { it.bank == candidate.bank && it.last4 == candidate.suffix }) }
                compose.waitUntil(20_000) { settings.uiState.value.cardNetworkTarget != null }
                val type = settings.uiState.value.cardTypeSuggestion ?: com.baraa.masroof.domain.model.CardType.CREDIT
                compose.onNodeWithTag("card-type-${type.name}").performScrollTo().performClick()
                compose.onNodeWithTag("sheet-apply").performClick()
                compose.waitUntil(20_000) { settings.uiState.value.cardNetworkTarget == null && onboarding.uiState.value.cards.first { it.bank == candidate.bank && it.suffix == candidate.suffix }.cardType != null }
            }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.onboarding_finalize)))
            compose.onNodeWithText(text(R.string.onboarding_finalize)).assertIsEnabled()
            screenshot("02-finish-setup-enabled")
            click(R.string.onboarding_finalize)
            compose.waitUntil(20_000) { onboarding.uiState.value.step == OnboardingStep.FINALIZE }
            click(R.string.onboarding_enter_app)
            compose.waitUntil(30_000) { !dashboard.uiState.value.loading && (dashboard.uiState.value.summary?.transactionCount ?: 0) > 0 }
            assertNull(dashboard.uiState.value.error)
            assertTrue(dashboard.uiState.value.ownedAccounts.isNotEmpty())
            assertTrue(dashboard.uiState.value.creditFacilities?.hasContent == true)
            screenshot("03-dashboard-with-data")
            val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
            repeat(8) { iteration ->
                for (enabled in listOf(false, true)) {
                    val start = System.nanoTime()
                    compose.runOnUiThread { analyticsVisible = enabled }
                    compose.waitForIdle()
                    if (iteration >= 2) (if (enabled) after else before).add((System.nanoTime() - start) / 1_000_000)
                }
            }
            File(host.getExternalFilesDir(null),"analytics-home-render-times.txt").writeText(
                "Same device, same persisted data, same generated Home. Two warmups; six samples per case.\n" +
                "Baseline without additive forecast card (ms): $before\nWith forecast card (ms): $after\n" +
                "Measures recomposition through Compose idle, including test synchronization; not startup latency. Training is independent background work.\n")
            runBlocking {
                val oldDay = container.dashboardPeriodWorkflow.currentPeriod().startDate.minusDays(1)
                val source = container.financialTransactionRepository.listAll().firstOrNull()?.sourceContainerId
                container.database.financialTransactionDao().insertTransactionIfAbsent(com.baraa.masroof.data.room.entity.FinancialTransactionEntity(
                    "analytics-prior-payment", "CREDIT_CARD_PAYMENT", "17.00", "SAR",
                    oldDay.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    source, null, "Previous period payment", null, null))
                container.analytics.request(); container.analytics.refresh()
            }
            compose.waitUntil(30_000) { !container.analytics.state.value.updating && container.analytics.state.value.snapshot?.quality?.excluded?.values?.flatten()?.contains("analytics-prior-payment") == true }
            compose.onNodeWithTag("lab-forecast-entry").performScrollTo()
            screenshot("03b-dashboard-forecast-entry")
            compose.onNodeWithTag("lab-forecast-entry").performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_title)).assertIsDisplayed()
            click(R.string.analysis_forecasts_tab)
            compose.onNodeWithText(text(R.string.analysis_forecasts_tab)).assertIsSelected()
            compose.onNodeWithText(text(R.string.lab_analysis_need_data)).assertDoesNotExist()
            compose.onNodeWithContentDescription(host.getString(R.string.guidance_about,
                text(R.string.lab_analysis_insufficient))).performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_need_data)).assertIsDisplayed()
            back()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("guidance-popup").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.lab_analysis_insufficient)).assertIsDisplayed()
            screenshot("03c-analysis-opened-from-home")
            compose.onNodeWithTag("analysis-quality-entry").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.analysis_quality_details)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.lab_analysis_show_data)).performScrollTo().performClick()
            compose.onNode(hasText(text(R.string.lab_analysis_excluded_scope), substring = true) and hasClickAction()).performScrollTo().performClick()
            val groupSize = container.analytics.state.value.snapshot!!.quality.excluded["OUTSIDE_PURCHASE_SCOPE"]!!.size
            repeat((groupSize + 19) / 20) {
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("lab-evidence-analytics-prior-payment").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithText(text(R.string.lab_analysis_more)).fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithTag("lab-evidence-analytics-prior-payment").fetchSemanticsNodes().isEmpty())
                    compose.onNodeWithText(text(R.string.lab_analysis_more)).performScrollTo().performClick()
            }
            compose.onNodeWithTag("lab-evidence-analytics-prior-payment").performScrollTo().performClick()
            compose.waitUntil(30_000) { dashboard.uiState.value.labSelectedTransaction?.id == "analytics-prior-payment" }
            compose.onAllNodes(hasText("Previous period payment") and hasAnyAncestor(hasTestTag("lab-analytics-transaction-detail"))).onFirst().performScrollTo().assertIsDisplayed()
            screenshot("03e-previous-period-transaction-detail")
            back()
            compose.onNodeWithTag("lab-analytics-transaction-detail").assertDoesNotExist()
            compose.onNodeWithTag("lab-evidence-analytics-prior-payment").assertIsDisplayed()
            screenshot("03f-quality-keeps-scroll-after-detail")
            back()
            compose.onNodeWithText(text(R.string.analysis_forecasts_tab)).assertIsSelected()
            back()
            compose.onNodeWithTag("lab-forecast-entry").performScrollTo().assertIsDisplayed()
            // Analytics settings opened from the existing center return to that center on Cancel.
            compose.onNode(hasContentDescription("unread notification", substring = true) or
                hasContentDescription(text(R.string.notification_center_title))).performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_settings)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.lab_analysis_budget_section)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.lab_analysis_cancel)).performScrollTo().performClick()
            compose.onAllNodesWithText(text(R.string.notification_center_title)).onFirst().assertIsDisplayed()
            compose.onNodeWithTag("lab-analysis-budget").assertDoesNotExist()
            screenshot("03d-unified-center-after-settings-cancel")
            click(R.string.approved_nav_transactions)
            screenshot("04-transactions")
            click(R.string.approved_nav_review)
            screenshot("05-review")
            click(R.string.approved_nav_settings)
            screenshot("06-settings")
            click(R.string.settings_banks_title)
            val bankName = settings.uiState.value.bankCatalog!!.banks.single { it.bankId == com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA.id }.displayName ?: text(R.string.bank_aljazira)
            compose.onNodeWithText(bankName).performClick()
            compose.waitForIdle()
            click(R.string.settings_bank_loans_title)
            compose.waitUntil(20_000) { settings.uiState.value.loans.any { it.loanType == LoanType.PERSONAL && it.ownership == OwnershipStatus.OWNED } }
            assertTrue(settings.uiState.value.loans.toString(),settings.uiState.value.loans.any { it.loanType == LoanType.PERSONAL && it.ownership == OwnershipStatus.OWNED })
            compose.onNodeWithText(text(R.string.settings_loan_type_personal)).assertExists()
            screenshot("07-recognized-loan")
            click(R.string.approved_nav_home)
            compose.waitUntil(20_000) { dashboard.uiState.value.loansOverview?.hasContent == true }
            assertEquals(0,runBlocking { container.reviewRepository.listRequired().size })
            screenshot("08-dashboard-with-loan")
            click(R.string.approved_nav_review)
            screenshot("09-review-after-ownership")
            // A genuinely new title stays in the original review flow, not the cards settings.
            val body="سداد تمويل خاص\nمن: 3001\nالقسط: SAR 23\nالمبلغ المتبقي: SAR 298\nلـ: تمويل شخصي\nفي: "+java.time.LocalDateTime.now().minusMinutes(2).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            runBlocking {container.processRawSmsUseCase.ingest(RawSms("ui-unknown-loan","AlJazira",body,java.time.Instant.now(),"ui-unknown-loan",com.baraa.masroof.sms.hash.SmsBodyHasher.sha256Hex(body)))}
            compose.runOnUiThread {review.refresh()}
            compose.waitUntil(20_000) {review.uiState.value.items.size==1}
            compose.onNodeWithText(text(R.string.reference_review_evidence)).performScrollTo().performClick()
            compose.waitUntil(20_000) {review.uiState.value.selectedDetail!=null}
            screenshot("10-original-review-detail")
            compose.onNodeWithTag("review-correct-type").performScrollTo().performClick()
            val action=compose.onNodeWithTag(reviewResolveTypeTestTag(FinancialTransactionType.LOAN_REPAYMENT))
            action.performScrollTo()
            screenshot("10b-original-classification-actions")
            action.performClick()
            compose.onNodeWithTag("sheet-apply").performClick()
            compose.waitUntil(20_000) {review.uiState.value.selectedDetail==null && review.uiState.value.items.isEmpty()}
            compose.onNodeWithText(text(R.string.review_empty)).assertExists()
            screenshot("11-review-resolved-in-place")

            // The manual decision itself is never labelled as a model decision.
            assertEquals(com.baraa.masroof.application.interpretation.InterpretationOrigin.MANUAL, runBlocking { container.interpretationProvenanceWorkflow.load("ui-unknown-loan") }.origin)
            runBlocking {
                requireNotNull(container.interpretationService).synchronizeFeedback()
                val future = body.replace("SAR 23", "SAR 243.21")
                container.processRawSmsUseCase.ingest(raw("ui-learned-loan",future))
            }
            click(R.string.approved_nav_home)
            fun openAmount(amount: String) {
                compose.runOnUiThread { dashboard.refresh() }
                compose.waitUntil(20_000) { !dashboard.uiState.value.loading && dashboard.uiState.value.allTransactions.any { it.amount.amount.toPlainString() == amount } }
                val tx = runBlocking { container.financialTransactionRepository.listAll().single { it.amount.amount.toPlainString() == amount } }
                compose.runOnUiThread { dashboard.openTransactionDetail(tx.id) }
                compose.waitUntil(20_000) { !dashboard.uiState.value.selectedTransactionSmsLoading }
            }
            openAmount("243.21")
            compose.onNodeWithTag("interpretation_provenance").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.lab_template_source)).assertExists()
            screenshot("12-learned-template-source")
            compose.runOnUiThread { dashboard.closeTransactionDetail() }

            val incoming = "حوالة محلية واردة\nبنك المرسل: بنك الرياض\nاودعت الى حساب: 3001\nمن: TEST_EMPLOYER\nمبلغ العملية: SAR 2000.00\nفي: " +
                java.time.LocalDateTime.now().minusMinutes(2).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            runBlocking { container.processRawSmsUseCase.ingest(raw("ui-model-income",incoming)) }
            assertNull(runBlocking { container.financialTransactionRepository.findByRawSmsId("ui-model-income") })
            click(R.string.approved_nav_review)
            compose.runOnUiThread { review.refresh() }
            compose.waitUntil(20_000) { review.uiState.value.items.size == 1 }
            compose.onNodeWithText(text(R.string.reference_review_evidence)).performScrollTo().performClick()
            compose.waitUntil(20_000) { review.uiState.value.selectedDetail != null }
            compose.onNodeWithTag("review-why").performScrollTo().performClick()
            compose.onNodeWithTag("interpretation_provenance").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.lab_unresolved_source)).assertExists()
            compose.onNodeWithText(text(R.string.lab_template_source)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.lab_understanding_pending)).assertExists()
            screenshot("13-statistical-abstention-review")
            compose.runOnUiThread { review.closeDetail() }

            // A learned human template can propose a family, but zero principal
            // still cannot pass the independent financial validation gate.
            val blockedReviewId = runBlocking {
                // The preceding unresolved review invalidates feedback asynchronously.
                // Rebuild before testing an established human-template suggestion.
                requireNotNull(container.interpretationService).synchronizeFeedback()
                container.processRawSmsUseCase.ingest(raw("ui-model-blocked",body.replace("SAR 23","SAR 0.00")))
                requireNotNull(container.interpretationService).synchronizeFeedback()
                assertEquals(com.baraa.masroof.application.interpretation.InterpretationOrigin.HUMAN_TEMPLATE,
                    container.interpretationProvenanceWorkflow.load("ui-model-blocked").origin)
                requireNotNull(container.reviewRepository.findByRawSmsId("ui-model-blocked")).id
            }
            compose.runOnUiThread { review.refresh() }
            compose.waitUntil(20_000) { review.uiState.value.items.size == 2 }
            compose.runOnUiThread { review.openDetail(blockedReviewId) }
            compose.waitUntil(20_000) { review.uiState.value.selectedDetail != null }
            compose.onNodeWithTag("review-why").performScrollTo().performClick()
            compose.onNodeWithTag("interpretation_provenance").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.lab_template_source)).assertExists()
            compose.onNodeWithText(text(R.string.lab_inference_pending)).assertExists()
            screenshot("14-unapproved-template-review")
            compose.runOnUiThread { review.closeDetail() }
            assertEquals(0,runBlocking { container.financialTransactionRepository.listAll().count { it.amount.amount.signum() == 0 } })

        } finally {
            compose.runOnUiThread { store.clear() }
            container.close()
            runBlocking { TestRuntime.reset(host) }
        }
    }
    private fun raw(id: String,body: String) = RawSms(id,"AlJazira",body,java.time.Instant.now(),id,
        com.baraa.masroof.sms.hash.SmsBodyHasher.sha256Hex(body))
    private fun selectOwnership(entityLabel: String, action: Int) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(entityLabel))
        // The action shares the label's card; match by that, since a clipped button has no on-screen bounds.
        fun SemanticsNode.hasText(value: String) =
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        val target = SemanticsMatcher("ownership action for $entityLabel") { node ->
            node.hasText(text(action)) && node.parent?.children?.any { it.hasText(entityLabel) } == true
        }
        compose.onNode(target).performScrollTo()
        // Registry refresh disables ownership writes; wait for the same readiness a user sees.
        compose.waitUntil(20_000) { runCatching { compose.onNode(target).assertIsEnabled() }.isSuccess }
        compose.onNode(target).performClick()
    }
    private fun back() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun text(id: Int) = host.getString(id)
    private fun click(id: Int) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(text(id))
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
    }
    private fun screenshot(name: String) = ScreenshotArtifactWriter.capture(compose, "replica-journey", name)
    private fun shell(command: String): String {
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {it.readText()}
    }
}
