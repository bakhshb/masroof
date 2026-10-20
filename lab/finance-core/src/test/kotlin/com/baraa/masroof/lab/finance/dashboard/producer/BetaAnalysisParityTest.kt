package com.baraa.masroof.lab.finance.dashboard.producer

import com.baraa.masroof.application.dashboard.DailySpendingTrendBuilder
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.dashboard.MerchantSpendingOverviewBuilder
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.core.money.Currency as OldCurrency
import com.baraa.masroof.core.money.Money as OldMoney
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.projection.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Cross-runs hash-pinned original calculators, using synthetic messages and zero network calls. */
class BetaAnalysisParityTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val first = LocalDate.of(2026, 8, 1)
    private val query = DashboardQuery(Environment.BETA, first.atStartOfDay(zone).toInstant(), first.plusDays(4).atStartOfDay(zone).toInstant(), zone, Currency.SAR)
    private val account = OwnedAsset(AssetId(Environment.BETA, BankId("BANK_ALJAZIRA"), "account"), AssetKind.ACCOUNT, "3001")
    private fun row(id: String, type: FinancialType, amount: String, day: Long = 0, foreign: Boolean = false, merchant: String = "Synthetic Store"):
        Pair<DashboardLedgerRow, DashboardPostingFacts> {
        val input = FinancialClassificationInput(PostingKey(Environment.BETA, id), PostingRevision(0, 1), type,
            Money.of(amount, if (foreign) Currency.USD else Currency.SAR), TransactionTime(query.from.plusSeconds(day * 86400 + 3600), zone),
            if (type == FinancialType.REFUND) FinancialEndpoint.External else FinancialEndpoint.Owned(account.id),
            if (type == FinancialType.REFUND) FinancialEndpoint.Owned(account.id) else FinancialEndpoint.External,
            mapOf(account.id to account), setOf("event-$id"), true, appliedFx = if (foreign) AppliedFx(BigDecimal("3.756957"), "SMS") else null)
        return DashboardLedgerRow((FinancialPostingRules.prepare(input) as PostingPreparation.Ready).posting, true) to
            DashboardPostingFacts(input.key, input.revision, "event-$id", merchant = merchant)
    }
    private fun batch(rows: List<Pair<DashboardLedgerRow, DashboardPostingFacts>>, fees: List<LinkedInternationalFee> = emptyList(),
        asOf: Instant = first.plusDays(2).atStartOfDay(zone).toInstant().minusNanos(1)) = DashboardReadBatch(query,
        ReadRevision("synthetic-consistent", asOf), rows.map { it.first }, listOf(account),
        DashboardCoverage(true, true, true, 0, completeMerchantFacts = true), postingFacts = rows.map { it.second }, fees = fees)
    private fun build(batch: DashboardReadBatch) = BetaDashboardBuilder.build(batch)
    private fun same(expected: BigDecimal, actual: BigDecimal?) { assertNotNull(actual); assertEquals(0, expected.compareTo(actual)) }

    @Test fun smsFeeMerchantAndDailyTrendCrossRunOriginalBuilders() = runBlocking {
        val rows = listOf(row("a", FinancialType.EXPENSE, "23", foreign = true),
            row("b", FinancialType.EXPENSE, "10", 1, true, "synthetic  store"),
            row("r", FinancialType.REFUND, "40", 1, true, "SYNTHETIC STORE"))
        val fees = rows.map { (row, fact) -> LinkedInternationalFee(row.posting.command.key, row.posting.command.revision,
            fact.evidenceId, Money.of(if (fact.evidenceId == "event-a") "1.99" else "0.05", Currency.SAR)) }
        val oldTransactions = rows.map { (row, fact) -> row.posting.classification.input.let { input ->
            FinancialTransaction(input.key.transactionId, FinancialTransactionType.valueOf(input.storedType.name),
                OldMoney.of(input.amount.amount, OldCurrency.USD), input.time.instant,
                if (input.storedType == FinancialType.REFUND) null else "account", if (input.storedType == FinancialType.REFUND) "account" else null,
                fact.merchant, null, null, listOf(fact.evidenceId), input.appliedFx!!.rateToSar, ExchangeRateSource.SMS, zone.id) } }
        val records = rows.map { (row, fact) ->
            ParsedEventRecord(ParsedEvent(fact.evidenceId, "raw-${fact.evidenceId}", Bank.BANK_ALJAZIRA,
                if (row.posting.command.type == FinancialType.REFUND) MessageFamily.REFUND else MessageFamily.PURCHASE,
                null, OldMoney.of(row.posting.command.amount.amount, OldCurrency.USD), null, null, null, null, fact.merchant, null,
                row.posting.command.time.instant, null, Confidence(1.0), ParseStatus.SUCCESS),
                ParsedEventDetails(internationalFee = OldMoney.of(fees.single { it.evidenceId == fact.evidenceId }.amount.amount, OldCurrency.SAR)))
        }
        val originalAmounts = TransactionSarEquivalentResolver(ForeignSarMarketRateProvider { _, _ -> error("No market lookup") })
            .resolve(oldTransactions, records, emptyMap()).mapValues { it.value.sarAmount }
        val oldMerchants = MerchantSpendingOverviewBuilder.build(oldTransactions, OldCurrency.SAR, originalAmounts)
        val originalTrend = DailySpendingTrendBuilder.build(FinancialPeriod(first, first.plusDays(4)), oldTransactions, records,
            OldCurrency.SAR, originalAmounts, zone, first.plusDays(1))
        val result = build(batch(rows, fees)).snapshot
        same(BigDecimal("86.41"), result.transactions.single { it.key.transactionId == "a" }.sarEquivalent!!.amount)
        assertEquals(oldMerchants.merchants.map { it.displayName }, result.analysis!!.merchants.map { it.merchant })
        same(oldMerchants.merchants.single().totalSpent.amount, result.analysis.merchants.single().net.amount)
        assertEquals(rows.map {it.first.posting.command.key}.toSet(),result.analysis.merchants.single().transactionIds)
        assertEquals("synthetic store",result.analysis.merchants.single().merchantKey)
        assertEquals(oldMerchants.merchants.single().purchaseTransactionCount, result.analysis.merchants.single().purchaseTransactionCount)
        assertTrue(result.analysis.merchants.single().net.amount.signum() < 0)
        val trend = result.analysis.trends.single { it.scope == TrendScope.OVERALL && it.category == null }
        assertEquals(originalTrend.points.map { it.date }, trend.points.map { it.date })
        originalTrend.points.zip(trend.points).forEach { (old, current) ->
            same(old.spending.amount, current.net!!.amount); same(old.beforeRefunds.amount, current.beforeRefunds!!.amount)
            same(old.refunds.amount, current.refunds!!.amount)
        }
        same(originalTrend.averageDailySpending.amount, trend.averageDailySpending!!.amount)
        same(originalTrend.totalSpending.amount, trend.totalSpending!!.amount)
        same(originalTrend.totalBeforeRefunds.amount, trend.totalBeforeRefunds!!.amount)
        same(originalTrend.totalRefunds.amount, trend.totalRefunds!!.amount)
    }

    @Test fun ordinaryAndExplicitOutflowScopesKeepMovementsSeparate() {
        val rows = listOf(row("purchase", FinancialType.EXPENSE, "10"), row("withdraw", FinancialType.CASH_WITHDRAWAL, "100"),
            row("transfer", FinancialType.EXTERNAL_TRANSFER_OUT, "200"), row("bill", FinancialType.BILL_PAYMENT, "5"))
        val analysis = build(batch(rows)).snapshot.analysis!!
        fun total(scope: TrendScope) = analysis.trends.single { it.scope == scope && it.category == null }.points
            .fold(BigDecimal.ZERO) { sum, point -> sum + point.net!!.amount }
        same(BigDecimal("15"), total(TrendScope.OVERALL)); same(BigDecimal("315"), total(TrendScope.FINANCIAL_OUTFLOWS))
        assertEquals(2, analysis.trends.single { it.scope == TrendScope.OVERALL && it.category == null }.points.first().transactions.size)
    }

    @Test fun futureAndEmptyHistoryHaveKnownZeroButMissingFxDoesNot() {
        val empty = batch(emptyList())
        val future = build(empty.copy(revision = empty.revision.copy(asOf = query.from.minusSeconds(60)))).snapshot.analysis!!
        assertTrue(future.trends.all { it.points.isEmpty() && it.averageDailySpending!!.amount.signum() == 0 && it.totalSpending!!.amount.signum() == 0 })
        val known = build(empty).snapshot.analysis!!
        assertEquals(2, known.trends.first().points.size)
        assertTrue(known.trends.first().points.all { it.net!!.amount.signum() == 0 })
        val foreign = row("unknown", FinancialType.EXPENSE, "23", foreign = true).let { (row, facts) ->
            val input = row.posting.classification.input.copy(appliedFx = null)
            DashboardLedgerRow((FinancialPostingRules.prepare(input) as PostingPreparation.Ready).posting, true) to facts }
        val result = build(batch(listOf(foreign)))
        assertTrue(DashboardSection.TRENDS in result.unavailableSections)
        assertTrue(DashboardSection.MERCHANT_TOTALS in result.unavailableSections)
        assertTrue(result.snapshot.analysis!!.trends.first().points.all { it.net == null })
        assertNull(result.snapshot.analysis.trends.first().totalSpending)
    }

    @Test fun merchantThresholdRefundAndUnknownCoverageAreDistinct() {
        val rows = listOf(row("one", FinancialType.EXPENSE, "10"), row("refund", FinancialType.REFUND, "50"))
        assertTrue(build(batch(rows)).snapshot.analysis!!.merchants.isEmpty())
        val unknown = build(batch(rows).copy(coverage = DashboardCoverage(true, true, true, 0)))
        assertTrue(DashboardSection.MERCHANT_TOTALS in unknown.unavailableSections)
        val known = build(batch(emptyList()))
        assertFalse(DashboardSection.MERCHANT_TOTALS in known.unavailableSections)
        assertTrue(known.snapshot.analysis!!.merchants.isEmpty())
    }

    @Test fun commitmentsKeepMissingUserCoverageUnknownAndAcceptCompleteOccurrences() {
        val period = DisplayPeriod(query.from, query.until, zone, PeriodKind.SALARY, null)
        val row = CommitmentRow(Environment.BETA, "rent-once", "Synthetic rent", Money.of("100", Currency.SAR), first,
            PaymentStatus.PAID, userCommitmentId = "rent")
        val partial = batch(emptyList()).copy(periods = DashboardPeriods(salary = period), commitmentOccurrences = listOf(row))
        assertNull(build(partial).snapshot.commitments!!.total)
        assertEquals(listOf(row), build(partial).snapshot.commitments!!.rows)
        val complete = build(partial.copy(coverage = partial.coverage.copy(completeUserCommitments = true)))
        assertFalse(DashboardSection.COMMITMENTS in complete.unavailableSections)
        assertEquals(Money.of("100", Currency.SAR), complete.snapshot.commitments!!.total)
        assertEquals(Money.of("100", Currency.SAR), complete.snapshot.commitments.paid)
        assertEquals(Money.of("0", Currency.SAR), complete.snapshot.commitments.remaining)
    }

    @Test fun priorStatementPaymentWindowAndForeignObligationCannotBecomeUnpaidOrZero() {
        val facility = OwnedAsset(account.id.copy(localId = "facility"), AssetKind.CREDIT_FACILITY)
        val card = OwnedAsset(account.id.copy(localId = "card"), AssetKind.CREDIT_CARD, "7271", facility.id, CreditCardRole.PRIMARY)
        val period = DisplayPeriod(query.from, query.until, zone, PeriodKind.SALARY, null)
        val base = batch(emptyList()).copy(assets = listOf(account, facility, card), periods = DashboardPeriods(salary = period),
            coverage = DashboardCoverage(true, true, true, 0, completeUserCommitments = true),
            assetFacts = listOf(DashboardAssetFacts(facility.id, statement = ObservedStatement(Money.of("100", Currency.SAR),
                query.from.minusSeconds(86400), first))))
        // A payment between issue and salary start is outside the provided read windows. The
        // original reader includes those payments. We cannot prove PAID or UNPAID from this batch.
        val prior = build(base)
        assertTrue(prior.snapshot.commitments!!.rows.isEmpty()); assertNull(prior.snapshot.commitments.total)
        assertTrue(DashboardSection.COMMITMENTS in prior.unavailableSections)
        val current = build(base.copy(assetFacts = listOf(DashboardAssetFacts(facility.id,
            statement = ObservedStatement(Money.of("100", Currency.SAR), query.from, first)))))
        assertEquals(PaymentStatus.UNPAID, current.snapshot.commitments!!.rows.single().status)
        assertEquals(Money.of("100", Currency.SAR), current.snapshot.commitments.total)
        val foreign = build(base.copy(assetFacts = listOf(DashboardAssetFacts(facility.id,
            statement = ObservedStatement(Money.of("100", Currency.USD), query.from, first)))))
        assertTrue(foreign.snapshot.commitments!!.rows.isEmpty()); assertNull(foreign.snapshot.commitments.total)
    }
    @Test fun scopedChartCategoriesPartitionExistingMoneyAndEvidenceIncludingRefundsAndFees() {
        val rows=listOf(row("purchase",FinancialType.EXPENSE,"10",foreign=true),
            row("refund",FinancialType.REFUND,"3",1,true),row("fee",FinancialType.FEE,"2"),
            row("withdrawal-fee",FinancialType.FEE,"3"),
            row("transfer",FinancialType.EXTERNAL_TRANSFER_OUT,"5"),row("bill",FinancialType.BILL_PAYMENT,"7"))
            .map {(row,facts) -> row to facts.copy(messageFamily=when(row.posting.command.type) {
                FinancialType.REFUND -> DashboardEvidenceFamily.REFUND
                FinancialType.FEE -> if(row.posting.command.key.transactionId=="withdrawal-fee") DashboardEvidenceFamily.WITHDRAWAL else DashboardEvidenceFamily.FEE
                FinancialType.BILL_PAYMENT -> DashboardEvidenceFamily.BILL_PAYMENT
                FinancialType.EXTERNAL_TRANSFER_OUT -> DashboardEvidenceFamily.TRANSFER_OUT
                else -> DashboardEvidenceFamily.PURCHASE
            })}
        val fee=LinkedInternationalFee(rows.first().first.posting.command.key,rows.first().first.posting.command.revision,
            rows.first().second.evidenceId,Money.of("1.25",Currency.SAR))
        val result=build(batch(rows,listOf(fee)))
        for(trend in result.snapshot.analysis!!.trends.filter {it.category==null}) {
            assertTrue(trend.categoryTrends.isNotEmpty())
            trend.points.forEachIndexed {index,point ->
                val pieces=trend.categoryTrends.values.map {part -> part.points.singleOrNull {it.date==point.date} ?:
                    DailySpending(point.date,SignedAmount(BigDecimal.ZERO,Currency.SAR),SignedAmount(BigDecimal.ZERO,Currency.SAR),
                        SignedAmount(BigDecimal.ZERO,Currency.SAR),emptySet())}
                same(point.net!!.amount,pieces.fold(BigDecimal.ZERO) {sum,p -> sum+p.net!!.amount})
                same(point.beforeRefunds!!.amount,pieces.fold(BigDecimal.ZERO) {sum,p -> sum+p.beforeRefunds!!.amount})
                same(point.refunds!!.amount,pieces.fold(BigDecimal.ZERO) {sum,p -> sum+p.refunds!!.amount})
                assertEquals(point.transactions,pieces.flatMap {it.transactions}.toSet())
                assertEquals(point.transactions.size,pieces.sumOf {it.transactions.size})
            }
        }
        val accountTrend=result.snapshot.analysis!!.trends.single {it.scope==TrendScope.ASSET && it.assetId==account.id}
        same(BigDecimal("38.82"),accountTrend.categoryTrends.getValue(ChartCategory.ACCOUNT_PURCHASES).totalSpending!!.amount)
        same(BigDecimal("11.27"),accountTrend.categoryTrends.getValue(ChartCategory.UNATTRIBUTED_REFUNDS).totalRefunds!!.amount)
        same(BigDecimal("5"),accountTrend.categoryTrends.getValue(ChartCategory.FEES).totalSpending!!.amount)
        val overall=result.snapshot.analysis!!.trends.single {it.scope==TrendScope.OVERALL && it.category==null}
        same(BigDecimal("3"),overall.categoryTrends.getValue(ChartCategory.CASH_WITHDRAWALS).totalSpending!!.amount)
    }

}
