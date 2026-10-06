package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.sms.hash.SmsBodyHasher
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomRawSmsRepositoryTest {

    private lateinit var db: MasroofDatabase
    private lateinit var repo: RoomRawSmsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomRawSmsRepository(db.rawSmsDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun listIdsByReceivedAt_returnsEveryRowOldestFirst() = runBlocking {
        repo.insertIfAbsent(raw("sms-c", "2026-08-03T10:00:00Z"))
        repo.insertIfAbsent(raw("sms-a", "2026-08-01T10:00:00Z"))
        repo.insertIfAbsent(raw("sms-b", "2026-08-02T10:00:00Z"))

        assertEquals(listOf("sms-a", "sms-b", "sms-c"), repo.listIdsByReceivedAt())
    }

    @Test
    fun listIdsByReceivedAt_isEmptyWithoutEvidence() = runBlocking {
        assertTrue(repo.listIdsByReceivedAt().isEmpty())
    }

    @Test
    fun duplicateInsert_doesNotAddAnotherId() = runBlocking {
        val row = raw("sms-a", "2026-08-01T10:00:00Z")
        assertEquals(RawSmsInsertResult.Inserted, repo.insertIfAbsent(row))
        assertEquals(RawSmsInsertResult.AlreadyExists, repo.insertIfAbsent(row))
        assertEquals(listOf("sms-a"), repo.listIdsByReceivedAt())
    }

    private fun raw(id: String, at: String): RawSms {
        val body = "body-$id"
        return RawSms(
            id = id,
            sender = "AlJazira",
            body = body,
            receivedAt = Instant.parse(at),
            deviceMessageId = id,
            bodyHash = SmsBodyHasher.sha256Hex(body),
        )
    }
}
