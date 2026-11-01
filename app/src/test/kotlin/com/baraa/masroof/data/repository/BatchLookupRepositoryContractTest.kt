package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

/** Batch lookups return exactly what the per-id lookups they replace return, across chunk boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchLookupRepositoryContractTest {
    private lateinit var db: MasroofDatabase
    private lateinit var raw: RoomRawSmsRepository
    private lateinit var ledger: RoomFinancialTransactionRepository
    private lateinit var corrections: RoomUserCorrectionRepository
    private val now = Instant.parse("2026-08-03T10:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        raw = RoomRawSmsRepository(db.rawSmsDao())
        ledger = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao(), batchChunkSize = 2)
        corrections = RoomUserCorrectionRepository(db.userCorrectionDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun getByIdsAndLinkedRawSmsIds_matchPerIdLookups() = runBlocking {
        (1..5).forEach { raw.insertIfAbsent(rawSms("sms-$it")) }
        (1..3).forEach { ledger.save(transaction("tx-$it"), listOf("sms-$it")) }
        val perId = object : FinancialTransactionRepository by ledger {
            override suspend fun getByIds(ids: Collection<String>) = ids.distinct().mapNotNull { getById(it) }
            override suspend fun listLinkedRawSmsIds(rawSmsIds: Collection<String>) =
                rawSmsIds.filterTo(linkedSetOf()) { isRawSmsLinked(it) }
        }
        val ids = listOf("tx-3", "tx-1", "missing", "tx-2", "tx-1")
        val rawIds = (1..5).map { "sms-$it" } + "sms-missing"

        assertEquals(perId.getByIds(ids).sortedBy { it.id }, ledger.getByIds(ids).sortedBy { it.id })
        assertEquals(setOf("sms-1", "sms-2", "sms-3"), ledger.listLinkedRawSmsIds(rawIds))
        assertEquals(perId.listLinkedRawSmsIds(rawIds), ledger.listLinkedRawSmsIds(rawIds))
    }

    @Test
    fun correctionListAll_equalsCorrectionsForEveryStoredRawSms() = runBlocking {
        (1..3).forEach { raw.insertIfAbsent(rawSms("sms-$it")) }
        listOf("sms-2" to 2L, "sms-1" to 5L, "sms-2" to 1L).forEachIndexed { index, (target, minutes) ->
            corrections.save(
                UserCorrection("c-$index", target, null, null, "merchant-$index", null, now.plusSeconds(minutes * 60)),
            )
        }

        assertEquals(corrections.listForRawSmsIds(raw.listIdsByReceivedAt()), corrections.listAll())
    }

    private fun rawSms(id: String) = RawSms(id, "AlJazira", "body-$id", now, null, "hash-$id")

    private fun transaction(id: String) = FinancialTransaction(
        id = id,
        type = FinancialTransactionType.EXPENSE,
        amount = Money.of(BigDecimal("10.00"), Currency.SAR),
        occurredAt = now,
        sourceContainerId = null,
        destinationContainerId = null,
        merchant = null,
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = emptyList(),
    )
}
