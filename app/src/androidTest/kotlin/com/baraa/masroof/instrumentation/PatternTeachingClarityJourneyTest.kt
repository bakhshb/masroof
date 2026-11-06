package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.MessagePatternsWorkflow
import com.baraa.masroof.application.patterns.PatternTeachingSource
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.patterns.MessagePatternsScreen
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.ar
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PatternTeachingClarityJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    // A bare click, not clickAr: this journey must fail when a button drops below the fold.
    private fun click(id: Int) = compose.onNodeWithText(compose.ar(id)).performClick()
    private val body = "Inward transfer (SARIE)\n100.00 SAR\nFrom TEST PERSON\nFrom BJAZ\nAccount\n02-09-2026 08:01\nRef. No. *8PJ7"
    private fun capture(name: String) {
        val size = InstrumentationRegistry.getArguments().getString("previewSize", "phone")
        ScreenshotArtifactWriter.capture(compose, "pattern-teaching", "$size-$name")
    }
    private fun select(value: String) {
        val start = body.indexOf(value)
        compose.onNodeWithTag("pattern-candidate-$start-${start + value.length}").performScrollTo().performClick()
        if (value == "100.00 SAR") capture("selected-value")
        compose.onNodeWithTag("pattern-bind").assertIsDisplayed().performClick()
    }

    @Test fun questionBackAndMissingAnswerRemainVisibleThroughTheWholeTeachingFlow() = runBlocking {
        val file = compose.activity.cacheDir.resolve("teaching-clarity-${java.util.UUID.randomUUID()}.json")
        val store = MessagePatternStore(file)
        val workflow = MessagePatternsWorkflow(store, DatabaseAccessGate())
        workflow.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank"), "STC Bank"))
        try {
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                    LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MasroofTheme { Surface {
                        MessagePatternsScreen(workflow, source = PatternTeachingSource("STC", "STC Bank", body), onBack = {})
                    } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-operation-TRANSFER_IN").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-operation-TRANSFER_IN").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-question").assertIsDisplayed()
            capture("message")
            click(R.string.teaching_next)
            select("100.00 SAR")
            compose.onNodeWithContentDescription(compose.ar(R.string.review_back)).assertIsDisplayed().performClick()
            compose.onNodeWithTag("pattern-question").assertTextContains("مبلغ العملية", substring = true)
            // Every field question carries a plain explanation with an SMS example.
            compose.onNodeWithTag("pattern-field-help").assertIsDisplayed()
            compose.onNode(hasAnyAncestor(hasTestTag("pattern-field-help")) and hasText("مثال", substring = true)).assertIsDisplayed()
            compose.onNodeWithTag("pattern-bind").performClick()
            repeat(5) { click(R.string.teaching_skip_field) }
            compose.onNodeWithTag("pattern-question").assertIsDisplayed().assertTextContains("بنك الطرف الآخر", substring = true)
            compose.onNodeWithTag("pattern-candidates").performTouchInput { swipeUp() }
            compose.onNodeWithTag("pattern-question").assertIsDisplayed()
            compose.onNodeWithText(compose.ar(R.string.teaching_skip_field)).assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.ar(R.string.review_back)).assertIsDisplayed()
            capture("bank-question")
            select("BJAZ")
            select("02-09-2026 08:01")
            click(R.string.teaching_skip_field)
            compose.onNodeWithTag("pattern-question").assertIsDisplayed()
            capture("review")
            click(R.string.teaching_try_another)
            compose.onNodeWithTag("pattern-sample").assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.ar(R.string.review_back)).performClick()
            compose.onNodeWithText(compose.ar(R.string.pattern_save)).assertIsDisplayed()
            assertTrue(store.load().banks.single().patterns.isEmpty())
            click(R.string.pattern_save)
            compose.waitUntil(10_000) { store.load().banks.single().patterns.isNotEmpty() }
            assertNull(store.load().banks.single().currentPatterns.single().factContract)
        } finally { file.delete() }
    }
}
