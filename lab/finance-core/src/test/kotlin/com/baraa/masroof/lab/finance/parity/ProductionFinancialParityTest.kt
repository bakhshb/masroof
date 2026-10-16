package com.baraa.masroof.lab.finance.parity

import com.baraa.masroof.application.dashboard.*
import com.baraa.masroof.core.money.Currency as OldCurrency
import com.baraa.masroof.core.money.Money as OldMoney
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.FlowBucket
import com.baraa.masroof.lab.finance.dashboard.SummaryMetric
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.projection.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Cross-runs original, hash-pinned production code. Expected amounts are never a new oracle. */
class ProductionFinancialParityTest {
    private val bank = Bank.BANK_ALJAZIRA
    private val time = Instant.parse("2026-08-01T10:00:00Z")
    private val period = FinancialPeriod(LocalDate.parse("2026-07-27"), LocalDate.parse("2026-08-27"))
    private fun asset(name: String, kind: AssetKind, parent: OwnedAsset? = null,
        role: CreditCardRole? = null) = OwnedAsset(AssetId(Environment.BETA, BankId(bank.id), name), kind,
        if (kind in setOf(AssetKind.CREDIT_CARD, AssetKind.DEBIT_CARD)) "1234" else null, parent?.id, role)
    private val account = asset("account", AssetKind.ACCOUNT)
    private val other = asset("other", AssetKind.ACCOUNT)
    private val facility = asset("facility", AssetKind.CREDIT_FACILITY)
    private val credit = asset("credit", AssetKind.CREDIT_CARD, facility, CreditCardRole.PRIMARY)
    private val supplement = asset("supplement", AssetKind.CREDIT_CARD, facility, CreditCardRole.SUPPLEMENTARY)
    private val debit = asset("debit", AssetKind.DEBIT_CARD, account)
    private val loan = asset("loan", AssetKind.LOAN)
    private val registry = listOf(account, other, facility, credit, supplement, debit, loan).associateBy { it.id }
    private val ids by lazy { mapOf(account to FinancialContainerIdFactory.accountId(bank, "3001"),
        other to FinancialContainerIdFactory.accountId(bank, "3002"),
        credit to FinancialContainerIdFactory.cardId(bank, "1234"),
        supplement to FinancialContainerIdFactory.cardId(bank, "4321"),
        debit to FinancialContainerIdFactory.cardId(bank, "5678"),
        facility to FinancialContainerIdFactory.cardId(bank, "facility"),
        loan to FinancialContainerIdFactory.loanId(bank, LoanType.PERSONAL)) }

    private data class Case(val name: String, val type: FinancialType, val amount: String,
        val source: OwnedAsset? = null, val destination: OwnedAsset? = null,
        val financing: Boolean = false, val currency: Currency = Currency.SAR,
        val fx: AppliedFx? = null, val salary: Boolean = false)

    // Amounts/scenarios retain the existing production MonthlyFinancialSummaryCalculatorTest and
    // ForeignPurchaseSarConverterTest inputs, augmented with explicit confirmed ownership/channel.
    private fun fixtures() = listOf(
        Case("account-purchase", FinancialType.EXPENSE, "100", account),
        Case("debit-purchase", FinancialType.EXPENSE, "100", debit),
        Case("credit-purchase", FinancialType.EXPENSE, "100", credit),
        Case("supplementary-purchase", FinancialType.EXPENSE, "100", supplement),
        Case("credit-settlement", FinancialType.CREDIT_CARD_PAYMENT, "100", account, facility),
        Case("account-refund", FinancialType.REFUND, "20", destination = account),
        Case("debit-refund", FinancialType.REFUND, "20", destination = debit),
        Case("credit-refund", FinancialType.REFUND, "120", destination = credit),
        Case("loan", FinancialType.LOAN_REPAYMENT, "3036.11", account, loan),
        Case("financing-fee", FinancialType.FEE, "3036.11", account, financing = true),
        Case("fee", FinancialType.FEE, "5", account),
        Case("bill", FinancialType.BILL_PAYMENT, "100", account),
        Case("explicit-bill-over-financing", FinancialType.BILL_PAYMENT, "100", account, financing = true),
        Case("withdrawal", FinancialType.CASH_WITHDRAWAL, "500", account),
        Case("external-out", FinancialType.EXTERNAL_TRANSFER_OUT, "300", account),
        Case("income", FinancialType.INCOME, "1000", destination = account, salary = true),
        Case("stored-income-over-other-attribution", FinancialType.INCOME, "1000", destination = account),
        Case("external-in", FinancialType.EXTERNAL_TRANSFER_IN, "200", destination = account),
        Case("salary-transfer-in", FinancialType.EXTERNAL_TRANSFER_IN, "200", destination = account, salary = true),
        Case("self-transfer", FinancialType.SELF_TRANSFER, "500", account, other),
    )

    private fun input(case: Case) = FinancialClassificationInput(PostingKey(Environment.BETA, case.name),
        PostingRevision(0, 1), case.type, Money.of(case.amount, case.currency),
        TransactionTime(time, ZoneId.of("Asia/Riyadh")),
        case.source?.let { FinancialEndpoint.Owned(it.id) } ?: FinancialEndpoint.External,
        case.destination?.let { FinancialEndpoint.Owned(it.id) } ?: FinancialEndpoint.External,
        registry, setOf("event-${case.name}"), true,
        financingLoan = if (case.financing) loan.id else null, appliedFx = case.fx,
        incomeAttribution = if (case.salary) IncomeAttribution.SALARY else IncomeAttribution.OTHER)

    private fun oldTransaction(case: Case) = FinancialTransaction(case.name,
        FinancialTransactionType.valueOf(case.type.name), OldMoney.of(case.amount, OldCurrency.valueOf(case.currency.name)),
        time, ids[case.source], ids[case.destination], null, null, null, listOf("event-${case.name}"),
        case.fx?.rateToSar, case.fx?.let { ExchangeRateSource.valueOf(it.source) }, "Asia/Riyadh")

    private fun record(case: Case): ParsedEventRecord {
        val endpoint = case.source ?: case.destination
        val channel = when (endpoint?.kind) {
            AssetKind.CREDIT_CARD, AssetKind.CREDIT_FACILITY -> CardSmsChannel.CREDIT
            AssetKind.DEBIT_CARD -> CardSmsChannel.DEBIT
            else -> null
        }
        return ParsedEventRecord(ParsedEvent("event-${case.name}", "raw-${case.name}", bank,
            if (case.financing) MessageFamily.FINANCING_INSTALLMENT else when (case.type) {
                FinancialType.EXPENSE -> MessageFamily.PURCHASE
                FinancialType.REFUND -> MessageFamily.REFUND
                else -> MessageFamily.UNKNOWN
            },
            null, oldTransaction(case).amount, null, null, null, null, null, null, time, null,
            Confidence(1.0), ParseStatus.SUCCESS), ParsedEventDetails(cardSmsChannel = channel,
            loanType = if (case.financing) LoanType.PERSONAL else null, salaryIncomeWording = case.salary))
    }

    private fun monthly(cases: List<Case>, equivalents: Map<String, OldMoney> = emptyMap()) =
        MonthlyFinancialSummaryCalculator.summarize(period, cases.map(::oldTransaction), 0,
            sarEquivalents = equivalents, parsedRecords = cases.map(::record))

    private fun oldMetrics(summary: MonthlyFinancialSummary) = mapOf(
        SummaryMetric.SPENDING_GROSS to summary.spendingGross.amount,
        SummaryMetric.REFUNDS to summary.refunds.amount,
        SummaryMetric.SPENDING_NET to summary.spendingNet.amount,
        SummaryMetric.INCOME to summary.income.amount,
        SummaryMetric.EXTERNAL_IN to summary.externalTransfersIn.amount,
        SummaryMetric.EXTERNAL_OUT to summary.externalTransfersOut.amount,
        SummaryMetric.CREDIT_SETTLEMENTS to summary.creditCardPayments.amount,
        SummaryMetric.CASH_WITHDRAWALS to summary.cashWithdrawals.amount,
        SummaryMetric.SELF_TRANSFERS to summary.selfTransfers.amount,
        SummaryMetric.COMBINED_INCOMING to summary.combinedIncoming.amount,
        SummaryMetric.NET_CASH_FLOW to summary.netCashFlow.amount)

    private fun candidate(case: Case) = FinancialImpactClassifier.classify(input(case)) as FinancialClassification.Classified
    private fun assertMonthlyParity(cases: List<Case>) {
        val actual = cases.map(::candidate)
        oldMetrics(monthly(cases)).forEach { (key, expected) ->
            assertEquals("${cases.map { it.name }} $key", 0,
                expected.compareTo(actual.fold(BigDecimal.ZERO) { sum, row -> sum + row.metrics.getValue(key).amount }))
        }
        assertEquals(cases.size, monthly(cases).transactionCount)
    }

    @Test fun originalMonthlyCalculatorMatchesEachResolvedCaseAndWholeBatch() {
        fixtures().forEach { assertMonthlyParity(listOf(it)) }
        assertMonthlyParity(fixtures())
        assertMonthlyParity(emptyList())
    }

    @Test fun refundHeavyAggregateRetainsNegativeSpendingAndPositiveNetCash() {
        val cases = listOf(Case("refund", FinancialType.REFUND, "120", destination = account),
            Case("purchase", FinancialType.EXPENSE, "100", account))
        assertMonthlyParity(cases)
        assertTrue(monthly(cases).spendingNet.amount.signum() < 0)
        assertTrue(monthly(cases).netCashFlow.amount.signum() > 0)
    }

    private fun oldFlows(case: Case): Map<FlowBucket, BigDecimal> {
        val tx = oldTransaction(case)
        val scope = CurrentAccountTransactionScope(setOf(ids.getValue(account), ids.getValue(other)),
            setOf("3001", "3002"), ownedDebitCardContainerIds = setOf(ids.getValue(debit)),
            debitCardLinkedAccountIds = mapOf(ids.getValue(debit) to ids.getValue(account)))
        val context = AccountFlowClassifier.buildContext(listOf(tx), listOf(record(case)), OldCurrency.SAR, emptyMap())
        val assignments = AccountFlowClassifier.classify(tx, scope, context)
        // Vocabulary adapters only. Category decisions run inside unchanged AccountFlowClassifier.
        return assignments.mapNotNull { assignment -> when (assignment) {
            is FlowAssignment.Expense -> when (assignment.category) {
                FlowExpenseCategory.EXTERNAL_TRANSFER_OUT -> FlowBucket.EXTERNAL_OUT
                FlowExpenseCategory.CREDIT_CARD_PAYMENT -> FlowBucket.CREDIT_SETTLEMENT
                FlowExpenseCategory.CASH_WITHDRAWAL -> FlowBucket.CASH_WITHDRAWAL
                FlowExpenseCategory.BILL_PAYMENT -> FlowBucket.BILLS
                FlowExpenseCategory.POS_PURCHASE -> FlowBucket.POS_PURCHASES
                FlowExpenseCategory.FEE -> FlowBucket.FEES
                FlowExpenseCategory.LOAN_REPAYMENT -> FlowBucket.LOAN
            }
            is FlowAssignment.Income -> when (assignment.category) {
                FlowIncomeCategory.SALARY -> FlowBucket.SALARY
                FlowIncomeCategory.OTHER_INCOME -> FlowBucket.OTHER_INCOME
                FlowIncomeCategory.EXTERNAL_TRANSFER_IN -> FlowBucket.EXTERNAL_IN
                FlowIncomeCategory.ACCOUNT_REFUND -> FlowBucket.ACCOUNT_REFUNDS
            }
            is FlowAssignment.SelfTransfer -> if (assignment.leg == SelfTransferLeg.IN) FlowBucket.INTERNAL_IN else FlowBucket.INTERNAL_OUT
            FlowAssignment.Excluded -> null
        } }.groupingBy { it }.fold(BigDecimal.ZERO) { sum, _ -> sum + tx.amount.amount }
    }

    @Test fun originalAccountFlowClassifierMatchesDebitCreditLoanRefundAndResolvedFlowBuckets() {
        fixtures().forEach { case ->
            val expected = oldFlows(case)
            val actual = candidate(case).accountMovements.groupBy { it.bucket }.mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.amount.amount } }
            assertEquals(case.name, expected.keys, actual.keys)
            expected.forEach { (bucket, amount) -> assertEquals("${case.name} $bucket", 0, amount.compareTo(actual.getValue(bucket))) }
        }
    }

    @Test fun originalAccountOutflowTotalsMatchCandidateActualSpendingPerCaseAndAggregate() {
        fun verify(cases: List<Case>) {
            val flows = cases.flatMap { oldFlows(it).entries }.groupBy { it.key }
                .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.value } }
            fun amount(bucket: FlowBucket) = OldMoney.of(flows[bucket] ?: BigDecimal.ZERO, OldCurrency.SAR)
            val original = AccountOutflow(OldCurrency.SAR, amount(FlowBucket.EXTERNAL_OUT),
                amount(FlowBucket.CREDIT_SETTLEMENT), amount(FlowBucket.CASH_WITHDRAWAL),
                amount(FlowBucket.BILLS), amount(FlowBucket.POS_PURCHASES), amount(FlowBucket.FEES),
                amount(FlowBucket.LOAN), amount(FlowBucket.INTERNAL_OUT))
            val actual = cases.fold(BigDecimal.ZERO) { sum, case ->
                sum + candidate(case).metrics.getValue(SummaryMetric.ACTUAL_ACCOUNT_SPENDING).amount }
            assertEquals(cases.map { it.name }.toString(), 0, original.coreTotal.amount.compareTo(actual))
        }
        fixtures().forEach { verify(listOf(it)) }
        verify(fixtures())
        verify(emptyList())
    }

    @Test fun originalLoanAttributionHonorsStoredTypeOverOldFinancingEvidence() {
        fixtures().filter { it.financing || it.type == FinancialType.LOAN_REPAYMENT }.forEach { case ->
            val expected = LoanRepaymentAttribution.isLoanRepayment(oldTransaction(case), listOf(record(case)))
            assertEquals(case.name, expected, candidate(case).settlement == Settlement.LOAN)
        }
    }

    @Test fun originalFxConversionMatchesPersistedCandidateWithoutChangingNativeFacts() {
        // Original approved converter fixtures; no new conversion implementation in this harness.
        data class FxCase(val name: String, val amount: String, val currency: Currency, val rate: String, val fee: String?)
        val cases = listOf(FxCase("usd-purchase", "23", Currency.USD, "3.756957", "1.99"),
            FxCase("usd-refund", "6.51", Currency.USD, "3.756957", null),
            FxCase("eur-purchase", "20", Currency.EUR, "4.3466", null),
            FxCase("gbp-purchase", "10", Currency.GBP, "5.0797", null))
        val report = mutableListOf("M8.4 BEGINNING ONLY; offline synthetic resolved fixtures; no posting authority",
            "Coverage inventory only; completed JUnit results determine assertion status",
            "Oracle provenance: parity/oracle-sha256.properties; original code, test-only",
            "20 cases: 11 monthly metrics per-case and aggregate; account flow buckets; stored-INCOME precedence; loan precedence; zero and negative totals",
            "FX_PERSISTED_RATE_PARITY: FOUR_ORIGINAL_CONVERTER_CASES; native facts retained")
        cases.forEach { fx ->
            val refund = fx.name.contains("refund")
            val case = Case(fx.name, if (refund) FinancialType.REFUND else FinancialType.EXPENSE, fx.amount,
                source = if (refund) null else account, destination = if (refund) account else null,
                currency = fx.currency, fx = AppliedFx(BigDecimal(fx.rate), "SMS"))
            val converted = ForeignPurchaseSarConverter.foreignToSar(oldTransaction(case).amount,
                BigDecimal(fx.rate), fx.fee?.let { OldMoney.of(it, OldCurrency.SAR) })!!
            val old = monthly(listOf(case), mapOf(case.name to converted))
            val new = candidate(case)
            val projection = PersistedSarProjection.project(input(case), fx.fee?.let {
                LinkedInternationalFee(input(case).key, input(case).revision, "event-${case.name}", Money.of(it, Currency.SAR))
            }) as SarRowProjection.Available
            assertEquals(fx.currency, new.input.amount.currency)
            assertEquals(case.fx, new.input.appliedFx)
            assertEquals(OldCurrency.SAR, old.currency)
            assertEquals(fx.currency, new.metrics.getValue(SummaryMetric.SPENDING_NET).currency)
            assertEquals(0, converted.amount.compareTo(projection.sarAmount.amount))
            oldMetrics(old).forEach { (key, expected) ->
                assertEquals("${case.name} $key", 0, expected.compareTo(projection.metrics.getValue(key).amount))
            }
            assertEquals(new, projection.native)
            assertEquals(0, monthly(listOf(case)).spendingGross.amount.signum())
            assertEquals(1, monthly(listOf(case)).excludedOtherCurrencyCount)
            report += "${fx.name}: reference_sar=${if (refund) old.refunds.amount else old.spendingGross.amount}; candidate_sar=${projection.sarAmount.amount}; native=${new.input.amount.amount} ${fx.currency}"
        }
        report += "ADDITIONAL_JUNIT: mixed native/foreign 11 monthly metrics, fees/refunds/loan/settlement; zero/negative totals; source precedence; period boundaries; missing FX unavailable"
        report += "REMAINING: durable ledger/correction integration, actual enrichment/market/historical lookup parity, statement due/outstanding balances, full facility/loan/dashboard projections and real-holdout/performance approval"
        val output = Path.of(System.getProperty("m8.parity.report"))
        Files.createDirectories(output.parent)
        Files.writeString(output, report.joinToString("\n", postfix = "\n"))
    }

    @Test fun mixedCurrencyPeriodCrossRunsAllMonthlyMetricsIncludingFeesLoansAndCreditSettlements() {
        val cases = fixtures().flatMap { original -> listOf(original, original.copy(
            name = "usd-${original.name}", currency = Currency.USD,
            fx = AppliedFx(BigDecimal("3.756957"), "SMS"))) }
        val fees = cases.filter { it.currency != Currency.SAR }.map { case ->
            LinkedInternationalFee(input(case).key, input(case).revision, "event-${case.name}", Money.of("1.99", Currency.SAR)) }
        val equivalents = cases.filter { it.currency != Currency.SAR }.associate { case -> case.name to
            ForeignPurchaseSarConverter.foreignToSar(oldTransaction(case).amount, case.fx!!.rateToSar,
                if (case.type == FinancialType.REFUND) null else OldMoney.of("1.99", OldCurrency.SAR))!! }
        fun verify(selected: List<Case>) {
            val keys = selected.map { input(it).key }.toSet()
            val actual = SarPeriodProjector.project(Environment.BETA,
                com.baraa.masroof.lab.finance.dashboard.DisplayPeriod(time.minusSeconds(1), time.plusSeconds(1),
                    ZoneId.of("Asia/Riyadh"), com.baraa.masroof.lab.finance.dashboard.PeriodKind.SELECTED, null),
                selected.map(::input), fees.filter { it.key in keys })
            assertTrue(actual.unavailable.isEmpty())
            oldMetrics(monthly(selected, equivalents)).forEach { (metric, expected) ->
                assertEquals("$metric ${selected.map { it.name }}", 0,
                    expected.compareTo(actual.totals!!.getValue(metric).amount))
            }
        }
        cases.forEach { verify(listOf(it)) }
        verify(cases)
        verify(emptyList())
        verify(cases.filter { it.type == FinancialType.REFUND })
    }
}
