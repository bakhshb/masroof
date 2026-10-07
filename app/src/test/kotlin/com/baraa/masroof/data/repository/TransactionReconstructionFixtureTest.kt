package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.dao.FinancialTransactionDao
import com.baraa.masroof.data.room.dao.ParsedEventDao
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.data.room.entity.ParsedEventEntity
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.testsupport.TransactionReconstructionFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Locks today's reconstruction results and the per-transaction query shape.
 * A later batch loader must keep the domain objects and can lower the counts.
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
    fun listAll_usesOneLinkQueryAndOneParsedEventLookupPerLink() = runBlocking {
        countingTransactions.reset()
        countingParsedEvents.reset()

        repo.listAll()

        val transactions = TransactionReconstructionFixture.all.size
        val links = TransactionReconstructionFixture.all.sumOf { it.links.size }
        assertEquals(1, countingTransactions.listAllCalls)
        assertEquals(transactions, countingTransactions.linkQueries)
        assertEquals(links, countingParsedEvents.findByRawSmsIdCalls)
    }

    private class CountingFinancialTransactionDao(
        private val delegate: FinancialTransactionDao,
    ) : FinancialTransactionDao by delegate {
        var listAllCalls: Int = 0
        var linkQueries: Int = 0

        fun reset() {
            listAllCalls = 0
            linkQueries = 0
        }

        override suspend fun listAll(): List<FinancialTransactionEntity> {
            listAllCalls += 1
            return delegate.listAll()
        }

        override suspend fun listRawSmsIdsForTransaction(transactionId: String): List<String> {
            linkQueries += 1
            return delegate.listRawSmsIdsForTransaction(transactionId)
        }
    }

    private class CountingParsedEventDao(
        private val delegate: ParsedEventDao,
    ) : ParsedEventDao by delegate {
        var findByRawSmsIdCalls: Int = 0

        fun reset() {
            findByRawSmsIdCalls = 0
        }

        override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventEntity? {
            findByRawSmsIdCalls += 1
            return delegate.findByRawSmsId(rawSmsId)
        }
    }
}
