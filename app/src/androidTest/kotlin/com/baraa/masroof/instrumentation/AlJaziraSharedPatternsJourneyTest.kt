package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.R
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.patterns.MessagePatternsScreen
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.testsupport.ingest
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AlJaziraSharedPatternsJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bankId = Bank.BANK_ALJAZIRA.id
    private fun raw(id: String): RawSms {
        val text = "Purchase\nAmount:10 SAR\nFrom account:4321\nMerchant:SHOP\nOn:2026-10-15 12:30"
        return RawSms(id, "Bank-AlJazira-AD", text, Instant.parse("2026-10-15T10:00:00Z"), id, SmsBodyHasher.sha256Hex(text))
    }

    @Test fun bundledAlJaziraCanBeDisabledAndRestoredWithoutTeachingOrChangingPostedEvidence() = runBlocking {
        val context = TestStorageContext(compose.activity, "aljazira-pattern-${java.util.UUID.randomUUID()}")
        val c = AppContainer(context)
        try {
            c.messagePatternsWorkflow.confirmPatternAccount(bankId, "4321")
            c.processRawSmsUseCase.ingest(raw("posted"))
            val posted = c.parsedEventRepository.findByRawSmsId("posted")
            assertEquals(1, c.financialTransactionRepository.listAll().size)
            val dark = mutableStateOf(false)
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity) {
                    MasroofTheme(darkTheme = dark.value) { Surface { MessagePatternsScreen(c.messagePatternsWorkflow, bankId, onBack = {}) } }
                }
            }
            val toggle = "pattern-toggle-$bankId-bank.purchase"
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-open-$bankId-bank.purchase").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-open-$bankId-bank.purchase").performScrollTo().performClick()
            compose.onNodeWithTag(toggle).performScrollTo()
            ScreenshotArtifactWriter.capture(compose, "pattern-aljazira", "ready-light")
            compose.runOnIdle { dark.value = true }
            ScreenshotArtifactWriter.capture(compose, "pattern-aljazira", "ready-dark")
            compose.onNodeWithTag(toggle).performClick()
            compose.waitUntil(10_000) { runBlocking { !c.messagePatternsWorkflow.load().banks.single { it.bankId == bankId }.currentPatterns.single { it.id == "bank.purchase" }.enabled } }
            c.processRawSmsUseCase.ingest(raw("pending"))
            assertEquals(1, c.financialTransactionRepository.listAll().size)
            // The toggle's save finishes before the screen leaves its busy state; Advanced is disabled until then.
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("pattern-advanced") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-advanced").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-restore-$bankId-bank.purchase-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-restore-$bankId-bank.purchase-1").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { c.messagePatternsWorkflow.load().banks.single { it.bankId == bankId }.currentPatterns.single { it.id == "bank.purchase" }.revision == 3 } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(toggle).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(AppLocaleContext.wrap(compose.activity, "ar").getString(R.string.teaching_saved_patterns)).performClick()
            compose.onNodeWithText(AppLocaleContext.wrap(compose.activity, "ar").getString(R.string.pattern_pending_title)).performScrollTo().performClick()
            val apply = AppLocaleContext.wrap(compose.activity, "ar").getString(R.string.pattern_apply_pending)
            compose.onNodeWithText(apply).performScrollTo().performClick()
            compose.waitUntil(15_000) { runBlocking {
                c.financialTransactionRepository.listAll().size == 2 && c.reviewRepository.findByRawSmsId("pending")?.status == ReviewStatus.RESOLVED
            } }
            assertEquals(posted, c.parsedEventRepository.findByRawSmsId("posted"))
        } finally { c.close(); context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
    }
}
