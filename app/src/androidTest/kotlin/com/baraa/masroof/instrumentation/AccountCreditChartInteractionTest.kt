package com.baraa.masroof.instrumentation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.DailySpendingTrend
import com.baraa.masroof.presentation.common.MasroofLineChart
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountCreditChartInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unchangedTitles_scopesAndCreditMeanLines_workInBothLanguagesAndThemes() {
        val language = mutableStateOf("ar")
        val dark = mutableStateOf(false)
        val page = mutableStateOf("accounts")
        val state = FidelityFixtures.financial()
        val trends = state.spendingTrends!!
        assertEquals("7200.00", trends.accounts.totalSpending.amount.toPlainString())
        assertEquals("8200.00", trends.byAccount.getValue("BANK_ALJAZIRA:3001").totalSpending.amount.toPlainString())
        val facility = state.creditFacilities!!.facilities.first()
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        key(language.value, dark.value, page.value) {
                            when (page.value) {
                                "accounts" -> AccountsSummaryScreen(state, {}, {}, {})
                                "accountDetail" -> AccountDetailScreen(state.ownedAccounts.first(), state, {}, {})
                                "credit" -> CardsSummaryScreen(state, {}, {}, {}, {}, state.ownedCards.associate { CardOwnershipKey.of(it) to it.cardNetwork })
                                "primary" -> CardDetailScreen(facility.primary, state, {}, {})
                                else -> CardDetailScreen(facility.supplementaries.first(), state, {}, {})
                            }
                        }
                    }
                }
            }
        }
        for (lang in listOf("ar", "en")) for (night in listOf(false, true))
            for (id in listOf("accounts", "accountDetail", "credit", "primary", "supplementary")) {
                compose.runOnIdle { language.value = lang; dark.value = night; page.value = id }
                compose.waitForIdle()
                val context = AppLocaleContext.wrap(compose.activity, lang)
                val trend = when (id) {
                    "accounts" -> trends.accounts
                    "accountDetail" -> trends.byAccount.getValue("BANK_ALJAZIRA:3001")
                    "credit" -> trends.creditCards
                    "primary" -> trends.byCard.getValue(CardOwnershipKey.of(facility.primary))
                    else -> trends.byCard.getValue(CardOwnershipKey.of(facility.supplementaries.first()))
                }
                val title = context.getString(when (id) {
                    "accounts" -> R.string.approved_accounts_daily
                    "accountDetail" -> R.string.approved_account_daily
                    "credit" -> R.string.approved_credit_daily
                    else -> R.string.approved_card_daily
                })
                compose.onNodeWithText(title).assertExists()
                val chart = compose.onNodeWithTag("daily-expenses-chart").performScrollTo()
                val selectedIndex = when (id) {
                    "accounts" -> trend.points.indexOfFirst { it.date.toString() == "2026-09-29" } // settlement
                    "accountDetail" -> trend.points.indexOfFirst { it.date.toString() == "2026-10-08" } // internal + Mada
                    else -> trend.points.indexOfFirst { it.spending.amount.signum() > 0 }
                }
                chart.performTouchInput {
                    val density = compose.activity.resources.displayMetrics.density
                    val left = MasroofReference.chartLeft.value * density
                    val right = MasroofReference.chartRight.value * density
                    val fraction = selectedIndex.toFloat() / trend.points.lastIndex
                    click(Offset(left + (width - left - right) * if (lang == "ar") 1 - fraction else fraction, height * 0.4f))
                }
                val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
                assertTrue(description, description.contains(MoneyUiFormatter.format(trend.points[selectedIndex].spending, lang)))
                if (id == "accounts") assertEquals("2200.00", trend.points[selectedIndex].spending.amount.toPlainString())
                if (id == "accountDetail") assertEquals("1200.00", trend.points[selectedIndex].spending.amount.toPlainString())
                if (id !in listOf("accounts", "accountDetail")) {
                    compose.onNodeWithTag("daily-expenses-average").assertExists()
                    val bitmap = chart.captureToImage().asAndroidBitmap()
                    val density = bitmap.height / MasroofReference.chartHeight.value
                    val range = com.baraa.masroof.ui.components.MasroofLineChart.referenceRange(trend.points.map { it.spending.amount } + trend.averageDailySpending.amount)
                    val height = bitmap.height - (MasroofReference.chartTop.value + MasroofReference.chartBottom.value) * density
                    val fraction = trend.averageDailySpending.amount.subtract(range.minimum).toFloat() / range.span.toFloat()
                    val y = MasroofReference.chartTop.value * density + height * (1 - fraction)
                    assertTrue("Credit scope average line must be rendered: $id/$lang/$night",
                        AverageLinePixels.matches(bitmap, y,
                            (if (night) MasroofDarkColors.OnSurfaceVariant else MasroofLightColors.OnSurfaceVariant).toArgb(),
                            (if (night) MasroofDarkColors.Surface else MasroofLightColors.Surface).toArgb()))
                    bitmap.recycle()
                }
                capture("$id-$lang-${if (night) "dark" else "light"}")
                if (id in listOf("accounts", "accountDetail")) {
                    compose.onNodeWithContentDescription(context.getString(R.string.guidance_about, title)).performClick()
                    compose.onNodeWithText(context.getString(if (id == "accounts") R.string.reference_accounts_scope else R.string.reference_account_detail_scope)).assertIsDisplayed()
                    Espresso.pressBack()
                }
            }
    }

    private fun capture(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "account-credit-chart-review")
        dir.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
