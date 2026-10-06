package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TransactionSmsEvidenceLoaderTest {
    @Test
    fun loadForTransaction_returnsLinkedBodiesInOrder() = runBlocking {
        val loader = TransactionSmsEvidenceLoader(
            financialTransactionRepository = FakeFinancialTransactionRepository(
                links = mapOf("tx-1" to listOf("sms-a", "sms-b")),
            ),
            rawSmsRepository = FakeRawSmsRepository(
                mapOf(
                    "sms-a" to raw("sms-a", "body-a"),
                    "sms-b" to raw("sms-b", "body-b"),
                ),
            ),
        )

        val evidence = loader.loadForTransaction("tx-1")

        assertEquals(2, evidence.size)
        assertEquals("body-a", evidence[0].body)
        assertEquals("AlJazira", evidence[0].sender)
        assertEquals("body-b", evidence[1].body)
    }

    @Test
    fun loadForTransaction_skipsMissingRawRows() = runBlocking {
        val loader = TransactionSmsEvidenceLoader(
            financialTransactionRepository = FakeFinancialTransactionRepository(
                links = mapOf("tx-1" to listOf("sms-a", "sms-missing")),
            ),
            rawSmsRepository = FakeRawSmsRepository(
                mapOf("sms-a" to raw("sms-a", "body-a")),
            ),
        )

        val evidence = loader.loadForTransaction("tx-1")

        assertEquals(1, evidence.size)
        assertEquals("body-a", evidence.single().body)
    }

    @Test
    fun loadForTransaction_emptyWhenNoLinks() = runBlocking {
        val loader = TransactionSmsEvidenceLoader(
            financialTransactionRepository = FakeFinancialTransactionRepository(),
            rawSmsRepository = FakeRawSmsRepository(emptyMap()),
        )

        assertTrue(loader.loadForTransaction("tx-1").isEmpty())
    }

    private fun raw(id: String, body: String) = RawSms(
        id = id,
        sender = "AlJazira",
        body = body,
        receivedAt = Instant.parse("2026-08-17T15:23:00Z"),
        deviceMessageId = id,
        bodyHash = "hash-$id",
    )

    private class FakeFinancialTransactionRepository(
        private val links: Map<String, List<String>> = emptyMap(),
    ) : FinancialTransactionRepository {
        override suspend fun save(
            transaction: com.baraa.masroof.domain.model.FinancialTransaction,
            rawSmsIds: Collection<String>,
        ) = com.baraa.masroof.domain.repository.FinancialTransactionSaveResult.Saved

        override suspend fun getById(id: String) = null
        override suspend fun findByRawSmsId(rawSmsId: String) = null
        override suspend fun listAll() = emptyList<com.baraa.masroof.domain.model.FinancialTransaction>()
        override suspend fun listOccurredBetween(
            startInclusive: Instant,
            endExclusive: Instant,
        ) = emptyList<com.baraa.masroof.domain.model.FinancialTransaction>()

        override suspend fun isRawSmsLinked(rawSmsId: String) = false
        override suspend fun listRawSmsIds(transactionId: String) = links[transactionId].orEmpty()
        override suspend fun update(transaction: com.baraa.masroof.domain.model.FinancialTransaction) = false
        override suspend fun updateAppliedExchangeRate(
            id: String,
            exchangeRate: java.math.BigDecimal,
            source: com.baraa.masroof.domain.model.ExchangeRateSource,
        ) = false
        override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String) = false
        override suspend fun unlinkRawSms(rawSmsId: String) = false

        override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String) = false
    }

    private class FakeRawSmsRepository(
        private val rows: Map<String, RawSms>,
    ) : RawSmsRepository {
        override suspend fun insertIfAbsent(rawSms: RawSms) = RawSmsInsertResult.Inserted
        override suspend fun getById(id: String) = rows[id]
        override suspend fun existsById(id: String) = rows.containsKey(id)
        override suspend fun findByDeviceMessageId(deviceMessageId: String) = null
        override suspend fun listIdsByReceivedAt(): List<String> = rows.keys.toList()
        override suspend fun findCrossSourceNearDuplicate(
            sender: String,
            bodyHash: String,
            fromInclusive: Instant,
            toInclusive: Instant,
            lookingForLiveRow: Boolean,
        ) = null
    }
}
