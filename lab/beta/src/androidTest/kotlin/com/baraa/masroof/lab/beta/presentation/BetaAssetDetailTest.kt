package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.posting.TransactionTime
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.value.*
import com.baraa.masroof.ui.components.MasroofMoneyDisplay
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

class BetaAssetDetailTest {
    @get:Rule val compose = createComposeRule()
    private val accountId = AssetId(Environment.BETA, BankId("BANK_ALJAZIRA"), "account")
    private val cardId = accountId.copy(localId = "debit")
    private val query = DashboardQuery(Environment.BETA, Instant.parse("2026-10-01T00:00:00Z"),
        Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC, Currency.SAR)
    private fun signed(value: String) = SignedAmount(BigDecimal(value), Currency.SAR)

    @Test fun accountHeroIncludesInternalMovementInsteadOfExternalOnlyNet() {
        val flow = AccountFlow(Currency.SAR, FlowBucket.entries.associateWith { bucket ->
            Money.of(when (bucket) {
                FlowBucket.SALARY -> "10"
                FlowBucket.POS_PURCHASES -> "40"
                FlowBucket.INTERNAL_IN -> "5"
                else -> "0"
            }, Currency.SAR)
        }, Money.of("15", Currency.SAR), Money.of("40", Currency.SAR), Money.of("10", Currency.SAR),
            Money.of("40", Currency.SAR), signed("-30"), signed("-25"))
        val account = AccountSummary(accountId, null, flow = flow)
        val snapshot = DashboardSnapshot(query, listOf(account), emptyList(), emptyList())
        var expected = ""
        var externalOnly = ""
        compose.setContent {
            val language = LocalConfiguration.current.locales[0].toLanguageTag()
            expected = MasroofMoneyDisplay.format(BigDecimal("-25"), "SAR", language)
            externalOnly = MasroofMoneyDisplay.format(BigDecimal("-30"), "SAR", language)
            MasroofTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                BetaAssetDetail(snapshot, account, null, null, null, true, {}, true)
            } }
        }
        compose.onNodeWithText(expected).assertExists()
        compose.onAllNodesWithText(externalOnly).assertCountEquals(0)
    }

    @Test fun debitDisplaysBuilderGrossRefundAndNetWithoutStatementDue() {
        val period = DisplayPeriod(query.from, query.until, query.zone, PeriodKind.SALARY, null)
        val debit = DebitSpending(cardId, signed("80"), salaryPeriod = period)
        val trend = SpendingTrend(cardId, null, period, Currency.SAR, emptyList(), null,
            scope = TrendScope.ASSET, totalSpending = signed("80"), totalBeforeRefunds = signed("100"), totalRefunds = signed("20"))
        val analysis = AnalysisSummary(Currency.SAR, SummaryMetric.entries.associateWith { null }, 0, 0, 0, listOf(trend), emptyList())
        val snapshot = DashboardSnapshot(query, emptyList(), listOf(debit), emptyList(), analysis = analysis)
        var gross = ""
        var refund = ""
        var dueLabel = ""
        compose.setContent {
            val language = LocalConfiguration.current.locales[0].toLanguageTag()
            gross = MasroofMoneyDisplay.format(BigDecimal("100"), "SAR", language)
            refund = MasroofMoneyDisplay.format(BigDecimal("20"), "SAR", language)
            dueLabel = androidx.compose.ui.res.stringResource(R.string.beta_dash_due)
            MasroofTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                BetaAssetDetail(snapshot, null, debit, null, null, true, {}, true)
            } }
        }
        compose.onNodeWithText(gross).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(refund).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(dueLabel).assertCountEquals(0)
    }
    @Test fun accountMovementsShowInternalTransferDirectionFromQualifiedEndpoints() {
        val account=AccountSummary(accountId,null)
        val other=accountId.copy(localId="other")
        val otherBank=accountId.copy(bank=BankId("ANOTHER_BANK"))
        fun transfer(key: String, source: AssetId?, destination: AssetId?)=TransactionFacts(
            com.baraa.masroof.lab.finance.posting.PostingKey(Environment.BETA,key),
            com.baraa.masroof.lab.finance.posting.PostingRevision(0,1),
            TransactionTime(query.from,query.zone),
            com.baraa.masroof.lab.finance.posting.FinancialType.SELF_TRANSFER,
            com.baraa.masroof.lab.finance.posting.FinancialType.SELF_TRANSFER,
            Money.of("50",Currency.SAR),key,null,null,setOfNotNull(source,destination),emptySet(),emptySet(),emptySet(),
            setOf(ChartCategory.INTERNAL_TRANSFERS),null,null,null,emptySet(),source,destination)
        val snapshot=DashboardSnapshot(query,listOf(account,AccountSummary(other,null),AccountSummary(otherBank,null)),emptyList(),emptyList(),transactions=listOf(
            transfer("incoming",other,accountId),transfer("outgoing",accountId,other),
            transfer("same-account",accountId,accountId),transfer("same-suffix-other-bank",otherBank,accountId)))
        var number=""
        compose.setContent {
            number=MasroofMoneyDisplay.format(BigDecimal("50"),"SAR",LocalConfiguration.current.locales[0].toLanguageTag()).substringBeforeLast(' ')
            MasroofTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
                BetaAssetDetail(snapshot,account,null,null,null,true,{},true)
            }}
        }
        for((key,sign) in listOf("incoming" to "+","outgoing" to "−","same-account" to "","same-suffix-other-bank" to "+")) {
            compose.onNodeWithTag("dash-transaction-$key").performScrollTo()
                .assert(hasText(sign+number))
        }
    }

}
