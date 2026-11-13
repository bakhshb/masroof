package com.baraa.masroof.application.commitment

import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.testsupport.FakeFinancialTransactionRepository
import com.baraa.masroof.testsupport.tx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class CommitmentFromTransactionServiceTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), zone)

    @Test
    fun createFromTransaction_cancellationPropagatesWithoutReportingARejectedWrite() = runBlocking {
        val cancelled = CancellationException("stopped")
        val repository = object : CommitmentRepository by ThrowOnDuplicateCommitmentRepository() {
            override suspend fun create(commitment: Commitment): Unit = throw cancelled
        }
        val tx = tx("cancelled", amount = "71.00", merchant = "Netflix")
        val service = CommitmentFromTransactionService(repository, FakeFinancialTransactionRepository(listOf(tx)), zone, clock)
        try {
            service.createFromTransaction(tx.id)
            fail("Cancellation must not become a create_failed result")
        } catch (caught: CancellationException) {
            assertSame(cancelled, caught)
        }
    }

    @Test
    fun createFromTransaction_insertRaceWithAStoredRow_returnsAlreadyExists() = runBlocking {
        val tx = tx(id = "tx-netflix", amount = "71.00", merchant = "Netflix")
        val stored = ThrowOnDuplicateCommitmentRepository()
        // A concurrent writer stores the row between the pre-check and this insert.
        val racing = object : CommitmentRepository by stored {
            override suspend fun create(commitment: Commitment) {
                stored.create(commitment)
                throw IllegalStateException("duplicate sourceTransactionId")
            }
        }
        val service = CommitmentFromTransactionService(racing, FakeFinancialTransactionRepository(listOf(tx)), zone, clock)

        assertEquals(CommitmentCreationResult.AlreadyExists, service.createFromTransaction(tx.id))
        assertEquals(1, stored.created.size)
    }

    @Test
    fun createFromTransaction_failedInsertWithoutARow_isRejected() = runBlocking {
        val tx = tx(id = "tx-netflix", amount = "71.00", merchant = "Netflix")
        val failing = object : CommitmentRepository by ThrowOnDuplicateCommitmentRepository() {
            override suspend fun create(commitment: Commitment): Unit = throw IllegalStateException("disk full")
        }
        val service = CommitmentFromTransactionService(failing, FakeFinancialTransactionRepository(listOf(tx)), zone, clock)

        assertEquals(CommitmentCreationResult.Rejected("create_failed"), service.createFromTransaction(tx.id))
    }

    @Test
    fun createFromTransaction_defaultsToMonthlyRecurrence() = runBlocking {
        val tx = tx(id = "tx-netflix", amount = "71.00", merchant = "Netflix")
        val repository = ThrowOnDuplicateCommitmentRepository()
        val service = CommitmentFromTransactionService(
            commitmentRepository = repository,
            financialTransactionRepository = FakeFinancialTransactionRepository(listOf(tx)),
            zoneId = zone,
            clock = clock,
        )

        assertEquals(CommitmentCreationResult.Success, service.createFromTransaction(tx.id))
        assertEquals(CommitmentRecurrence.MONTHLY, repository.created.single().recurrence)
        assertEquals(CommitmentCreationResult.AlreadyExists, service.createFromTransaction(tx.id))
    }

    @Test
    fun createFromTransaction_refundIsRejected() = runBlocking {
        val refund = tx(id = "tx-refund", type = FinancialTransactionType.REFUND, amount = "71.00", merchant = "Netflix")
        val service = CommitmentFromTransactionService(
            commitmentRepository = ThrowOnDuplicateCommitmentRepository(),
            financialTransactionRepository = FakeFinancialTransactionRepository(listOf(refund)),
            zoneId = zone,
            clock = clock,
        )

        assertEquals(
            CommitmentCreationResult.Rejected("invalid_transaction"),
            service.createFromTransaction(refund.id),
        )
    }

    @Test
    fun createFromTransaction_aRegularTransferAndABillKnownOnlyByItsBillerBecomeCommitments() = runBlocking {
        val rent = tx(id = "tx-rent", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT, amount = "2500.00", counterparty = "Landlord")
        val bill = tx(id = "tx-bill", type = FinancialTransactionType.BILL_PAYMENT, amount = "175.00")
        val repository = ThrowOnDuplicateCommitmentRepository()
        val service = CommitmentFromTransactionService(repository, FakeFinancialTransactionRepository(listOf(rent, bill)), zone, clock)

        assertEquals(CommitmentCreationResult.Success, service.createFromTransaction(rent.id))
        // Without a name the bill stays rejected; the suggestion passes the biller it was grouped by.
        assertEquals(CommitmentCreationResult.Rejected("invalid_transaction"), service.createFromTransaction(bill.id))
        assertEquals(CommitmentCreationResult.Success, service.createFromTransaction(bill.id, fallbackName = " Water Co "))
        assertEquals(listOf("Landlord", "Water Co"), repository.created.map { it.name })
        // An incoming transfer is never a commitment.
        assertEquals(false, CommitmentFromTransactionService.canMarkAsCommitment(FinancialTransactionType.EXTERNAL_TRANSFER_IN))
    }

    private class ThrowOnDuplicateCommitmentRepository : CommitmentRepository {
        private val bySource = mutableMapOf<String, Commitment>()
        val created: List<Commitment> get() = bySource.values.toList()

        override suspend fun create(commitment: Commitment) {
            if (bySource.containsKey(commitment.sourceTransactionId)) {
                throw IllegalStateException("duplicate sourceTransactionId")
            }
            bySource[commitment.sourceTransactionId] = commitment
        }

        override suspend fun update(commitment: Commitment) = throw UnsupportedOperationException()

        override suspend fun delete(id: String) = throw UnsupportedOperationException()

        override suspend fun get(id: String): Commitment? = bySource.values.find { it.id == id }

        override suspend fun getBySourceTransactionId(sourceTransactionId: String): Commitment? =
            bySource[sourceTransactionId]

        override suspend fun listAll(): List<Commitment> = bySource.values.toList()
    }
}
