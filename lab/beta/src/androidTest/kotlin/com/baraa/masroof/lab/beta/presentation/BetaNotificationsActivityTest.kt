package com.baraa.masroof.lab.beta.presentation

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.application.notification.BetaNotificationType
import com.baraa.masroof.lab.finance.value.BankId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaNotificationsActivityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val body="عملية مالية غير معروفة بصيغة جديدة"
    private fun seed(review: Boolean) {
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.READ_SMS")).use {it.readBytes()}
        BetaContainer.resetOffline(context)
        BetaContainer(context).use {beta ->
            beta.putMarker("notifications","synthetic")
            beta.acknowledgeStart()
            if(review) {
                val bank=BankId("BANK_ALJAZIRA")
                beta.setBankEnabled(bank,true)
                beta.importEvidence(EvidenceRequest("notification-test",EvidenceOrigin.EXPLICIT_SAMPLE),listOf(
                    EvidenceMessage("notification-evidence","AlJazira",body,Instant.parse("2026-10-18T12:00:00Z"),ReceiptTimeSource.USER_SUPPLIED)))
                beta.processAutomaticEvidence()
                assertEquals(1,beta.evidenceInboxCounts().needsReview)
            }
        }
    }
    private fun await(tag: String) {
        compose.waitUntil(15000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.waitForIdle()
    }
    private fun drain() {val done=CountDownLatch(1);BetaIo.execute {done.countDown()};assertTrue(done.await(20,TimeUnit.SECONDS))}
    @Test fun notificationOpensReviewAndReadStatePersistsAfterRecreationAndReopen() {
        seed(true)
        try {ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
            await("dashboard-ready")
            compose.onNodeWithTag("beta-tools").assertDoesNotExist()
            compose.onNodeWithTag("beta-notifications").performClick()
            val title=context.getString(R.string.beta_approved_settings_hub_review_title)
            compose.waitUntil(15000) {compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText(title).performClick()
            await("review-filter-pending")
            scenario.recreate()
            await("review-filter-pending")
        }
        drain()
        BetaContainer(context).use {beta ->
            assertTrue(beta.notifications().items.single {it.type==BetaNotificationType.REVIEW_REQUIRED}.isRead)
            assertEquals(1,beta.evidenceInboxCounts().needsReview)
        }} finally {BetaContainer.resetOffline(context)}
    }
    @Test fun backFromNotificationsRestoresTheSelectedReviewDetail() {
        seed(true)
        try {ActivityScenario.launch(BetaActivity::class.java).use {
            await("dashboard-ready")
            compose.onNode(hasText(context.getString(R.string.beta_nav_review)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
            await("history-review-0")
            compose.onNodeWithTag("history-review-0").performScrollTo().performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("beta-notifications").performClick()
            compose.onNodeWithTag("beta-page-back").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("review-filter-pending").assertDoesNotExist()
        }} finally {drain();BetaContainer.resetOffline(context)}
    }
    @Test fun emptyNotificationCenterUsesApprovedEmptyStateAndHeaderBack() {
        seed(false)
        try {ActivityScenario.launch(BetaActivity::class.java).use {
            await("dashboard-ready")
            compose.onNodeWithTag("beta-notifications").performClick()
            val empty=context.getString(R.string.beta_approved_notification_center_empty)
            compose.waitUntil(15000) {compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("beta-page-back").performClick()
            await("dashboard-ready")
        }} finally {drain();BetaContainer.resetOffline(context)}
    }
}
