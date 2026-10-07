package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.dao.FinancialTransactionDao
import com.baraa.masroof.data.room.dao.ParsedEventDao
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionRawSmsLinkEntity
import com.baraa.masroof.data.room.entity.ParsedEventEntity
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.testsupport.TransactionReconstructionFixture
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
 * Locks reconstruction results and the batched query shape.
 * A list of N transactions inside one bind chunk costs one transaction query,
 * one link query, and one parsed-event query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionReconstructionFixtureTest {
    private lateinit var db: MasroofDatabase
    private lateinit var countingTransactions: CountingFinancialTransactionDao
    private lateinit var countingParsedEvents: CountingParsedEventDao
    private lateinit var repo: RoomFinancialTransactionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            TransactionReconstructionFixture.seed(
                rawSmsRepository = RoomRawSmsRepository(db.rawSmsDao()),
                parsedEventRepository = RoomParsedEventRepository(db.parsedEventDao()),
                financialTransactionRepository = RoomFinancialTransactionRepository(
                    db.financialTransactionDao(),
                    db.parsedEventDao(),
                ),
            )
        }
        countingTransactions = CountingFinancialTransactionDao(db.financialTransactionDao())
        countingParsedEvents = CountingParsedEventDao(db.parsedEventDao())
        repo = RoomFinancialTransactionRepository(countingTransactions, countingParsedEvents)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun singleManyAndMultiLink_roundTripThroughEveryListRead() = runBlocking {
        val expected = TransactionReconstructionFixture.all.associate { it.expected.id to it.expected }
        val reads = listOf(
            repo.listAll(),
            repo.listByTypes(listOf(FinancialTransactionType.EXPENSE)),
            repo.listByTypesOccurredSince(
                types = listOf(FinancialTransactionType.EXPENSE),
                startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
            ),
            repo.listOccurredBetween(
                startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
                endExclusive = Instant.parse("2026-08-04T00:00:00Z"),
            ),
            repo.listAwaitingAppliedExchangeRate(Currency.SAR),
        )
        val awaitingId = TransactionReconstructionFixture.many[1].expected.id
        assertEquals(listOf(awaitingId), reads.last().map { it.id })
        reads.dropLast(1).forEach { loaded ->
            assertEquals(expected.keys, loaded.map { it.id }.toSet())
            loaded.forEach { actual -> assertEquals(expected.getValue(actual.id), actual) }
        }
        expected.values.forEach { sample ->
            assertEquals(sample, repo.getById(sample.id))
        }
        val multi = TransactionReconstructionFixture.multiLink.expected
        assertEquals(listOf("pe-recon-multi-a", "pe-recon-multi-b"), multi.linkedParsedEventIds)
        assertEquals(multi.linkedParsedEventIds, repo.getById(multi.id)!!.linkedParsedEventIds)
    }

    @Test
    fun listReads_useOneLinkQueryAndOneParsedEventQueryPerChunk() = runBlocking {
        val transactions = TransactionReconstructionFixture.all.size
        val links = TransactionReconstructionFixture.all.sumOf { it.links.size }
        assertOneChunk(transactions, links) { repo.listAll() }
        assertOneChunk(transactions, links) { repo.listByTypes(listOf(FinancialTransactionType.EXPENSE)) }
        assertOneChunk(transactions, links) {
            repo.listByTypesOccurredSince(
                types = listOf(FinancialTransactionType.EXPENSE),
                startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
            )
        }
        assertOneChunk(transactions, links) {
            repo.listOccurredBetween(
                startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
                endExclusive = Instant.parse("2026-08-04T00:00:00Z"),
            )
        }
        assertOneChunk(transactionCount = 1, linkCount = 1) {
            repo.listAwaitingAppliedExchangeRate(Currency.SAR)
        }
        assertOneChunk(transactionCount = 1, linkCount = 2) {
            repo.listOccurredBetween(
                startInclusive = Instant.parse("2026-08-03T00:00:00Z"),
                endExclusive = Instant.parse("2026-08-04T00:00:00Z"),
            )
        }

        countingTransactions.reset()
        countingParsedEvents.reset()
        assertEquals(emptyList<FinancialTransaction>(), repo.listByTypes(emptyList()))
        assertEquals(0, countingTransactions.transactionListQueries)
        assertEquals(0, countingTransactions.linkBatchQueries)
        assertEquals(0, countingParsedEvents.listByRawSmsIdsCalls)
    }

    @Test
    fun listAll_splitsLinkAndParsedEventReadsOnTheBindChunkSize() = runBlocking {
        val chunked = RoomFinancialTransactionRepository(
            dao = countingTransactions,
            parsedEventDao = countingParsedEvents,
            batchChunkSize = 1,
        )
        countingTransactions.reset()
        countingParsedEvents.reset()

        val loaded = chunked.listAll()

        val expected = TransactionReconstructionFixture.all.associate { it.expected.id to it.expected }
        assertEquals(expected.keys, loaded.map { it.id }.toSet())
        loaded.forEach { actual -> assertEquals(expected.getValue(actual.id), actual) }
        val transactions = TransactionReconstructionFixture.all.size
        val links = TransactionReconstructionFixture.all.sumOf { it.links.size }
        assertEquals(1, countingTransactions.transactionListQueries)
        assertEquals(transactions, countingTransactions.linkBatchQueries)
        assertEquals(links, countingParsedEvents.listByRawSmsIdsCalls)
        assertEquals(0, countingTransactions.perTransactionLinkQueries)
        assertEquals(0, countingParsedEvents.findByRawSmsIdCalls)
        assertTrue(countingTransactions.linkIdChunks.all { it.size == 1 })
        assertEquals(expected.keys, countingTransactions.linkIdChunks.flatten().toSet())
        assertTrue(countingParsedEvents.rawSmsIdChunks.all { it.size == 1 })
        assertEquals(
            listOf("pe-recon-multi-a", "pe-recon-multi-b"),
            loaded.single { it.id == TransactionReconstructionFixture.multiLink.expected.id }.linkedParsedEventIds,
        )
    }

    @Test
    fun missingParsedEvent_isOmittedOnBatchAndSingleReads() = runBlocking {
        val rawId = "raw-recon-orphan"
        val occurredAt = Instant.parse("2026-08-04T01:00:00Z")
        RoomRawSmsRepository(db.rawSmsDao()).insertIfAbsent(
            RawSms(
                id = rawId,
                sender = "AlJazira",
                body = "body-$rawId",
                receivedAt = occurredAt,
                deviceMessageId = rawId,
                bodyHash = "hash-$rawId",
            ),
        )
        val transaction = TransactionReconstructionFixture.single.expected.copy(
            id = "tx-recon-orphan",
            occurredAt = occurredAt,
            linkedParsedEventIds = emptyList(),
            appliedExchangeRate = null,
            exchangeRateSource = null,
        )
        RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
            .save(transaction, listOf(rawId))

        val single = repo.getById(transaction.id)
        val batched = repo.listAll().single { it.id == transaction.id }
        assertEquals(transaction, single)
        assertEquals(single, batched)
    }

    private suspend fun assertOneChunk(
        transactionCount: Int,
        linkCount: Int,
        load: suspend () -> List<FinancialTransaction>,
    ) {
        countingTransactions.reset()
        countingParsedEvents.reset()
        val loaded = load()
        assertEquals(transactionCount, loaded.size)
        assertEquals(1, countingTransactions.transactionListQueries)
        assertEquals(1, countingTransactions.linkBatchQueries)
        assertEquals(listOf(transactionCount), countingTransactions.linkIdChunks.map { it.size })
        assertEquals(0, countingTransactions.perTransactionLinkQueries)
        assertEquals(1, countingParsedEvents.listByRawSmsIdsCalls)
        assertEquals(listOf(linkCount), countingParsedEvents.rawSmsIdChunks.map { it.size })
        assertEquals(0, countingParsedEvents.findByRawSmsIdCalls)
    }

    private class CountingFinancialTransactionDao(
        private val delegate: FinancialTransactionDao,
    ) : FinancialTransactionDao by delegate {
        var transactionListQueries: Int = 0
        var linkBatchQueries: Int = 0
        var perTransactionLinkQueries: Int = 0
        val linkIdChunks: MutableList<List<String>> = mutableListOf()

        fun reset() {
            transactionListQueries = 0
            linkBatchQueries = 0
            perTransactionLinkQueries = 0
            linkIdChunks.clear()
        }

        override suspend fun listAll(): List<FinancialTransactionEntity> = countList { delegate.listAll() }

        override suspend fun listByTypes(types: List<String>): List<FinancialTransactionEntity> =
            countList { delegate.listByTypes(types) }

        override suspend fun listByTypesOccurredSince(
            types: List<String>,
            startInclusiveEpochMillis: Long,
        ): List<FinancialTransactionEntity> =
            countList { delegate.listByTypesOccurredSince(types, startInclusiveEpochMillis) }

        override suspend fun listAwaitingAppliedExchangeRate(
            primaryCurrency: String,
        ): List<FinancialTransactionEntity> =
            countList { delegate.listAwaitingAppliedExchangeRate(primaryCurrency) }

        override suspend fun listOccurredBetween(
            startInclusiveEpochMillis: Long,
            endExclusiveEpochMillis: Long,
        ): List<FinancialTransactionEntity> =
            countList { delegate.listOccurredBetween(startInclusiveEpochMillis, endExclusiveEpochMillis) }

        override suspend fun listLinksForTransactions(
            transactionIds: List<String>,
        ): List<FinancialTransactionRawSmsLinkEntity> {
            linkBatchQueries += 1
            linkIdChunks += transactionIds
            return delegate.listLinksForTransactions(transactionIds)
        }

        override suspend fun listRawSmsIdsForTransaction(transactionId: String): List<String> {
            perTransactionLinkQueries += 1
            return delegate.listRawSmsIdsForTransaction(transactionId)
        }

        private suspend fun countList(
            block: suspend () -> List<FinancialTransactionEntity>,
        ): List<FinancialTransactionEntity> {
            transactionListQueries += 1
            return block()
        }
    }

    private class CountingParsedEventDao(
        private val delegate: ParsedEventDao,
    ) : ParsedEventDao by delegate {
        var listByRawSmsIdsCalls: Int = 0
        var findByRawSmsIdCalls: Int = 0
        val rawSmsIdChunks: MutableList<List<String>> = mutableListOf()

        fun reset() {
            listByRawSmsIdsCalls = 0
            findByRawSmsIdCalls = 0
            rawSmsIdChunks.clear()
        }

        override suspend fun listByRawSmsIds(rawSmsIds: List<String>): List<ParsedEventEntity> {
            listByRawSmsIdsCalls += 1
            rawSmsIdChunks += rawSmsIds
            return delegate.listByRawSmsIds(rawSmsIds)
        }

        override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventEntity? {
            findByRawSmsIdCalls += 1
            return delegate.findByRawSmsId(rawSmsId)
        }
    }
}
