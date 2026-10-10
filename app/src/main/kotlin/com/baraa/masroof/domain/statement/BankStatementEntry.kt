package com.baraa.masroof.domain.statement

import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Canonical statement CSV, version [CanonicalStatementFormat.VERSION].
 *
 * The contract lives here so the screen cannot invent columns. A file is one
 * UTF-8 comma-separated table. The optional first record is the directive
 * `# masroof-statement-v1`. The header row names columns; order does not matter.
 *
 * Required headers: `bankId`, `accountMasked`, `bookedAt`, `direction`,
 * `amount`, `currency`, `description`.
 *
 * Optional headers: `reference`, `openingBalance`, `openingBalanceCurrency`,
 * `closingBalance`, `closingBalanceCurrency`.
 *
 * `bankId` is an existing [Bank] id such as `BANK_ALJAZIRA`. `UNKNOWN` and any
 * id outside the caller-supplied known set are rejected. The bank is never
 * inferred from the account suffix.
 *
 * `accountMasked` is the exact suffix stored by
 * [com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId]:
 * `account:<bankId>:<accountMasked>`. A shared last4 is not an identity.
 *
 * `bookedAt` is an offset-less ISO-8601 local date (`YYYY-MM-DD`) or local
 * date-time (`YYYY-MM-DDTHH:MM:SS` with optional fraction). It is civil time in
 * [com.baraa.masroof.domain.assembly.BankTransactionTimePolicy]. AlJazira is
 * `Asia/Riyadh`. An offset or `Z` is rejected. A date-only value matches that
 * civil date. A date-time also requires the ledger clock to fall inside
 * [StatementMatchPolicy.BOOKING_TIME_WINDOW].
 *
 * `reference`, when present, matches only a ledger side that carries exactly
 * that one comparable reference. It does not match a same-day amount whose
 * ledger reference is missing or different. See [StatementMatchPolicy].
 *
 * `direction` is `DEBIT` or `CREDIT`. `amount` is a non-negative exact decimal
 * with at most two non-zero fractional digits. `currency` is an ISO code the
 * app already models. Spreadsheet formulas are rejected and never evaluated.
 *
 * Opening and closing balances are optional exact decimals with their own ISO
 * currency. They are repeated facts for one bank account, not movement lines.
 * This comparison does not rebuild a running balance from SMS.
 *
 * The parser streams with [CanonicalStatementFormat.MAX_BYTES] and
 * [CanonicalStatementFormat.MAX_DATA_ROWS]. It keeps text in memory for the
 * comparison only. It does not upload, sync, log raw statement text, or write
 * a temp file.
 */
object CanonicalStatementFormat {
    const val VERSION: Int = 1
    const val VERSION_DIRECTIVE: String = "# masroof-statement-v1"
    const val MAX_BYTES: Long = 1_048_576L
    const val MAX_DATA_ROWS: Int = 5_000
    const val MAX_AMOUNT_PLAIN: String = "1000000000.00"
    const val MAX_DESCRIPTION_LENGTH: Int = 500
    const val MAX_REFERENCE_LENGTH: Int = 128

    val REQUIRED_HEADERS: Set<String> = setOf(
        "bankId",
        "accountMasked",
        "bookedAt",
        "direction",
        "amount",
        "currency",
        "description",
    )

    val OPTIONAL_HEADERS: Set<String> = setOf(
        "reference",
        "openingBalance",
        "openingBalanceCurrency",
        "closingBalance",
        "closingBalanceCurrency",
    )

    val HEADERS: Set<String> = REQUIRED_HEADERS + OPTIONAL_HEADERS
}

enum class StatementDirection {
    DEBIT,
    CREDIT,
}

enum class StatementRejection {
    OVERSIZED,
    TRUNCATED,
    UNRECOGNIZED,
    AMBIGUOUS_HEADER,
    UNSUPPORTED_SEPARATOR,
    DUPLICATE_ROW,
    OUT_OF_RANGE,
    UNKNOWN_BANK,
    FORMULA,
    UNREADABLE,
    UNOWNED_ACCOUNT,
}

/**
 * One movement from a canonical statement. Text stays in memory for the session.
 */
data class BankStatementEntry(
    val lineNumber: Int,
    val bank: Bank,
    val accountMasked: String,
    val bookedDate: LocalDate,
    val bookedAtTime: LocalDateTime?,
    val bookedAtRaw: String,
    val direction: StatementDirection,
    val amount: Money,
    val description: String,
    val reference: String?,
)

data class StatementAccountBalance(
    val bank: Bank,
    val accountMasked: String,
    val opening: Money?,
    val closing: Money?,
)

data class ParsedBankStatement(
    val formatVersion: Int,
    val entries: List<BankStatementEntry>,
    val balances: List<StatementAccountBalance>,
)

sealed interface StatementParseResult {
    data class Accepted(val statement: ParsedBankStatement) : StatementParseResult

    data class Rejected(val reason: StatementRejection) : StatementParseResult
}

/** Reads one canonical CSV. Implementations must not persist the file. */
fun interface StatementParser {
    fun parse(stream: InputStream, knownBankIds: Set<String>): StatementParseResult
}
