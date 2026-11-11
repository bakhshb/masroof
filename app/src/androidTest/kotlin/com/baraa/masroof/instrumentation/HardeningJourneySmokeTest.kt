package com.baraa.masroof.instrumentation

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
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
        clickText(text(R.string.reference_review_evidence), scroll = true)
        clickTag("review-correct-type")
        clickTag(reviewResolveTypeTestTag(FinancialTransactionType.EXPENSE))
        clickTag("sheet-apply")
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
        clickTag("review-ignored-row")
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
        waitUntil("financial navigation is ready") {
            composeRule.contentDescriptionExists(text(R.string.dashboard_open_settings)) &&
                composeRule.contentDescriptionExists(text(R.string.nav_home))
        }
        assertBlockedScreenAbsent()
        composeRule.onNodeWithContentDescription(text(R.string.nav_home))
            .assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithContentDescription(text(R.string.dashboard_open_settings))
            .assertIsDisplayed()
        assertClearOfSystemBars()
    }

    /** targetSdk 35+ draws edge-to-edge; the header and bottom navigation must stay off the system bars. */
    private fun assertClearOfSystemBars() {
        val decor = composeRule.activity.window.decorView
        val bars = androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val header = composeRule.onNode(hasText(text(R.string.app_name)), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val home = composeRule.onNodeWithContentDescription(text(R.string.nav_home))
            .fetchSemanticsNode().boundsInWindow
        assertTrue("Header top ${header.top} is under the status bar (${bars.top})", header.top >= bars.top)
        assertTrue("Navigation bottom ${home.bottom} is under the navigation bar (${decor.height - bars.bottom})",
            home.bottom <= decor.height - bars.bottom)
    }

    private fun openReviewQueue() {
        clickText(text(R.string.nav_review))
        awaitText(text(R.string.review_title), scroll = false)
    }

    private fun returnToDashboard() {
        clickText(text(R.string.nav_home))
        composeRule.waitForIdle()
    }

    private fun awaitText(value: String, scroll: Boolean) {
        waitUntil("text '$value'") { textExists(value) }
        if (scroll) composeRule.scrollTo(textMatcher(value))
        composeRule.onAllNodes(textMatcher(value))[0].assertIsDisplayed()
    }

    private fun clickText(value: String, scroll: Boolean = false) {
        val matcher = textMatcher(value) and hasClickAction()
        waitUntil("clickable text '$value'") { composeRule.nodeExists(matcher) }
        if (scroll) composeRule.scrollTo(matcher)
        clickableNode(value).performClick()
        composeRule.waitForIdle()
    }

    private fun clickTag(tag: String) {
        val matcher = hasTestTag(tag)
        waitUntil("tag '$tag'") { composeRule.nodeExists(matcher) }
        composeRule.scrollTo(matcher)
        composeRule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
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

    private fun textExists(value: String): Boolean = composeRule.nodeExists(textMatcher(value))

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
