package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.testsupport.tx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scenario: three owned accounts with self-transfers and external outflow.
 *
 * Account 1: 10,000 in → 5,000 self to A2 + 5,000 expense → remaining 0
 * Account 2: 5,000 in + 5,000 from A1 → 1,000 self to A3 + 7,000 external out → remaining 2,000
 * Account 3: 1,000 from A2 → remaining 1,000
 * Fleet total remaining: 3,000
 */
class OwnedAccountsFlowSummaryTest {
  @Test
    fun fleetTotalRemaining_sumsAllInMinusAllOut() {
    val account1 = "account:bank_aljazira:3001"
    val account2 = "account:bank_aljazira:3002"
    val account3 = "account:bank_aljazira:3003"
    val transactions = listOf(
      tx("a1-in", FinancialTransactionType.EXTERNAL_TRANSFER_IN, "10000", dest = account1, linked = listOf("evt-a1-in")),
      tx("a2-in", FinancialTransactionType.EXTERNAL_TRANSFER_IN, "5000", dest = account2, linked = listOf("evt-a2-in")),
      tx("a1-self", FinancialTransactionType.SELF_TRANSFER, "5000", source = account1, dest = account2, linked = listOf("evt-a1-self")),
      tx("a2-self", FinancialTransactionType.SELF_TRANSFER, "1000", source = account2, dest = account3, linked = listOf("evt-a2-self")),
      tx("a1-pos", FinancialTransactionType.EXPENSE, "5000", source = account1, linked = listOf("evt-a1-pos")),
      tx("a2-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "7000", source = account2, linked = listOf("evt-a2-out")),
    )

    val summary1 = summarizeAccount(account1, transactions)
    val summary2 = summarizeAccount(account2, transactions)
    val summary3 = summarizeAccount(account3, transactions)

    assertEquals(
      SignedMoneyAmount.zero(Currency.SAR),
      summary1.cashPosition().remaining,
    )
    assertEquals(
      SignedMoneyAmount.of(Money.of("2000.00", Currency.SAR)),
      summary2.cashPosition().remaining,
    )
    assertEquals(
      SignedMoneyAmount.of(Money.of("1000.00", Currency.SAR)),
      summary3.cashPosition().remaining,
    )

    val fleet = AccountsSummary.fromSummaries(
      accounts = listOf(
        Bank.BANK_ALJAZIRA to "3001",
        Bank.BANK_ALJAZIRA to "3002",
        Bank.BANK_ALJAZIRA to "3003",
      ),
      summaries = listOf(summary1, summary2, summary3),
    )

    assertEquals(
      SignedMoneyAmount.of(Money.of("3000.00", Currency.SAR)),
      fleet.totalRemaining,
    )
    assertEquals(
      SignedMoneyAmount.of(Money.of("3000.00", Currency.SAR)),
      fleet.externalSummary()?.remaining,
    )
    assertEquals(fleet.totalRemaining, fleet.accounts.map { it.cashPosition }.reduce { acc, position -> acc.plus(position) })
  }

  @Test
  fun totalIncome_doesNotCountTransferToAnotherOwnedAccount() {
    val account1 = "account:bank_aljazira:3001"
    val account2 = "account:bank_aljazira:3002"
    val transactions = listOf(
      tx("received", FinancialTransactionType.EXTERNAL_TRANSFER_IN, "10000", dest = account1, linked = listOf("evt-received")),
      tx("moved", FinancialTransactionType.SELF_TRANSFER, "10000", source = account1, dest = account2, linked = listOf("evt-moved")),
    )

    val fleet = AccountsSummary.fromSummaries(
      accounts = listOf(
        Bank.BANK_ALJAZIRA to "3001",
        Bank.BANK_ALJAZIRA to "3002",
      ),
      summaries = listOf(
        summarizeAccount(account1, transactions),
        summarizeAccount(account2, transactions),
      ),
    )

    assertEquals(Money.of("10000.00", Currency.SAR), fleet.totalInflow)
    assertEquals(Money.zero(Currency.SAR), fleet.totalOutflow)
    assertEquals(
      SignedMoneyAmount.of(Money.of("10000.00", Currency.SAR)),
      fleet.totalRemaining,
    )
  }

  @Test
  fun externalMovement_excludesSelfTransferFromAccountRemaining_likeV019() {
    val summary = CurrentAccountSummary.of(
      currency = Currency.SAR,
      salary = Money.of("31731.68", Currency.SAR),
      otherIncome = Money.zero(Currency.SAR),
      externalTransfersIn = Money.of("34293.00", Currency.SAR),
      selfTransfersIn = Money.zero(Currency.SAR),
      creditCardPayments = Money.zero(Currency.SAR),
      billPayments = Money.of("2345.52", Currency.SAR),
      externalTransfersOut = Money.of("5304.00", Currency.SAR),
      cashWithdrawals = Money.zero(Currency.SAR),
      posPurchases = Money.zero(Currency.SAR),
      fees = Money.of("3036.11", Currency.SAR),
      selfTransfersOut = Money.of("76078.00", Currency.SAR),
    )

    assertEquals(
      SignedMoneyAmount.of(Money.of("55339.05", Currency.SAR)),
      summary.externalMovement().remaining,
    )
    assertEquals(
      SignedMoneyAmount.difference(summary.inflow.total, summary.outflow.total),
      summary.cashPosition().remaining,
    )
  }

  @Test
  fun cashPosition_includesSelfTransfers_externalMovement_excludesThem() {
    val summary = CurrentAccountSummary.of(
      currency = Currency.SAR,
      salary = Money.of("1000", Currency.SAR),
      otherIncome = Money.zero(Currency.SAR),
      externalTransfersIn = Money.zero(Currency.SAR),
      selfTransfersIn = Money.zero(Currency.SAR),
      creditCardPayments = Money.zero(Currency.SAR),
      billPayments = Money.of("500", Currency.SAR),
      externalTransfersOut = Money.zero(Currency.SAR),
      cashWithdrawals = Money.zero(Currency.SAR),
      posPurchases = Money.zero(Currency.SAR),
      fees = Money.zero(Currency.SAR),
      selfTransfersOut = Money.of("200", Currency.SAR),
    )

    assertEquals(
      SignedMoneyAmount.of(Money.of("300.00", Currency.SAR)),
      summary.cashPosition().remaining,
    )
    assertEquals(
      SignedMoneyAmount.of(Money.of("500.00", Currency.SAR)),
      summary.externalMovement().remaining,
    )
  }

  private fun summarizeAccount(
    containerId: String,
    transactions: List<FinancialTransaction>,
  ): CurrentAccountSummary =
    CurrentAccountSummaryCalculator.summarize(
      transactions = transactions,
      parsedRecords = emptyList(),
      ownedAccountContainerIds = setOf(containerId),
    )
}
