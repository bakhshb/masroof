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
import com.baraa.masroof.domain.statement.StatementMatchPolicy
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
    private val owned = setOf(
        StatementMatchPolicy.QualifiedAccount(Bank.BANK_ALJAZIRA, "3001"),
        StatementMatchPolicy.QualifiedAccount(Bank.BANK_ALJAZIRA, "3002"),
        StatementMatchPolicy.QualifiedAccount(Bank("D360"), "3001"),
    )
    private val parser = CanonicalCsvStatementParser()
    private val ledgerReferences = linkedMapOf<String, Set<String>>()

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
                reference = entry.reference,
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
        assertEquals(LocalDate.parse("2026-03-31"), total.periodEnd)
        assertEquals(LocalDate.parse("2026-03-01"), report.coverage.periodStart)
        assertEquals(LocalDate.parse("2026-03-31"), report.coverage.periodEnd)
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
        ledgerReferences["self-1"] = setOf(StatementMatchPolicy.normalizeReference("ST-1"))
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
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("BOUNDARY LATE").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("boundary-late").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("next-midnight").status)
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("OPPOSITE DIR").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("opposite").status)
        assertEquals("usd", report.line("USD PURCHASE").ledgerTransactionId)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("sar-not-usd").status)
        assertEquals(StatementComparisonStatus.MATCHED, report.line("REF COLLISION A").status)
        assertEquals("ref-one", report.line("REF COLLISION A").ledgerTransactionId)
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("REF COLLISION B").status)
        assertEquals(StatementComparisonStatus.MATCHED, report.ledger("ref-one").status)
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
        assertMoney("74.00", sar.matchedDebit)
        assertMoney("8.00", sar.matchedCredit)
        assertMoney("50.00", sar.statementOnlyDebit)
        assertMoney("12.00", sar.statementOnlyCredit)
        assertMoney("77.00", sar.ledgerOnlyDebit)
        assertMoney("0.00", sar.ledgerOnlyCredit)
        assertMoney("10.00", sar.ambiguousStatementDebit)
        assertMoney("10.00", sar.ambiguousLedgerDebit)
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
    fun deviceJourneyFixture_showsMatchedStatementOnlyLedgerOnlyAndAmbiguous() = runBlocking {
        val csv = """
            # masroof-statement-v1
            # periodStart=2026-04-01
            # periodEnd=2026-04-30
            bankId,accountMasked,bookedAt,direction,amount,currency,description,reference
            BANK_ALJAZIRA,3001,2026-04-02,DEBIT,12.00,SAR,MATCHED ANON,REF-M
            BANK_ALJAZIRA,3001,2026-04-10,DEBIT,5.00,SAR,AMBIGUOUS ANON A,
            BANK_ALJAZIRA,3001,2026-04-10,DEBIT,5.00,SAR,AMBIGUOUS ANON B,
            BANK_ALJAZIRA,3001,2026-04-11,DEBIT,4.00,SAR,STATEMENT ONLY ANON,REF-S
        """.trimIndent() + "\n"
        val rows = listOf(
            posted("m19-matched", FinancialTransactionType.EXPENSE, "12.00", local("2026-04-02T12:00:00"), StatementDirection.DEBIT, reference = "REF-M"),
            posted("m19-ambiguous-a", FinancialTransactionType.EXPENSE, "5.00", local("2026-04-10T09:00:00"), StatementDirection.DEBIT),
            posted("m19-ambiguous-b", FinancialTransactionType.EXPENSE, "5.00", local("2026-04-10T18:00:00"), StatementDirection.DEBIT),
            posted("m19-ledger-only", FinancialTransactionType.EXPENSE, "7.00", local("2026-04-11T12:00:00"), StatementDirection.DEBIT),
        )
        val ledger = RecordingLedger(rows)
        val result = ImportStatementUseCase(
            parser,
            StatementReconciliationService(ledger) { transactions ->
                transactions.associate { it.id to ledgerReferences[it.id].orEmpty() }
            },
        ).import(ByteArrayInputStream(csv.toByteArray()), known, owned)
        val report = (result as StatementImportResult.Compared).report
        assertEquals(0, ledger.mutations)
        assertEquals(1, report.counts.matched)
        assertEquals(1, report.counts.statementOnly)
        assertEquals(1, report.counts.ledgerOnly)
        assertEquals(4, report.counts.ambiguous)
        assertEquals(0, report.counts.unsupported)
        assertEquals(StatementComparisonStatus.MATCHED, report.line("MATCHED ANON").status)
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("STATEMENT ONLY ANON").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("m19-ledger-only").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.line("AMBIGUOUS ANON A").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.line("AMBIGUOUS ANON B").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.ledger("m19-ambiguous-a").status)
        assertEquals(StatementComparisonStatus.AMBIGUOUS, report.ledger("m19-ambiguous-b").status)
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
            ParsedBankStatement(
                formatVersion = 1,
                coverage = com.baraa.masroof.domain.statement.StatementCoverage(
                    LocalDate.parse("2026-05-01"),
                    LocalDate.parse("2026-05-01"),
                ),
                entries = listOf(entry),
                balances = emptyList(),
            ),
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

    @Test
    fun sameDayAmount_withDistantBookingTime_isNotMatched() = runBlocking {
        val csv = statementCsv(
            "BANK_ALJAZIRA,3001,2026-04-02T09:00:00,DEBIT,12.00,SAR,MORNING ANON",
        )
        val ledger = RecordingLedger(
            listOf(
                posted(
                    "evening",
                    FinancialTransactionType.EXPENSE,
                    "12.00",
                    local("2026-04-02T18:00:00"),
                    StatementDirection.DEBIT,
                ),
            ),
        )
        val report = compared(ledger, acceptedText(csv))
        assertEquals(0, ledger.mutations)
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("MORNING ANON").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("evening").status)
        assertEquals(0, report.counts.matched)
    }

    @Test
    fun referenceMismatch_isNotMatched() = runBlocking {
        val csv = statementCsv(
            "BANK_ALJAZIRA,3001,2026-04-02T09:00:00,DEBIT,12.00,SAR,REF ANON,REF-1",
        )
        val ledger = RecordingLedger(
            listOf(
                posted(
                    "other-ref",
                    FinancialTransactionType.EXPENSE,
                    "12.00",
                    local("2026-04-02T09:00:00"),
                    StatementDirection.DEBIT,
                    reference = "REF-2",
                ),
            ),
        )
        val report = compared(ledger, acceptedText(csv))
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("REF ANON").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("other-ref").status)
        assertEquals(0, report.counts.matched)
    }

    @Test
    fun suppliedReference_withoutLedgerReference_isNotMatched() = runBlocking {
        val csv = statementCsv(
            "BANK_ALJAZIRA,3001,2026-04-02,DEBIT,12.00,SAR,UNBACKED ANON,REF-1",
        )
        val ledger = RecordingLedger(
            listOf(
                posted(
                    "no-ref",
                    FinancialTransactionType.EXPENSE,
                    "12.00",
                    local("2026-04-02T12:00:00"),
                    StatementDirection.DEBIT,
                ),
            ),
        )
        val report = compared(ledger, acceptedText(csv))
        assertEquals(StatementComparisonStatus.STATEMENT_ONLY, report.line("UNBACKED ANON").status)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("no-ref").status)
        assertEquals(0, report.counts.matched)
    }

    @Test
    fun closeBookingTime_andSameReference_stillMatch() = runBlocking {
        val csv = statementCsv(
            "BANK_ALJAZIRA,3001,2026-04-02T09:00:00,DEBIT,12.00,SAR,CLOSE ANON,REF-1",
        )
        val ledger = RecordingLedger(
            listOf(
                posted(
                    "close",
                    FinancialTransactionType.EXPENSE,
                    "12.00",
                    local("2026-04-02T09:05:00"),
                    StatementDirection.DEBIT,
                    reference = "ref-1",
                ),
            ),
        )
        val report = compared(ledger, acceptedText(csv))
        assertEquals(StatementComparisonStatus.MATCHED, report.line("CLOSE ANON").status)
        assertEquals("close", report.line("CLOSE ANON").ledgerTransactionId)
    }

    @Test
    fun externalAccount_isRejectedBeforeTheLedgerIsRead() = runBlocking {
        val csv = statementCsv(
            "BANK_ALJAZIRA,3001,2026-04-02,DEBIT,12.00,SAR,EXTERNAL ANON",
        )
        val ledger = RecordingLedger(emptyList())
        val result = ImportStatementUseCase(parser, StatementReconciliationService(ledger))
            .import(ByteArrayInputStream(csv.toByteArray()), known, emptySet())
        assertEquals(StatementImportResult.Rejected(StatementRejection.UNOWNED_ACCOUNT), result)
        assertEquals(0, ledger.rangeReads)
        assertEquals(0, ledger.mutations)
    }

    @Test
    fun sameSuffixOnAnotherBank_doesNotAuthorizeTheAccount() = runBlocking {
        val aljazira = statementCsv(
            "BANK_ALJAZIRA,3001,2026-04-02,DEBIT,12.00,SAR,ALJAZIRA ANON",
        )
        val otherLedger = RecordingLedger(emptyList())
        val otherBank = ImportStatementUseCase(parser, StatementReconciliationService(otherLedger))
            .import(
                ByteArrayInputStream(aljazira.toByteArray()),
                known,
                setOf(StatementMatchPolicy.QualifiedAccount(Bank("D360"), "3001")),
            )
        val longerLedger = RecordingLedger(emptyList())
        val longer = ImportStatementUseCase(parser, StatementReconciliationService(longerLedger))
            .import(
                ByteArrayInputStream(aljazira.toByteArray()),
                known,
                setOf(StatementMatchPolicy.QualifiedAccount(Bank.BANK_ALJAZIRA, "99883001")),
            )
        assertEquals(StatementImportResult.Rejected(StatementRejection.UNOWNED_ACCOUNT), otherBank)
        assertEquals(StatementImportResult.Rejected(StatementRejection.UNOWNED_ACCOUNT), longer)
        assertEquals(0, otherLedger.rangeReads)
        assertEquals(0, longerLedger.rangeReads)

        val ownedExact = setOf(StatementMatchPolicy.QualifiedAccount(Bank("D360"), "3001"))
        val d360 = statementCsv(
            "D360,3001,2026-04-02,DEBIT,12.00,SAR,D360 ANON",
        )
        val ledger = RecordingLedger(emptyList())
        val accepted = ImportStatementUseCase(parser, StatementReconciliationService(ledger))
            .import(ByteArrayInputStream(d360.toByteArray()), known, ownedExact)
        assertTrue(accepted is StatementImportResult.Compared)
        assertEquals(1, ledger.rangeReads)
        assertEquals(0, ledger.mutations)
    }

    private suspend fun assertRejected(stream: ByteArrayInputStream, reason: StatementRejection) {
        val ledger = RecordingLedger(edgeLedger())
        val result = ImportStatementUseCase(parser, StatementReconciliationService(ledger))
            .import(stream, known, owned)
        assertEquals(StatementImportResult.Rejected(reason), result)
        assertEquals(0, ledger.mutations)
        assertEquals(0, ledger.rangeReads)
    }

    private suspend fun assertRejected(stream: java.io.FileInputStream, reason: StatementRejection) {
        val ledger = RecordingLedger(emptyList())
        val result = ImportStatementUseCase(parser, StatementReconciliationService(ledger))
            .import(stream, known, owned)
        assertEquals(StatementImportResult.Rejected(reason), result)
        assertEquals(0, ledger.mutations)
        assertEquals(0, ledger.rangeReads)
    }

    @Test
    fun coverageIncludesLedgerMovementBetweenStatementRows() = runBlocking {
        val report = marchCoverage(
            posted("march-27", FinancialTransactionType.EXPENSE, "8.00", local("2026-03-27T12:00:00"), StatementDirection.DEBIT),
        )
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("march-27").status)
        assertEquals(1, report.counts.ledgerOnly)
    }

    @Test
    fun coverageIncludesLedgerMovementOnPeriodEnd() = runBlocking {
        val report = marchCoverage(
            posted("march-31", FinancialTransactionType.EXPENSE, "8.00", local("2026-03-31T23:00:00"), StatementDirection.DEBIT),
        )
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("march-31").status)
    }

    @Test
    fun coverageExcludesLedgerMovementOnTheFollowingDay() = runBlocking {
        val ledger = RecordingLedger(
            listOf(
                posted("april-1", FinancialTransactionType.EXPENSE, "8.00", local("2026-04-01T00:00:00"), StatementDirection.DEBIT),
            ),
        )
        val report = compared(ledger, acceptedText(marchStatement()))
        assertTrue(report.ledgerLines.none { it.transactionId == "april-1" })
        assertEquals(0, report.counts.ledgerOnly)
    }

    @Test
    fun statementRowOutsideDeclaredCoverage_isRejectedBeforeTheLedgerIsRead() = runBlocking {
        val csv = statementCsv(
            "BANK_ALJAZIRA,3001,2026-04-02,DEBIT,10.00,SAR,OUTSIDE",
            periodStart = "2026-03-01",
            periodEnd = "2026-03-31",
        )
        val ledger = RecordingLedger(emptyList())
        val result = ImportStatementUseCase(parser, StatementReconciliationService(ledger))
            .import(ByteArrayInputStream(csv.toByteArray()), known, owned)
        assertEquals(StatementImportResult.Rejected(StatementRejection.OUT_OF_RANGE), result)
        assertEquals(0, ledger.rangeReads)
        assertEquals(0, ledger.mutations)
    }

    @Test
    fun totalsUseTheDeclaredCoverageDates() = runBlocking {
        val report = marchCoverage(
            posted("march-27", FinancialTransactionType.EXPENSE, "8.00", local("2026-03-27T12:00:00"), StatementDirection.DEBIT),
        )
        assertEquals(LocalDate.parse("2026-03-01"), report.coverage.periodStart)
        assertEquals(LocalDate.parse("2026-03-31"), report.coverage.periodEnd)
        val total = report.total("BANK_ALJAZIRA", "3001", Currency.SAR)
        assertEquals(LocalDate.parse("2026-03-01"), total.periodStart)
        assertEquals(LocalDate.parse("2026-03-31"), total.periodEnd)
        assertEquals(StatementComparisonStatus.LEDGER_ONLY, report.ledger("march-27").status)
    }

    private fun marchStatement(): String = statementCsv(
        "BANK_ALJAZIRA,3001,2026-03-05,DEBIT,10.00,SAR,EARLY",
        "BANK_ALJAZIRA,3001,2026-03-20,DEBIT,11.00,SAR,LATE",
        periodStart = "2026-03-01",
        periodEnd = "2026-03-31",
    )

    private suspend fun marchCoverage(vararg rows: FinancialTransaction): StatementReconciliationReport {
        val ledger = RecordingLedger(rows.toList())
        return compared(ledger, acceptedText(marchStatement()))
    }

    private fun statementCsv(
        vararg rows: String,
        periodStart: String = "2026-01-01",
        periodEnd: String = "2026-12-31",
    ): String {
        val padded = rows.map { row ->
            if (row.split(',').size == 7) "$row," else row
        }
        return (
            listOf(
                "# masroof-statement-v1",
                "# periodStart=$periodStart",
                "# periodEnd=$periodEnd",
                "bankId,accountMasked,bookedAt,direction,amount,currency,description,reference",
            ) + padded
        ).joinToString("\n") + "\n"
    }

    private fun acceptedText(csv: String): ParsedBankStatement =
        parser.parse(ByteArrayInputStream(csv.toByteArray()), known).let { parsed ->
            (parsed as StatementParseResult.Accepted).statement
        }

    private fun accepted(name: String): ParsedBankStatement =
        parser.parse(statementFixture(name).inputStream(), known).let { parsed ->
            (parsed as StatementParseResult.Accepted).statement
        }

    private suspend fun compared(ledger: RecordingLedger, statement: ParsedBankStatement) =
        StatementReconciliationService(ledger) { transactions ->
            transactions.associate { it.id to ledgerReferences[it.id].orEmpty() }
        }.compare(statement)

    private fun edgeLedger(): List<FinancialTransaction> = listOf(
        posted("boundary-match", FinancialTransactionType.EXPENSE, "12.00", local("2026-04-02T23:59:59"), StatementDirection.DEBIT, reference = "B-1"),
        posted("next-midnight", FinancialTransactionType.EXPENSE, "12.00", local("2026-04-03T00:00:00"), StatementDirection.DEBIT),
        posted("boundary-late", FinancialTransactionType.EXPENSE, "13.00", local("2026-04-02T01:00:00"), StatementDirection.DEBIT, reference = "B-2"),
        posted("next-day", FinancialTransactionType.EXPENSE, "14.00", local("2026-04-03T12:00:00"), StatementDirection.DEBIT, reference = "B-3"),
        posted("opposite", FinancialTransactionType.EXPENSE, "12.00", local("2026-04-04T12:00:00"), StatementDirection.DEBIT, reference = "B-4"),
        posted("usd", FinancialTransactionType.EXPENSE, "20.00", local("2026-04-05T12:00:00"), StatementDirection.DEBIT, Currency.USD, reference = "B-5"),
        posted("sar-not-usd", FinancialTransactionType.EXPENSE, "20.00", local("2026-04-05T12:00:00"), StatementDirection.DEBIT),
        posted("ref-one", FinancialTransactionType.EXPENSE, "33.00", local("2026-04-06T12:00:00"), StatementDirection.DEBIT, reference = "REF-A"),
        posted("fee", FinancialTransactionType.FEE, "7.00", local("2026-04-07T12:00:00"), StatementDirection.DEBIT, reference = "FEE-1"),
        posted("refund", FinancialTransactionType.REFUND, "8.00", local("2026-04-08T12:00:00"), StatementDirection.CREDIT, reference = "RF-1"),
        posted("purchase", FinancialTransactionType.EXPENSE, "8.00", local("2026-04-08T12:00:00"), StatementDirection.DEBIT, reference = "PU-1"),
        posted(
            "d360",
            FinancialTransactionType.EXPENSE,
            "20.00",
            local("2026-04-09T12:00:00"),
            StatementDirection.DEBIT,
            bank = Bank("D360"),
            zoneId = "Asia/Riyadh",
            reference = "D-1",
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
        reference: String? = null,
    ): FinancialTransaction {
        val normalized = StatementMatchPolicy.normalizeReference(reference)
        if (normalized.isNotEmpty()) ledgerReferences[id] = setOf(normalized)
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
