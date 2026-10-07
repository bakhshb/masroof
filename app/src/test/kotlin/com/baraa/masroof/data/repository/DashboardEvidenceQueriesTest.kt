package com.baraa.masroof.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.CardSmsChannel
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
    fun latestCreditCardRowFacts_areNewestRowPerCard() = runBlocking<Unit> {
        val expected = all
            .filter { it.details.isCreditCardSms() && it.event.cardRef?.last4 != null }
            .groupBy { it.event.cardRef }
            .map { (_, rows) -> rows.maxBy { it.event.id } }
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
    fun firstDebitCardFacts_areFirstDebitAndFirstSourceAccountRowPerCard() = runBlocking<Unit> {
        val last4s = listOf("2210", "8219", "6666", "7271", "0000")
        val wanted = all.filter { it.event.cardRef?.last4 in last4s }
            .groupBy { it.event.bank to it.event.cardRef?.last4 }
        val expected = wanted.values.flatMap { rows ->
            listOfNotNull(
                rows.filter { it.details.isDebitCardSms() }.minByOrNull { it.event.id },
                rows.filter { it.details.debitSourceAccountLast4 != null || it.event.sourceAccountRef != null }
                    .minByOrNull { it.event.id },
            )
        }.distinctBy { it.event.id }
        assertTrue(expected.any { it.event.cardRef?.last4 == "6666" && it.details.cardSmsChannel == CardSmsChannel.CREDIT })
        assertExact(expected, world.parsedRepo.listFirstDebitCardFacts(last4s), defaults.listFirstDebitCardFacts(last4s))
        assertEquals(emptyList<ParsedEventRecord>(), world.parsedRepo.listFirstDebitCardFacts(emptyList()))
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

    private fun expectedLatestAvailable(beforeExclusive: Instant): List<ParsedEventRecord> {
        fun at(record: ParsedEventRecord) = record.event.occurredAt ?: receivedAt.getValue(record.event.rawSmsId)
        return all
            .filter {
                it.details.cardSmsChannel == CardSmsChannel.CREDIT &&
                    it.details.availableBalance != null &&
                    it.event.cardRef?.last4 != null &&
                    at(it).isBefore(beforeExclusive)
            }
            .groupBy { it.event.cardRef }
            .values
            .flatMap { rows ->
                val latest = rows.maxOf { at(it) }
                rows.filter { at(it) == latest }
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
