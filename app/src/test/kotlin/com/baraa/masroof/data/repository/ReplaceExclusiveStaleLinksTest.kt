package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.sms.hash.SmsBodyHasher
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReplaceExclusiveStaleLinksTest {

    private lateinit var db: MasroofDatabase
    private lateinit var repo: RoomFinancialTransactionRepository
    private lateinit var rawRepo: RoomRawSmsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
    }

    private suspend fun seedRawSms(id: String, body: String) {
        rawRepo.insertIfAbsent(
            RawSms(
                id = id,
                sender = "AlJazira",
                body = body,
                receivedAt = Instant.parse("2026-08-27T04:36:00Z"),
                deviceMessageId = id,
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun replaceExclusiveStaleLinks_swapsStaleExternalPairForSelfTransfer() = runBlocking {
        seedRawSms("sms-out", "out")
        seedRawSms("sms-in", "in")
        val amount = Money.of("5500.00", Currency.SAR)
        val sourceId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val destId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002")

        repo.save(
            FinancialTransaction(
                id = "tx-stale-out",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                amount = amount,
                occurredAt = Instant.parse("2026-08-27T04:36:00Z"),
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                merchant = null,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = emptyList(),
            ),
            listOf("sms-out"),
        )
        repo.save(
            FinancialTransaction(
                id = "tx-stale-in",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                amount = amount,
                occurredAt = Instant.parse("2026-08-27T04:36:00Z"),
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                merchant = null,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = emptyList(),
            ),
            listOf("sms-in"),
        )

        val result = repo.replaceExclusiveStaleLinks(
            transaction = FinancialTransaction(
                id = "tx-self",
                type = FinancialTransactionType.SELF_TRANSFER,
                amount = amount,
                occurredAt = Instant.parse("2026-08-27T04:36:00Z"),
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                merchant = null,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = emptyList(),
            ),
            rawSmsIds = listOf("sms-out", "sms-in"),
            staleRawSmsIds = listOf("sms-out", "sms-in"),
        )

        assertEquals(FinancialTransactionSaveResult.Saved, result)
        assertEquals(1, repo.listAll().size)
        val tx = repo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-out", "sms-in"), repo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun replaceExclusiveStaleLinks_conflict_keepsStaleRows() = runBlocking {
        seedRawSms("sms-out", "out")
        seedRawSms("sms-other", "other")
        val amount = Money.of("5500.00", Currency.SAR)
        val sourceId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val destId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002")

        repo.save(
            FinancialTransaction(
                id = "tx-stale-out",
                type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                amount = amount,
                occurredAt = Instant.parse("2026-08-27T04:36:00Z"),
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                merchant = null,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = emptyList(),
            ),
            listOf("sms-out", "sms-other"),
        )

        val result = repo.replaceExclusiveStaleLinks(
            transaction = FinancialTransaction(
                id = "tx-self",
                type = FinancialTransactionType.SELF_TRANSFER,
                amount = amount,
                occurredAt = Instant.parse("2026-08-27T04:36:00Z"),
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                merchant = null,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = emptyList(),
            ),
            rawSmsIds = listOf("sms-out"),
            staleRawSmsIds = listOf("sms-out"),
        )

        assertTrue(result is FinancialTransactionSaveResult.Conflict)
        assertEquals(1, repo.listAll().size)
        assertEquals("tx-stale-out", repo.listAll().single().id)
        assertEquals(
            setOf("sms-out", "sms-other"),
            repo.listRawSmsIds("tx-stale-out").toSet(),
        )
    }
}
