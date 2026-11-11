package com.baraa.masroof.application.dashboard

import com.baraa.masroof.testsupport.EmptyRawSmsRepository
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.testsupport.FakeFinancialTransactionRepository
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
            provenance = { null },
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
            provenance = { null },
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
            provenance = { null },
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

    private class FakeRawSmsRepository(
        private val rows: Map<String, RawSms>,
    ) : EmptyRawSmsRepository() {
        override suspend fun getById(id: String) = rows[id]
        override suspend fun listIdsByReceivedAt(): List<String> = rows.keys.toList()
    }
}
