package com.baraa.masroof.application.transaction

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

/** Pairing wins over posting a leg alone, including when a pair save loses a race. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferPairPersistenceTest : ReconciliationTestFixture() {
    private val source = AccountReference(Bank.BANK_ALJAZIRA, "3001")
    private val destination = AccountReference(Bank.BANK_ALJAZIRA, "3003")

    @Test
    fun failedPairSave_postsNeitherLegAlone() = runBlocking {
        persistLegs()
        val rejectingPairs = object : FinancialTransactionRepository by ftRepo {
            override suspend fun save(transaction: FinancialTransaction, rawSmsIds: Collection<String>) =
                if (rawSmsIds.size == 2) FinancialTransactionSaveResult.Conflict(rawSmsIds.first(), "guard")
                else ftRepo.save(transaction, rawSmsIds)
        }

        val report = service(rejectingPairs).reconcileAffectedRawSmsIds(listOf(OUT, IN))

        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(1, report.summary.failed)
        assertEquals(setOf(OUT, IN), report.failedRawSmsIds)
    }

    @Test
    fun pairSaveThatLosesToAPeerSingleLeg_isHealedInTheSamePassAndNotFailed() = runBlocking {
        persistLegs()
        val peerPostsFirst = object : FinancialTransactionRepository by ftRepo {
            var raced = false
            override suspend fun save(transaction: FinancialTransaction, rawSmsIds: Collection<String>): FinancialTransactionSaveResult {
                if (rawSmsIds.size == 2 && !raced) {
                    raced = true
                    ftRepo.save(externalOut(), listOf(OUT))
                }
                return ftRepo.save(transaction, rawSmsIds)
            }
        }

        val report = service(peerPostsFirst).reconcileAffectedRawSmsIds(listOf(IN))

        assertEquals(0, report.summary.failed)
        assertTrue(report.failedRawSmsIds.isEmpty())
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf(OUT, IN), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun bothLegsReconciledAtOnce_endAsOneSelfTransferWithoutFailures() = runBlocking {
        repeat(20) { round ->
            db.clearAllTables()
            persistLegs()
            val reports = listOf(OUT, IN).map { id ->
                async(Dispatchers.IO) { service(ftRepo).reconcileAffectedRawSmsIds(listOf(id)) }
            }.awaitAll()

            val tx = ftRepo.listAll().single()
            assertEquals("round $round", FinancialTransactionType.SELF_TRANSFER, tx.type)
            assertEquals("round $round", setOf(OUT, IN), ftRepo.listRawSmsIds(tx.id).toSet())
            assertEquals("round $round", 0, reports.sumOf { it.summary.failed })
        }
    }

    private suspend fun persistLegs() {
        confirmation.confirmAccountOwned(source)
        confirmation.confirmAccountOwned(destination)
        val occurredAt = LocalDateTime.parse("2026-08-10T12:00:00")
        persistEvent(
            smsId = OUT,
            at = Instant.parse("2026-08-10T09:00:00Z"),
            details = ParsedEventDetails(occurredAtLocal = occurredAt, transactionReference = "REF-RACE"),
            event = event(
                id = "pe-out", rawSmsId = OUT, family = MessageFamily.TRANSFER_OUT, amount = money("700.00"),
                source = source, destination = destination, network = BankNetworkType.INTER_BANK,
            ),
        )
        persistEvent(
            smsId = IN,
            at = Instant.parse("2026-08-10T09:01:00Z"),
            details = ParsedEventDetails(occurredAtLocal = occurredAt.plusMinutes(1), transactionReference = "REF-RACE"),
            event = event(
                id = "pe-in", rawSmsId = IN, family = MessageFamily.TRANSFER_IN, amount = money("700.00"),
                source = source, destination = destination, network = BankNetworkType.INTER_BANK,
            ),
        )
    }

    private fun externalOut() = FinancialTransaction(
        id = TransactionIdFactory.fromRawSmsIds(listOf(OUT)),
        type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
        amount = money("700.00"),
        occurredAt = Instant.parse("2026-08-10T09:00:00Z"),
        sourceContainerId = FinancialContainerIdFactory.accountId(source),
        destinationContainerId = null,
        merchant = null,
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = listOf("pe-out"),
    )

    private fun service(ledger: FinancialTransactionRepository) = TransactionReconciliationService(
        parsedEventRepository = parsedRepo,
        rawSmsRepository = rawRepo,
        financialTransactionRepository = ledger,
        ownershipResolver = OwnershipResolver(accounts, cards, loans),
        ownershipConfirmationService = confirmation,
    )

    private companion object {
        const val OUT = "sms-race-out"
        const val IN = "sms-race-in"
    }
}
