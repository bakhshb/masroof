package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.R
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.patterns.MessagePatternsScreen
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.ar
import com.baraa.masroof.testsupport.ingest
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PatternFinancialActivationJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val body = "Internal incoming transfer\nAmount:10SAR\nFrom:TEST PERSON\nAcc:321\nAt:15/10/26 12:30"
    private fun sms(id: String, amount: String, reference: String = "321") = body.replace("10SAR", "${amount}SAR").replace("Acc:321", "Acc:$reference").let {
        RawSms(id, "STC Bank", it, Instant.parse("2026-10-15T10:00:00Z"), id, SmsBodyHasher.sha256Hex(it))
    }
    private fun openApproval() {
        compose.onAllNodesWithText(compose.ar(R.string.pattern_financial_approve))[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("pattern-reference-context").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("pattern-reference-context").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("pattern-financial-save").assertIsEnabled() }.isSuccess }
    }

    @Test fun choosingTheAlreadySelectedOptionKeepsApprovalAvailable() = runBlocking {
        val context = TestStorageContext(compose.activity, "financial-pattern-${java.util.UUID.randomUUID()}")
        val c = AppContainer(context)
        try {
            val w = c.messagePatternsWorkflow
            w.confirmPatternAccount("BANK_STC", "4321")
            val qattah = "دفع قطة\nمبلغ:37.50 ر.س\nإلى:TEST PERSON\nفي:13/10/26 12:30"
            c.processRawSmsUseCase.ingest(RawSms("qattah", "STC Bank", qattah, Instant.parse("2026-10-15T10:00:00Z"), "qattah",
                SmsBodyHasher.sha256Hex(qattah)))
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity) {
                    MasroofTheme { Surface { MessagePatternsScreen(w, "BANK_STC", onBack = {}) } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-open-BANK_STC-stc-qattah-ar").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-open-BANK_STC-stc-qattah-ar").performScrollTo().performClick()
            compose.onAllNodesWithText(compose.ar(R.string.pattern_financial_approve))[0].performScrollTo().performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("pattern-financial-save").assertIsEnabled() }.isSuccess }
            val outgoing = AppLocaleContext.wrap(compose.activity, "ar").resources.getStringArray(R.array.pattern_operations)[
                com.baraa.masroof.application.patterns.PatternOperation.TRANSFER_OUT.ordinal]
            for (selected in listOf(outgoing, compose.ar(R.string.pattern_financial_currency_from_message))) {
                compose.onNode(hasText(selected) and hasClickAction()).performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("pattern-financial-save").assertIsEnabled()
            }
        } finally { c.close(); context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
    }

    @Test fun singlePageApprovalUsesImportedExamplesAndPostsVaryingCounterpartyReferencesToTheSelectedAccount() = runBlocking {
        val context = TestStorageContext(compose.activity, "financial-pattern-${java.util.UUID.randomUUID()}")
        val c = AppContainer(context)
        try {
            val w = c.messagePatternsWorkflow
            w.confirmPatternAccount("BANK_STC", "4321")
            val approvedGeneration = w.load().generation + 1
            c.processRawSmsUseCase.ingest(sms("pending", "10"))
            c.processRawSmsUseCase.ingest(sms("pending-second", "20", "654"))
            val outgoingBody = "Internal outward transfer\nAmount:5SAR\nTo:OTHER PERSON\nAcc:987\nAt:15/10/26 13:30"
            c.processRawSmsUseCase.ingest(RawSms("other-pattern", "STC Bank", outgoingBody,
                Instant.parse("2026-10-15T11:00:00Z"), "other-pattern", SmsBodyHasher.sha256Hex(outgoingBody)))
            val dark = mutableStateOf(false)
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity) {
                    MasroofTheme(darkTheme = dark.value) { Surface { MessagePatternsScreen(w, "BANK_STC", onBack = {}) } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-open-BANK_STC-stc-internal-in").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-open-BANK_STC-stc-internal-in").performScrollTo().performClick()
            openApproval()
            compose.onNodeWithText(compose.ar(R.string.pattern_approval_imported_message)).assertExists()
            compose.onNodeWithTag("pattern-account-last4").assertDoesNotExist()
            compose.onNodeWithText(compose.ar(R.string.pattern_approval_add_account)).performScrollTo().performClick()
            compose.onNodeWithTag("pattern-account-last4").performTextInput("5555")
            compose.onNodeWithTag("pattern-financial-save").assertIsNotEnabled()
            compose.onNodeWithText(compose.ar(R.string.pattern_approval_cancel_account)).performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("pattern-financial-save").assertIsEnabled() }.isSuccess }
            compose.onNodeWithTag("pattern-financial-save").performScrollTo()
            compose.waitForIdle()
            ScreenshotArtifactWriter.publish(compose.onRoot().captureToImage().asAndroidBitmap(), "pattern-financial", "single-page-light")
            compose.runOnIdle { dark.value = true }
            compose.waitForIdle()
            ScreenshotArtifactWriter.publish(compose.onRoot().captureToImage().asAndroidBitmap(), "pattern-financial", "single-page-dark")
            // Discarding a validated preview never approves or posts anything.
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_financial_approve)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns.all { it.factContract == null })
            assertTrue(c.financialTransactionRepository.listAll().isEmpty())
            openApproval()
            compose.onNodeWithTag("pattern-financial-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { w.load().generation == approvedGeneration } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-apply-matching").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(compose.ar(R.string.pattern_approval_ready)).assertExists()
            assertEquals(3, c.reviewRepository.listRequired().size)
            assertTrue(c.financialTransactionRepository.listAll().isEmpty())
            compose.onNodeWithTag("pattern-apply-matching").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("pattern-review-change").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-review-change").assertTextEquals(
                com.baraa.masroof.application.locale.AppDigits.display(AppLocaleContext.wrap(compose.activity, "ar")
                    .getString(R.string.pattern_approval_review_change, 3, 1)))
            compose.onNodeWithTag("pattern-apply-matching").assertDoesNotExist()
            assertEquals(1, c.reviewRepository.listRequired().size)
            assertEquals(ReviewStatus.REQUIRED, c.reviewRepository.findByRawSmsId("other-pattern")!!.status)
            assertEquals(2, c.financialTransactionRepository.listAll().size)
            val period = FinancialPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
            val account = c.dashboardService.loadOverview(period).accountsFleet!!.accounts.single { it.maskedNumber == "4321" }
            assertEquals("30.00", account.totalIn.amount.toPlainString())
            val evidence = c.parsedEventRepository.findByRawSmsId("pending")
            c.processRawSmsUseCase.ingest(sms("future", "40", "999"))
            assertEquals(3, c.financialTransactionRepository.listAll().size)
            compose.onNodeWithTag("pattern-advanced").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-restore-BANK_STC-stc-internal-in-1").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { w.load().generation > approvedGeneration } }
            // Persistence completes before the catalog coroutine publishes the restored screen.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(compose.ar(R.string.pattern_approval_waiting)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("pattern-apply-result").assertDoesNotExist()
            compose.onNodeWithText(compose.ar(R.string.pattern_approval_saved)).assertDoesNotExist()
            compose.onNodeWithText(compose.ar(R.string.pattern_approval_waiting)).assertExists()
            w.setEnabled(w.load().generation, "BANK_STC", "stc-internal-in", false)
            c.processRawSmsUseCase.ingest(sms("disabled", "50"))
            c.processStoredSmsUseCase.process("pending")
            assertEquals(evidence, c.parsedEventRepository.findByRawSmsId("pending"))
            assertEquals(3, c.financialTransactionRepository.listAll().size)
        } finally { c.close(); context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
    }
}
