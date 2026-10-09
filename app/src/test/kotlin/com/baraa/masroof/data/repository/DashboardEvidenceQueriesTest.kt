package com.baraa.masroof.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.domain.ids.TransactionIdFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.isCreditCardSms
import com.baraa.masroof.parsing.model.isDebitCardSms
import com.baraa.masroof.parsing.model.isStatementSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Batch and fact queries behind the scoped dashboard read path. Each Room query must return
 * exactly the rows its [ParsedEventRepository] contract describes; the in-memory defaults
 * must return a superset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardEvidenceQueriesTest {
    private lateinit var world: DashboardLedgerWorld
    private lateinit var all: List<ParsedEventRecord>
    private lateinit var receivedAt: Map<String, Instant>
    private lateinit var defaults: ParsedEventRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        world = DashboardLedgerWorld(ApplicationProvider.getApplicationContext<Context>())
        world.importFixtureCorpus()
        world.seedSyntheticHistory(DashboardLedgerWorld.SYNTHETIC_MONTHS)
        all = world.parsedRepo.listAll()
        receivedAt = world.rawRepo.getByIds(all.map { it.event.rawSmsId }).associate { it.id to it.receivedAt }
        defaults = ListAllOnlyParsedEventRepository(all)
    }

    @After
    fun tearDown() {
        world.close()
    }

    @Test
    fun cardStatementFacts_areEveryStatementRow() = runBlocking<Unit> {
        val expected = all.filter { it.details.isStatementSms() }
        assertTrue(expected.size > 10)
        assertExact(expected, world.parsedRepo.listCardStatementFacts(), defaults.listCardStatementFacts())
    }

    @Test
    fun latestCreditCardRowFacts_areNewestEventTimePerCard() = runBlocking<Unit> {
        val expected = all
            .filter { it.details.isCreditCardSms() && it.event.cardRef?.last4 != null }
            .groupBy { it.event.cardRef }
            .map { (_, rows) -> latestByEventTime(rows) }
        assertTrue(expected.map { it.event.cardRef?.last4 }.containsAll(listOf("7271", "5555", "4444", "3333")))
        assertExact(expected, world.parsedRepo.listLatestCreditCardRowFacts(), defaults.listLatestCreditCardRowFacts())
    }

    @Test
    fun latestAvailableBalanceFacts_keepLatestPerCardBeforeBound_includingTies() = runBlocking<Unit> {
        val bounds = DashboardLedgerWorld.SYNTHETIC_MONTHS.flatMap { month ->
            listOf(1, 21, 25).map { day -> month.atDay(day).atStartOfDay(world.zone).toInstant() }
        }
        var sawTie = false
        for (bound in bounds) {
            val expected = expectedLatestAvailable(bound)
            sawTie = sawTie || expected.groupBy { it.event.cardRef }.values.any { it.size > 1 }
            assertExact(
                expected,
                world.parsedRepo.listLatestCreditCardAvailableBalanceFacts(bound),
                defaults.listLatestCreditCardAvailableBalanceFacts(bound),
                label = "before $bound",
            )
        }
        assertTrue("tied balance rows must be exercised", sawTie)
    }

    @Test
    fun financingInstallmentFacts_areInstallmentsWithLoanType() = runBlocking<Unit> {
        val expected = all.filter {
            it.event.messageFamily == MessageFamily.FINANCING_INSTALLMENT && it.details.loanType != null
        }
        assertTrue(expected.size > DashboardLedgerWorld.SYNTHETIC_MONTHS.size)
        assertExact(expected, world.parsedRepo.listFinancingInstallmentFacts(), defaults.listFinancingInstallmentFacts())
    }

    @Test
    fun exchangeRateFacts_areRowsWithMerchantAndRate() = runBlocking<Unit> {
        val expected = all.filter { it.event.merchant != null && it.details.exchangeRate != null }
        assertTrue(expected.any { it.event.parseStatus == ParseStatus.REVIEW_REQUIRED })
        assertExact(expected, world.parsedRepo.listExchangeRateFacts(), defaults.listExchangeRateFacts())
    }

    @Test
    fun firstDebitCardFacts_areEarliestDebitAndSourceAccountRowPerCard() = runBlocking<Unit> {
        val last4s = listOf("2210", "8219", "6666", "7271", "0000")
        val wanted = all.filter { it.event.cardRef?.last4 in last4s }
            .groupBy { it.event.bank to it.event.cardRef?.last4 }
        val expected = wanted.values.flatMap { rows ->
            listOfNotNull(
                rows.filter { it.details.isDebitCardSms() }.let(::earliestByEventTimeOrNull),
                rows.filter { it.details.debitSourceAccountLast4 != null || it.event.sourceAccountRef != null }
                    .let(::earliestByEventTimeOrNull),
            )
        }.distinctBy { it.event.id }
        assertTrue(expected.any { it.event.cardRef?.last4 == "6666" && it.details.cardSmsChannel == CardSmsChannel.CREDIT })
        assertExact(expected, world.parsedRepo.listFirstDebitCardFacts(last4s), defaults.listFirstDebitCardFacts(last4s))
        assertEquals(emptyList<ParsedEventRecord>(), world.parsedRepo.listFirstDebitCardFacts(emptyList()))
    }

    @Test
    fun chronologicalFacts_ignoreLexicalIds_andDoNotBorrowFactsAfterAsOf() = runBlocking<Unit> {
        val early = Instant.parse("2024-01-01T00:00:00Z")
        val middle = Instant.parse("2024-06-01T00:00:00Z")
        val late = Instant.parse("2024-12-01T00:00:00Z")
        // Insert the lexically smaller id second, with the earlier event time.
        saveCard(
            id = "10",
            occurredAt = late,
            receivedAt = late,
            last4 = "9009",
            channel = CardSmsChannel.CREDIT,
            availableBalance = "20.00",
        )
        saveCard(
            id = "9",
            occurredAt = early,
            receivedAt = early,
            last4 = "9009",
            channel = CardSmsChannel.CREDIT,
            availableBalance = "10.00",
        )
        // Receipt time fills a missing event time and beats a lexically greater id.
        saveCard(
            id = "8",
            occurredAt = null,
            receivedAt = late.plusSeconds(60),
            last4 = "9008",
            channel = CardSmsChannel.CREDIT,
            availableBalance = "8.00",
        )
        saveCard(
            id = "80",
            occurredAt = middle,
            receivedAt = early,
            last4 = "9008",
            channel = CardSmsChannel.CREDIT,
            availableBalance = "80.00",
        )
        // Same instant: the greatest event id wins.
        saveCard(
            id = "tie-9",
            occurredAt = middle,
            receivedAt = early,
            last4 = "9010",
            channel = CardSmsChannel.CREDIT,
        )
        saveCard(
            id = "tie-10",
            occurredAt = middle,
            receivedAt = late,
            last4 = "9010",
            channel = CardSmsChannel.CREDIT,
        )
        // Same last4 at another bank stays its own group.
        saveCard(
            id = "d360-late",
            occurredAt = late,
            receivedAt = late,
            last4 = "9009",
            channel = CardSmsChannel.CREDIT,
            bank = Bank("D360"),
            availableBalance = "50.00",
        )
        saveCard(
            id = "d360-early",
            occurredAt = early,
            receivedAt = early,
            last4 = "9009",
            channel = CardSmsChannel.CREDIT,
            bank = Bank("D360"),
            availableBalance = "40.00",
        )

        assertEquals(listOf("10"), idsForLast4(world.parsedRepo.listLatestCreditCardRowFacts(), "9009", Bank.BANK_ALJAZIRA))
        assertEquals(listOf("8"), idsForLast4(world.parsedRepo.listLatestCreditCardRowFacts(), "9008", Bank.BANK_ALJAZIRA))
        assertEquals(listOf("tie-9"), idsForLast4(world.parsedRepo.listLatestCreditCardRowFacts(), "9010", Bank.BANK_ALJAZIRA))
        assertEquals(listOf("d360-late"), idsForLast4(world.parsedRepo.listLatestCreditCardRowFacts(), "9009", Bank("D360")))

        assertEquals(
            listOf("9"),
            idsForLast4(world.parsedRepo.listLatestCreditCardAvailableBalanceFacts(middle), "9009", Bank.BANK_ALJAZIRA),
        )
        assertEquals(
            listOf("10"),
            idsForLast4(world.parsedRepo.listLatestCreditCardAvailableBalanceFacts(late.plusSeconds(1)), "9009", Bank.BANK_ALJAZIRA),
        )
        assertEquals(
            emptyList<String>(),
            idsForLast4(world.parsedRepo.listLatestCreditCardAvailableBalanceFacts(early), "9009", Bank.BANK_ALJAZIRA),
        )
        assertEquals(
            listOf("d360-early"),
            idsForLast4(world.parsedRepo.listLatestCreditCardAvailableBalanceFacts(middle), "9009", Bank("D360")),
        )
    }

    @Test
    fun firstDebitFacts_useEventTime_whenLexicalIdsAreReversed() = runBlocking<Unit> {
        val early = Instant.parse("2024-01-01T00:00:00Z")
        val late = Instant.parse("2024-12-01T00:00:00Z")
        // '10' < '9' lexically, and it happened later. MIN(id) would pick the wrong row.
        saveCard(
            id = "10",
            occurredAt = late,
            receivedAt = late,
            last4 = "9002",
            channel = CardSmsChannel.DEBIT,
            debitSource = "2222",
        )
        saveCard(
            id = "9",
            occurredAt = early,
            receivedAt = early,
            last4 = "9002",
            channel = CardSmsChannel.DEBIT,
            debitSource = "1111",
        )
        // Source-account evidence with no event time still orders by receipt time.
        saveCard(
            id = "src-10",
            occurredAt = null,
            receivedAt = late,
            last4 = "9003",
            channel = CardSmsChannel.CREDIT,
            sourceAccount = "XXXX3002",
        )
        saveCard(
            id = "src-9",
            occurredAt = null,
            receivedAt = early,
            last4 = "9003",
            channel = CardSmsChannel.CREDIT,
            sourceAccount = "XXXX3001",
        )
        // Equal event time: the least id is the stable tie-break.
        saveCard(
            id = "same-9",
            occurredAt = early,
            receivedAt = late,
            last4 = "9004",
            channel = CardSmsChannel.DEBIT,
            debitSource = "3333",
        )
        saveCard(
            id = "same-10",
            occurredAt = early,
            receivedAt = early,
            last4 = "9004",
            channel = CardSmsChannel.DEBIT,
            debitSource = "4444",
        )

        val debit = world.parsedRepo.listFirstDebitCardFacts(listOf("9002", "9003", "9004"))
        assertEquals(listOf("9"), debit.filter { it.event.cardRef?.last4 == "9002" }.map { it.event.id })
        assertEquals("1111", debit.single { it.event.id == "9" }.details.debitSourceAccountLast4)
        assertEquals(listOf("src-9"), debit.filter { it.event.cardRef?.last4 == "9003" }.map { it.event.id })
        assertEquals(listOf("same-10"), debit.filter { it.event.cardRef?.last4 == "9004" }.map { it.event.id })
    }

    @Test
    fun creditAndDebitEvidenceQueries_useExistingCardIndex() {
        val db = world.db.openHelper.readableDatabase
        val plans = linkedMapOf(
            "latestCredit" to explain(
                db,
                """
                SELECT MAX(p3.id)
                FROM parsed_event p3
                INNER JOIN raw_sms r3 ON r3.id = p3.rawSmsId
                INNER JOIN (
                    SELECT p2.cardBankId AS cardBankId,
                           p2.cardLast4 AS cardLast4,
                           MAX(COALESCE(p2.occurredAtEpochMillis, r2.receivedAtEpochMillis)) AS sortAt
                    FROM parsed_event p2
                    INNER JOIN raw_sms r2 ON r2.id = p2.rawSmsId
                    WHERE p2.cardSmsChannel IN ('CREDIT', 'STATEMENT')
                      AND p2.cardLast4 IS NOT NULL
                    GROUP BY p2.cardBankId, p2.cardLast4
                ) chosen
                  ON chosen.cardBankId IS p3.cardBankId
                 AND chosen.cardLast4 = p3.cardLast4
                 AND COALESCE(p3.occurredAtEpochMillis, r3.receivedAtEpochMillis) = chosen.sortAt
                WHERE p3.cardSmsChannel IN ('CREDIT', 'STATEMENT')
                  AND p3.cardLast4 IS NOT NULL
                GROUP BY p3.cardBankId, p3.cardLast4
                """.trimIndent(),
            ),
            "availableBalance" to explain(
                db,
                """
                SELECT pe.* FROM parsed_event pe
                INNER JOIN raw_sms rs ON rs.id = pe.rawSmsId
                INNER JOIN (
                    SELECT p2.cardBankId AS cardBankId,
                           p2.cardLast4 AS cardLast4,
                           MAX(COALESCE(p2.occurredAtEpochMillis, r2.receivedAtEpochMillis)) AS latestAt
                    FROM parsed_event p2
                    INNER JOIN raw_sms r2 ON r2.id = p2.rawSmsId
                    WHERE p2.cardSmsChannel = 'CREDIT'
                      AND p2.availableBalanceDecimal IS NOT NULL
                      AND p2.cardLast4 IS NOT NULL
                      AND COALESCE(p2.occurredAtEpochMillis, r2.receivedAtEpochMillis) < 1
                    GROUP BY p2.cardBankId, p2.cardLast4
                ) latest ON latest.cardBankId IS pe.cardBankId AND latest.cardLast4 = pe.cardLast4
                WHERE pe.cardSmsChannel = 'CREDIT'
                  AND pe.availableBalanceDecimal IS NOT NULL
                  AND pe.cardLast4 IS NOT NULL
                  AND COALESCE(pe.occurredAtEpochMillis, rs.receivedAtEpochMillis) = latest.latestAt
                  AND COALESCE(pe.occurredAtEpochMillis, rs.receivedAtEpochMillis) < 1
                ORDER BY pe.id
                """.trimIndent(),
            ),
            "firstDebit" to explain(
                db,
                """
                SELECT MIN(p3.id)
                FROM parsed_event p3
                INNER JOIN raw_sms r3 ON r3.id = p3.rawSmsId
                INNER JOIN (
                    SELECT p2.bankId AS bankId,
                           p2.cardLast4 AS cardLast4,
                           MIN(COALESCE(p2.occurredAtEpochMillis, r2.receivedAtEpochMillis)) AS sortAt
                    FROM parsed_event p2
                    INNER JOIN raw_sms r2 ON r2.id = p2.rawSmsId
                    WHERE p2.cardLast4 IN ('2210')
                      AND p2.cardSmsChannel = 'DEBIT'
                    GROUP BY p2.bankId, p2.cardLast4
                ) chosen
                  ON chosen.bankId IS p3.bankId
                 AND chosen.cardLast4 = p3.cardLast4
                 AND COALESCE(p3.occurredAtEpochMillis, r3.receivedAtEpochMillis) = chosen.sortAt
                WHERE p3.cardSmsChannel = 'DEBIT'
                GROUP BY p3.bankId, p3.cardLast4
                """.trimIndent(),
            ),
        )
        java.io.File("/opt/cursor/artifacts").mkdirs()
        java.io.File("/opt/cursor/artifacts/m7-query-plan.txt").writeText(
            plans.entries.joinToString("\n") { (name, plan) -> "== $name ==\n$plan" },
        )
        plans.forEach { (name, plan) ->
            assertTrue(
                "$name plan must keep the existing card index and not require a new one:\n$plan",
                "index_parsed_event_cardSmsChannel_cardLast4" in plan ||
                    "index_parsed_event_cardBankId_cardLast4" in plan,
            )
        }
    }

    @Test
    fun batchLookups_spanMultipleChunks_andSkipMissingIds() = runBlocking<Unit> {
        val count = RoomBatch.MAX_BIND_ARGS * 2 + 37
        val rawIds = (1..count).map { "bulk-sms:${it.toString().padStart(5, '0')}" }
        val start = Instant.parse("2027-01-01T00:00:00Z")
        val transactions = rawIds.mapIndexed { index, rawId ->
            world.rawRepo.insertIfAbsent(
                RawSms(rawId, "BankAlJazira", "bulk $index", start.plusSeconds(index.toLong()), null, "bulk-hash-$index"),
            )
            world.parsedRepo.save(event(rawId))
            val tx = FinancialTransaction(
                id = TransactionIdFactory.fromRawSmsIds(listOf(rawId)),
                type = if (index % 2 == 0) FinancialTransactionType.CREDIT_CARD_PAYMENT else FinancialTransactionType.EXPENSE,
                amount = Money.of("1.00", Currency.SAR),
                occurredAt = start.plusSeconds(index.toLong()),
                sourceContainerId = null,
                destinationContainerId = null,
                merchant = null,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = listOf("evt-$rawId"),
            )
            world.ftRepo.save(tx, listOf(rawId))
            tx
        }
        val lookup = rawIds + listOf("missing-1", "missing-2") + rawIds.take(5)

        assertEquals(rawIds.toSet(), world.rawRepo.getByIds(lookup).map { it.id }.toSet())
        assertEquals(count, world.rawRepo.getByIds(lookup).size)
        val parsed = world.parsedRepo.listByRawSmsIds(lookup)
        assertEquals(rawIds.map { "evt-$it" }, parsed.map { it.event.id })
        assertEquals(
            rawIds.toSet(),
            world.ftRepo.listRawSmsIdsForTransactions(transactions.map { it.id } + "missing-tx"),
        )

        val bulkPayments = transactions.filter { it.type == FinancialTransactionType.CREDIT_CARD_PAYMENT }
        assertTrue(bulkPayments.size > RoomBatch.MAX_BIND_ARGS)
        val bulkPaymentIds = bulkPayments.map { it.id }.toSet()
        val loadedBulkPayments = world.ftRepo.listByTypes(listOf(FinancialTransactionType.CREDIT_CARD_PAYMENT))
            .filter { it.id in bulkPaymentIds }
        assertEquals(bulkPayments.sortedBy { it.id }, loadedBulkPayments.sortedBy { it.id })

        val since = start.plusSeconds(100)
        val payments = world.ftRepo.listByTypesOccurredSince(listOf(FinancialTransactionType.CREDIT_CARD_PAYMENT), since)
        val expected = world.ftRepo.listByTypes(listOf(FinancialTransactionType.CREDIT_CARD_PAYMENT))
            .filter { !it.occurredAt.isBefore(since) }
        assertEquals(expected, payments)
        assertEquals(since, payments.first().occurredAt)
        assertEquals(emptyList<FinancialTransaction>(), world.ftRepo.listByTypesOccurredSince(emptyList(), since))
    }

    private fun expectedLatestAvailable(beforeExclusive: Instant): List<ParsedEventRecord> =
        all
            .filter {
                it.details.cardSmsChannel == CardSmsChannel.CREDIT &&
                    it.details.availableBalance != null &&
                    it.event.cardRef?.last4 != null &&
                    effectiveAt(it).isBefore(beforeExclusive)
            }
            .groupBy { it.event.cardRef }
            .values
            .flatMap { rows ->
                val latest = rows.maxOf { effectiveAt(it) }
                rows.filter { effectiveAt(it) == latest }
            }

    /** Latest effective instant, then the greatest event id. */
    private fun latestByEventTime(rows: List<ParsedEventRecord>): ParsedEventRecord {
        val latest = rows.maxOf { effectiveAt(it) }
        return rows.filter { effectiveAt(it) == latest }.maxBy { it.event.id }
    }

    /** Earliest effective instant, then the least event id. Empty input returns null. */
    private fun earliestByEventTimeOrNull(rows: List<ParsedEventRecord>): ParsedEventRecord? {
        if (rows.isEmpty()) return null
        val earliest = rows.minOf { effectiveAt(it) }
        return rows.filter { effectiveAt(it) == earliest }.minBy { it.event.id }
    }

    private fun effectiveAt(record: ParsedEventRecord): Instant =
        record.event.occurredAt ?: receivedAt.getValue(record.event.rawSmsId)

    private fun idsForLast4(
        records: List<ParsedEventRecord>,
        last4: String,
        bank: Bank,
    ): List<String> =
        records
            .filter { it.event.cardRef?.bank == bank && it.event.cardRef?.last4 == last4 }
            .map { it.event.id }

    private suspend fun saveCard(
        id: String,
        occurredAt: Instant?,
        receivedAt: Instant,
        last4: String,
        channel: CardSmsChannel,
        bank: Bank = Bank.BANK_ALJAZIRA,
        availableBalance: String? = null,
        debitSource: String? = null,
        sourceAccount: String? = null,
    ) {
        val rawId = "raw-$id"
        world.rawRepo.insertIfAbsent(
            RawSms(rawId, bank.id, "body $id", receivedAt, null, "hash-$id"),
        )
        world.parsedRepo.save(
            ParsedEvent(
                id = id,
                rawSmsId = rawId,
                bank = bank,
                messageFamily = MessageFamily.PURCHASE,
                direction = null,
                amount = Money.of("1.00", Currency.SAR),
                purchaseChannel = null,
                sourceAccountRef = sourceAccount?.let { AccountReference(bank, it) },
                destinationAccountRef = null,
                cardRef = CardReference(bank, last4),
                merchant = null,
                counterparty = null,
                occurredAt = occurredAt,
                bankNetworkType = null,
                confidence = Confidence(0.9),
                parseStatus = ParseStatus.SUCCESS,
            ),
            ParsedEventDetails(
                cardSmsChannel = channel,
                availableBalance = availableBalance?.let { Money.of(it, Currency.SAR) },
                debitSourceAccountLast4 = debitSource,
            ),
        )
    }

    private fun explain(db: SupportSQLiteDatabase, sql: String): String = buildString {
        db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) {
                append(cursor.getString(3))
                append('\n')
            }
        }
    }

    private fun assertExact(
        expected: List<ParsedEventRecord>,
        room: List<ParsedEventRecord>,
        default: List<ParsedEventRecord>,
        label: String = "",
    ) {
        val expectedIds = expected.map { it.event.id }.sorted()
        assertEquals(label, expectedIds, room.map { it.event.id })
        assertEquals(label, expected.sortedBy { it.event.id }, room)
        assertTrue("$label default must be a superset", default.map { it.event.id }.containsAll(expectedIds))
    }

    private fun event(rawSmsId: String) = ParsedEvent(
        id = "evt-$rawSmsId",
        rawSmsId = rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = MessageFamily.PURCHASE,
        direction = null,
        amount = Money.of("1.00", Currency.SAR),
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = null,
        counterparty = null,
        occurredAt = null,
        bankNetworkType = null,
        confidence = Confidence(0.9),
        parseStatus = ParseStatus.SUCCESS,
    )

    /** Exposes only the required members so every fact lookup uses the interface default. */
    private class ListAllOnlyParsedEventRepository(
        private val records: List<ParsedEventRecord>,
    ) : ParsedEventRepository {
        override suspend fun save(event: ParsedEvent, details: com.baraa.masroof.parsing.model.ParsedEventDetails) = Unit
        override suspend fun getById(id: String): ParsedEventRecord? = records.firstOrNull { it.event.id == id }
        override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord? =
            records.firstOrNull { it.event.rawSmsId == rawSmsId }
        override suspend fun deleteByRawSmsId(rawSmsId: String) = Unit
        override suspend fun listAll(): List<ParsedEventRecord> = records
        override suspend fun listReceivedBetween(startInclusive: Instant, endExclusive: Instant) = records
    }
}
