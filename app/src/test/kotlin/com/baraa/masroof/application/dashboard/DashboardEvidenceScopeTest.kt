package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

class DashboardEvidenceScopeTest {
    private val periodEnd = Instant.parse("2026-08-27T00:00:00Z")

    @Test
    fun load_mergesFactsAndLinkedEvidence_sortedByEventId_withOneRawSmsBatchPerStage() = runBlocking {
        val repos = Repos(
            facts = listOf(record("evt-c", "sms-c"), record("evt-a", "sms-a")),
            linked = mapOf("tx-1" to listOf(record("evt-b", "sms-b"), record("evt-a", "sms-a"))),
        )

        val evidence = repos.scope().load(listOf(tx("tx-1", "evt-b", "evt-a")), listOf(card("2210"), card("2210")), periodEnd)

        assertEquals(listOf("evt-a", "evt-b", "evt-c"), evidence.parsedRecords.map { it.event.id })
        assertEquals(setOf("sms-a", "sms-b", "sms-c"), evidence.rawSmsById.keys)
        assertEquals(listOf(listOf("sms-c", "sms-a"), listOf("sms-b")), repos.rawSmsBatches)
        assertEquals(listOf(listOf("2210")), repos.debitFactLast4s)
        assertEquals(listOf(periodEnd), repos.availableBalanceBounds)
    }

    @Test
    fun extend_skipsTransactionsAlreadyCovered() = runBlocking {
        val repos = Repos(facts = listOf(record("evt-a", "sms-a")), linked = emptyMap())
        val scope = repos.scope()
        val evidence = scope.load(emptyList(), emptyList(), periodEnd)

        val extended = scope.extend(evidence, listOf(tx("tx-covered", "evt-a"), tx("tx-unlinked")))

        assertSame(evidence, extended)
        assertTrue(repos.linkLookups.isEmpty())
    }

    @Test
    fun extend_loadsOnlyUncoveredTransactions() = runBlocking {
        val repos = Repos(
            facts = listOf(record("evt-a", "sms-a")),
            linked = mapOf("tx-new" to listOf(record("evt-n", "sms-n"))),
        )
        val scope = repos.scope()
        val evidence = scope.load(emptyList(), emptyList(), periodEnd)

        val extended = scope.extend(evidence, listOf(tx("tx-covered", "evt-a"), tx("tx-new", "evt-n")))

        assertEquals(listOf(listOf("tx-new")), repos.linkLookups)
        assertEquals(listOf("evt-a", "evt-n"), extended.parsedRecords.map { it.event.id })
        assertEquals(setOf("sms-a", "sms-n"), extended.rawSmsById.keys)
    }

    private class Repos(
        private val facts: List<ParsedEventRecord>,
        private val linked: Map<String, List<ParsedEventRecord>>,
    ) {
        val rawSmsBatches = mutableListOf<List<String>>()
        val linkLookups = mutableListOf<List<String>>()
        val debitFactLast4s = mutableListOf<List<String>>()
        val availableBalanceBounds = mutableListOf<Instant>()
        private val byRawSmsId = (facts + linked.values.flatten()).associateBy { it.event.rawSmsId }

        fun scope() = DashboardEvidenceScope(ftRepo(), parsedRepo(), rawRepo())

        private fun ftRepo(): FinancialTransactionRepository = object : FinancialTransactionRepository by unused() {
            override suspend fun listRawSmsIdsForTransactions(transactionIds: Collection<String>): Set<String> {
                linkLookups += transactionIds.toList()
                return transactionIds.flatMap { id -> linked[id].orEmpty().map { it.event.rawSmsId } }.toSet()
            }
        }

        private fun parsedRepo(): ParsedEventRepository = object : ParsedEventRepository by unused() {
            override suspend fun listAll(): List<ParsedEventRecord> = error("whole-history scan")
            override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>) = rawSmsIds.mapNotNull(byRawSmsId::get)
            override suspend fun listCardStatementFacts() = facts
            override suspend fun listLatestCreditCardRowFacts() = emptyList<ParsedEventRecord>()
            override suspend fun listLatestCreditCardAvailableBalanceFacts(beforeExclusive: Instant): List<ParsedEventRecord> {
                availableBalanceBounds += beforeExclusive
                return emptyList()
            }
            override suspend fun listFinancingInstallmentFacts() = emptyList<ParsedEventRecord>()
            override suspend fun listExchangeRateFacts() = emptyList<ParsedEventRecord>()
            override suspend fun listFirstDebitCardFacts(cardLast4s: Collection<String>): List<ParsedEventRecord> {
                debitFactLast4s += cardLast4s.toList()
                return emptyList()
            }
        }

        private fun rawRepo(): RawSmsRepository = object : RawSmsRepository by unused() {
            override suspend fun getById(id: String): RawSms? = error("per-row RawSms lookup")
            override suspend fun getByIds(ids: Collection<String>): List<RawSms> {
                rawSmsBatches += ids.toList()
                return ids.map { RawSms(it, "BankAlJazira", "body $it", periodEndMinusDay, null, "hash-$it") }
            }
        }

        private inline fun <reified T> unused(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                error("unexpected call: ${method.name}")
            } as T
    }

    private fun tx(id: String, vararg linkedEventIds: String) = FinancialTransaction(
        id = id,
        type = FinancialTransactionType.EXPENSE,
        amount = Money.of("1.00", Currency.SAR),
        occurredAt = periodEndMinusDay,
        sourceContainerId = null,
        destinationContainerId = null,
        merchant = null,
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = linkedEventIds.toList(),
    )

    private fun card(last4: String) = CardRegistryEntry.forTest(
        bank = Bank.BANK_ALJAZIRA,
        last4 = last4,
        ownership = OwnershipStatus.OWNED,
    )

    private companion object {
        val periodEndMinusDay: Instant = Instant.parse("2026-08-26T00:00:00Z")

        fun record(eventId: String, rawSmsId: String) = ParsedEventRecord(
            event = ParsedEvent(
                id = eventId,
                rawSmsId = rawSmsId,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.PURCHASE,
                direction = null,
                amount = null,
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
            ),
            details = ParsedEventDetails(),
        )
    }
}
