package com.baraa.masroof.data.statement

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.statement.CanonicalStatementFormat
import com.baraa.masroof.domain.statement.StatementParseResult
import com.baraa.masroof.domain.statement.StatementRejection
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCsvStatementParserTest {
    private val parser = CanonicalCsvStatementParser()
    private val known = setOf("BANK_ALJAZIRA", "D360")

    @Test
    fun canonicalFixture_readsVersionedRowsAndBalances() {
        val parsed = parser.parse(fixture("nine_matched_one_statement_only.csv"), known)
        val statement = (parsed as StatementParseResult.Accepted).statement
        assertEquals(CanonicalStatementFormat.VERSION, statement.formatVersion)
        assertEquals(10, statement.entries.size)
        assertEquals(Money.of("10000.00", Currency.SAR), statement.balances.single().opening)
        assertEquals(Money.of("9000.00", Currency.SAR), statement.balances.single().closing)
        assertEquals("2026-03-01", statement.coverage.periodStart.toString())
        assertEquals("2026-03-31", statement.coverage.periodEnd.toString())
        assertEquals("2026-03-09", statement.entries[8].bookedDate.toString())
        assertEquals("MISSING ANON", statement.entries.last().description)
    }

    @Test
    fun edgeFixture_acceptsTwoBanksAndTwoCurrencies() {
        val parsed = parser.parse(fixture("edge_cases.csv"), known) as StatementParseResult.Accepted
        assertEquals(setOf("BANK_ALJAZIRA", "D360"), parsed.statement.entries.map { it.bank.id }.toSet())
        assertEquals(setOf(Currency.SAR, Currency.USD), parsed.statement.entries.map { it.amount.currency }.toSet())
    }

    @Test
    fun duplicateRows_areRejected() {
        assertEquals(StatementRejection.DUPLICATE_ROW, reject("duplicate_rows.csv"))
    }

    @Test
    fun truncatedFile_isRejected() {
        assertEquals(StatementRejection.TRUNCATED, reject("truncated.csv"))
    }

    @Test
    fun unrecognizedFile_isRejected() {
        assertEquals(StatementRejection.UNRECOGNIZED, reject("unrecognized.csv"))
    }

    @Test
    fun semicolonSeparator_isRejected() {
        assertEquals(StatementRejection.UNSUPPORTED_SEPARATOR, reject("semicolon.csv"))
    }

    @Test
    fun formula_isRejectedWithoutEvaluation() {
        assertEquals(StatementRejection.FORMULA, reject("formula.csv"))
    }

    @Test
    fun unknownBank_isRejected() {
        assertEquals(StatementRejection.UNKNOWN_BANK, reject("unknown_bank.csv"))
        val other = csv("bankId,accountMasked,bookedAt,direction,amount,currency,description", "NOT_A_BANK,3001,2026-03-01,DEBIT,1.00,SAR,GROCERY ANON")
        assertEquals(StatementRejection.UNKNOWN_BANK, parser.parse(other, known).reason())
    }

    @Test
    fun ambiguousHeader_isRejected() {
        assertEquals(StatementRejection.AMBIGUOUS_HEADER, reject("ambiguous_header.csv"))
    }

    @Test
    fun offsetDate_negativeAmount_andExcessScale_areOutOfRange() {
        assertEquals(
            StatementRejection.OUT_OF_RANGE,
            parser.parse(
                csv(
                    "bankId,accountMasked,bookedAt,direction,amount,currency,description",
                    "BANK_ALJAZIRA,3001,2026-03-01T00:00:00Z,DEBIT,1.00,SAR,GROCERY ANON",
                ),
                known,
            ).reason(),
        )
        assertEquals(
            StatementRejection.OUT_OF_RANGE,
            parser.parse(
                csv(
                    "bankId,accountMasked,bookedAt,direction,amount,currency,description",
                    "BANK_ALJAZIRA,3001,2026-03-01,DEBIT,-1.00,SAR,GROCERY ANON",
                ),
                known,
            ).reason(),
        )
        assertEquals(
            StatementRejection.OUT_OF_RANGE,
            parser.parse(
                csv(
                    "bankId,accountMasked,bookedAt,direction,amount,currency,description",
                    "BANK_ALJAZIRA,3001,2026-03-01,DEBIT,1.234,SAR,GROCERY ANON",
                ),
                known,
            ).reason(),
        )
    }

    @Test
    fun oversizedStream_isRejected() {
        val bytes = ByteArray((CanonicalStatementFormat.MAX_BYTES + 1).toInt()) { 'a'.code.toByte() }
        val parsed = parser.parse(ByteArrayInputStream(bytes), known)
        assertEquals(StatementRejection.OVERSIZED, parsed.reason())
    }

    @Test
    fun malformedUtf8_isRejected() {
        val parsed = parser.parse(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte())), known)
        assertTrue(parsed is StatementParseResult.Rejected)
        assertEquals(StatementRejection.UNRECOGNIZED, parsed.reason())
    }

    private fun reject(name: String): StatementRejection = parser.parse(fixture(name), known).reason()

    private fun StatementParseResult.reason(): StatementRejection =
        (this as StatementParseResult.Rejected).reason

    @Test
    fun rowOutsideDeclaredCoverage_isRejected() {
        assertEquals(
            StatementRejection.OUT_OF_RANGE,
            parser.parse(
                covered(
                    "bankId,accountMasked,bookedAt,direction,amount,currency,description",
                    "BANK_ALJAZIRA,3001,2026-04-02,DEBIT,1.00,SAR,GROCERY ANON",
                    periodStart = "2026-03-01",
                    periodEnd = "2026-03-31",
                ),
                known,
            ).reason(),
        )
    }

    @Test
    fun missingCoverageDirectives_areRejected() {
        assertEquals(
            StatementRejection.AMBIGUOUS_HEADER,
            parser.parse(
                ByteArrayInputStream(
                    """
                    # masroof-statement-v1
                    bankId,accountMasked,bookedAt,direction,amount,currency,description
                    BANK_ALJAZIRA,3001,2026-03-01,DEBIT,1.00,SAR,GROCERY ANON
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                ),
                known,
            ).reason(),
        )
    }

    private fun csv(header: String, row: String) = covered(header, row)

    private fun covered(
        header: String,
        row: String,
        periodStart: String = "2026-03-01",
        periodEnd: String = "2026-03-31",
    ) = ByteArrayInputStream(
        """
        # masroof-statement-v1
        # periodStart=$periodStart
        # periodEnd=$periodEnd
        $header
        $row
        """.trimIndent().toByteArray(Charsets.UTF_8),
    )

    private fun fixture(name: String) = statementFixture(name).inputStream()
}

internal fun statementFixture(name: String): File =
    listOf(
        File("src/test/resources/testdata/statements/$name"),
        File("app/src/test/resources/testdata/statements/$name"),
    ).firstOrNull { it.isFile } ?: error("Missing testdata/statements/$name")
