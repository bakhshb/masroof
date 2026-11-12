package com.baraa.masroof.instrumentation

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestRuntime
import com.baraa.masroof.testsupport.ingest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Exercises the production root, summary routes and transaction details with synthetic persisted SMS. */
class SummaryTransactionBackNavigationTest {
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
                    for (card in listOf("2210", "7271")) {
                        container.review.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, card))
                    }
                    repeat(10) { index ->
                        val at = LocalDateTime.now().minusMinutes(index + 1L)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        for (credit in listOf(false, true)) {
                            val merchant = "Navigation ${if (credit) "Credit" else "Debit"} ${'A' + index}"
                            val instrument = if (credit) "بطاقة ائتمانية: 7271" else "بطاقة مدى: 2210\nخصمت من حساب: 3001"
                            val body = "شراء من نقاط البيع\n$instrument\nلدى: $merchant\nبمبلغ: 12.00 SAR\nفي: $at"
                            val id = "navigation-$credit-$index"
                            container.sms.processRawSmsUseCase.ingest(RawSms(id, "AlJazira", body, Instant.now(), id, SmsBodyHasher.sha256Hex(body)))
                        }
                    }
                    assertEquals(20, container.storage.financialTransactionRepository.listAll().size)
                }
                try { base.evaluate() } finally { TestRuntime.close() }
            }
        }
    }

    @get:Rule val rule: TestRule = RuleChain.outerRule(seed).around(compose)

    @Test fun accountTransactionBack_restoresAccountAndScroll() = checkJourney(account = true)
    @Test fun creditTransactionBack_restoresCardAndScroll() = checkJourney(account = false)
    @Test fun madaTransactionBack_restoresCardAndScroll() = checkJourney(account = false, mada = true)

    private fun checkJourney(account: Boolean, mada: Boolean = false) {
        val summary = if (account) R.string.dashboard_accounts_summary_screen_title else R.string.dashboard_cards_summary_screen_title
        val detail = if (account) R.string.reference_account_detail_title else R.string.reference_card_detail_title
        val detailHeading = hasText(text(detail)) and !hasClickAction()
        val merchant = "Navigation ${if (account || mada) "Debit" else "Credit"} H"
        awaitText(text(R.string.reference_shortcut_accounts))
        openSummary(account)
        if (mada) clickText(text(R.string.reference_mada_tab))
        val suffix = if (account) "3001" else if (mada) "2210" else "7271"
        clickText(suffix, substring = true)
        compose.waitUntil(30_000) { compose.onNode(detailHeading).isDisplayed() }
        compose.onNode(detailHeading).assertIsDisplayed()

        // Both toolbar Back and Android Back must restore the exact detail and scroll position.
        for (systemBack in listOf(false, true)) {
            val row = compose.onNode(hasText(merchant) and hasClickAction())
            row.performScrollTo().assertIsDisplayed()
            val before = scrollPosition()
            assertTrue("Fixture must scroll before opening the transaction", before > 0f)
            row.performClick()
            compose.onNodeWithText(text(R.string.transaction_detail_title)).assertIsDisplayed()
            if (systemBack) {
                compose.activityRule.scenario.recreate()
                compose.onNodeWithText(text(R.string.transaction_detail_title)).assertIsDisplayed()
                compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
                pressDeviceBack(compose)
            } else {
                compose.onNodeWithContentDescription(text(R.string.review_back)).performClick()
            }
            compose.waitUntil(10_000) { compose.onNode(detailHeading).isDisplayed() }
            compose.onNode(detailHeading).assertIsDisplayed()
            compose.onNode(hasText(merchant) and hasClickAction()).assertIsDisplayed()
            assertEquals("Returning from a transaction must retain scroll", before, scrollPosition(), 1f)
        }

        pressDeviceBack(compose)
        compose.onNodeWithText(text(summary)).assertIsDisplayed()
        pressDeviceBack(compose)
        openSummary(account)
        compose.onNodeWithText(text(summary)).assertIsDisplayed()
        compose.onNode(detailHeading).assertDoesNotExist()
    }

    private fun openSummary(account: Boolean) = clickText(text(
        if (account) R.string.reference_shortcut_accounts else R.string.reference_shortcut_cards,
    ))

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun awaitText(value: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun clickText(value: String, substring: Boolean = false) {
        val target = hasText(value, substring = substring) and hasClickAction()
        compose.waitUntil(30_000) {
            compose.onAllNodes(target).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        // Dashboard data loads asynchronously and can move a shortcut after scrolling.
        // Navigate through the identified action; transaction rows below still receive real taps.
        compose.onAllNodes(target).onFirst().performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun scrollPosition(): Float = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
