package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.testsupport.tx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinancialTransactionPeriodQueryTest {
    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var repo: RoomFinancialTransactionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        repo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun listOccurredBetween_includesStartExcludesEndAndOrdersNewestFirst() = runBlocking {
        val start = Instant.parse("2026-07-27T00:00:00+03:00")
        val end = Instant.parse("2026-08-27T00:00:00+03:00")
        save(tx("before", occurredAt = start.minusSeconds(1)))
        save(tx("start", occurredAt = start))
        save(tx("mid-b", occurredAt = start.plusSeconds(20)))
        save(tx("mid-a", occurredAt = start.plusSeconds(10)))
        save(tx("end", occurredAt = end))
        save(tx("after", occurredAt = end.plusSeconds(1)))

        val result = repo.listOccurredBetween(start, end)
        assertEquals(listOf("mid-b", "mid-a", "start"), result.map { it.id })
    }

    private suspend fun save(tx: FinancialTransaction) {
        val rawId = "raw-${tx.id}"
        rawRepo.insertIfAbsent(
            RawSms(
                id = rawId,
                sender = "ALJAZIRA",
                body = "body-${tx.id}",
                receivedAt = tx.occurredAt,
                deviceMessageId = null,
                bodyHash = "hash-${tx.id}",
            ),
        )
        repo.save(tx, listOf(rawId))
    }
}
