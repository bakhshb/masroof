package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.domain.period.FinancialPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSpendingTrendsTest {
    private val bank = Bank.BANK_ALJAZIRA
    private val otherBank = Bank("OTHER")
    private val date = LocalDate.parse("2026-07-20")
    private val period = FinancialPeriod(date, date.plusDays(3))

    @Test
    fun accountChartsIncludeConfirmedOutflows_whileOverallKeepsItsOwnScope() {
        val accountId = FinancialContainerIdFactory.accountId(bank, "1111")
        val registry = listOf(AccountRegistryEntry.forTest(bank, "1111"))
        val purchase = transaction("purchase", "100", accountId)
        val withdrawal = transaction("withdrawal", "400", accountId).copy(type = FinancialTransactionType.CASH_WITHDRAWAL)
        val transfer = transaction("external", "500", accountId).copy(type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT)
        val installment = transaction("installment", "1800", accountId).copy(type = FinancialTransactionType.LOAN_REPAYMENT)
        val refund = transaction("refund", "20", null).copy(type = FinancialTransactionType.REFUND, destinationContainerId = accountId)
        val input = context(registry, listOf(purchase,withdrawal,transfer,installment,refund))
        val result = DashboardSpendingTrendsBuilder.build(input, AccountsDashboardProjection.build(input),
            CardsDashboardSection(CreditFacilitiesOverview(emptyList(),emptyList(),Currency.SAR),emptyMap(),emptyMap()))
        assertEquals("2780.00", result.accounts.totalSpending.amount.toPlainString())
        assertEquals("2780.00", result.byAccount.getValue("${bank.id}:1111").totalSpending.amount.toPlainString())
        assertEquals("2780.00", result.overall.totalSpending.amount.toPlainString())
        assertEquals("2800.00", result.accounts.totalBeforeRefunds.amount.toPlainString())
        assertEquals("20.00", result.accounts.totalRefunds.amount.toPlainString())
        assertEquals(setOf("purchase","withdrawal","external","installment","refund"), result.accounts.transactionIdsByDate[date])
        assertPartitions(result)
        val selected = ChartDataWorkflow.select(result.accounts, setOf(ChartDataCategory.CASH_WITHDRAWALS, ChartDataCategory.LOAN_REPAYMENTS))
        assertEquals("2200.00", selected.totalSpending.amount.toPlainString())
        assertEquals("733.33", selected.averageDailySpending.amount.toPlainString())
        assertEquals(setOf("withdrawal", "installment"), selected.transactionIdsByDate[date])
    }

    @Test
    fun accountAndCardScopes_areBankQualified_andAggregateDistinctTransactions() {
        val accounts = listOf(AccountRegistryEntry.forTest(bank, "1111"), AccountRegistryEntry.forTest(otherBank, "1111"))
        val purchase = transaction("purchase", "100", FinancialContainerIdFactory.cardId(bank, "2222"))
        val otherPurchase = transaction("other", "50", FinancialContainerIdFactory.cardId(otherBank, "2222"))
        val accountExpense = transaction("account", "30", FinancialContainerIdFactory.accountId(bank, "1111"))
        val context = context(accounts, listOf(purchase, otherPurchase, accountExpense))
        val accountSection = AccountsDashboardProjection.build(context).copy(
            transactionAccountInvolvement = mapOf(purchase.id to setOf(FinancialContainerIdFactory.accountId(bank, "1111"))),
        )
        val primary = row(bank, "2222")
        val supplementary = row(bank, "3333")
        val firstFacility = facility(primary, listOf(supplementary))
        val secondFacility = facility(row(otherBank, "2222"))
        val cards = CardsDashboardSection(
            CreditFacilitiesOverview(listOf(firstFacility, secondFacility), emptyList(), Currency.SAR),
            // A shared evidence row must count once in the aggregate, even if two card scopes include it.
            mapOf(purchase.id to setOf("${bank.id}:2222", "${bank.id}:3333")), emptyMap(),
        )
        val result = DashboardSpendingTrendsBuilder.build(context, accountSection, cards)
        assertEquals("180.00", result.overall.totalSpending.amount.toPlainString())
        assertEquals("30.00", result.accounts.totalSpending.amount.toPlainString())
        assertEquals("150.00", result.creditCards.totalSpending.amount.toPlainString())
        assertEquals("30.00", result.byAccount.getValue("${bank.id}:1111").totalSpending.amount.toPlainString())
        assertEquals("0.00", result.byAccount.getValue("${otherBank.id}:1111").totalSpending.amount.toPlainString())
        assertEquals("100.00", result.byCard.getValue("${bank.id}:3333").totalSpending.amount.toPlainString())
        assertEquals("50.00", result.byCard.getValue("${otherBank.id}:2222").totalSpending.amount.toPlainString())
    }

    @Test
    fun debitScope_usesPersistedAttributionWithoutRequiringAnAccountInTheSms() {
        val purchase = transaction("googlepay", "120", null)
        val context = context(emptyList(), listOf(purchase))
        val debit = DebitCardOverview(bank, "2210", "Mada", null, null, null, SignedMoneyAmount.zero(Currency.SAR), null)
        val cards = CardsDashboardSection(CreditFacilitiesOverview(emptyList(), listOf(debit), Currency.SAR),
            emptyMap(), mapOf(purchase.id to setOf("${bank.id}:2210")))
        val result = DashboardSpendingTrendsBuilder.build(context, AccountsDashboardProjection.build(context), cards)
        assertEquals("120.00", result.byCard.getValue("${bank.id}:2210").totalSpending.amount.toPlainString())
        assertEquals("0.00", result.creditCards.totalSpending.amount.toPlainString())
    }

    @Test
    fun registryLinkedDebitWithoutSmsAccount_includesPurchaseAndRefundInAccountChartAndMovements() {
        val accountId = FinancialContainerIdFactory.accountId(bank, "1111")
        val cardId = FinancialContainerIdFactory.cardId(bank, "2210")
        val purchase = transaction("googlepay", "120", cardId).copy(linkedParsedEventIds = listOf("evt-purchase"))
        val refund = transaction("refund", "20", null).copy(type = FinancialTransactionType.REFUND,
            destinationContainerId = cardId, linkedParsedEventIds = listOf("evt-refund"))
        val creditRefund = refund.copy(id = "credit-refund", linkedParsedEventIds = listOf("evt-credit"))
        val accounts = listOf(AccountRegistryEntry.forTest(bank, "1111"), AccountRegistryEntry.forTest(otherBank, "1111"))
        val context = context(accounts, listOf(purchase, refund, creditRefund)).copy(
            evidence = DashboardEvidence(listOf(
                debitRecord("evt-purchase", MessageFamily.PURCHASE),
                debitRecord("evt-refund", MessageFamily.REFUND),
                debitRecord("evt-credit", MessageFamily.REFUND).copy(details = ParsedEventDetails(cardSmsChannel = CardSmsChannel.CREDIT)),
            ), emptyMap()),
            debitCardScope = DebitCardScopeFacts(setOf(cardId), mapOf(cardId to accountId)),
        )
        val accountSection = AccountsDashboardProjection.build(context)
        val cards = CardsDashboardSection(CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR), emptyMap(), emptyMap())
        val trends = DashboardSpendingTrendsBuilder.build(context, accountSection, cards)
        assertEquals(setOf(accountId), accountSection.transactionAccountInvolvement[purchase.id])
        assertEquals(setOf(accountId), accountSection.transactionAccountInvolvement[refund.id])
        assertEquals(emptySet<String>(), accountSection.transactionAccountInvolvement[creditRefund.id])
        assertEquals(Money.of("120", Currency.SAR), accountSection.fleet.outflow.posPurchases)
        assertEquals(Money.of("20", Currency.SAR), accountSection.fleet.inflow.accountRefunds)
        assertEquals("100.00", trends.byAccount.getValue("${bank.id}:1111").totalSpending.amount.toPlainString())
        assertEquals("0.00", trends.byAccount.getValue("${otherBank.id}:1111").totalSpending.amount.toPlainString())
        assertEquals("100.00", trends.accounts.totalSpending.amount.toPlainString())
        assertEquals("33.33", trends.accounts.averageDailySpending.amount.toPlainString())
    }

    @Test
    fun purchasesDefault_keepsCardPurchasesCashAndExternalTransfers_withoutDuplicateMovements() {
        val accountId = FinancialContainerIdFactory.accountId(bank, "1111")
        val cardId = FinancialContainerIdFactory.cardId(bank, "2222")
        val credit = transaction("credit", "100", cardId)
        val debit = transaction("debit", "120", accountId)
        val cash = transaction("cash", "200", accountId).copy(type = FinancialTransactionType.CASH_WITHDRAWAL)
        val unknownCash = cash.copy(id = "cash-without-card", amount = Money.of("50", Currency.SAR))
        val external = cash.copy(id = "external", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT, amount = Money.of("300", Currency.SAR))
        val refund = debit.copy(id = "refund", type = FinancialTransactionType.REFUND, amount = Money.of("20", Currency.SAR))
        val excluded = listOf(
            transaction("account-only", "70", accountId),
            transaction("bill", "80", cardId).copy(type = FinancialTransactionType.BILL_PAYMENT),
            transaction("fee", "10", cardId).copy(type = FinancialTransactionType.FEE),
            transaction("loan", "150", accountId).copy(type = FinancialTransactionType.LOAN_REPAYMENT),
            transaction("settlement", "100", accountId).copy(type = FinancialTransactionType.CREDIT_CARD_PAYMENT),
            transaction("self-transfer", "500", accountId).copy(type = FinancialTransactionType.SELF_TRANSFER),
        )
        val input = context(listOf(AccountRegistryEntry.forTest(bank, "1111")),
            listOf(credit, credit, debit, cash, unknownCash, external, refund) + excluded)
        val cards = CardsDashboardSection(
            CreditFacilitiesOverview(listOf(facility(row(bank, "2222"))), listOf(mada("2210"), mada("2211")), Currency.SAR),
            mapOf(credit.id to setOf("${bank.id}:2222")),
            listOf(debit, cash, refund).associate { it.id to setOf("${bank.id}:2210") },
        )
        val trends = DashboardSpendingTrendsBuilder.build(input, AccountsDashboardProjection.build(input), cards)
        assertEquals("750.00", trends.purchases.totalSpending.amount.toPlainString())
        assertEquals("250.00", trends.purchases.averageDailySpending.amount.toPlainString())
        assertEquals(setOf("credit", "debit", "cash", "cash-without-card", "external", "refund"), trends.purchases.transactionIdsByDate[date])
        assertEquals("1060.00", trends.overall.totalSpending.amount.toPlainString())
        assertEquals("300.00", trends.byCard.getValue("${bank.id}:2210").totalSpending.amount.toPlainString())
        assertEquals("100.00", trends.byCard.getValue("${bank.id}:2210").averageDailySpending.amount.toPlainString())
        assertEquals("0.00", trends.byCard.getValue("${bank.id}:2211").totalSpending.amount.toPlainString())
        assertEquals("300.00", trends.debitCards!!.totalSpending.amount.toPlainString())
    }

    @Test
    fun persistedFamiliesExcludeCardBillsSettlementsAndInstallments_evenWithExpenseType() {
        val cardId = FinancialContainerIdFactory.cardId(bank, "2222")
        val families = listOf(MessageFamily.PURCHASE, MessageFamily.BILL_PAYMENT,
            MessageFamily.CARD_PAYMENT, MessageFamily.FINANCING_INSTALLMENT, MessageFamily.FEE, MessageFamily.WITHDRAWAL)
        val rows = families.mapIndexed { i, _ -> transaction("row-$i", "100", cardId).copy(linkedParsedEventIds = listOf("event-$i")) }
        val input = context(emptyList(), rows).copy(evidence = DashboardEvidence(families.mapIndexed { i, family ->
            debitRecord("event-$i", family).copy(event = debitRecord("event-$i", family).event.copy(cardRef = CardReference(bank, "2222")),
                details = ParsedEventDetails(cardSmsChannel = CardSmsChannel.CREDIT))
        }, emptyMap()))
        val cards = CardsDashboardSection(CreditFacilitiesOverview(listOf(facility(row(bank, "2222"))), emptyList(), Currency.SAR),
            rows.associate { it.id to setOf("${bank.id}:2222") }, emptyMap())
        val trend = DashboardSpendingTrendsBuilder.build(input, AccountsDashboardProjection.build(input), cards).purchases
        assertEquals("200.00", trend.totalSpending.amount.toPlainString())
        assertEquals(setOf("row-0", "row-5"), trend.transactionIdsByDate[date])
    }

    @Test
    fun accountsCountSettlementsAndSourceInternalLeg_onlyDetailsIncludeInternal() {
        val a = FinancialContainerIdFactory.accountId(bank, "1111")
        val b = FinancialContainerIdFactory.accountId(bank, "2222")
        fun out(id: String, value: String, type: FinancialTransactionType) = transaction(id, value, a).copy(type = type)
        val cashRows = listOf(transaction("purchase", "30", a),
            out("bill", "40", FinancialTransactionType.BILL_PAYMENT), out("loan", "50", FinancialTransactionType.LOAN_REPAYMENT),
            out("settlement", "60", FinancialTransactionType.CREDIT_CARD_PAYMENT), out("external", "70", FinancialTransactionType.EXTERNAL_TRANSFER_OUT),
            out("cash", "80", FinancialTransactionType.CASH_WITHDRAWAL), out("fee", "10", FinancialTransactionType.FEE))
        val internal = out("internal", "100", FinancialTransactionType.SELF_TRANSFER).copy(destinationContainerId = b)
        val back = internal.copy(id = "internal-back", sourceContainerId = b, destinationContainerId = a, amount = Money.of("35", Currency.SAR))
        val refund = transaction("refund", "20", null).copy(type = FinancialTransactionType.REFUND, destinationContainerId = a)
        val credit = transaction("credit", "90", a).copy(linkedParsedEventIds = listOf("cc-purchase"))
        val creditRefund = refund.copy(id = "credit-refund", amount = Money.of("15", Currency.SAR), linkedParsedEventIds = listOf("cc-refund"))
        val salary = refund.copy(id = "salary", type = FinancialTransactionType.INCOME, amount = Money.of("1000", Currency.SAR))
        val inbound = salary.copy(id = "external-in", type = FinancialTransactionType.EXTERNAL_TRANSFER_IN)
        val input = context(listOf(AccountRegistryEntry.forTest(bank, "1111"), AccountRegistryEntry.forTest(bank, "2222")),
            cashRows + listOf(internal, back, refund, credit, creditRefund, salary, inbound, internal)).copy(
            evidence = DashboardEvidence(listOf(debitRecord("cc-purchase", MessageFamily.PURCHASE), debitRecord("cc-refund", MessageFamily.REFUND))
                .map { it.copy(details = ParsedEventDetails(cardSmsChannel = CardSmsChannel.CREDIT)) }, emptyMap()))
        val emptyCards = CardsDashboardSection(CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR), emptyMap(), emptyMap())
        val result = DashboardSpendingTrendsBuilder.build(input, AccountsDashboardProjection.build(input), emptyCards)
        assertEquals("320.00", result.accounts.totalSpending.amount.toPlainString())
        assertEquals("106.67", result.accounts.averageDailySpending.amount.toPlainString())
        assertEquals("420.00", result.byAccount.getValue("${bank.id}:1111").totalSpending.amount.toPlainString())
        assertEquals("35.00", result.byAccount.getValue("${bank.id}:2222").totalSpending.amount.toPlainString())
        assertEquals((cashRows.map { it.id } + refund.id).toSet(), result.accounts.transactionIdsByDate[date])
        assertEquals((cashRows.map { it.id } + listOf(refund.id, internal.id)).toSet(), result.byAccount.getValue("${bank.id}:1111").transactionIdsByDate[date])
        assertEquals(setOf(back.id), result.byAccount.getValue("${bank.id}:2222").transactionIdsByDate[date])
        assertEquals("335.00", result.overall.totalSpending.amount.toPlainString())
    }

    @Test
    fun accountSourceIsBankQualified_unknownSourcesAndForeignRefundsStayOut() {
        val a = FinancialContainerIdFactory.accountId(bank, "1111")
        val other = FinancialContainerIdFactory.accountId(otherBank, "1111")
        val rows = listOf(transaction("own", "25", a), transaction("other", "50", other), transaction("unknown", "200", null),
            transaction("unknown-bill", "300", null).copy(type = FinancialTransactionType.BILL_PAYMENT),
            transaction("foreign-refund", "100", null).copy(type = FinancialTransactionType.REFUND, destinationContainerId = other))
        val input = context(listOf(AccountRegistryEntry.forTest(bank, "1111")), rows)
        val cards = CardsDashboardSection(CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR), emptyMap(), emptyMap())
        val result = DashboardSpendingTrendsBuilder.build(input, AccountsDashboardProjection.build(input), cards)
        assertEquals("25.00", result.accounts.totalSpending.amount.toPlainString())
        assertEquals(setOf("own"), result.accounts.transactionIdsByDate[date])
    }

    @Test
    fun accountFeeInstallmentAndResolvedFx_areCountedWithTheirActualAmounts() {
        val a = FinancialContainerIdFactory.accountId(bank, "1111")
        val fee = transaction("installment", "180", a).copy(type = FinancialTransactionType.FEE, linkedParsedEventIds = listOf("financing"))
        val record = debitRecord("financing", MessageFamily.FINANCING_INSTALLMENT).copy(
            event = debitRecord("financing", MessageFamily.FINANCING_INSTALLMENT).event.copy(cardRef = null),
            details = ParsedEventDetails(loanType = com.baraa.masroof.domain.model.LoanType.PERSONAL))
        val foreign = transaction("usd", "10", a).copy(amount = Money.of("10", Currency.USD))
        val unresolved = foreign.copy(id = "unresolved")
        val input = context(listOf(AccountRegistryEntry.forTest(bank, "1111")), listOf(fee, foreign, unresolved)).copy(
            evidence = DashboardEvidence(listOf(record), emptyMap()), sarEquivalents = mapOf(foreign.id to Money.of("37.50", Currency.SAR)))
        val cards = CardsDashboardSection(CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR), emptyMap(), emptyMap())
        val result = DashboardSpendingTrendsBuilder.build(input, AccountsDashboardProjection.build(input), cards)
        assertEquals("217.50", result.accounts.totalSpending.amount.toPlainString())
        assertEquals("72.50", result.accounts.averageDailySpending.amount.toPlainString())
        assertEquals(setOf(fee.id, foreign.id), result.accounts.transactionIdsByDate[date])
    }

    @Test
    fun creditAttributionWinsOverAccountLink_whileItsSettlementStillCounts() {
        val a = FinancialContainerIdFactory.accountId(bank, "1111")
        val credit = transaction("credit", "100", a)
        val refund = transaction("credit-refund", "20", null).copy(type = FinancialTransactionType.REFUND, destinationContainerId = a)
        val settlement = transaction("settlement", "80", a).copy(type = FinancialTransactionType.CREDIT_CARD_PAYMENT)
        val input = context(listOf(AccountRegistryEntry.forTest(bank, "1111")), listOf(credit, refund, settlement))
        val cards = CardsDashboardSection(CreditFacilitiesOverview(listOf(facility(row(bank, "3333"))), emptyList(), Currency.SAR),
            listOf(credit, refund, settlement).associate { it.id to setOf("${bank.id}:3333") }, emptyMap())
        val result = DashboardSpendingTrendsBuilder.build(input, AccountsDashboardProjection.build(input), cards)
        assertEquals("80.00", result.accounts.totalSpending.amount.toPlainString())
        assertEquals(setOf(settlement.id), result.accounts.transactionIdsByDate[date])
        assertEquals("80.00", result.creditCards.totalSpending.amount.toPlainString())
        assertPartitions(result)
    }

    private fun mada(last4: String) = DebitCardOverview(bank, last4, "Mada", "Account", "1111", null,
        SignedMoneyAmount.zero(Currency.SAR), null)

    private fun assertPartitions(result: DashboardSpendingTrends) {
        val trends = listOf(result.overall, result.purchases, result.accounts, result.creditCards) +
            result.byAccount.values + result.byCard.values + listOfNotNull(result.debitCards)
        trends.forEach { trend ->
            org.junit.Assert.assertTrue(trend.categoryTrends.isNotEmpty())
            trend.points.forEach { point ->
                val parts = trend.categoryTrends.values.map { part -> part.points.first { it.date == point.date } }
                assertEquals(point.spending.amount, parts.fold(java.math.BigDecimal.ZERO.setScale(2)) { sum, part -> sum + part.spending.amount })
                assertEquals(point.beforeRefunds.amount, parts.fold(java.math.BigDecimal.ZERO.setScale(2)) { sum, part -> sum + part.beforeRefunds.amount })
                assertEquals(point.refunds.amount, parts.fold(java.math.BigDecimal.ZERO.setScale(2)) { sum, part -> sum + part.refunds.amount })
                assertEquals(trend.transactionIdsByDate[point.date].orEmpty(), trend.categoryTrends.values.flatMap { it.transactionIdsByDate[point.date].orEmpty() }.toSet())
            }
        }
    }

    private fun debitRecord(id: String, family: MessageFamily) = ParsedEventRecord(
        event = ParsedEvent(id = id, rawSmsId = "sms-$id", bank = bank, messageFamily = family,
            direction = MoneyDirection.OUTGOING, amount = Money.of("120", Currency.SAR),
            purchaseChannel = null, sourceAccountRef = null, destinationAccountRef = null,
            cardRef = CardReference(bank, "2210"), merchant = null, counterparty = null,
            occurredAt = Instant.parse("2026-07-20T07:00:00Z"), bankNetworkType = null,
            confidence = Confidence(1.0), parseStatus = ParseStatus.SUCCESS),
        details = ParsedEventDetails(cardSmsChannel = CardSmsChannel.DEBIT),
    )

    private fun context(accounts: List<AccountRegistryEntry>, transactions: List<FinancialTransaction>) = DashboardProjectionContext(
        period, Instant.parse("2026-07-23T00:00:00Z"), date.plusDays(2), DashboardEvidence.EMPTY,
        transactions, emptyMap(), 0, accounts,
        accounts.map { FinancialContainerIdFactory.accountId(it.bank, it.maskedNumber) }.toSet(),
        accounts.map { it.maskedNumber }.toSet(), emptyList(), emptyList(),
        DebitCardScopeFacts(emptySet(), emptyMap()), Locale.ENGLISH, ZoneId.of("Asia/Riyadh"), Currency.SAR,
    )

    private fun transaction(id: String, amount: String, source: String?) = FinancialTransaction(
        id, FinancialTransactionType.EXPENSE, Money.of(amount, Currency.SAR), Instant.parse("2026-07-20T07:00:00Z"),
        source, null, null, null, null, emptyList(),
    )

    private fun row(bank: Bank, last4: String) = CreditCardDashboardRow(bank, last4,
        SignedMoneyAmount.zero(Currency.SAR), SignedMoneyAmount.zero(Currency.SAR), null, null)

    private fun facility(primary: CreditCardDashboardRow, supplementaries: List<CreditCardDashboardRow> = emptyList()) =
        CreditFacilityOverview(primary.bank, primary, supplementaries, null,
            SignedMoneyAmount.zero(Currency.SAR), SignedMoneyAmount.zero(Currency.SAR), null, null, Currency.SAR)
}
