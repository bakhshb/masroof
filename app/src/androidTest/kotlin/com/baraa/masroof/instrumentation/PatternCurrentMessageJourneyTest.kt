package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baraa.masroof.R
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.patterns.PatternTeachingSource
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.patterns.MessagePatternsScreen
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.testsupport.ar
import com.baraa.masroof.testsupport.clickAr
import com.baraa.masroof.testsupport.ingest
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class PatternCurrentMessageJourneyTest {
    // The prefilled selection repeats the value as text, so a chip is found by its span.
    private fun candidateTag(body: String, value: String) = body.indexOf(value).let { "pattern-candidate-$it-${it + value.length}" }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun teachApproveAndApplyFromReviewPostsOnlyTheExplicitCurrentMessage() = runBlocking {
        val context = TestStorageContext(compose.activity, "ui-current-${java.util.UUID.randomUUID()}")
        val c = AppContainer(context)
        try {
            val w = c.patterns.messagePatternsWorkflow
            w.saveBank(w.load().generation, BankMessagePatterns("UI_BANK", listOf("UI Bank"), "بنك التجربة"))
            w.confirmPatternAccount("UI_BANK", "1234")
            val body = "Purchase\nAmount:10SAR\nAt:2026-10-15 12:30"
            for ((id, text) in listOf("ui-current" to body, "ui-other" to body.replace("10SAR", "20SAR"))) {
                c.sms.processRawSmsUseCase.ingest(RawSms(id, "UI Bank", text, Instant.parse("2026-10-15T10:00:00Z"), id, SmsBodyHasher.sha256Hex(text)))
            }
            val current = c.storage.reviewRepository.findByRawSmsId("ui-current")!!
            val untouched = c.storage.parsedEventRepository.findByRawSmsId("ui-other")
            var returnedToReview = false
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity) {
                    MasroofTheme { Surface {
                        MessagePatternsScreen(w, source = PatternTeachingSource("UI_BANK", "UI Bank", body, current.id), onBack = { returnedToReview = true })
                    } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-body").fetchSemanticsNodes().isNotEmpty() }
            compose.clickAr(R.string.teaching_next)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("10SAR").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(candidateTag(body, "10SAR")).performScrollTo().performClick()
            compose.onNodeWithTag("pattern-bind").performClick()
            compose.clickAr(R.string.teaching_skip_field)
            compose.clickAr(R.string.teaching_skip_field)
            compose.onNodeWithTag(candidateTag(body, "2026-10-15 12:30")).performScrollTo().performClick()
            compose.onNodeWithTag("pattern-bind").performClick()
            compose.clickAr(R.string.pattern_preview)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_save)).fetchSemanticsNodes().isNotEmpty() }
            compose.clickAr(R.string.pattern_save)
            // From Review, saving opens the financial approval directly.
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-financial-save").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("pattern-financial-save").assertIsEnabled() }.isSuccess }
            compose.onNodeWithTag("pattern-financial-save").performScrollTo().performClick()
            // A taught pattern starts off; financial approval is the gate that switches it on.
            compose.waitUntil(10_000) { runBlocking { w.load().banks.single { it.bankId == "UI_BANK" }.currentPatterns.single().factContract != null } }
            assertTrue(w.load().banks.single { it.bankId == "UI_BANK" }.currentPatterns.single().enabled)
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-apply-current").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                println(compose.onRoot(useUnmergedTree = true).printToString())
                throw failure
            }
            compose.onNodeWithTag("pattern-apply-current").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(compose.ar(R.string.teaching_apply_posted)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(ReviewStatus.RESOLVED, c.storage.reviewRepository.getById(current.id)?.status)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            assertEquals(ReviewStatus.REQUIRED, c.storage.reviewRepository.findByRawSmsId("ui-other")!!.status)
            assertEquals(untouched, c.storage.parsedEventRepository.findByRawSmsId("ui-other"))
            compose.onNodeWithText(compose.ar(R.string.teaching_apply_posted)).assertExists()
            // Back is ignored while the post-apply pending refresh is still busy, so retry until it lands.
            val returnToReview = compose.onNodeWithContentDescription(compose.ar(R.string.teaching_return_review))
            compose.waitUntil(10_000) { if (!returnedToReview) returnToReview.performClick(); returnedToReview }
        } finally { c.close(); context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
        Unit
    }
}
