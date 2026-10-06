package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.RawSmsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessingRetryRepositoryTest {
    @Test
    fun markRequired_rollsBackWhenAnyRowInTheBatchCannotBeSaved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.rawSmsDao().insertIfAbsent(sms("sms-a", 1))
            db.rawSmsDao().insertIfAbsent(sms("sms-b", 2))
            val repo = RoomProcessingRetryRepository(db.processingRetryDao())
            val createdAt = Instant.parse("2026-08-11T12:00:00Z")

            val failure = runCatching {
                repo.markRequired(listOf("sms-a", "missing-sms", "sms-b"), createdAt)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(repo.listRetryableRawSmsIds().isEmpty())

            repo.markRequired(listOf("sms-a", "sms-b"), createdAt)
            assertEquals(listOf("sms-a", "sms-b"), repo.listRetryableRawSmsIds())
        } finally {
            db.close()
        }
    }

    private fun sms(id: String, receivedAtEpochMillis: Long) = RawSmsEntity(
        id = id,
        sender = "AlJazira",
        body = id,
        receivedAtEpochMillis = receivedAtEpochMillis,
        deviceMessageId = id,
        bodyHash = id,
        dedupeKey = id,
    )
}
