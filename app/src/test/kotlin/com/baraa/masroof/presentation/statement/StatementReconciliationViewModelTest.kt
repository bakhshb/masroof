package com.baraa.masroof.presentation.statement

import com.baraa.masroof.application.statement.StatementCurrencyTotal
import com.baraa.masroof.application.statement.StatementImportResult
import com.baraa.masroof.application.statement.StatementLineComparison
import com.baraa.masroof.application.statement.StatementReconciliationCounts
import com.baraa.masroof.application.statement.StatementReconciliationReport
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.statement.BankStatementEntry
import com.baraa.masroof.domain.statement.StatementComparisonStatus
import com.baraa.masroof.domain.statement.StatementCoverage
import com.baraa.masroof.domain.statement.StatementDirection
import com.baraa.masroof.domain.statement.StatementRejection
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen state model. The emulator journey is StatementReconciliationSmokeTest.
 */
class StatementReconciliationViewModelTest {
    @Test
    fun rejectedFile_isVisibleAsRejectionWithoutAReport() {
        val state = statementReconciliationUiState(
            StatementImportResult.Rejected(StatementRejection.OVERSIZED),
        )
        assertEquals(StatementRejection.OVERSIZED, state.rejection)
        assertFalse(state.hasReport)
        assertFalse(state.busy)
        assertTrue(state.totals.isEmpty())
    }

    @Test
    fun comparedReport_keepsCurrencyTotalsAndCountsSeparate() {
        val state = statementReconciliationUiState(StatementImportResult.Compared(sampleReport()))
        assertTrue(state.hasReport)
        assertEquals(9, state.matchedCount)
        assertEquals(1, state.statementOnlyCount)
        assertEquals(0, state.matchedFleetPaymentCount)
        assertEquals(1, state.matchedSelfTransferCount)
        assertEquals("591.25 SAR", state.totals.single { it.currencyCode == "SAR" }.matchedDebit)
        assertEquals("20.00 USD", state.totals.single { it.currencyCode == "USD" }.matchedDebit)
        assertEquals("MISSING ANON", state.statementOnlyLines.single().title)
        assertEquals("GROCERY ANON", state.matchedLines.single().title)
        assertEquals("2026-03-01", state.coverageStart)
        assertEquals("2026-03-31", state.coverageEnd)
        assertTrue(state.balanceUnsupported)
    }

    private fun sampleReport(): StatementReconciliationReport {
        val missing = BankStatementEntry(
            lineNumber = 10,
            bank = Bank.BANK_ALJAZIRA,
            accountMasked = "3001",
            bookedDate = LocalDate.parse("2026-03-10"),
            bookedAtTime = null,
            bookedAtRaw = "2026-03-10",
            direction = StatementDirection.DEBIT,
            amount = Money.of("999.99", Currency.SAR),
            description = "MISSING ANON",
            reference = "REF-10",
        )
        return StatementReconciliationReport(
            formatVersion = 1,
            coverage = StatementCoverage(
                LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-03-31"),
            ),
            counts = StatementReconciliationCounts(
                matched = 9,
                statementOnly = 1,
                ledgerOnly = 0,
                ambiguous = 0,
                unsupported = 0,
            ),
            statementLines = listOf(
                StatementLineComparison(
                    entry = missing.copy(description = "GROCERY ANON", lineNumber = 1),
                    status = StatementComparisonStatus.MATCHED,
                    ledgerTransactionId = "posted-0",
                    ledgerType = null,
                ),
                StatementLineComparison(
                    entry = missing,
                    status = StatementComparisonStatus.STATEMENT_ONLY,
                    ledgerTransactionId = null,
                    ledgerType = null,
                ),
            ),
            ledgerLines = emptyList(),
            totals = listOf(
                total(Currency.SAR, "591.25"),
                total(Currency.USD, "20.00"),
            ),
            balances = emptyList(),
            balanceCheck = StatementComparisonStatus.UNSUPPORTED,
            matchedFleetPaymentCount = 0,
            matchedSelfTransferCount = 1,
        )
    }

    private fun total(currency: Currency, matchedDebit: String) = StatementCurrencyTotal(
        bankId = "BANK_ALJAZIRA",
        accountMasked = "3001",
        periodStart = LocalDate.parse("2026-03-01"),
        periodEnd = LocalDate.parse("2026-03-10"),
        currency = currency,
        matchedCount = 1,
        matchedDebit = BigDecimal(matchedDebit).setScale(2),
        matchedCredit = BigDecimal.ZERO.setScale(2),
        statementOnlyCount = 0,
        statementOnlyDebit = BigDecimal.ZERO.setScale(2),
        statementOnlyCredit = BigDecimal.ZERO.setScale(2),
        ledgerOnlyCount = 0,
        ledgerOnlyDebit = BigDecimal.ZERO.setScale(2),
        ledgerOnlyCredit = BigDecimal.ZERO.setScale(2),
        ambiguousStatementCount = 0,
        ambiguousStatementDebit = BigDecimal.ZERO.setScale(2),
        ambiguousStatementCredit = BigDecimal.ZERO.setScale(2),
        ambiguousLedgerCount = 0,
        ambiguousLedgerDebit = BigDecimal.ZERO.setScale(2),
        ambiguousLedgerCredit = BigDecimal.ZERO.setScale(2),
        unsupportedCount = 0,
    )
}
