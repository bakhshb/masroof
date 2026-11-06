package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.MessagePatternsWorkflow
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.patterns.MessagePatternsScreen
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.ar
import com.baraa.masroof.testsupport.clickAr
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessagePatternsJourneyTest {
    // The prefilled selection repeats the value as text, so a chip is found by its span.
    private fun candidateTag(body: String, value: String) = body.indexOf(value).let { "pattern-candidate-$it-${it + value.length}" }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun teachPreviewSaveAndDisableInArabic() = runBlocking {
        val file = compose.activity.cacheDir.resolve("synthetic-pattern-journey.json")
        file.delete()
        val store = MessagePatternStore(file)
        val workflow = MessagePatternsWorkflow(store, DatabaseAccessGate())
        workflow.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank"), "بنك التجربة"))
        val dark = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) { Surface { MessagePatternsScreen(workflow, "STC", onBack = {}) } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_teach)).fetchSemanticsNodes().isNotEmpty() }
        capture("pattern-bank")
        compose.clickAr(R.string.pattern_teach)
        compose.onNodeWithTag("pattern-body").performScrollTo().performTextInput("Payment\nAmount:10SAR\nTo:SHOP")
        compose.clickAr(R.string.teaching_next)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("10SAR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(candidateTag("Payment\nAmount:10SAR\nTo:SHOP", "10SAR")).performScrollTo().performClick()
        compose.onNodeWithTag("pattern-bind").performClick()
        compose.clickAr(R.string.pattern_preview)
        compose.clickAr(R.string.teaching_try_another)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-sample").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.load().banks.single().patterns.isEmpty())
        compose.onNodeWithTag("pattern-sample").performScrollTo().performTextInput("Payment\nAmount:250SAR\nTo:SHOP")
        compose.clickAr(R.string.pattern_test)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("مبلغ العملية: 250SAR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("مبلغ العملية: 250SAR").performScrollTo().assertIsDisplayed()
        capture("pattern-preview")
        compose.onNodeWithContentDescription(compose.ar(R.string.review_back)).performClick()
        compose.clickAr(R.string.pattern_save)
        compose.waitUntil(10_000) { store.load().banks.single().patterns.size == 1 }
        val patternId = store.load().banks.single().currentPatterns.single().id
        // A taught pattern starts off until the user switches it on.
        assertFalse(store.load().banks.single().currentPatterns.single().enabled)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-toggle-STC-$patternId").fetchSemanticsNodes().isNotEmpty() }
        capture("pattern-disabled")
        compose.runOnIdle { dark.value = true }
        capture("pattern-disabled-dark")
        compose.onNodeWithTag("pattern-toggle-STC-$patternId").performScrollTo().performClick()
        compose.waitUntil(10_000) { store.load().banks.single().patterns.size == 2 }
        assertTrue(store.load().banks.single().currentPatterns.single().enabled)
        compose.onNodeWithContentDescription(compose.ar(R.string.teaching_saved_patterns)).performClick()
        compose.clickAr(R.string.pattern_teach)
        compose.onNodeWithTag("pattern-body").performScrollTo().performTextInput("Payment\nAmount:30SAR")
        compose.clickAr(R.string.teaching_next)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("30SAR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pattern-body").assertExists()
        // Exercise the editor's Back action directly; Gboard may consume a hardware Back even after hiding.
        compose.onNodeWithContentDescription(compose.ar(R.string.review_back)).performClick()
        compose.onNodeWithText(compose.ar(R.string.teaching_next)).assertExists()
        compose.onNodeWithContentDescription(compose.ar(R.string.review_back)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_teach)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, store.load().banks.single().patterns.size)
        file.delete()
        Unit
    }

    @Test fun reviewSourcePrefillsImmutableTextAndReturnsWithoutResolvingReview() = runBlocking {
        val file = compose.activity.cacheDir.resolve("synthetic-review-pattern-journey.json")
        file.delete()
        val store = MessagePatternStore(file)
        val workflow = MessagePatternsWorkflow(store, DatabaseAccessGate())
        val source = com.baraa.masroof.application.patterns.PatternTeachingSource(null, "STC Bank", "Payment\nAmount:73SAR")
        val returned = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme { Surface {
                    if (returned.value) androidx.compose.material3.Text("Review remains pending")
                    else MessagePatternsScreen(workflow, source = source, onBack = { returned.value = true })
                } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_add_bank)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("STC Bank").assertRejectsTextEdits("STC Bank")
        compose.onNodeWithText(compose.ar(R.string.pattern_bank_name)).performTextInput("بنك التجربة")
        compose.clickAr(R.string.pattern_add_bank)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pattern-body").assertRejectsTextEdits(source.body)
        compose.clickAr(R.string.teaching_next)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("73SAR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(candidateTag(source.body, "73SAR")).performScrollTo().performClick()
        compose.onNodeWithTag("pattern-bind").performClick()
        compose.clickAr(R.string.pattern_preview)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_save)).fetchSemanticsNodes().isNotEmpty() }
        capture("pattern-from-review")
        compose.clickAr(R.string.pattern_save)
        compose.onNodeWithContentDescription(compose.ar(R.string.teaching_return_review)).performClick()
        compose.waitUntil(10_000) { returned.value }
        assertEquals(listOf("STC Bank"), store.load().banks.single().senders)
        assertEquals(1, store.load().banks.single().patterns.size)
        assertEquals("Payment\nAmount:73SAR", source.body)
        file.delete()
        Unit
    }

    @Test fun copyFromReviewSurvivesRotationAndBackReturnsToThePattern() = runBlocking {
        val file = compose.activity.cacheDir.resolve("synthetic-review-copy-restore.json")
        file.delete()
        val store = MessagePatternStore(file)
        val workflow = MessagePatternsWorkflow(store, DatabaseAccessGate())
        val source = com.baraa.masroof.application.patterns.PatternTeachingSource(null, "STC Bank", "Payment\nAmount:73SAR")
        val returned = androidx.compose.runtime.mutableStateOf(false)
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme { Surface {
                    if (returned.value) androidx.compose.material3.Text("Review remains pending")
                    else MessagePatternsScreen(workflow, source = source, onBack = { returned.value = true })
                } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_add_bank)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.ar(R.string.pattern_bank_name)).performTextInput("بنك التجربة")
        compose.clickAr(R.string.pattern_add_bank)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-body").fetchSemanticsNodes().isNotEmpty() }
        compose.clickAr(R.string.teaching_next)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("73SAR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(candidateTag(source.body, "73SAR")).performScrollTo().performClick()
        compose.onNodeWithTag("pattern-bind").performClick()
        compose.clickAr(R.string.pattern_preview)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.ar(R.string.pattern_save)).fetchSemanticsNodes().isNotEmpty() }
        compose.clickAr(R.string.pattern_save)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-copy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pattern-copy").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-body").fetchSemanticsNodes().isNotEmpty() }
        // A copy is a teaching step inside the pattern; after rotation, Back must still return to that pattern, not leave to Review.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-body").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { returned.value || compose.onAllNodesWithTag("pattern-copy").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("Back from a restored copy left the pattern screen", returned.value)
        file.delete()
        Unit
    }

    @Test fun bankPagePreviewsPendingMessagesWithoutRemovingReviews() = runBlocking {
        val context = compose.activity
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.baraa.masroof.data.room.MasroofDatabase::class.java)
            .allowMainThreadQueries().build()
        val file = context.cacheDir.resolve("synthetic-pending-patterns.json")
        file.delete()
        try {
            val store = MessagePatternStore(file)
            val gate = DatabaseAccessGate()
            val raw = com.baraa.masroof.data.repository.RoomRawSmsRepository(db.rawSmsDao())
            val reviews = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
            val ingestion = com.baraa.masroof.application.review.IngestionReviewService(reviews,
                com.baraa.masroof.sms.time.InstantClock { java.time.Instant.parse("2026-08-01T00:00:00Z") })
            val body = "Payment\nAmount:10SAR"
            val pattern = com.baraa.masroof.engine.bank.patterns.SavedMessagePattern.fromChoices("purchase", "STC", "STC Bank", "PURCHASE", body,
                listOf(com.baraa.masroof.engine.bank.patterns.FieldChoice("amount", body.indexOf("10SAR"), body.length)))
            store.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank"), "بنك التجربة", listOf(pattern)))
            for ((index, amount) in listOf("10", "250").withIndex()) {
                val text = body.replace("10SAR", "${amount}SAR")
                raw.insertIfAbsent(com.baraa.masroof.domain.model.RawSms("pending-$index", "STC Bank", text,
                    java.time.Instant.parse("2026-08-01T00:00:00Z"), "pending-$index", com.baraa.masroof.sms.hash.SmsBodyHasher.sha256Hex(text)))
                ingestion.requireReview("pending-$index", com.baraa.masroof.application.review.IngestionReviewService.REASON_UNSUPPORTED_FORMAT)
            }
            val workflow = MessagePatternsWorkflow(store, gate, pendingPreview = com.baraa.masroof.application.patterns.PendingPatternsWorkflow(
                store, gate, reviews, raw, com.baraa.masroof.data.repository.RoomParsedEventRepository(db.parsedEventDao()),
                com.baraa.masroof.data.repository.RoomUserCorrectionRepository(db.userCorrectionDao()),
                com.baraa.masroof.data.repository.RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao()),
                com.baraa.masroof.bank.BankSmsRegistry(emptyList())))
            var openedReview: String? = null
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                    MasroofTheme { Surface { MessagePatternsScreen(workflow, "STC", onOpenReview = { openedReview = it }, onBack = {}) } }
                }
            }
            compose.clickAr(R.string.pattern_pending_title)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("مبلغ العملية: 250SAR").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("مبلغ العملية: 250SAR").performScrollTo().assertIsDisplayed()
            capture("pattern-pending-results")
            compose.onAllNodesWithText(compose.ar(R.string.pattern_pending_open))[0].performScrollTo().performClick()
            assertTrue(reviews.getById(requireNotNull(openedReview))!!.rawSmsId in listOf("pending-0", "pending-1"))
            assertEquals(2, reviews.listRequired().size)
            compose.onNodeWithContentDescription(compose.ar(R.string.settings_back)).performClick()
            compose.onNodeWithTag("pattern-open-STC-purchase").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-toggle-STC-purchase").performScrollTo().performClick()
            compose.waitUntil(10_000) { !store.load().banks.single().currentPatterns.single().enabled }
            // Back is ignored while the screen is still busy saving the switch; retry until the bank page shows.
            compose.waitUntil(10_000) {
                val onBankPage = compose.onAllNodesWithText(compose.ar(R.string.pattern_pending_title)).fetchSemanticsNodes().isNotEmpty()
                if (!onBankPage) compose.onAllNodesWithContentDescription(compose.ar(R.string.teaching_saved_patterns)).fetchSemanticsNodes()
                    .firstOrNull()?.let { compose.onNodeWithContentDescription(compose.ar(R.string.teaching_saved_patterns)).performClick() }
                onBankPage
            }
            compose.clickAr(R.string.pattern_pending_title)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("غير مطابق: 2").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, reviews.listRequired().size)
        } finally { db.close(); file.delete() }
        Unit
    }

    @Test fun stcBundledPatternsAppearWithoutTeachingAndKeepUserDisable() = runBlocking {
        val file = compose.activity.cacheDir.resolve("synthetic-stc-bundle.json")
        file.delete()
        try {
            val banks = com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.decode(
                compose.activity.assets.open("bank-patterns/BANK_STC.patterns.json").bufferedReader().use { it.readText() }).banks
            val workflow = MessagePatternsWorkflow(MessagePatternStore(file), DatabaseAccessGate(), bundledBanks = banks)
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                    MasroofTheme { Surface { MessagePatternsScreen(workflow, "BANK_STC", onBack = {}) } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-open-BANK_STC-stc-internal-in").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(8, workflow.load().banks.single().currentPatterns.size)
            capture("stc-ready-patterns")
            compose.onNodeWithTag("pattern-open-BANK_STC-stc-internal-in").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-toggle-BANK_STC-stc-internal-in").performScrollTo().performClick()
            compose.waitUntil(10_000) { MessagePatternStore(file).load().generation == 2L }
            val reopened = MessagePatternsWorkflow(MessagePatternStore(file), DatabaseAccessGate(), bundledBanks = banks).load()
            assertEquals(2L, reopened.generation)
            assertEquals(1, reopened.banks.single().currentPatterns.count { !it.enabled })
        } finally { file.delete() }
        Unit
    }

    private fun capture(name: String) = ScreenshotArtifactWriter.capture(compose, "pattern-journey", name)

    /** Read-only text either has no SetText action (Compose 1.8+) or ignores it; the text must stay. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertRejectsTextEdits(original: String) {
        if (androidx.compose.ui.semantics.SemanticsActions.SetText in fetchSemanticsNode().config) {
            performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) {
                it(androidx.compose.ui.text.AnnotatedString("changed"))
            }
        }
        assertTextContains(original)
    }
}
