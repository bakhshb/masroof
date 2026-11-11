package com.baraa.masroof.instrumentation

import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.application.dashboard.*
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.ui.theme.MasroofTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production screens with explicit test facts; no real financial records are read. */
@RunWith(AndroidJUnit4::class)
class DashboardEntityScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun creditSummaryAndDetail_matchBothThemes_andChartChangesSelectedDay() {
        val dark = mutableStateOf(false)
        val selected = mutableStateOf<CreditCardDashboardRow?>(null)
        val state = fixture()
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (selected.value == null) {
                            CardsSummaryScreen(state, onBack = {},
                                cardNetworksByLast4 = state.ownedCards.associate { CardOwnershipKey.of(it) to it.cardNetwork },
                                onOpenCard = { selected.value = it }, onOpenDebit = {})
                        } else {
                            CardDetailScreen(selected.value!!, state, onBack = { selected.value = null }, onOpenTransaction = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        capture("credit-summary-light")
        scrollTo(hasText("بطاقة السفر", substring = true))
        capture("credit-cards-light")
        compose.onNode(hasText("بطاقة السفر", substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ar(R.string.due_amount)).assertIsDisplayed()
        compose.onNodeWithText(ar(R.string.due_date)).assertIsDisplayed()
        capture("credit-detail-light")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        capture("credit-detail-dark")
        scrollTo(hasTestTag("daily-expenses-chart"))
        val chartBounds = compose.onNodeWithTag("daily-expenses-chart").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("daily-expenses-chart").performTouchInput {
            val density = compose.activity.resources.displayMetrics.density
            val left = com.baraa.masroof.ui.theme.MasroofReference.chartLeft.value * density
            val right = com.baraa.masroof.ui.theme.MasroofReference.chartRight.value * density
            click(Offset(left + (chartBounds.width-left-right) * (1f - 6f / 14f), chartBounds.height * 0.12f))
        }
        val tapped = compose.onNodeWithTag("daily-expenses-chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        val expectedAmount = MoneyUiFormatter.format(SignedMoneyAmount.of(Money.of("120", Currency.SAR)), "ar")
        assertTrue("Touching a point must expose its actual amount", tapped.any { it.contains(expectedAmount) })
        capture("credit-point-selected-dark")
        compose.onNodeWithText(ar(R.string.chart_previous_point)).assertDoesNotExist()
        compose.onNodeWithText(ar(R.string.chart_next_point)).assertDoesNotExist()
        val previousDayAction = compose.onNodeWithTag("daily-expenses-chart").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == ar(R.string.chart_previous_point) }
        compose.runOnIdle { assertTrue(previousDayAction.action()) }
        val after = compose.onNodeWithTag("daily-expenses-chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertNotEquals(tapped, after)
        capture("credit-chart-dark")
        compose.runOnIdle { selected.value = null }
        compose.waitForIdle()
        capture("credit-summary-dark")
    }

    @Test
    fun loanDetails_showRecordedInstallmentAndBalance_withoutCreditFields() {
        val dark = mutableStateOf(false)
        val loan = LoanOverview(Bank.BANK_ALJAZIRA, LoanType.PERSONAL, "قرض شخصي",
            Money.of("42000", Currency.SAR), Instant.parse("2026-10-01T07:00:00Z"),
            Money.of("1500", Currency.SAR), Instant.parse("2026-09-28T07:00:00Z"),
            SignedMoneyAmount.of(Money.of("1500", Currency.SAR)), null)
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LoanDetailScreen(loan, fixture(), onBack = {}, onOpenTransaction = {})
                    }
                }
            }
        }
        compose.onNodeWithText(ar(R.string.last_installment)).assertIsDisplayed()
        compose.onNodeWithText(ar(R.string.installment_date)).assertIsDisplayed()
        compose.onNodeWithText(ar(R.string.due_amount)).assertDoesNotExist()
        capture("loan-detail-light")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        capture("loan-detail-dark")
    }

    @Test
    fun accountSummaryAndDetails_useTheSharedPattern_inBothThemes() {
        val dark = mutableStateOf(false)
        val selected = mutableStateOf(false)
        val zero = Money.zero(Currency.SAR)
        val summary = CurrentAccountSummary.of(Currency.SAR, Money.of("12000", Currency.SAR),
            zero, zero, zero, Money.of("2400", Currency.SAR), Money.of("500", Currency.SAR),
            Money.of("800", Currency.SAR), Money.of("200", Currency.SAR), Money.of("1200", Currency.SAR),
            zero, selfTransfersOut = zero)
        val account = OwnedAccountUi(Bank.BANK_ALJAZIRA, "3001", "حساب الراتب", summary)
        val original = fixture()
        val originalTrends = requireNotNull(original.spendingTrends)
        val state = original.copy(ownedAccounts = listOf(account), currentAccount = summary,
            accountsFleet = AccountsSummary.fromSummaries(listOf(account.bank to account.maskedNumber), listOf(summary)),
            spendingTrends = originalTrends.copy(byAccount = mapOf("${account.bank.id}:3001" to originalTrends.overall)))
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (selected.value) AccountDetailScreen(account, state, onBack = { selected.value = false }, onOpenTransaction = {})
                        else AccountsSummaryScreen(state, onBack = {}, onOpenAccount = { selected.value = true })
                    }
                }
            }
        }
        compose.waitForIdle()
        capture("account-summary-light")
        scrollTo(hasText("حساب الراتب", substring = true))
        compose.onNode(hasText("حساب الراتب", substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
        capture("account-detail-light")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        capture("account-detail-dark")
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(matcher)
    }

    @Test
    fun failedLoad_showsErrorInsteadOfEmptyCards_andPreservesCachedDataWithNotice() {
        val state = mutableStateOf(DashboardUiState(loading = false, error = DashboardError.LOAD_FAILED))
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CardsSummaryScreen(state.value, onBack = {}, onOpenCard = {},
                            onOpenDebit = {}, cardNetworksByLast4 = emptyMap())
                    }
                }
            }
        }
        compose.onNodeWithText(ar(R.string.dashboard_load_error)).assertIsDisplayed()
        compose.onNodeWithText(ar(R.string.dashboard_cards_summary_empty)).assertDoesNotExist()
        capture("load-error-light")
        compose.runOnIdle {
            state.value = fixture().copy(error = DashboardError.LOAD_FAILED, currentAccount = CurrentAccountSummary.zero(Currency.SAR))
        }
        compose.onNodeWithText(ar(R.string.dashboard_load_error)).assertIsDisplayed()
        compose.onNodeWithText(ar(R.string.dashboard_cards_summary_empty)).assertDoesNotExist()
        scrollTo(hasText("بطاقة السفر", substring = true))
        compose.onNode(hasText("بطاقة السفر", substring = true) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun debitOnlySummary_doesNotInventACreditDueOrCreditChart() {
        val original = fixture()
        val trend = requireNotNull(original.spendingTrends).overall
        val bank = Bank.BANK_ALJAZIRA
        val debit = DebitCardOverview(bank, "2210", "بطاقة مدى", "حساب الراتب", "3001",
            CardNetwork.MADA, trend.totalSpending, null)
        val state = original.copy(
            creditFacilities = CreditFacilitiesOverview(emptyList(), listOf(debit), Currency.SAR),
            ownedCards = listOf(OwnedCardUi(bank, "2210", "بطاقة مدى", CardNetwork.MADA)),
            spendingTrends = original.spendingTrends.copy(byCard = mapOf("${bank.id}:2210" to trend)),
        )
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CardsSummaryScreen(state, onBack = {}, onOpenCard = {}, onOpenDebit = {},
                            cardNetworksByLast4 = mapOf("${bank.id}:2210" to CardNetwork.MADA))
                    }
                }
            }
        }
        compose.onNodeWithText(ar(R.string.dashboard_credit_card_aggregate_due)).assertDoesNotExist()
        compose.onNodeWithText(ar(R.string.credit_daily)).assertDoesNotExist()
        compose.onNodeWithText(ar(R.string.dashboard_debit_card_linked_account)).assertIsDisplayed()
        capture("debit-summary-light")
    }

    private fun ar(id: Int) = AppLocaleContext.wrap(InstrumentationRegistry.getInstrumentation().targetContext, "ar").getString(id)

    private fun fixture(): DashboardUiState {
        val bank = Bank.BANK_ALJAZIRA
        val start = LocalDate.parse("2026-09-27")
        val period = FinancialPeriod(start, LocalDate.parse("2026-10-27"))
        val points = (0..14).map { day -> DailySpendingPoint(start.plusDays(day.toLong()),
            SignedMoneyAmount.of(Money.of(if (day % 3 == 0) "120" else "0", Currency.SAR))) }
        val trend = DailySpendingTrend(points, SignedMoneyAmount.of(Money.of("40", Currency.SAR)))
        val snapshot = CreditCardBalanceSnapshot(Money.of("18500", Currency.SAR), Money.of("2400", Currency.SAR),
            LocalDate.parse("2026-10-20"), Instant.parse("2026-10-01T07:00:00Z"), Instant.parse("2026-10-10T07:00:00Z"))
        val primary = CreditCardDashboardRow(bank, "1234", trend.totalSpending, trend.totalSpending, null, snapshot)
        val secondary = primary.copy(last4 = "5678", snapshot = snapshot.copy(availableBalance = Money.of("3500", Currency.SAR)))
        val facility = CreditFacilityOverview(bank, primary, listOf(secondary),
            StatementDueSnapshot(Money.of("2400", Currency.SAR), snapshot.updatedAt, snapshot.dueDate), trend.totalSpending, trend.totalSpending, null, null, Currency.SAR)
        return DashboardUiState(loading = false, period = com.baraa.masroof.application.dashboard.DashboardSalaryPeriod(period.startDate, period.endDateExclusive),
            creditFacilities = CreditFacilitiesOverview(listOf(facility), emptyList(), Currency.SAR),
            ownedCards = listOf(OwnedCardUi(bank, "1234", "بطاقة السفر", CardNetwork.VISA), OwnedCardUi(bank, "5678", "بطاقة العائلة", CardNetwork.MASTERCARD)),
            spendingTrends = DashboardSpendingTrends(trend, trend, trend, emptyMap(), mapOf("${bank.id}:1234" to trend, "${bank.id}:5678" to trend), purchases = trend))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        assertNoEasternDigits()
        ScreenshotArtifactWriter.capture(compose, "masroof-design-screenshots", name)
    }

    private fun assertNoEasternDigits() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val violations = nodes.flatMap { it.config[SemanticsProperties.Text] }.map { it.text }
            .filter { text -> text.any { it in '\u0660'..'\u0669' || it in '\u06F0'..'\u06F9' } }
        assertTrue("Visible text must use 0123456789: $violations", violations.isEmpty())
    }

    @Test
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    fun arabicCalendar_usesAsciiDigitsForDaysYearsAndSelection() {
        compose.setContent {
            val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MasroofTheme(darkTheme = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        androidx.compose.material3.DatePicker(state = androidx.compose.material3.rememberDatePickerState(
                            initialSelectedDateMillis = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("2026", substring = true)).fetchSemanticsNodes().isNotEmpty())
        capture("ascii-calendar-light")
    }

    // updateConfiguration is deliberate: dialog windows read the host Activity resources, not LocalContext.
    @Suppress("DEPRECATION")
    @Test
    fun renameAccount_displaysImportedEasternDigitsAsAscii_withoutChangingSavedName() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hostResources = compose.activity.resources
        val originalConfiguration = android.content.res.Configuration(hostResources.configuration)
        val localizedConfiguration = AppLocaleContext.wrap(compose.activity, "ar").resources.configuration
        instrumentation.runOnMainSync { hostResources.updateConfiguration(localizedConfiguration, hostResources.displayMetrics) }
        try {
            val savedName = mutableStateOf<String?>(null)
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                    MasroofTheme(darkTheme = false) {
                        com.baraa.masroof.presentation.settings.SettingsRenameAccountDialog(
                            target = com.baraa.masroof.presentation.settings.ManagedAccountUi("test", Bank.BANK_ALJAZIRA,
                                "3001", com.baraa.masroof.domain.model.OwnershipStatus.OWNED, "حساب ٣٠٠١"),
                            updating = false, onDismiss = {}, onSave = { savedName.value = it })
                    }
                }
            }
            compose.waitForIdle()
            capture("ascii-account-input-light")
            compose.onNodeWithText(ar(R.string.settings_save)).performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals("حساب ٣٠٠١", savedName.value) }
        } finally {
            instrumentation.runOnMainSync { hostResources.updateConfiguration(originalConfiguration, hostResources.displayMetrics) }
        }
    }
}
