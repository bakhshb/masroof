package com.baraa.masroof.instrumentation

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.MainActivity
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Device smoke for the hardening train. JVM tests stay the fast PR gate;
 * these journeys run on an emulator and supplement them.
 *
 * Each method is seeded before launch. Android Test Orchestrator clears
 * package data so the journeys do not share a database.
 */
@RunWith(AndroidJUnit4::class)
class HardeningJourneySmokeTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rule: TestRule = RuleChain
        .outerRule(JourneySeedRule())
        .around(composeRule)

    @Test
    fun startup_reachesFinancialUiWhenMaintenanceIsReady() {
        awaitFinancialShell()
        composeRule.activityRule.scenario.recreate()
        awaitFinancialShell()
    }

    @Test
    fun dashboard_showsPersistedTransactionAfterReload() {
        awaitText(JourneyFixtures.DASHBOARD_MERCHANT, scroll = true)
        assertBlockedScreenAbsent()
        composeRule.activityRule.scenario.recreate()
        awaitText(JourneyFixtures.DASHBOARD_MERCHANT, scroll = true)
        assertStoredExpense(
            rawSmsId = JourneyFixtures.DASHBOARD_SMS,
            merchant = JourneyFixtures.DASHBOARD_MERCHANT,
            resolutionKind = null,
            expectLinkedReview = false,
        )
    }

    @Test
    fun reviewCorrection_persistsChosenTypeAndReloads() {
        openReviewQueue()
        clickText(JourneyFixtures.CORRECTION_BODY, scroll = true)
        clickText(text(R.string.txn_type_expense), scroll = true)
        awaitText(text(R.string.review_empty), scroll = false)
        assertActionFailureAbsent()

        returnToDashboard()
        awaitText(JourneyFixtures.CORRECTION_MERCHANT, scroll = true)
        composeRule.activityRule.scenario.recreate()
        awaitText(JourneyFixtures.CORRECTION_MERCHANT, scroll = true)
        assertStoredExpense(
            rawSmsId = JourneyFixtures.CORRECTION_SMS,
            merchant = JourneyFixtures.CORRECTION_MERCHANT,
            resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE,
            expectLinkedReview = true,
        )
    }

    @Test
    fun reviewRestore_persistsRestoredPurchaseAndReloads() {
        openReviewQueue()
        awaitText(text(R.string.review_empty), scroll = false)
        clickText(text(R.string.review_tab_ignored))
        clickText(JourneyFixtures.RESTORE_BODY, scroll = true)
        clickText(text(R.string.review_action_restore), scroll = true)
        awaitText(text(R.string.review_ignored_empty), scroll = false)
        assertActionFailureAbsent()

        returnToDashboard()
        awaitText(JourneyFixtures.RESTORE_MERCHANT, scroll = true)
        composeRule.activityRule.scenario.recreate()
        awaitText(JourneyFixtures.RESTORE_MERCHANT, scroll = true)
        assertStoredExpense(
            rawSmsId = JourneyFixtures.RESTORE_SMS,
            merchant = JourneyFixtures.RESTORE_MERCHANT,
            resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE,
            expectLinkedReview = false,
        )
    }

    private fun awaitFinancialShell() {
        awaitText(text(R.string.dashboard_empty_period), scroll = true)
        assertBlockedScreenAbsent()
        composeRule.onNodeWithContentDescription(text(R.string.dashboard_open_settings))
            .assertIsDisplayed()
    }

    private fun openReviewQueue() {
        val settings = text(R.string.dashboard_open_settings)
        composeRule.waitUntil(TIMEOUT_MS) { contentDescriptionExists(settings) }
        composeRule.onNodeWithContentDescription(settings).performClick()
        clickText(text(R.string.settings_hub_review_title), scroll = true)
        awaitText(text(R.string.review_title), scroll = false)
    }

    private fun returnToDashboard() {
        pressBack()
        awaitText(text(R.string.settings_title), scroll = false)
        pressBack()
    }

    private fun pressBack() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun awaitText(value: String, scroll: Boolean) {
        composeRule.waitUntil(TIMEOUT_MS) { textExists(value) }
        val node = composeRule.onAllNodes(textMatcher(value))[0]
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed()
    }

    private fun clickText(value: String, scroll: Boolean = false) {
        composeRule.waitUntil(TIMEOUT_MS) { textExists(value) }
        val node = clickableNode(value)
        if (scroll) node.performScrollTo()
        node.performClick()
    }

    private fun clickableNode(value: String): SemanticsNodeInteraction =
        composeRule.onNode(textMatcher(value) and hasClickAction())

    private fun textMatcher(value: String) = hasText(value, substring = true)

    private fun textExists(value: String): Boolean =
        composeRule.onAllNodes(textMatcher(value))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    private fun contentDescriptionExists(value: String): Boolean =
        composeRule.onAllNodesWithContentDescription(value)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    private fun assertBlockedScreenAbsent() {
        val blocked = text(R.string.startup_maintenance_blocked)
        val shown = composeRule.onAllNodes(textMatcher(blocked))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size
        assertEquals(blocked, 0, shown)
    }

    private fun assertActionFailureAbsent() {
        val failed = text(R.string.review_action_failed)
        val shown = composeRule.onAllNodes(textMatcher(failed))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size
        assertEquals(failed, 0, shown)
    }

    private fun assertStoredExpense(
        rawSmsId: String,
        merchant: String,
        resolutionKind: ReviewResolutionKind?,
        expectLinkedReview: Boolean,
    ) {
        val app = composeRule.activity.application as MasroofApplication
        runBlocking(Dispatchers.IO) {
            val transaction = app.container.financialTransactionRepository.findByRawSmsId(rawSmsId)
            assertNotNull(transaction)
            assertEquals(merchant, transaction!!.merchant)
            assertEquals(FinancialTransactionType.EXPENSE, transaction.type)
            if (resolutionKind == null) return@runBlocking
            val review = app.container.reviewRepository.findByRawSmsId(rawSmsId)
            assertNotNull(review)
            assertEquals(ReviewStatus.RESOLVED, review!!.status)
            assertEquals(resolutionKind, review.resolutionKind)
            if (expectLinkedReview) {
                assertEquals(transaction.id, review.resolvedTransactionId)
            }
        }
    }

    private fun text(id: Int): String = composeRule.activity.getString(id)

    private companion object {
        const val TIMEOUT_MS: Long = 20_000L
    }
}

/**
 * Runs before the compose rule launches [MainActivity].
 */
private class JourneySeedRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                JourneySeeder.prepare(description.methodName)
                base.evaluate()
            }
        }
}
