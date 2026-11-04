package com.baraa.masroof.instrumentation

import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.MainActivity
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.R
import com.baraa.masroof.application.locale.AppLocalePreferences
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.ui.theme.MasroofSheetTokens
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Launches real MainActivity, including attachBaseContext, startup and production navigation. */
class FoldModalWindowJourneyTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val seed = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val args = InstrumentationRegistry.getArguments()
                val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MasroofApplication
                app.getSharedPreferences(AppLocalePreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(AppLocalePreferences.KEY_LANGUAGE_TAG, args.getString("language") ?: "ar").commit()
                app.container.themePreferencesRepository.setThemeMode(if (args.getString("dark") == "true") ThemeMode.DARK else ThemeMode.LIGHT)
                JourneySeeder.prepare("dashboard_showsPersistedTransactionAfterReload")
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                fun command(value: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand(value)).use { it.readBytes().toString(Charsets.UTF_8) }
                val initialOverride = command("wm size").lineSequence()
                    .firstOrNull { it.startsWith("Override size:") }?.substringAfter(':')?.trim() ?: "reset"
                try { base.evaluate() } finally { command("wm size $initialOverride") }
            }
        }
    }
    @get:Rule val rule: TestRule = RuleChain.outerRule(seed).around(compose)
    private val language get() = InstrumentationRegistry.getArguments().getString("language") ?: "ar"
    private val appearance get() = if (InstrumentationRegistry.getArguments().getString("dark") == "true") "dark" else "light"
    private fun text(id: Int) = AppLocaleContext.wrap(compose.activity, language).getString(id)
    private fun exists(tag: String) = runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    private fun awaitShell() {
        compose.waitUntil(30_000) { runCatching { compose.onAllNodesWithContentDescription(text(R.string.dashboard_open_settings)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun clickText(value: String, withinList: Boolean = false) {
        val target = hasText(value) and hasClickAction() and
            if (withinList) hasAnyAncestor(hasScrollToIndexAction()) else SemanticsMatcher("any container") { true }
        try { compose.waitUntil(20_000) {
            if (compose.onAllNodes(target).fetchSemanticsNodes().isEmpty()) {
                // English labels can move the action beyond the lazy viewport on a folded screen.
                runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(value)) }
            }
            compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
        } } catch (failure: Throwable) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                println("CLICK '$value' root=$index ${compose.onAllNodes(isRoot())[index].printToString()}")
            }
            throw failure
        }
        val node = compose.onAllNodes(target).onFirst()
        runCatching { node.performScrollTo() }
        // Navigation is setup for the fold assertions. Invoke the identified action,
        // not a coordinate that can move while the destination finishes loading.
        // The sheet footer below still exercises actual touch after every resize.
        node.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    private fun openSheet(kind: String) {
        if (kind == "chart" || kind == "layout") {
            val tag = if (kind == "chart") "chart-customize" else "dashboard-customize"
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
        when (kind) {
            "chart", "layout" -> {
                val tag = if (kind == "chart") "chart-customize" else "dashboard-customize"
                compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsEnabled()
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            "filter" -> {
                clickText(text(R.string.reference_all_types))
                clickText(text(R.string.transaction_list_filter_sheet_title))
            }
            else -> clickText(text(R.string.transaction_detail_edit_category))
        }
        compose.waitUntil(20_000) { exists("sheet-overlay") }
        compose.waitForIdle()
    }
    private var requestedWindow: IntSize? = null
    private val controlTags = listOf("sheet-actions", "sheet-close", "sheet-apply", "sheet-cancel")
    private data class ActivityGeometry(val activity: MainActivity, val size: IntSize, val contentHeight: Int,
        val metricsWidth: Int, val density: Float)
    private data class ModalGeometry(val activity: ActivityGeometry, val overlay: Rect, val scrim: Rect,
        val sheet: Rect, val controls: Map<String, Rect>)

    private fun currentGeometry(): ActivityGeometry {
        val activity = compose.activity
        return ActivityGeometry(activity, IntSize(activity.window.decorView.width, activity.window.decorView.height),
            activity.findViewById<android.view.View>(android.R.id.content).height,
            activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.density)
    }
    private fun activityGeometry() = compose.runOnIdle { currentGeometry() }
    private fun resize(size: String, overlayOpen: Boolean = true) {
        val dimensions = size.split('x').map(String::toInt)
        requestedWindow = IntSize(dimensions[0], dimensions[1])
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm size $size")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        // Poll without runOnIdle: Espresso idle has no timeout and may not settle mid-resize.
        compose.waitUntil(30_000) {
            compose.runOnUiThread { currentGeometry() }.size == requestedWindow && (!overlayOpen || exists("sheet-overlay"))
        }
        compose.waitForIdle()
    }
    private fun measureModal(): ModalGeometry {
        // Activity recreation, system insets and the Dialog are laid out independently.
        // Bracket semantic reads with UI-thread window reads instead of mixing frames.
        val before = activityGeometry()
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val measured = ModalGeometry(before, bounds("sheet-overlay"), bounds("sheet-scrim"),
            bounds("masroof-bottom-sheet"), controlTags.associateWith(::bounds))
        assertEquals("Activity geometry changed while measuring the modal", before, activityGeometry())
        return measured
    }
    private fun assertGeometry(value: ModalGeometry) {
        val activity = value.activity
        val overlay = value.overlay
        val scrim = value.scrim
        val sheet = value.sheet
        requestedWindow?.let { assertEquals("Both requested window dimensions must be applied", it, activity.size) }
        assertTrue("Activity content must be laid out: $activity", activity.size.width > 0 && activity.contentHeight > 0)
        val width = activity.size.width.toFloat()
        assertEquals("Locale-wrapped Activity metrics must follow its current window", width, activity.metricsWidth.toFloat(), 2f)
        assertEquals("Overlay must span actual Activity window: $value", width, overlay.width, 2f)
        assertTrue("Overlay must cover all Activity content: $value", overlay.height >= activity.contentHeight - 2f)
        assertTrue("Overlay must fit within the current Android window: $value", overlay.height <= activity.size.height + 2f)
        assertEquals(overlay.left, scrim.left, 1f); assertEquals(overlay.right, scrim.right, 1f)
        assertEquals(overlay.top, scrim.top, 1f); assertEquals(overlay.bottom, scrim.bottom, 1f)
        assertTrue("Sheet is not centered in the full window: $value", abs(sheet.center.x - overlay.center.x) <= 2f)
        assertTrue(sheet.width <= MasroofSheetTokens.maxWidth.value * activity.density + 2f)
        value.controls.forEach { (tag, child) ->
            assertTrue("$tag must fit completely inside the current window: $child / $overlay",
                child.width > 0 && child.height > 0 && child.left >= overlay.left && child.right <= overlay.right &&
                    child.top >= overlay.top && child.bottom <= overlay.bottom)
        }
    }
    private fun verify(kind: String, profile: String) {
        var previous: ModalGeometry? = null
        var lastMeasurement: ModalGeometry? = null
        var lastFailure: Throwable? = null
        try { compose.waitUntil(30_000) {
            runCatching {
                val measured = measureModal()
                lastMeasurement = measured
                assertGeometry(measured)
                // Require a second identical complete measurement. Passing an old
                // width while height/insets are still changing is not convergence.
                val stable = measured == previous
                previous = measured
                stable
            }.getOrElse { failure -> lastFailure = failure; previous = null; false }
        } } catch (failure: Throwable) {
            println("WINDOW kind=$kind profile=$profile requested=$requestedWindow last=$lastMeasurement")
            lastFailure?.let { failure.addSuppressed(it); println("WINDOW last failure: $it") }
            // A failing screenshot must not hide the geometry failure itself.
            runCatching { capture("$kind-$language-$appearance-$profile-failure") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        // All geometry assertions above use the same immutable measurement.
        // A permanently undersized overlay still times out with its actual bounds.
        if (profile == "folded" || profile == "reopened") {
            compose.onNodeWithTag("sheet-actions").assertIsDisplayed()
            compose.onNodeWithTag("sheet-close").assertIsDisplayed()
            compose.onNodeWithTag("sheet-apply").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.sheet_cancel)).assertIsDisplayed()
        }
        capture("$kind-$language-$appearance-$profile")
    }
    private fun capture(name: String) = ScreenshotArtifactWriter.capture(compose, "fold-modal-window", name)
    private fun journey(kind: String) {
        awaitShell()
        if (kind == "filter" || kind == "reclassify") {
            clickText(text(R.string.approved_nav_transactions))
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(text(R.string.transaction_list_title)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (kind == "reclassify") {
            // The dashboard also contains this merchant. Wait for the destination,
            // then select the clickable transaction row inside its lazy list.
            clickText(JourneyFixtures.DASHBOARD_MERCHANT, withinList = true)
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(text(R.string.transaction_detail_title)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        openSheet(kind)
        verify(kind, "folded")
        resize("1600x2000"); verify(kind, "unfolded")
        resize("640x1440"); verify(kind, "refolded")
        // Verify the footer really receives a touch after folding, not just a semantics action.
        compose.onNodeWithTag("sheet-cancel").performTouchInput { click(center) }
        compose.waitUntil(10_000) { !exists("sheet-overlay") }
        openSheet(kind)
        resize("1200x960"); verify(kind, "short")
        // Real Activity recreation retains the open sheet, including its draft ownership.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { exists("sheet-overlay") }
        verify(kind, "recreated")
        pressDeviceBack(compose); compose.waitUntil(10_000) { !exists("sheet-overlay") }
        // Re-open after returning to the folded window, without a modal present.
        resize("640x1440", overlayOpen = false)
        openSheet(kind); verify(kind, "reopened")
        compose.onNodeWithTag("sheet-cancel").performTouchInput { click(center) }
        compose.waitUntil(10_000) { !exists("sheet-overlay") }
    }
    @Test fun chart_handlesFoldingThroughRealMainActivity() = journey("chart")
    @Test fun layout_handlesFoldingThroughRealMainActivity() = journey("layout")
    @Test fun filter_handlesFoldingThroughRealMainActivity() = journey("filter")
    @Test fun reclassify_handlesFoldingThroughRealMainActivity() = journey("reclassify")
}
