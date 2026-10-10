package com.baraa.masroof.data.statement

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.statement.BankStatementEntry
import com.baraa.masroof.domain.statement.CanonicalStatementFormat
import com.baraa.masroof.domain.statement.ParsedBankStatement
import com.baraa.masroof.domain.statement.StatementAccountBalance
import com.baraa.masroof.domain.statement.StatementCoverage
import com.baraa.masroof.domain.statement.StatementMatchPolicy
import com.baraa.masroof.domain.statement.StatementDirection
import com.baraa.masroof.domain.statement.StatementParseResult
import com.baraa.masroof.domain.statement.StatementParser
import com.baraa.masroof.domain.statement.StatementRejection
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Streaming reader for [CanonicalStatementFormat]. Rejects the whole file before
 * any comparison. Does not evaluate formulas, infer a bank from a suffix, or
 * write the bytes to disk.
 */
class CanonicalCsvStatementParser : StatementParser {
    override fun parse(stream: InputStream, knownBankIds: Set<String>): StatementParseResult {
        val text = try {
            readBoundedUtf8(stream)
        } catch (limit: ByteLimitExceeded) {
            return StatementParseResult.Rejected(StatementRejection.OVERSIZED)
        } catch (malformed: CharacterCodingException) {
            return StatementParseResult.Rejected(StatementRejection.UNRECOGNIZED)
        } catch (io: java.io.IOException) {
            return StatementParseResult.Rejected(StatementRejection.UNREADABLE)
        }
        return try {
            StatementParseResult.Accepted(parseText(text.removePrefix("\uFEFF"), knownBankIds))
        } catch (failure: ParseFailure) {
            StatementParseResult.Rejected(failure.reason)
        }
    }

    private fun readBoundedUtf8(stream: InputStream): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return InputStreamReader(ByteLimitedInputStream(stream, CanonicalStatementFormat.MAX_BYTES), decoder)
            .use { it.readText() }
    }

    private fun parseText(text: String, knownBankIds: Set<String>): ParsedBankStatement {
        if (text.isBlank()) fail(StatementRejection.UNRECOGNIZED)
        val records = csvRecords(text).filterNot { record -> record.all { it.isBlank() } }
        if (records.isEmpty()) fail(StatementRejection.UNRECOGNIZED)

        var cursor = 0
        var sawVersion = false
        var periodStart: LocalDate? = null
        var periodEnd: LocalDate? = null
        var pendingBankId: String? = null
        val declaredAccounts = linkedSetOf<StatementMatchPolicy.QualifiedAccount>()
        while (cursor < records.size && isDirective(records[cursor])) {
            val directive = records[cursor][0].trim()
            when {
                directive == CanonicalStatementFormat.VERSION_DIRECTIVE -> {
                    if (sawVersion) fail(StatementRejection.UNRECOGNIZED)
                    sawVersion = true
                }
                directive.startsWith(CanonicalStatementFormat.PERIOD_START_DIRECTIVE) -> {
                    if (periodStart != null) fail(StatementRejection.AMBIGUOUS_HEADER)
                    periodStart = parseCoverageDate(directive.removePrefix(CanonicalStatementFormat.PERIOD_START_DIRECTIVE))
                }
                directive.startsWith(CanonicalStatementFormat.PERIOD_END_DIRECTIVE) -> {
                    if (periodEnd != null) fail(StatementRejection.AMBIGUOUS_HEADER)
                    periodEnd = parseCoverageDate(directive.removePrefix(CanonicalStatementFormat.PERIOD_END_DIRECTIVE))
                }
                directive.startsWith(CanonicalStatementFormat.BANK_ID_DIRECTIVE) -> {
                    if (pendingBankId != null) fail(StatementRejection.AMBIGUOUS_HEADER)
                    pendingBankId = parseDirectiveBankId(
                        directive.removePrefix(CanonicalStatementFormat.BANK_ID_DIRECTIVE),
                        knownBankIds,
                    )
                }
                directive.startsWith(CanonicalStatementFormat.ACCOUNT_MASKED_DIRECTIVE) -> {
                    val bankId = pendingBankId ?: fail(StatementRejection.AMBIGUOUS_HEADER)
                    val masked = parseDirectiveAccount(
                        directive.removePrefix(CanonicalStatementFormat.ACCOUNT_MASKED_DIRECTIVE),
                    )
                    val account = StatementMatchPolicy.QualifiedAccount(Bank.fromId(bankId), masked)
                    if (!declaredAccounts.add(account)) fail(StatementRejection.AMBIGUOUS_HEADER)
                    pendingBankId = null
                }
                else -> fail(StatementRejection.UNRECOGNIZED)
            }
            cursor += 1
        }
        if (pendingBankId != null) fail(StatementRejection.AMBIGUOUS_HEADER)
        if (cursor >= records.size) {
            if (declaredAccounts.isEmpty()) fail(StatementRejection.AMBIGUOUS_HEADER)
            return statementWithoutRows(requireCoverage(periodStart, periodEnd), declaredAccounts)
        }
        val headerRecord = records[cursor]
        rejectUnsupportedSeparator(headerRecord)
        val headers = headerRecord.map { it.trim() }
        validateHeaders(headers)
        val coverage = requireCoverage(periodStart, periodEnd)
        val headerIndex = headers.withIndex().associate { it.value to it.index }
        cursor += 1

        val seen = HashSet<MovementKey>()
        val entries = mutableListOf<BankStatementEntry>()
        val balances = linkedMapOf<String, BalanceBuilder>()
        var lineNumber = 0
        for (record in records.drop(cursor)) {
            lineNumber += 1
            if (lineNumber > CanonicalStatementFormat.MAX_DATA_ROWS) {
                fail(StatementRejection.OVERSIZED)
            }
            if (record.size < headers.size) fail(StatementRejection.TRUNCATED)
            if (record.size > headers.size) fail(StatementRejection.UNRECOGNIZED)
            record.forEach { cell ->
                if (cell.trimStart().startsWith("=")) fail(StatementRejection.FORMULA)
            }
            entries += parseRow(
                record = record,
                headerIndex = headerIndex,
                lineNumber = lineNumber,
                knownBankIds = knownBankIds,
                seen = seen,
                balances = balances,
                coverage = coverage,
                declaredAccounts = declaredAccounts,
            )
        }
        if (entries.isEmpty() && declaredAccounts.isEmpty()) fail(StatementRejection.AMBIGUOUS_HEADER)
        return ParsedBankStatement(
            formatVersion = CanonicalStatementFormat.VERSION,
            coverage = coverage,
            accounts = declaredAccounts.toList(),
            entries = entries,
            balances = balances.values.map { it.build() },
        )
    }

    private fun requireCoverage(periodStart: LocalDate?, periodEnd: LocalDate?): StatementCoverage {
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
            fail(StatementRejection.AMBIGUOUS_HEADER)
        }
        return StatementCoverage(periodStart, periodEnd)
    }

    private fun statementWithoutRows(
        coverage: StatementCoverage,
        accounts: Set<StatementMatchPolicy.QualifiedAccount>,
    ): ParsedBankStatement = ParsedBankStatement(
        formatVersion = CanonicalStatementFormat.VERSION,
        coverage = coverage,
        accounts = accounts.toList(),
        entries = emptyList(),
        balances = emptyList(),
    )

    private fun parseDirectiveBankId(raw: String, knownBankIds: Set<String>): String {
        val bankId = raw.trim()
        if (!BANK_ID.matches(bankId) || bankId == Bank.UNKNOWN.id || bankId !in knownBankIds) {
            fail(StatementRejection.UNKNOWN_BANK)
        }
        return bankId
    }

    private fun parseDirectiveAccount(raw: String): String {
        val masked = raw.trim()
        if (!ACCOUNT_MASKED.matches(masked)) fail(StatementRejection.OUT_OF_RANGE)
        return masked
    }

    private fun isDirective(record: List<String>): Boolean =
        record.size == 1 && record[0].trim().startsWith("#")

    private fun parseCoverageDate(raw: String): LocalDate {
        val text = raw.trim()
        if (!DATE.matches(text)) fail(StatementRejection.AMBIGUOUS_HEADER)
        val date = try {
            LocalDate.parse(text)
        } catch (error: DateTimeParseException) {
            fail(StatementRejection.AMBIGUOUS_HEADER)
        }
        if (date.year !in YEAR_MIN..YEAR_MAX) fail(StatementRejection.OUT_OF_RANGE)
        return date
    }

    private fun rejectUnsupportedSeparator(headerRecord: List<String>) {
        if (headerRecord.size != 1) return
        val raw = headerRecord[0]
        val separator = when {
            raw.contains(';') && !raw.contains(',') -> ';'
            raw.contains('\t') && !raw.contains(',') -> '\t'
            else -> return
        }
        val parts = raw.split(separator).map { it.trim() }
        if (CanonicalStatementFormat.REQUIRED_HEADERS.all { it in parts }) {
            fail(StatementRejection.UNSUPPORTED_SEPARATOR)
        }
    }

    private fun validateHeaders(headers: List<String>) {
        val recognized = headers.count { it in CanonicalStatementFormat.HEADERS }
        if (recognized == 0) fail(StatementRejection.UNRECOGNIZED)
        if (headers.any { it.isEmpty() }) fail(StatementRejection.AMBIGUOUS_HEADER)
        if (headers.size != headers.toSet().size) fail(StatementRejection.AMBIGUOUS_HEADER)
        if (headers.any { it !in CanonicalStatementFormat.HEADERS }) fail(StatementRejection.AMBIGUOUS_HEADER)
        if (!CanonicalStatementFormat.REQUIRED_HEADERS.all { it in headers }) {
            fail(StatementRejection.AMBIGUOUS_HEADER)
        }
    }

    private fun parseRow(
        record: List<String>,
        headerIndex: Map<String, Int>,
        lineNumber: Int,
        knownBankIds: Set<String>,
        seen: MutableSet<MovementKey>,
        balances: MutableMap<String, BalanceBuilder>,
        coverage: StatementCoverage,
        declaredAccounts: Set<StatementMatchPolicy.QualifiedAccount>,
    ): BankStatementEntry {
        val bankId = cell(record, headerIndex, "bankId")
        if (!BANK_ID.matches(bankId) || bankId == Bank.UNKNOWN.id || bankId !in knownBankIds) {
            fail(StatementRejection.UNKNOWN_BANK)
        }
        val bank = Bank.fromId(bankId)
        val accountMasked = cell(record, headerIndex, "accountMasked")
        if (!ACCOUNT_MASKED.matches(accountMasked)) fail(StatementRejection.OUT_OF_RANGE)
        if (declaredAccounts.isNotEmpty() &&
            StatementMatchPolicy.QualifiedAccount(bank, accountMasked) !in declaredAccounts
        ) {
            fail(StatementRejection.AMBIGUOUS_HEADER)
        }
        val bookedRaw = cell(record, headerIndex, "bookedAt")
        val booked = parseBookedAt(bookedRaw)
        if (booked.date.isBefore(coverage.periodStart) || booked.date.isAfter(coverage.periodEnd)) {
            fail(StatementRejection.OUT_OF_RANGE)
        }
        val direction = when (cell(record, headerIndex, "direction")) {
            "DEBIT" -> StatementDirection.DEBIT
            "CREDIT" -> StatementDirection.CREDIT
            else -> fail(StatementRejection.OUT_OF_RANGE)
        }
        val currency = currency(cell(record, headerIndex, "currency"))
        val amount = money(cell(record, headerIndex, "amount"), currency)
        val description = cell(record, headerIndex, "description")
        if (description.length > CanonicalStatementFormat.MAX_DESCRIPTION_LENGTH) {
            fail(StatementRejection.OUT_OF_RANGE)
        }
        val reference = optional(record, headerIndex, "reference")?.also { value ->
            if (value.length > CanonicalStatementFormat.MAX_REFERENCE_LENGTH) {
                fail(StatementRejection.OUT_OF_RANGE)
            }
        }
        val opening = optionalMoney(
            record,
            headerIndex,
            amountColumn = "openingBalance",
            currencyColumn = "openingBalanceCurrency",
        )
        val closing = optionalMoney(
            record,
            headerIndex,
            amountColumn = "closingBalance",
            currencyColumn = "closingBalanceCurrency",
        )
        val key = MovementKey(
            bankId = bankId,
            accountMasked = accountMasked,
            bookedAtRaw = bookedRaw,
            direction = direction.name,
            amount = amount.amount.toPlainString(),
            currency = currency.name,
            description = description,
            reference = reference.orEmpty(),
        )
        if (!seen.add(key)) fail(StatementRejection.DUPLICATE_ROW)
        val balanceKey = bankId + "\u001f" + accountMasked
        val builder = balances.getOrPut(balanceKey) { BalanceBuilder(bank, accountMasked) }
        builder.merge(opening, closing)
        return BankStatementEntry(
            lineNumber = lineNumber,
            bank = bank,
            accountMasked = accountMasked,
            bookedDate = booked.date,
            bookedAtTime = booked.time,
            bookedAtRaw = bookedRaw,
            direction = direction,
            amount = amount,
            description = description,
            reference = reference,
        )
    }

    private fun parseBookedAt(raw: String): BookedAt {
        if (DATE.matches(raw)) {
            val date = try {
                LocalDate.parse(raw)
            } catch (error: DateTimeParseException) {
                fail(StatementRejection.OUT_OF_RANGE)
            }
            if (date.year !in YEAR_MIN..YEAR_MAX) fail(StatementRejection.OUT_OF_RANGE)
            return BookedAt(date, null)
        }
        if (DATE_TIME.matches(raw)) {
            val time = try {
                LocalDateTime.parse(raw)
            } catch (error: DateTimeParseException) {
                fail(StatementRejection.OUT_OF_RANGE)
            }
            if (time.year !in YEAR_MIN..YEAR_MAX) fail(StatementRejection.OUT_OF_RANGE)
            return BookedAt(time.toLocalDate(), time)
        }
        fail(StatementRejection.OUT_OF_RANGE)
    }

    private fun optionalMoney(
        record: List<String>,
        headerIndex: Map<String, Int>,
        amountColumn: String,
        currencyColumn: String,
    ): Money? {
        val amountRaw = optional(record, headerIndex, amountColumn)
        val currencyRaw = optional(record, headerIndex, currencyColumn)
        if (amountRaw == null && currencyRaw == null) return null
        if (amountRaw == null || currencyRaw == null) fail(StatementRejection.AMBIGUOUS_HEADER)
        return money(amountRaw, currency(currencyRaw))
    }

    private fun currency(code: String): Currency =
        Currency.entries.firstOrNull { it.name == code } ?: fail(StatementRejection.OUT_OF_RANGE)

    private fun money(raw: String, currency: Currency): Money {
        if (!AMOUNT.matches(raw)) fail(StatementRejection.OUT_OF_RANGE)
        val parsed = BigDecimal(raw)
        if (parsed.signum() < 0) fail(StatementRejection.OUT_OF_RANGE)
        if (parsed.stripTrailingZeros().scale() > Money.SCALE) fail(StatementRejection.OUT_OF_RANGE)
        if (parsed > BigDecimal(CanonicalStatementFormat.MAX_AMOUNT_PLAIN)) {
            fail(StatementRejection.OUT_OF_RANGE)
        }
        return Money.of(parsed, currency)
    }

    private fun cell(record: List<String>, headerIndex: Map<String, Int>, name: String): String {
        val value = record[headerIndex.getValue(name)].trim()
        if (value.isEmpty()) fail(StatementRejection.OUT_OF_RANGE)
        return value
    }

    private fun optional(record: List<String>, headerIndex: Map<String, Int>, name: String): String? {
        val index = headerIndex[name] ?: return null
        val value = record[index].trim()
        return value.ifEmpty { null }
    }

    private fun csvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (inQuotes) {
                if (character == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index += 2
                        continue
                    }
                    inQuotes = false
                    index += 1
                    continue
                }
                field.append(character)
                index += 1
                continue
            }
            when (character) {
                '"' -> {
                    if (field.isNotEmpty()) fail(StatementRejection.UNRECOGNIZED)
                    inQuotes = true
                    index += 1
                }
                ',' -> {
                    record.add(field.toString())
                    field.clear()
                    if (record.size > MAX_COLUMNS) fail(StatementRejection.UNRECOGNIZED)
                    index += 1
                }
                '\n', '\r' -> {
                    record.add(field.toString())
                    field.clear()
                    records.add(record)
                    record = mutableListOf()
                    index += if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') 2 else 1
                }
                else -> {
                    field.append(character)
                    index += 1
                }
            }
        }
        if (inQuotes) fail(StatementRejection.TRUNCATED)
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record.add(field.toString())
            records.add(record)
        }
        return records
    }

    private class BalanceBuilder(
        private val bank: Bank,
        private val accountMasked: String,
    ) {
        private var opening: Money? = null
        private var closing: Money? = null

        fun merge(nextOpening: Money?, nextClosing: Money?) {
            opening = agree(opening, nextOpening)
            closing = agree(closing, nextClosing)
        }

        fun build(): StatementAccountBalance =
            StatementAccountBalance(
                bank = bank,
                accountMasked = accountMasked,
                opening = opening,
                closing = closing,
            )

        private fun agree(current: Money?, incoming: Money?): Money? {
            if (incoming == null) return current
            if (current == null) return incoming
            if (current != incoming) fail(StatementRejection.AMBIGUOUS_HEADER)
            return current
        }
    }

    private data class BookedAt(
        val date: LocalDate,
        val time: LocalDateTime?,
    )

    private data class MovementKey(
        val bankId: String,
        val accountMasked: String,
        val bookedAtRaw: String,
        val direction: String,
        val amount: String,
        val currency: String,
        val description: String,
        val reference: String,
    )

    private class ParseFailure(val reason: StatementRejection) : RuntimeException(reason.name)

    private class ByteLimitExceeded : RuntimeException()

    private class ByteLimitedInputStream(
        private val source: InputStream,
        private val maxBytes: Long,
    ) : InputStream() {
        private var readBytes: Long = 0

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) {
                readBytes += 1
                if (readBytes > maxBytes) throw ByteLimitExceeded()
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = source.read(buffer, offset, length)
            if (count > 0) {
                readBytes += count
                if (readBytes > maxBytes) throw ByteLimitExceeded()
            }
            return count
        }
    }

    private companion object {
        const val MAX_COLUMNS: Int = 32
        const val YEAR_MIN: Int = 2000
        const val YEAR_MAX: Int = 2100
        val BANK_ID: Regex = Regex("^[A-Z][A-Z0-9_]{0,63}$")
        val ACCOUNT_MASKED: Regex = Regex("^[0-9A-Za-z*]{1,64}$")
        val DATE: Regex = Regex("""\d{4}-\d{2}-\d{2}""")
        val DATE_TIME: Regex = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?""")
        val AMOUNT: Regex = Regex("""\d+(\.\d+)?""")

        private fun fail(reason: StatementRejection): Nothing = throw ParseFailure(reason)
    }
}
