package com.baraa.masroof.domain.statement

import com.baraa.masroof.domain.assembly.BankTransactionTimePolicy
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import java.time.LocalDate
import java.time.ZoneId

enum class StatementComparisonStatus {
    MATCHED,
    STATEMENT_ONLY,
    LEDGER_ONLY,
    AMBIGUOUS,
    UNSUPPORTED,
}

/**
 * How a statement line pairs with one posted ledger side.
 *
 * Booking window: the statement line's civil date in the bank zone, expanded by
 * [BOOKING_WINDOW_DAYS] on each side. Zero means the ledger movement must fall
 * on that same local day, `[date 00:00, next date 00:00)`. A date-only
 * `bookedAt` is that civil date. A date-time uses its civil date; the clock
 * time does not widen the window.
 *
 * Zone: [BankTransactionTimePolicy] only. AlJazira is `Asia/Riyadh`. A bank
 * without a fixed zone uses the single distinct `occurredAtZone` already stored
 * on posted rows for that bank. Zero or several stored zones leave the line
 * [com.baraa.masroof.domain.statement.StatementComparisonStatus.UNSUPPORTED].
 * The device zone is not a match key.
 *
 * A match requires the same bank, the same qualified account suffix, the same
 * direction, the same currency, and an equal amount. Description and reference
 * never force a match and never collapse two movements. One statement line
 * pairs with one ledger side only. Any collision is ambiguous.
 *
 * A self-transfer exposes two sides of one posted transaction: debit on the
 * source account and credit on the destination account. Each side may match
 * its own statement line. That is one internal transfer, not two expenses.
 */
object StatementMatchPolicy {
    const val BOOKING_WINDOW_DAYS: Int = 0

    /** Read superset around civil dates so a non-fixed zone can still be loaded. Not a match window. */
    const val QUERY_SUPERSET_PADDING_DAYS: Long = 2

    enum class AccountEndpoint {
        SOURCE,
        DESTINATION,
    }

    data class QualifiedAccount(
        val bank: Bank,
        val accountMasked: String,
    )

    fun accountKey(bank: Bank, accountMasked: String): String =
        FinancialContainerIdFactory.accountId(bank, accountMasked)

    /**
     * Bank-qualified account id only. Returns null for cards, loans, legacy
     * unqualified ids, and any id that does not round-trip through
     * [FinancialContainerIdFactory]. Suffix equality is not enough.
     */
    fun qualifiedAccount(containerId: String?): QualifiedAccount? {
        if (!FinancialContainerIdParser.isBankQualifiedAccountId(containerId)) return null
        val bankId = FinancialContainerIdParser.accountBankId(containerId) ?: return null
        val masked = containerId!!
            .removePrefix("account:")
            .substringAfter(':')
            .trim()
        if (bankId.isEmpty() || masked.isEmpty()) return null
        val bank = Bank.fromId(bankId)
        if (bank == Bank.UNKNOWN) return null
        val expected = FinancialContainerIdFactory.accountId(bank, masked)
        if (containerId != expected) return null
        return QualifiedAccount(bank, masked)
    }

    fun zoneForPostedMovement(bank: Bank, persistedZoneId: String?): ZoneId? {
        BankTransactionTimePolicy.fixedZone(bank)?.let { return it }
        if (persistedZoneId.isNullOrBlank()) return null
        val parsed = runCatching { ZoneId.of(persistedZoneId) }.getOrNull() ?: return null
        return BankTransactionTimePolicy.resolve(bank, persistedZoneId, parsed)
    }

    /**
     * Zone for statement lines of [bank]. Fixed zones win. Otherwise exactly one
     * stored zone from the posted rows is required.
     */
    fun zoneForStatementBank(bank: Bank, distinctPersistedZones: Set<String?>): ZoneId? {
        BankTransactionTimePolicy.fixedZone(bank)?.let { return it }
        val only = distinctPersistedZones.singleOrNull() ?: return null
        return zoneForPostedMovement(bank, only)
    }

    fun datesMatch(statementDate: LocalDate, ledgerDate: LocalDate): Boolean {
        val window = BOOKING_WINDOW_DAYS.toLong()
        val start = statementDate.minusDays(window)
        val end = statementDate.plusDays(window)
        return !ledgerDate.isBefore(start) && !ledgerDate.isAfter(end)
    }

    /**
     * Cash direction of one account endpoint. Null means the endpoint is not a
     * comparable statement side (do not guess debit versus credit).
     */
    fun cashDirection(
        type: FinancialTransactionType,
        endpoint: AccountEndpoint,
    ): StatementDirection? =
        when (type) {
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.FEE,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.LOAN_REPAYMENT,
            -> if (endpoint == AccountEndpoint.SOURCE) StatementDirection.DEBIT else null

            FinancialTransactionType.SELF_TRANSFER ->
                if (endpoint == AccountEndpoint.SOURCE) {
                    StatementDirection.DEBIT
                } else {
                    StatementDirection.CREDIT
                }

            FinancialTransactionType.INCOME,
            FinancialTransactionType.REFUND,
            FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            -> if (endpoint == AccountEndpoint.DESTINATION) StatementDirection.CREDIT else null

            FinancialTransactionType.ADJUSTMENT,
            FinancialTransactionType.UNKNOWN,
            -> null
        }
}
