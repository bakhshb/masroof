package com.baraa.masroof.application.statement

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.statement.BankStatementEntry
import com.baraa.masroof.domain.statement.StatementAccountBalance
import com.baraa.masroof.domain.statement.StatementCoverage
import com.baraa.masroof.domain.statement.StatementComparisonStatus
import com.baraa.masroof.domain.statement.StatementDirection
import java.math.BigDecimal
import java.time.LocalDate

/**
 * In-memory result of one user-initiated comparison.
 *
 * Totals are exact decimals grouped by bank, account, statement period, and
 * currency. Amounts in different currencies are never added. Nothing here is
 * written back to the ledger.
 *
 * [balanceCheck] is [StatementComparisonStatus.UNSUPPORTED] when the file
 * supplied an opening or closing balance. Those figures are echoed and are not
 * checked against SMS history.
 */
data class StatementReconciliationReport(
    val formatVersion: Int,
    /** Declared file coverage. Not the first and last movement dates. */
    val coverage: StatementCoverage,
    val counts: StatementReconciliationCounts,
    val statementLines: List<StatementLineComparison>,
    val ledgerLines: List<LedgerLineComparison>,
    val totals: List<StatementCurrencyTotal>,
    val balances: List<StatementAccountBalance>,
    val balanceCheck: StatementComparisonStatus?,
    /**
     * Distinct matched ledger transactions that count as expenses. A
     * self-transfer is not an expense, so its debit and credit lines are not
     * two fleet payments.
     */
    val matchedFleetPaymentCount: Int,
    /** Distinct matched self-transfers, however many statement sides paired. */
    val matchedSelfTransferCount: Int,
)

data class StatementReconciliationCounts(
    val matched: Int,
    val statementOnly: Int,
    val ledgerOnly: Int,
    val ambiguous: Int,
    val unsupported: Int,
)

data class StatementLineComparison(
    val entry: BankStatementEntry,
    val status: StatementComparisonStatus,
    val ledgerTransactionId: String?,
    val ledgerType: FinancialTransactionType?,
)

data class LedgerLineComparison(
    val transactionId: String,
    val type: FinancialTransactionType,
    val bank: Bank,
    val accountMasked: String,
    val direction: StatementDirection,
    val amount: BigDecimal,
    val currency: Currency,
    val civilDate: LocalDate,
    val status: StatementComparisonStatus,
)

data class StatementCurrencyTotal(
    val bankId: String,
    val accountMasked: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val currency: Currency,
    val matchedCount: Int,
    val matchedDebit: BigDecimal,
    val matchedCredit: BigDecimal,
    val statementOnlyCount: Int,
    val statementOnlyDebit: BigDecimal,
    val statementOnlyCredit: BigDecimal,
    val ledgerOnlyCount: Int,
    val ledgerOnlyDebit: BigDecimal,
    val ledgerOnlyCredit: BigDecimal,
    val ambiguousStatementCount: Int,
    val ambiguousStatementDebit: BigDecimal,
    val ambiguousStatementCredit: BigDecimal,
    val ambiguousLedgerCount: Int,
    val ambiguousLedgerDebit: BigDecimal,
    val ambiguousLedgerCredit: BigDecimal,
    val unsupportedCount: Int,
)
