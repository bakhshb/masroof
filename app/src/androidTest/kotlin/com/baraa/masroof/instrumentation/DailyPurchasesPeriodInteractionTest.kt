package com.baraa.masroof.instrumentation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.DailySpendingTrend
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production components with explicit synthetic facts. Device back is injected, not a UI click. */
class DailyPurchasesPeriodInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun approvedChanges_workInBothLanguagesAndThemes() {
        val language = mutableStateOf("ar")
        val dark = mutableStateOf(false)
        val commitments = mutableStateOf(false)
        var previous = 0
        var next = 0
        var customizations = 0
        val financial = FidelityFixtures.financial()
        val trends = financial.spendingTrends!!
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        key(language.value, dark.value) {
                            if (commitments.value) CommitmentsSummaryScreen(financial, onBack = { commitments.value = false }, onOpenCommitment = {})
                            else Column(Modifier.fillMaxSize().testTag("purchases-home").verticalScroll(rememberScrollState())
                                .padding(MasroofSpacing.screenHorizontal)) {
                                MasroofText(androidx.compose.ui.res.stringResource(R.string.approved_nav_home), style = MaterialTheme.typography.headlineSmall)
                                MasroofPeriodPill(FinancialPeriodUiFormatter.formatRange(localized, financial.period!!, compact = true),
                                    onPrevious = { previous++ }, onNext = { next++ }, onCustomize = { customizations++ },
                                    customizeLabel = localized.getString(R.string.dashboard_customize))
                                HomeSpendingTrendSection(trends)
                            }
                        }
                    }
                }
            }
        }
        for (lang in listOf("ar", "en")) for (night in listOf(false, true)) {
            compose.runOnIdle { language.value = lang; dark.value = night }
            compose.waitForIdle()
            val context = AppLocaleContext.wrap(compose.activity, lang)
            val appearance = if (night) "dark" else "light"
            compose.onNodeWithTag("dashboard-customize").assertIsDisplayed().performClick()
            compose.onNodeWithTag("period-previous").performClick()
            compose.onNodeWithTag("period-next").performClick()
            val left = compose.onNodeWithTag("period-previous").fetchSemanticsNode().boundsInRoot.center.x
            val right = compose.onNodeWithTag("period-next").fetchSemanticsNode().boundsInRoot.center.x
            assertEquals(lang == "ar", left > right)
            compose.onNodeWithTag("home-chart-scope-0").assertIsSelected()
            selectDay(trends.purchases, lang, first = true)
            assertAverageLine(trends.purchases, night)
            capture("purchases-$lang-$appearance")
            val purchasesHelp = context.getString(R.string.dashboard_purchases_trend_basis)
            compose.onNodeWithContentDescription(context.getString(R.string.guidance_about, context.getString(R.string.dashboard_daily_purchases))).performClick()
            compose.onNodeWithText(purchasesHelp).assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("guidance-popup").assertDoesNotExist()
            compose.onNodeWithTag("home-chart-scope-1").performClick().assertIsSelected()
            selectDay(trends.overall, lang, first = false)
            assertAverageLine(trends.overall, night)
            capture("all-expenses-$lang-$appearance")
            compose.onNodeWithContentDescription(context.getString(R.string.guidance_about, context.getString(R.string.approved_all_expenses))).performClick()
            compose.onNodeWithText(context.getString(R.string.dashboard_all_trend_basis)).assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("home-chart-scope-0").performClick().assertIsSelected()
            compose.runOnIdle { commitments.value = true }
            Espresso.pressBack()
            compose.onNodeWithTag("purchases-home").assertIsDisplayed()
            compose.runOnIdle { commitments.value = true }
            compose.onNodeWithContentDescription(context.getString(R.string.dashboard_flow_detail_back)).performClick()
            compose.onNodeWithTag("purchases-home").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(4, previous); assertEquals(4, next); assertEquals(4, customizations) }
    }

    @Test
    fun madaDetail_showsAttributedCashOnItsOwnDay_inBothLanguagesAndThemes() {
        val language = mutableStateOf("ar")
        val dark = mutableStateOf(false)
        val financial = FidelityFixtures.financial()
        val debit = financial.creditFacilities!!.debitCards.first()
        val trend = financial.spendingTrends!!.byCard.getValue(CardOwnershipKey.of(debit))
        val cashIndex = trend.points.indexOfFirst { it.date == java.time.LocalDate.parse("2026-10-06") }
        assertEquals("400.00", trend.points[cashIndex].spending.amount.toPlainString())
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        key(language.value, dark.value) {
                            DebitCardDetailScreen(debit, financial, debit.network, {}, {})
                        }
                    }
                }
            }
        }
        for (lang in listOf("ar", "en")) for (night in listOf(false, true)) {
            compose.runOnIdle { language.value = lang; dark.value = night }
            compose.waitForIdle()
            val chart = compose.onNodeWithTag("daily-expenses-chart").performScrollTo()
            chart.performTouchInput {
                val density = compose.activity.resources.displayMetrics.density
                val left = MasroofReference.chartLeft.value * density
                val right = MasroofReference.chartRight.value * density
                val fraction = cashIndex.toFloat() / trend.points.lastIndex
                click(Offset(left + (width - left - right) * if (lang == "ar") 1 - fraction else fraction, height * 0.4f))
            }
            val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue(description, description.contains(MoneyUiFormatter.format(trend.points[cashIndex].spending, lang)))
            capture("mada-cash-$lang-${if (night) "dark" else "light"}")
        }
    }

    @Test
    fun futurePeriod_rendersAnEmptyStateAcrossHomeDailyAndMada() {
        val language = mutableStateOf("ar")
        val dark = mutableStateOf(false)
        val page = mutableStateOf("home")
        val empty = DailySpendingTrend(emptyList(), com.baraa.masroof.application.dashboard.SignedMoneyAmount.zero(com.baraa.masroof.core.money.Currency.SAR))
        val original = FidelityFixtures.financial()
        val futureStart = java.time.LocalDate.now().plusMonths(1)
        val trends = original.spendingTrends!!.copy(overall = empty, purchases = empty, accounts = empty, creditCards = empty,
            debitCards = empty, byAccount = original.spendingTrends!!.byAccount.mapValues { empty },
            byCard = original.spendingTrends!!.byCard.mapValues { empty })
        val financial = original.copy(period = com.baraa.masroof.domain.period.FinancialPeriod(futureStart, futureStart.plusDays(30)),
            dailySpendingTrend = empty, spendingTrends = trends)
        val debit = financial.creditFacilities!!.debitCards.first()
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        key(language.value, dark.value, page.value) {
                            when (page.value) {
                                "daily" -> DailyExpensesScreen(financial, {}, {})
                                "mada" -> DebitCardDetailScreen(debit, financial, debit.network, {}, {})
                                else -> Column(Modifier.padding(MasroofSpacing.screenHorizontal)) { HomeSpendingTrendSection(trends) }
                            }
                        }
                    }
                }
            }
        }
        for (lang in listOf("ar", "en")) for (night in listOf(false, true)) for (id in listOf("home", "daily", "mada")) {
            compose.runOnIdle { language.value = lang; dark.value = night; page.value = id }
            compose.waitForIdle()
            val context = AppLocaleContext.wrap(compose.activity, lang)
            compose.onNodeWithText(context.getString(R.string.dashboard_chart_no_elapsed_days)).assertExists()
            compose.onNodeWithTag("daily-expenses-chart").assertDoesNotExist()
        }
    }

    private fun selectDay(trend: DailySpendingTrend, lang: String, first: Boolean) {
        val chart = compose.onNodeWithTag("daily-expenses-chart").performScrollTo()
        val index = if (first) 0 else trend.points.lastIndex
        chart.performTouchInput {
            val density = compose.activity.resources.displayMetrics.density
            val left = MasroofReference.chartLeft.value * density
            val right = MasroofReference.chartRight.value * density
            val fraction = if ((lang == "ar") == first) 0.99f else 0.01f
            click(Offset(left + (width - left - right) * fraction, height * 0.4f))
        }
        val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, description.contains(MoneyUiFormatter.format(trend.points[index].spending, lang)))
        val average = compose.onNodeWithTag("daily-expenses-average").fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
        // The legend Row does not merge children; find its amount through the child text instead.
        compose.onNodeWithText(MoneyUiFormatter.format(trend.averageDailySpending, lang), substring = true).assertExists()
        assertFalse("No Eastern digits", Regex("[\u0660-\u0669\u06F0-\u06F9]").containsMatchIn(description + average))
    }

    private fun assertAverageLine(trend: DailySpendingTrend, dark: Boolean) {
        val bitmap = compose.onNodeWithTag("daily-expenses-chart").captureToImage().asAndroidBitmap()
        val density = bitmap.height / MasroofReference.chartHeight.value
        val range = com.baraa.masroof.ui.components.MasroofLineChart.referenceRange(trend.points.map { it.spending.amount } + trend.averageDailySpending.amount)
        val plotHeight = bitmap.height - (MasroofReference.chartTop.value + MasroofReference.chartBottom.value) * density
        val fraction = trend.averageDailySpending.amount.subtract(range.minimum).toFloat() / range.span.toFloat()
        val y = MasroofReference.chartTop.value * density + plotHeight * (1 - fraction)
        val foreground = (if (dark) MasroofDarkColors.OnSurfaceVariant else MasroofLightColors.OnSurfaceVariant).toArgb()
        val background = (if (dark) MasroofDarkColors.Surface else MasroofLightColors.Surface).toArgb()
        try {
            assertTrue("Average line must have the correct position, stroke coverage and dash pattern: density=$density y=$y",
                AverageLinePixels.matches(bitmap, y, foreground, background))
        } finally {
            bitmap.recycle()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "daily-purchases-review")
        dir.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
