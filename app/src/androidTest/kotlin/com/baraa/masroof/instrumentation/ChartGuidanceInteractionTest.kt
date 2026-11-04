package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.application.dashboard.DailySpendingPoint
import com.baraa.masroof.application.dashboard.DailySpendingTrend
import com.baraa.masroof.application.dashboard.SignedMoneyAmount
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.presentation.common.MasroofGuidanceLabel
import com.baraa.masroof.presentation.common.MasroofNavCard
import com.baraa.masroof.presentation.common.MasroofText
import com.baraa.masroof.presentation.dashboard.MoneyUiFormatter
import com.baraa.masroof.presentation.dashboard.SpendingTrendSection
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the actual Home chart component, using explicit synthetic daily totals. */
class ChartGuidanceInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openHelp_staysOpenWhenARefreshRewritesItsText() {
        val message = mutableStateOf("Updating")
        compose.setContent { MasroofTheme { Surface {
            com.baraa.masroof.presentation.common.MasroofGuidanceButton("Why", message.value, Modifier.testTag("help"))
        } } }
        compose.onNodeWithTag("help").performClick()
        compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
        compose.runOnIdle { message.value = "Ready" }
        compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
        compose.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun helpButton_opensFromATapBesideTheIconWithoutGrowingItsSlot() {
        compose.setContent { MasroofTheme { Surface {
            androidx.compose.foundation.layout.Box(Modifier.padding(MasroofSpacing.minimumTouchTarget)) {
                com.baraa.masroof.presentation.common.MasroofGuidanceButton("Why", "Because", Modifier.testTag("help"))
            }
        } } }
        val help = compose.onNodeWithTag("help")
            .assertWidthIsEqualTo(com.baraa.masroof.ui.theme.MasroofGuidanceStyle.iconSize)
            .assertHeightIsEqualTo(com.baraa.masroof.ui.theme.MasroofGuidanceStyle.iconSize)
        // Just outside the drawn icon, still inside the app's minimum touch target around it.
        val outsideIcon = (MasroofSpacing.minimumTouchTarget - com.baraa.masroof.ui.theme.MasroofGuidanceStyle.iconSize) / 2 -
            MasroofSpacing.borderWidth
        help.performTouchInput { click(Offset(-outsideIcon.toPx(), centerY)) }
        compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
    }

    @Test
    fun chartAndHelp_workInBothLanguagesAndThemes_withCorrectTouchDirection() {
        val language = mutableStateOf("ar")
        val dark = mutableStateOf(false)
        val start = LocalDate.parse("2026-10-01")
        val points = listOf("15", "120", "30", "80", "45").mapIndexed { index, amount ->
            DailySpendingPoint(start.plusDays(index.toLong()), SignedMoneyAmount.of(Money.of(amount, Currency.SAR)))
        }
        val trend = DailySpendingTrend(points, SignedMoneyAmount.of(Money.of("58", Currency.SAR)))
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().testTag("chart-demo-root").verticalScroll(rememberScrollState())
                            .padding(MasroofSpacing.screenHorizontal)) {
                            MasroofText(stringResource(R.string.approved_nav_home), style = MaterialTheme.typography.headlineSmall)
                            SpendingTrendSection(trend, stringResource(R.string.approved_all_expenses),
                                explanation = stringResource(R.string.approved_expense_basis))
                            com.baraa.masroof.presentation.common.MasroofAverageBand(MoneyUiFormatter.format(trend.averageDailySpending, language.value), "",
                                stringResource(R.string.approved_average_basis, trend.points.size))
                        }
                    }
                }
            }
        }
        listOf("ar" to false, "ar" to true, "en" to false, "en" to true).forEach { (tag, isDark) ->
            compose.runOnIdle { language.value = tag; dark.value = isDark }
            compose.waitForIdle()
            val chart = compose.onNodeWithTag("daily-expenses-chart")
            fun select(fraction: Float, amount: String) {
                val density = compose.activity.resources.displayMetrics.density
                chart.performTouchInput {
                    val left = com.baraa.masroof.ui.theme.MasroofReference.chartLeft.value * density
                    val right = com.baraa.masroof.ui.theme.MasroofReference.chartRight.value * density
                    click(Offset(left + (width-left-right) * fraction, height * 0.4f))
                }
                val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
                val expected = MoneyUiFormatter.format(Money.of(amount, Currency.SAR), tag)
                assertTrue("Touched date must show its own amount: $description", description.contains(expected))
            }
            select(if (tag == "ar") 0.98f else 0.02f, "15")
            select(if (tag == "ar") 0.02f else 0.98f, "45")
            select(if (tag == "ar") 0.75f else 0.25f, "120")
            val appearance = if (isDark) "dark" else "light"
            capture("chart-selected-$tag-$appearance")
            val context = AppLocaleContext.wrap(compose.activity, tag)
            compose.onNodeWithText(context.getString(R.string.chart_previous_point)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.chart_next_point)).assertDoesNotExist()
            val message = context.getString(R.string.approved_expense_basis)
            val label = context.getString(R.string.approved_all_expenses)
            val about = context.getString(R.string.guidance_about, label)
            compose.onNodeWithText(message).assertDoesNotExist()
            compose.onNodeWithContentDescription(about).performClick()
            compose.onNodeWithTag("guidance-popup").assertIsDisplayed()
            compose.onNodeWithText(message).assertIsDisplayed()
            capture("chart-guidance-$tag-$appearance")
            pressDeviceBack(compose)
            compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
            compose.onNodeWithContentDescription(about).performClick()
            pressOutsidePopup()
            compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
            val averageLabel = context.getString(R.string.approved_daily_average)
            compose.onNodeWithContentDescription(context.getString(R.string.guidance_about, averageLabel)).performClick()
            compose.onNodeWithText(context.getString(R.string.approved_average_basis, 5)).assertIsDisplayed()
            pressDeviceBack(compose)
        }
    }

    @Test
    fun pressingExplanation_doesNotTriggerItsParentAction() {
        var actions = 0
        compose.setContent {
            MasroofTheme {
                MasroofNavCard(onClick = { actions++ }) {
                    MasroofGuidanceLabel("Action", "Contextual explanation")
                }
            }
        }
        compose.onNode(hasContentDescription("Action", substring = true)).performClick()
        compose.onNodeWithText("Contextual explanation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions) }
        pressDeviceBack(compose)
        compose.onNode(hasText("Action") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    private fun capture(name: String) = ScreenshotArtifactWriter.capture(compose, "masroof-chart-guidance", name)

    private fun pressOutsidePopup() {
        // Inject through Android's window manager: a Popup lives in a separate window.
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val bottom = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        val x = location[0] + decor.width / 2f
        val y = location[1] + decor.height - bottom - 24f * decor.resources.displayMetrics.density
        val time = android.os.SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(time, time + if (action == android.view.MotionEvent.ACTION_UP) 30 else 0, action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        compose.waitForIdle()
    }
}
