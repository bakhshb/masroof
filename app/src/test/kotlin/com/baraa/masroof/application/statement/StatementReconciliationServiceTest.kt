package com.baraa.masroof.application.statement

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.statement.CanonicalCsvStatementParser
import com.baraa.masroof.data.statement.statementFixture
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.statement.BankStatementEntry
import com.baraa.masroof.domain.statement.CanonicalStatementFormat
import com.baraa.masroof.domain.statement.ParsedBankStatement
import com.baraa.masroof.domain.statement.StatementComparisonStatus
import com.baraa.masroof.domain.statement.StatementDirection
import com.baraa.masroof.domain.statement.StatementParseResult
import com.baraa.masroof.domain.statement.StatementRejection
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementReconciliationServiceTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val known = setOf("BANK_ALJAZIRA", "D360")
    private val parser = CanonicalCsvStatementParser()

    @Test
    fun nineMovements_matchNineLedgerRows_andLeaveTheTenthStatementOnly() = runBlocking {
        val statement = accepted("nine_matched_one_statement_only.csv")
        val matchedTypes = listOf(
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.FEE,
            FinancialTransactionType.REFUND,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.INCOME,
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.CASH_WITHDRAWAL,
        )
        val rows = statement.entries.take(9).mapIndexed { index, entry ->
            posted(
                id = "posted-$index",
                type = matchedTypes[index],
                amount = entry.amount.amount.toPlainString(),
                currency = entry.amount.currency,
                at = instant(entry),
                direction = entry.direction,
                merchant = "DIFFERENT",
            )
        } + posted(
            id = "outside",
            type = FinancialTransactionType.EXPENSE,
            amount = "1.00",
            at = local("2026-01-01T12:00:00"),
            direction = StatementDirection.DEBIT,
        )
        val ledger = RecordingLedger(rows)
        val before = ledger.rows.size
        val report = compared(ledger, statement)

        assertEquals(0, ledger.mutations)
        assertEquals(before, ledger.rows.size)
        assertEquals(1, ledger.rangeReads)
        assertEquals(9, report.counts.matched)
        assertEquals(1, report.counts.statementOnly)
        assertEquals(0, report.counts.ledgerOnly)
        assertEquals(0, report.counts.ambiguous)
        assertEquals(0, report.counts.unsupported)
        assertEquals(3, report.matchedFleetPaymentCount)
        assertEquals(0, report.matchedSelfTransferCount)
        assertTrue(report.ledgerLines.none { it.transactionId == "outside" })
        assertTrue(report.statementLines.filter { it.status == StatementComparisonStatus.MATCHED }
            .all { it.entry.description != "DIFFERENT" })
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("MISSING ANON").status)
        assertNull(report.line("MISSING ANON").ledgerTransactionId)

        val total = report.total("BANK_ALJAZIRA", "3001", Currency.SAR)
        assertEquals(LocalDate.parse("2026-03-01"), total.periodStart)
        assertEquals(LocalDate.parse("2026-03-10"), total.periodEnd)
        assertMoney("591.25", total.matchedDebit)
        assertMoney("5175.00", total.matchedCredit)
        assertMoney("999.99", total.statementOnlyDebit)
        assertMoney("0.00", total.statementOnlyCredit)
        assertMoney("0.00", total.ledgerOnlyDebit)
        assertEquals(1, report.totals.size)
        assertEquals(Currency.SAR, report.totals.single().currency)
        assertEquals(Money.of("10000.00", Currency.SAR), report.balances.single().opening)
        assertEquals(Money.of("9000.00", Currency.SAR), report.balances.single().closing)
        assertEquals(StatementComparisonStatus.UNSUPPORTED, report.balanceCheck)
    }

    @Test
    fun selfTransfer_matchesBothSides_withoutTwoFleetPayments() = runBlocking {
        val statement = accepted("self_transfer.csv")
        val rows = listOf(
            FinancialTransaction(
                id = "self-1",
                type = FinancialTransactionType.SELF_TRANSFER,
                amount = Money.of("150.00", Currency.SAR),
                occurredAt = local("2026-03-12T09:30:00"),
                sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
                destinationContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002"),
                merchant = null,
                counterparty = "OWN",
                categoryId = null,
                linkedParsedEventIds = emptyList(),
                occurredAtZone = zone.id,
            ),
        )
        val ledger = RecordingLedger(rows)
        val report = compared(ledger, statement)

        assertEquals(0, ledger.mutations)
        assertEquals(2, report.counts.matched)
        assertEquals(0, report.counts.statementOnly)
        assertEquals(0, report.matchedFleetPaymentCount)
        assertEquals(1, report.matchedSelfTransferCount)
        assertEquals(listOf("self-1"), report.statementLines.mapNotNull { it.ledgerTransactionId }.distinct())
        assertEquals(
            StatementComparisonStatus.MATCHED,
            report.statementLines.single { it.entry.direction == StatementDirection.DEBIT }.status,
        )
        assertEquals(
            StatementComparisonStatus.MATCHED,
            report.statementLines.single { it.entry.direction == StatementDirection.CREDIT }.status,
        )
        assertMoney("150.00", report.total("BANK_ALJAZIRA", "3001", Currency.SAR).matchedDebit)
        assertMoney("0.00", report.total("BANK_ALJAZIRA", "3001", Currency.SAR).matchedCredit)
        assertMoney("0.00", report.total("BANK_ALJAZIRA", "3002", Currency.SAR).matchedDebit)
        assertMoney("150.00", report.total("BANK_ALJAZIRA", "3002", Currency.SAR).matchedCredit)
        assertTrue(report.totals.all { it.currency == Currency.SAR })
    }

    @Test
    fun collisionsStayAmbiguous_andDoNotCrossBankSuffixCurrencyOrDirection() = runBlocking {
        val statement = accepted("edge_cases.csv")
        val ledger = RecordingLedger(edgeLedger())
        val before = ledger.rows.size
        val report = compared(ledger, statement)

        assertEquals(0, ledger.mutations)
        assertEquals(before, ledger.rows.size)
        assertEquals(StatementComparisonStatus.MATCHED, report.line("BOUNDARY MATCH").status)
        assertEquals("boundary-match", report.line("BOUNDARY MATCH").ledgerTransactionId)
        assertEquals(StatementComparisonStatus.MATCHED, report.line("BOUNDARY LATE").status)
        assertEquals("boundary-late", report.line("BOUNDARY LATE").ledgerTransactionId)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("next-midnight").status)
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("OPPOSITE DIR").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("opposite").status)
        assertEquals("usd", report.line("USD PURCHASE").ledgerTransactionId)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("sar-not-usd").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.line("REF COLLISION A").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.line("REF COLLISION B").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.ledger("ref-one").status)
        assertEquals("fee", report.line("FEE EDGE").ledgerTransactionId)
        assertEquals(FinancialTransactionType.FEE, report.line("FEE EDGE").ledgerType)
        assertEquals("refund", report.line("REFUND EDGE").ledgerTransactionId)
        assertEquals(FinancialTransactionType.REFUND, report.line("REFUND EDGE").ledgerType)
        assertEquals("purchase", report.line("PURCHASE EDGE").ledgerTransactionId)
        assertEquals("d360", report.line("OTHER BANK SAME SUFFIX").ledgerTransactionId)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("aljazira-same-suffix").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.line("AMBIGUOUS A").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.line("AMBIGUOUS B").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.ledger("amb-a").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.ledger("amb-b").status)
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("STATEMENT ONLY EDGE").status)
        assertTrue(report.ledgerLines.none { it.transactionId == "outside" })
        assertTrue(report.statementLines.none { it.status == StatementComparisonStatus.MATCHED && it.ledgerTransactionId == null })

        val sar = report.total("BANK_ALJAZIRA", "3001", Currency.SAR)
        assertMoney("54.00", sar.matchedDebit)
        assertMoney("8.00", sar.matchedCredit)
        assertMoney("4.00", sar.statementOnlyDebit)
        assertMoney("12.00", sar.statementOnlyCredit)
        assertMoney("64.00", sar.ledgerOnlyDebit)
        assertMoney("0.00", sar.ledgerOnlyCredit)
        assertMoney("76.00", sar.ambiguousStatementDebit)
        assertMoney("43.00", sar.ambiguousLedgerDebit)
        val usd = report.total("BANK_ALJAZIRA", "3001", Currency.USD)
        assertMoney("20.00", usd.matchedDebit)
        assertMoney("0.00", usd.matchedCredit)
        assertMoney("0.00", usd.ledgerOnlyDebit)
        val other = report.total("D360", "3001", Currency.SAR)
        assertMoney("20.00", other.matchedDebit)
        assertMoney("0.00", other.ledgerOnlyDebit)
        assertEquals(7, report.matchedFleetPaymentCount)
        assertEquals(0, report.matchedSelfTransferCount)
        assertEquals(3, report.totals.size)
        assertEquals(setOf(Currency.SAR, Currency.USD), report.totals.map { it.currency }.toSet())
    }

    @Test
    fun conflictingStoredZones_stayUnsupported() = runBlocking {
        val entry = BankStatementEntry(
            lineNumber = 1,
            bank = Bank("D360"),
            accountMasked = "3001",
            bookedDate = LocalDate.parse("2026-05-01"),
            bookedAtTime = null,
            bookedAtRaw = "2026-05-01",
            direction = StatementDirection.DEBIT,
            amount = Money.of("9.00", Currency.SAR),
            description = "ZONE",
            reference = null,
        )
        val rows = listOf(
            posted(
                id = "riyadh",
                type = FinancialTransactionType.EXPENSE,
                amount = "9.00",
                at = LocalDateTime.parse("2026-05-01T12:00:00").atZone(ZoneId.of("Asia/Riyadh")).toInstant(),
                direction = StatementDirection.DEBIT,
                bank = Bank("D360"),
                zoneId = "Asia/Riyadh",
            ),
            posted(
                id = "paris",
                type = FinancialTransactionType.EXPENSE,
                amount = "9.00",
                at = LocalDateTime.parse("2026-05-01T12:00:00").atZone(ZoneId.of("Europe/Paris")).toInstant(),
                direction = StatementDirection.DEBIT,
                bank = Bank("D360"),
                zoneId = "Europe/Paris",
            ),
        )
        val ledger = RecordingLedger(rows)
        val report = StatementReconciliationService(ledger).compare(
            ParsedBankStatement(formatVersion = 1, entries = listOf(entry), balances = emptyList()),
        )
        assertEquals(0, ledger.mutations)
        assertTrue(report.statementLines.all { it.status == StatementComparisonStatus.UNSUPPORTED })
        assertTrue(report.ledgerLines.all { it.status == StatementComparisonStatus.UNSUPPORTED })
        assertEquals(0, report.counts.matched)
    }

    @Test
    fun oversizedTruncatedAndUnrecognized_doNotReadOrWriteTheLedger() = runBlocking {
        val oversized = ByteArray((CanonicalStatementFormat.MAX_BYTES + 1).toInt()) { 'a'.code.toByte() }
        assertRejected(ByteArrayInputStream(oversized), StatementRejection.OVERSIZED)
        assertRejected(statementFixture("truncated.csv").inputStream(), StatementRejection.TRUNCATED)
        assertRejected(statementFixture("unrecognized.csv").inputStream(), StatementRejection.UNRECOGNIZED)
        assertRejected(statementFixture("duplicate_rows.csv").inputStream(), StatementRejection.DUPLICATE_ROW)
    }

    private suspend fun assertRejected(stream: ByteArrayInputStream, reason: StatementRejection) {
        val ledger = RecordingLedger(edgeLedger())
        val result = ImportStatementUseCase(parser, StatementReconciliationService(ledger))
            .import(stream, known)
        assertEquals(StatementImportResult.Rejected(reason), result)
        assertEquals(0, ledger.mutations)
        assertEquals(0, ledger.rangeReads)
    }

    private suspend fun assertRejected(stream: java.io.FileInputStream, reason: StatementRejection) {
        val ledger = RecordingLedger(emptyList())
        val result = ImportStatementUseCase(parser, StatementReconciliationService(ledger))
            .import(stream, known)
        assertEquals(StatementImportResult.Rejected(reason), result)
        assertEquals(0, ledger.mutations)
        assertEquals(0, ledger.rangeReads)
    }

    private fun accepted(name: String): ParsedBankStatement =
        parser.parse(statementFixture(name).inputStream(), known).let { parsed ->
            (parsed as StatementParseResult.Accepted).statement
        }

    private suspend fun compared(ledger: RecordingLedger, statement: ParsedBankStatement) =
        StatementReconciliationService(ledger).compare(statement)

    private fun edgeLedger(): List<FinancialTransaction> = listOf(
        posted("boundary-match", FinancialTransactionType.EXPENSE, "12.00", local("2026-04-02T23:59:59"), StatementDirection.DEBIT),
        posted("next-midnight", FinancialTransactionType.EXPENSE, "12.00", local("2026-04-03T00:00:00"), StatementDirection.DEBIT),
        posted("boundary-late", FinancialTransactionType.EXPENSE, "13.00", local("2026-04-02T01:00:00"), StatementDirection.DEBIT),
        posted("next-day", FinancialTransactionType.EXPENSE, "14.00", local("2026-04-03T12:00:00"), StatementDirection.DEBIT),
        posted("opposite", FinancialTransactionType.EXPENSE, "12.00", local("2026-04-04T12:00:00"), StatementDirection.DEBIT),
        posted("usd", FinancialTransactionType.EXPENSE, "20.00", local("2026-04-05T12:00:00"), StatementDirection.DEBIT, Currency.USD),
        posted("sar-not-usd", FinancialTransactionType.EXPENSE, "20.00", local("2026-04-05T12:00:00"), StatementDirection.DEBIT),
        posted("ref-one", FinancialTransactionType.EXPENSE, "33.00", local("2026-04-06T12:00:00"), StatementDirection.DEBIT),
        posted("fee", FinancialTransactionType.FEE, "7.00", local("2026-04-07T12:00:00"), StatementDirection.DEBIT),
        posted("refund", FinancialTransactionType.REFUND, "8.00", local("2026-04-08T12:00:00"), StatementDirection.CREDIT),
        posted("purchase", FinancialTransactionType.EXPENSE, "8.00", local("2026-04-08T12:00:00"), StatementDirection.DEBIT),
        posted(
            "d360",
            FinancialTransactionType.EXPENSE,
            "20.00",
            local("2026-04-09T12:00:00"),
            StatementDirection.DEBIT,
            bank = Bank("D360"),
            zoneId = "Asia/Riyadh",
        ),
        posted("aljazira-same-suffix", FinancialTransactionType.EXPENSE, "20.00", local("2026-04-09T12:00:00"), StatementDirection.DEBIT),
        posted("amb-a", FinancialTransactionType.EXPENSE, "5.00", local("2026-04-10T09:00:00"), StatementDirection.DEBIT),
        posted("amb-b", FinancialTransactionType.EXPENSE, "5.00", local("2026-04-10T18:00:00"), StatementDirection.DEBIT),
        posted("outside", FinancialTransactionType.EXPENSE, "1.00", local("2026-01-01T12:00:00"), StatementDirection.DEBIT),
    )

    private fun posted(
        id: String,
        type: FinancialTransactionType,
        amount: String,
        at: Instant,
        direction: StatementDirection,
        currency: Currency = Currency.SAR,
        bank: Bank = Bank.BANK_ALJAZIRA,
        accountMasked: String = "3001",
        zoneId: String? = zone.id,
        merchant: String? = "DIFFERENT",
    ): FinancialTransaction {
        val container = FinancialContainerIdFactory.accountId(bank, accountMasked)
        return FinancialTransaction(
            id = id,
            type = type,
            amount = Money.of(amount, currency),
            occurredAt = at,
            sourceContainerId = if (direction == StatementDirection.DEBIT) container else null,
            destinationContainerId = if (direction == StatementDirection.CREDIT) container else null,
            merchant = merchant,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
            occurredAtZone = zoneId,
        )
    }

    private fun instant(entry: BankStatementEntry): Instant {
        val local = entry.bookedAtTime ?: entry.bookedDate.atTime(12, 0)
        return local.atZone(zone).toInstant()
    }

    private fun local(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun StatementReconciliationReport.line(description: String) =
        statementLines.single { it.entry.description == description }

    private fun StatementReconciliationReport.ledger(id: String) =
        ledgerLines.single { it.transactionId == id }

    private fun StatementReconciliationReport.total(bankId: String, account: String, currency: Currency) =
        totals.single { it.bankId == bankId && it.accountMasked == account && it.currency == currency }

    private fun assertMoney(expected: String, actual: BigDecimal) {
        assertEquals(expected, 0, BigDecimal(expected).compareTo(actual))
    }
}

private class RecordingLedger(
    rows: List<FinancialTransaction>,
) : FinancialTransactionRepository {
    val rows: List<FinancialTransaction> = rows.toList()
    var mutations: Int = 0
    var rangeReads: Int = 0

    private fun mutate(): Nothing {
        mutations += 1
        error("statement reconciliation mutated the ledger")
    }

    override suspend fun save(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult = mutate()

    override suspend fun replaceExclusiveStaleLinks(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        staleRawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult = mutate()

    override suspend fun getById(id: String): FinancialTransaction? = rows.find { it.id == id }

    override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? = null

    override suspend fun listAll(): List<FinancialTransaction> = error("listAll is not the statement query")

    override suspend fun listOccurredBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction> {
        rangeReads += 1
        return rows.filter { !it.occurredAt.isBefore(startInclusive) && it.occurredAt.isBefore(endExclusive) }
    }

    override suspend fun isRawSmsLinked(rawSmsId: String): Boolean = false

    override suspend fun listRawSmsIds(transactionId: String): List<String> = emptyList()

    override suspend fun update(transaction: FinancialTransaction): Boolean = mutate()

    override suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: BigDecimal,
        source: com.baraa.masroof.domain.model.ExchangeRateSource,
    ): Boolean = mutate()

    override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean = mutate()

    override suspend fun unlinkRawSms(rawSmsId: String): Boolean = mutate()

    override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String): Boolean = mutate()
}
