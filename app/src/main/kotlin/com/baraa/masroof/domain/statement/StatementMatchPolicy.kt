package com.baraa.masroof.domain.statement

import com.baraa.masroof.domain.assembly.BankTransactionTimePolicy
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

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
 * `bookedAt` is that civil date. When the statement also supplies a clock time,
 * the ledger's bank-local time must fall inside [BOOKING_TIME_WINDOW]. A same-day
 * amount with a clearly different clock is not a match.
 *
 * Zone: [BankTransactionTimePolicy] only. AlJazira is `Asia/Riyadh`. A bank
 * without a fixed zone uses the single distinct `occurredAtZone` already stored
 * on posted rows for that bank. Zero or several stored zones leave the line
 * [com.baraa.masroof.domain.statement.StatementComparisonStatus.UNSUPPORTED].
 * The device zone is not a match key.
 *
 * A match requires the same bank, the same qualified account suffix, the same
 * direction, the same currency, and an equal amount. Description never forces a
 * match. A supplied statement reference matches only when the ledger side has
 * exactly one comparable reference and it is the same value. A supplied
 * reference with no ledger reference, or a different one, is not a match.
 * One statement line pairs with one ledger side only. Any collision is ambiguous.
 *
 * A self-transfer exposes two sides of one posted transaction: debit on the
 * source account and credit on the destination account. Each side may match
 * its own statement line. That is one internal transfer, not two expenses.
 */
object StatementMatchPolicy {
    const val BOOKING_WINDOW_DAYS: Int = 0

    /**
     * Maximum |statement clock − ledger bank-local clock| when the statement
     * line includes a time. Date-only lines do not use this window.
     */
    val BOOKING_TIME_WINDOW: Duration = Duration.ofMinutes(10)

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
     * Date-only statement lines stay on [datesMatch]. A supplied clock must sit
     * within [BOOKING_TIME_WINDOW] of the ledger's bank-local time.
     */
    fun bookingTimesCompatible(statementTime: LocalDateTime?, ledgerLocal: LocalDateTime): Boolean {
        if (statementTime == null) return true
        return Duration.between(statementTime, ledgerLocal).abs() <= BOOKING_TIME_WINDOW
    }

    fun normalizeReference(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT).orEmpty()

    /**
     * A blank statement reference adds no constraint. A supplied reference is
     * supported only by exactly one non-blank ledger reference with the same
     * normalized value. Missing or conflicting ledger references do not match.
     */
    fun referenceSupported(statementReference: String?, ledgerReferences: Set<String>): Boolean {
        val wanted = normalizeReference(statementReference)
        if (wanted.isEmpty()) return true
        val comparable = ledgerReferences.map(::normalizeReference).filter { it.isNotEmpty() }.toSet()
        if (comparable.size != 1) return false
        return comparable.single() == wanted
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
