package com.baraa.masroof.instrumentation

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.MainActivity
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.presentation.review.REVIEW_RESTORE_AS_IS_TEST_TAG
import com.baraa.masroof.presentation.review.reviewResolveTypeTestTag
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
        clickTag(reviewResolveTypeTestTag(FinancialTransactionType.EXPENSE))
        waitUntil("review queue empty or action failure") {
            textExists(text(R.string.review_empty)) ||
                textExists(text(R.string.review_action_failed))
        }
        assertActionFailureAbsent()
        awaitText(text(R.string.review_empty), scroll = false)

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
        clickTag(REVIEW_RESTORE_AS_IS_TEST_TAG)
        waitUntil("ignored list empty or restore failure") {
            textExists(text(R.string.review_ignored_empty)) ||
                textExists(text(R.string.review_action_failed))
        }
        assertActionFailureAbsent()
        awaitText(text(R.string.review_ignored_empty), scroll = false)

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
        waitUntil("settings action") { contentDescriptionExists(settings) }
        composeRule.onNodeWithContentDescription(settings).performClick()
        clickText(text(R.string.settings_hub_review_title), scroll = true)
        awaitText(text(R.string.review_title), scroll = false)
    }

    private fun returnToDashboard() {
        pressBack()
        awaitText(text(R.string.settings_title), scroll = false)
        pressBack()
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun awaitText(value: String, scroll: Boolean) {
        waitUntil("text '$value'") { textExists(value) }
        if (scroll) scrollTo(textMatcher(value))
        composeRule.onAllNodes(textMatcher(value))[0].assertIsDisplayed()
    }

    private fun clickText(value: String, scroll: Boolean = false) {
        val matcher = textMatcher(value) and hasClickAction()
        waitUntil("clickable text '$value'") { nodeExists(matcher) }
        if (scroll) scrollTo(matcher)
        clickableNode(value).performClick()
        composeRule.waitForIdle()
    }

    private fun clickTag(tag: String) {
        val matcher = hasTestTag(tag)
        waitUntil("tag '$tag'") { nodeExists(matcher) }
        scrollTo(matcher)
        composeRule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        val scrollables = composeRule.onAllNodes(hasScrollAction())
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        for (index in scrollables.indices) {
            val scrolled = runCatching {
                composeRule.onAllNodes(hasScrollAction())[index].performScrollToNode(matcher)
            }.isSuccess
            if (scrolled) return
        }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Timed out after ${TIMEOUT_MS}ms waiting for $description", e)
        }
    }

    private fun clickableNode(value: String): SemanticsNodeInteraction =
        composeRule.onNode(textMatcher(value) and hasClickAction())

    private fun textMatcher(value: String) = hasText(value, substring = true)

    private fun nodeExists(matcher: SemanticsMatcher): Boolean =
        composeRule.onAllNodes(matcher)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    private fun textExists(value: String): Boolean = nodeExists(textMatcher(value))

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
