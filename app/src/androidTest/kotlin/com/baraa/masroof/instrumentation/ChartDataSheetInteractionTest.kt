package com.baraa.masroof.instrumentation

import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.dashboard.*
import com.baraa.masroof.R
import com.baraa.masroof.data.preferences.SharedPrefsChartDataPreferencesRepository
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real native components and production chart builders with synthetic financial fixtures. */
class ChartDataSheetInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "phone"
        ScreenshotArtifactWriter.capture(compose, "chart-data-sheets/$profile", name.lowercase())
    }
    private fun assertActionsFit() {
        val sheet = compose.onNodeWithTag("masroof-bottom-sheet").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("sheet-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Actions below sheet bounds: sheet=$sheet actions=$actions", actions.bottom <= sheet.bottom + 1)
        compose.onNodeWithTag("sheet-close").assertIsDisplayed()
        compose.onNodeWithTag("sheet-apply").assertIsDisplayed()
    }

    @Test fun exerciseAllChartScopes_ar_light() = exerciseAllChartScopes("ar", false)
    @Test fun exerciseAllChartScopes_ar_dark() = exerciseAllChartScopes("ar", true)
    @Test fun exerciseAllChartScopes_en_light() = exerciseAllChartScopes("en", false)
    @Test fun exerciseAllChartScopes_en_dark() = exerciseAllChartScopes("en", true)

    private fun exerciseAllChartScopes(lang: String, night: Boolean) {
        val state = FidelityFixtures.financial()
        val trends = state.spendingTrends!!
        val facility = state.creditFacilities!!.facilities.first()
        val mada = state.creditFacilities.debitCards.first()
        val prefs = compose.activity.getSharedPreferences("native-chart-choices", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repository = SharedPrefsChartDataPreferencesRepository(prefs)
        val workflow = mutableStateOf(ChartDataWorkflow(repository))
        val language = mutableStateOf("ar"); val dark = mutableStateOf(false); val page = mutableStateOf("homePurchases")
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalChartDataWorkflow provides workflow.value) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        key(language.value, dark.value, page.value) {
                            when (page.value) {
                                "homePurchases", "homeOverall" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                    .padding(MasroofSpacing.screenHorizontal)) { HomeSpendingTrendSection(trends) }
                                "accounts" -> AccountsSummaryScreen(state, {}, {})
                                "accountDetail" -> AccountDetailScreen(state.ownedAccounts.first(), state, {}, {})
                                "credit" -> CardsSummaryScreen(state, {}, {}, {}, state.ownedCards.associate { CardOwnershipKey.of(it) to it.cardNetwork })
                                "primary" -> CardDetailScreen(facility.primary, state, {}, {})
                                "supplementary" -> CardDetailScreen(facility.supplementaries.first(), state, {}, {})
                                "mada" -> DebitCardDetailScreen(mada, state, com.baraa.masroof.domain.model.CardNetwork.MADA, {}, {})
                                else -> DailyExpensesScreen(state, {}, {})
                            }
                        }
                    }
                }
            }
        }
        for (id in listOf("homePurchases", "homeOverall", "accounts", "accountDetail", "credit", "primary", "supplementary", "mada", "daily")) {
                val chartKey = when (id) {
                    "homePurchases" -> "home:purchases"
                    "homeOverall", "daily" -> "home:overall"
                    "accounts" -> "accounts"
                    "accountDetail" -> "account:BANK_ALJAZIRA:3001"
                    "credit" -> "credit"
                    "primary" -> "card:${CardOwnershipKey.of(facility.primary)}"
                    "supplementary" -> "card:${CardOwnershipKey.of(facility.supplementaries.first())}"
                    else -> "card:${CardOwnershipKey.of(mada)}"
                }
                val source = when (id) {
                    "homePurchases" -> trends.purchases
                    "homeOverall", "daily" -> trends.overall
                    "accounts" -> trends.accounts
                    "accountDetail" -> trends.byAccount.getValue("BANK_ALJAZIRA:3001")
                    "credit" -> trends.creditCards
                    "primary" -> trends.byCard.getValue(CardOwnershipKey.of(facility.primary))
                    "supplementary" -> trends.byCard.getValue(CardOwnershipKey.of(facility.supplementaries.first()))
                    else -> trends.byCard.getValue(CardOwnershipKey.of(mada))
                }
                val category = source.categoryTrends.entries.first { it.value.totalBeforeRefunds.amount.signum() > 0 }.key
                compose.runOnIdle {
                    workflow.value.save(chartKey, source.categoryTrends.keys, source.categoryTrends.keys)
                    workflow.value.setHomeScope(if (id == "homePurchases") "purchases" else "overall")
                    language.value = lang; dark.value = night; page.value = id
                }
                compose.waitForIdle()
                compose.onNodeWithTag("chart-customize").performScrollTo().performClick()
                assertActionsFit()
                val localized = AppLocaleContext.wrap(compose.activity, lang)
                compose.onNodeWithText(localized.getString(R.string.sheet_cancel)).assertIsDisplayed()
                compose.onNodeWithText(localized.getString(R.string.chart_data_about)).assertExists()
                val categoryLabel = localized.getString(when (category) {
                    ChartDataCategory.CREDIT_PURCHASES -> R.string.chart_category_credit
                    ChartDataCategory.MADA_PURCHASES -> R.string.chart_category_mada
                    ChartDataCategory.ACCOUNT_PURCHASES -> R.string.chart_category_account_purchases
                    ChartDataCategory.OTHER_EXPENSES -> R.string.chart_category_other
                    ChartDataCategory.CASH_WITHDRAWALS -> R.string.chart_category_cash
                    ChartDataCategory.EXTERNAL_TRANSFERS -> R.string.chart_category_external
                    ChartDataCategory.BILLS -> R.string.chart_category_bills
                    ChartDataCategory.LOAN_REPAYMENTS -> R.string.chart_category_loans
                    ChartDataCategory.CREDIT_SETTLEMENTS -> R.string.chart_category_settlements
                    ChartDataCategory.FEES -> R.string.chart_category_fees
                    ChartDataCategory.INTERNAL_TRANSFERS -> R.string.chart_category_internal
                    ChartDataCategory.UNATTRIBUTED_REFUNDS -> R.string.chart_category_refunds
                })
                compose.onNodeWithText(categoryLabel).assertExists()
                capture("$id-$lang-${if (night) "dark" else "light"}-sheet")
                val choice = compose.onNodeWithTag("chart-category-${category.name}")
                choice.performScrollTo().performClick()
                // Hardware Back discards the draft and stays on the original screen.
                pressDeviceBack(compose); compose.waitForIdle()
                compose.onNodeWithTag("masroof-bottom-sheet").assertDoesNotExist()
                assertEquals(source.categoryTrends.keys, workflow.value.categories(source, chartKey))
                compose.onNodeWithTag("chart-customize").performScrollTo().performClick()
                choice.assertIsOn().performScrollTo().performClick()
                compose.onNodeWithTag("sheet-apply").performClick(); compose.waitForIdle()
                val selected = source.categoryTrends.keys - category
                assertEquals(selected, workflow.value.categories(source, chartKey))
                // Recreate the application workflow/repository consumer: choices survive reload.
                compose.runOnIdle { workflow.value = ChartDataWorkflow(repository) }
                compose.waitForIdle()
                assertEquals(selected, workflow.value.categories(source, chartKey))
                val expected = workflow.value.selected(source, chartKey)
                val index = source.points.indexOfFirst { it.spending.amount.signum() > 0 }.coerceAtLeast(0)
                val chart = compose.onNodeWithTag("daily-expenses-chart").performScrollTo()
                chart.performTouchInput {
                    val density = compose.activity.resources.displayMetrics.density
                    val left = MasroofReference.chartLeft.value * density; val right = MasroofReference.chartRight.value * density
                    val fraction = index.toFloat() / source.points.lastIndex
                    click(Offset(left + (width - left - right) * if (lang == "ar") 1 - fraction else fraction, height * 0.4f))
                }
                val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
                assertTrue(description, description.contains(MoneyUiFormatter.format(expected.points[index].spending, lang)))
                compose.onNodeWithTag("chart-custom-summary").assertExists()
                capture("$id-$lang-${if (night) "dark" else "light"}-applied")
                compose.onNodeWithTag("chart-customize").performScrollTo().performClick()
                choice.assertIsOff()
                compose.onNodeWithTag("sheet-reset").performClick()
                // Uncheck every category: Apply must be disabled, and Reset restores all.
                source.categoryTrends.keys.forEach { key -> compose.onNodeWithTag("chart-category-${key.name}").performScrollTo().performClick() }
                compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
                compose.onNodeWithTag("sheet-reset").performClick()
                compose.onNodeWithTag("sheet-apply").performClick()
                assertEquals(source.categoryTrends.keys, workflow.value.categories(source, chartKey))
            }
    }

    @Test fun existingSheets_shareFrameAndDraftActions_inBothLanguagesAndThemes() {
        val language = mutableStateOf("ar"); val dark = mutableStateOf(false); val page = mutableStateOf("layout")
        val open = mutableStateOf(true); val layout = mutableStateOf(DashboardLayoutSnapshot.default())
        val appliedFilter = mutableStateOf(TransactionListFilterState())
        val pendingType = mutableStateOf<FinancialTransactionType?>(null)
        var saves = 0
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (open.value) key(language.value, dark.value, page.value) {
                            when (page.value) {
                                "layout" -> DashboardCustomizeBottomSheet(layout.value, { open.value = false },
                                    { saves++; open.value = false }, { }, { _, _ -> }, {}, {})
                                "filter" -> TransactionListFilterBottomSheet(true, { open.value = false }, appliedFilter.value,
                                    listOf(FinancialTransactionType.EXPENSE, FinancialTransactionType.BILL_PAYMENT), emptyList(), emptyList(),
                                    onApply = { appliedFilter.value = it })
                                else -> TransactionReclassifyBottomSheet(FinancialTransactionType.EXPENSE, pendingType.value,
                                    false, { open.value = false }, { pendingType.value = it }, { saves++; open.value = false })
                            }
                        }
                    }
                }
            }
        }
        for (lang in listOf("ar", "en")) for (night in listOf(false, true)) for (id in listOf("layout", "filter", "reclassify")) {
            compose.runOnIdle { language.value = lang; dark.value = night; page.value = id; open.value = true; appliedFilter.value = TransactionListFilterState() }
            compose.waitForIdle(); assertActionsFit(); capture("$id-$lang-${if (night) "dark" else "light"}-sheet")
            if (id == "filter") {
                compose.onNodeWithTag("filter-type-EXPENSE").performClick()
                assertTrue(appliedFilter.value.types.isEmpty())
                compose.onNodeWithTag("sheet-cancel").performClick()
                assertTrue(appliedFilter.value.types.isEmpty())
                compose.runOnIdle { open.value = true }
                compose.onNodeWithTag("filter-type-EXPENSE").assertIsOff().performClick()
                compose.onNodeWithTag("sheet-apply").performClick()
                assertEquals(setOf(FinancialTransactionType.EXPENSE), appliedFilter.value.types)
            } else {
                val before = saves
                pressDeviceBack(compose); compose.waitForIdle()
                assertEquals(before, saves)
                assertFalse(open.value)
            }
        }
    }
}
