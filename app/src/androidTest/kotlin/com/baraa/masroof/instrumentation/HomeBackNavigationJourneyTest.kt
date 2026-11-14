package com.baraa.masroof.instrumentation

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.MainActivity
import com.baraa.masroof.R
import com.baraa.masroof.application.locale.AppLocalePreferences
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.presentation.navigation.AnalyticsNavigation
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestRuntime
import com.baraa.masroof.testsupport.ingest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Android Back (and the toolbar arrow) always return to the previous visit, whatever opened the current screen. */
class HomeBackNavigationJourneyTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val seed = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val host = InstrumentationRegistry.getInstrumentation().targetContext
                runBlocking {
                    TestRuntime.reset(host)
                    host.getSharedPreferences(AppLocalePreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(AppLocalePreferences.KEY_LANGUAGE_TAG, "en").commit()
                    val container = TestRuntime.container(host)
                    container.maintenance.awaitPostStartupBackgroundWorkForTesting()
                    container.storage.appLocaleRepository.setLanguageTag("en")
                    container.storage.onboardingPreferencesRepository.setOnboardingStarted(true)
                    container.storage.onboardingPreferencesRepository.setOnboardingCompleted(true)
                    container.review.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
                    container.review.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "2210"))
                    repeat(3) { index ->
                        val body = "شراء من نقاط البيع\nبطاقة مدى: 2210\nخصمت من حساب: 3001\nلدى: Back Nav Shop ${'A' + index}\nبمبلغ: 12.00 SAR\nفي: ${minutesAgo(index + 1L)}"
                        ingest(container, "back-nav-$index", body)
                    }
                    // One unparsed message keeps a review item, which also yields a notification with a Review action.
                    ingest(container, "back-nav-review", "سداد تمويل خاص\nمن: 3001\nالقسط: SAR 23\nالمبلغ المتبقي: SAR 298\nلـ: تمويل شخصي\nفي: ${minutesAgo(5)}")
                    assertEquals(1, container.storage.reviewRepository.listRequired().size)
                    val source = container.storage.financialTransactionRepository.listAll().first { it.merchant == "Back Nav Shop A" }
                    container.dashboard.commitmentFromTransactionService.createFromTransaction(source.id)
                }
                try { base.evaluate() } finally { TestRuntime.close() }
            }
        }
    }

    private fun minutesAgo(minutes: Long) = LocalDateTime.now().minusMinutes(minutes).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private suspend fun ingest(container: com.baraa.masroof.application.AppContainer, id: String, body: String) {
        container.sms.processRawSmsUseCase.ingest(RawSms(id, "AlJazira", body, Instant.now(), id, SmsBodyHasher.sha256Hex(body)))
    }

    @get:Rule val rule: TestRule = RuleChain.outerRule(seed).around(compose)

    private val patternsScreen = hasTestTag("pattern-open-${Bank.BANK_ALJAZIRA.id}-bank.purchase")
    private val notificationsScreen get() = heading(text(R.string.notification_center_title))
    private val dashboardScreen get() = hasText(text(R.string.reference_shortcut_accounts)) and hasClickAction()

    @Test fun patternTeaching_notificationsTabsAndRecreate_backReturnsToTheSameVisit() {
        awaitNode(dashboardScreen)
        openPatterns()

        // 2 + 15 + 16: typed teaching text survives notifications; a repeated icon tap changes nothing; the toolbar arrow equals Back.
        clickText(text(R.string.pattern_teach))
        compose.onNodeWithTag("pattern-body").performScrollTo().performTextInput("Nav body 42")
        openNotifications()
        openNotifications()
        compose.onNodeWithContentDescription(text(R.string.review_back)).performClick()
        awaitNode(hasTestTag("pattern-body"))
        compose.onNodeWithTag("pattern-body").assertTextContains("Nav body 42", substring = true)
        pressDeviceBack(compose)
        awaitNode(patternsScreen)

        // 1: notifications icon then Back lands on the same patterns screen.
        openNotifications()
        pressDeviceBack(compose)
        awaitNode(patternsScreen)

        // 13: recreation (rotation / process restore) keeps the visit and its stack.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        awaitNode(patternsScreen)

        // 10: another bottom tab is history too; Back returns to patterns, not Home.
        clickText(text(R.string.nav_review))
        awaitNode(heading(text(R.string.review_title)))
        compose.onNode(patternsScreen).assertDoesNotExist()
        pressDeviceBack(compose)
        awaitNode(patternsScreen)

        // 11: re-tapping the current tab restarts it at the hub; Back then leaves that fresh visit.
        clickText(text(R.string.nav_settings))
        awaitNode(hasText(text(R.string.settings_banks_title)) and hasClickAction())
        compose.onNode(patternsScreen).assertDoesNotExist()
        pressDeviceBack(compose)
        awaitNode(dashboardScreen)

        // 12: Home clears history, and Back on Home never closes the app.
        openNotifications()
        clickText(text(R.string.nav_home))
        awaitNode(dashboardScreen)
        pressDeviceBack(compose)
        // The shortcut can sit below the fold on some screens, so check the app stayed open rather than what is in view.
        assertFalse(compose.activity.isFinishing)
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        awaitNode(dashboardScreen)
    }

    @Test fun transactionDetail_notificationsAndReviewAction_backUnwindsVisitByVisit() {
        awaitNode(dashboardScreen)
        clickText(text(R.string.reference_shortcut_accounts))
        clickText("3001", substring = true)
        val accountDetail = heading(text(R.string.reference_account_detail_title))
        awaitNode(accountDetail)
        clickText("Back Nav Shop B")
        val transactionDetail = heading(text(R.string.transaction_detail_title))
        awaitNode(transactionDetail)

        // 7: a notification action opens Review; Back returns to notifications, then to the origin.
        openNotifications()
        clickText(text(R.string.settings_hub_review_title))
        awaitNode(heading(text(R.string.review_title)))
        // A new Review visit opens on the list; the older visit gets its own message back when it returns.
        clickText(text(R.string.reference_review_evidence))
        val reviewDetail = heading(text(R.string.review_detail_title))
        awaitNode(reviewDetail)
        openNotifications()
        clickText(text(R.string.settings_hub_review_title))
        awaitNode(heading(text(R.string.review_title)))
        compose.onNode(reviewDetail).assertDoesNotExist()
        pressDeviceBack(compose)
        awaitNode(notificationsScreen)
        pressDeviceBack(compose)
        awaitNode(reviewDetail)
        pressDeviceBack(compose)
        awaitNode(heading(text(R.string.review_title)))
        compose.onNode(reviewDetail).assertDoesNotExist()
        pressDeviceBack(compose)
        awaitNode(notificationsScreen)

        // 3 + 14: the same transaction detail returns, then every visit unwinds in order.
        pressDeviceBack(compose)
        awaitNode(transactionDetail)
        pressDeviceBack(compose)
        awaitNode(accountDetail)
        pressDeviceBack(compose)
        awaitNode(heading(text(R.string.dashboard_accounts_summary_screen_title)))
        pressDeviceBack(compose)
        awaitNode(dashboardScreen)
    }

    @Test fun commitmentsAnalyticsAndLearningExample_backReturnsToOrigin() {
        awaitNode(dashboardScreen)

        // 4: a commitment opened from the Commitments screen returns there, not to Home.
        clickText(text(R.string.commitments))
        val commitments = heading(text(R.string.commitments))
        awaitNode(commitments)
        clickText("Back Nav Shop A")
        awaitNode(heading(text(R.string.settings_commitment_edit_title)))
        pressDeviceBack(compose)
        awaitNode(commitments)
        pressDeviceBack(compose)
        awaitNode(dashboardScreen)

        // 9: analytics stays open on the dashboard visit after notifications and Back.
        compose.onNodeWithTag("lab-forecast-entry").performScrollTo().performClick()
        val analytics = hasText(text(R.string.analysis_forecasts_tab))
        awaitNode(analytics)
        openNotifications()
        pressDeviceBack(compose)
        awaitNode(analytics)

        // 12: the Home tab always lands on the dashboard itself, never on a saved analytics page.
        clickText(text(R.string.nav_settings))
        clickText(text(R.string.nav_home))
        awaitNode(dashboardScreen)
        compose.onNode(analytics).assertDoesNotExist()
        compose.onNodeWithTag("lab-forecast-entry").performScrollTo().performClick()
        awaitNode(analytics)
        clickText(text(R.string.nav_home))
        awaitNode(dashboardScreen)
        compose.onNode(analytics).assertDoesNotExist()

        // An analytics alert (system notification tap) opens even while a dashboard transaction detail is open.
        clickText("Back Nav Shop", substring = true)
        awaitNode(heading(text(R.string.transaction_detail_title)))
        compose.runOnUiThread { AnalyticsNavigation.open("settings") }
        awaitNode(hasText(text(R.string.lab_analysis_budget_section)))
        compose.onNode(heading(text(R.string.transaction_detail_title))).assertDoesNotExist()
        clickText(text(R.string.lab_analysis_cancel))
        clickText(text(R.string.nav_home))
        awaitNode(dashboardScreen)

        // 6: a review example opened from message learning returns to that same settings page.
        clickText(text(R.string.nav_settings))
        clickText(text(R.string.analysis_learning_settings))
        clickText(text(R.string.personal_review_open, 1))
        clickText("Message 1 ·", substring = true)
        awaitNode(heading(text(R.string.review_detail_title)))
        pressDeviceBack(compose)
        awaitNode(heading(text(R.string.review_title)))
        pressDeviceBack(compose)
        awaitNode(hasText(text(R.string.personal_review_open, 1)) and hasClickAction())
    }

    @Test fun detailsTeachingAndRecreation_keepTheirPlaceAcrossTabsAndOtherVisits() {
        awaitNode(dashboardScreen)
        val transactionDetail = heading(text(R.string.transaction_detail_title))
        val transactionList = heading(text(R.string.transaction_list_title))
        val settingsHub = hasText(text(R.string.settings_banks_title)) and hasClickAction()

        // A re-tapped tab restarts only itself; the dashboard's open detail stays behind it.
        clickText("Back Nav Shop B")
        awaitNode(transactionDetail)
        clickText(text(R.string.nav_transactions))
        awaitNode(transactionList)
        clickText(text(R.string.nav_transactions))
        awaitNode(transactionList)
        pressDeviceBack(compose)
        awaitNode(transactionDetail)

        // A change made while Settings covers the detail shows as soon as Back returns to it.
        // A plain write sends no maintenance signal, so only the reload on leaving Settings can show it.
        clickText(text(R.string.nav_settings))
        awaitNode(settingsHub)
        runBlocking {
            val repository = TestRuntime.container(InstrumentationRegistry.getInstrumentation().targetContext).storage.financialTransactionRepository
            repository.update(repository.listAll().first { it.merchant == "Back Nav Shop B" }.copy(merchant = "Renamed In Settings"))
        }
        pressDeviceBack(compose)
        awaitNode(transactionDetail)
        awaitNode(hasText("Renamed In Settings"))
        pressDeviceBack(compose)
        awaitNode(dashboardScreen)

        // Teaching from Review keeps its step and bindings, and an older Review visit gets its message back.
        clickText(text(R.string.nav_review))
        clickText(text(R.string.reference_review_evidence))
        val reviewDetail = heading(text(R.string.review_detail_title))
        awaitNode(reviewDetail)
        click(hasTestTag("review-teach-pattern"))
        clickText(text(R.string.teaching_next))
        click(hasTestTag("pattern-bind") and isEnabled())
        val preview = hasText(text(R.string.pattern_preview)) and hasClickAction() and isEnabled()
        awaitNode(preview)
        openNotifications()
        clickText(text(R.string.nav_review))
        awaitNode(heading(text(R.string.review_title)))
        compose.onNode(reviewDetail).assertDoesNotExist()
        compose.onNode(hasTestTag("pattern-bind")).assertDoesNotExist()
        pressDeviceBack(compose)
        awaitNode(notificationsScreen)
        pressDeviceBack(compose)
        awaitNode(preview)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        awaitNode(preview)
        // Back walks the teaching steps: second field, first field (still binding), kind, then the message it came from.
        pressDeviceBack(compose)
        compose.onNode(preview).assertExists()
        pressDeviceBack(compose)
        awaitNode(hasTestTag("pattern-operation-PURCHASE"))
        pressDeviceBack(compose)
        awaitNode(reviewDetail)

        // Recreating the activity with an alert's launch intent must not open the alert again over Settings.
        clickText(text(R.string.nav_settings))
        awaitNode(settingsHub)
        compose.runOnUiThread { compose.activity.intent.putExtra("lab_analysis_alert", "settings") }
        repeat(2) {
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
            awaitNode(settingsHub)
        }
        compose.onNode(hasText(text(R.string.lab_analysis_budget_section))).assertDoesNotExist()
        pressDeviceBack(compose)
        awaitNode(reviewDetail)
    }

    private fun openPatterns() {
        clickText(text(R.string.nav_settings))
        clickText(text(R.string.settings_banks_title))
        // The row shows the catalog display name, which stays Arabic in every locale.
        click(hasText("بنك الجزيرة") and hasClickAction())
        clickText(text(R.string.settings_bank_teaching))
        awaitNode(patternsScreen)
    }

    private fun openNotifications() {
        compose.onNode(hasContentDescription("unread notification", substring = true) or
            hasContentDescription(text(R.string.notification_center_title))).performClick()
        awaitNode(notificationsScreen)
    }

    private fun heading(value: String) = hasText(value) and !hasClickAction()

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun awaitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    private fun clickText(value: String, substring: Boolean = false) =
        click(hasText(value, substring = substring) and hasClickAction())

    private fun click(target: SemanticsMatcher) {
        awaitNode(target)
        val node = compose.onAllNodes(target).onFirst()
        // Bottom tabs and toolbar actions sit outside any scroll container.
        runCatching { node.performScrollTo() }
        node.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }
}
