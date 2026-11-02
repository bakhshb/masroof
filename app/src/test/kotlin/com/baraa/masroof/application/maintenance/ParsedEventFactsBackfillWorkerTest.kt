package com.baraa.masroof.application.maintenance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.sms.hash.SmsBodyHasher
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParsedEventFactsBackfillWorkerTest {
    @Test fun alreadyQueuedRetiredWorkFinishesWithoutBackfilling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).build()
        val body = "Purchase\nAmount:10 SAR"
        try {
            RoomRawSmsRepository(database.rawSmsDao()).insertIfAbsent(
                RawSms("android-sms:1", "AlJazira", body, Instant.parse("2026-08-03T10:00:00Z"), "1", SmsBodyHasher.sha256Hex(body)),
            )
        } finally { database.close() }
        val directory = context.getDatabasePath(MasroofDatabase.NAME).parentFile!!
        fun snapshot() = directory.listFiles().orEmpty().filter(File::isFile).associate { it.name to it.readBytes().toList() }
        try {
            val before = snapshot()
            assertTrue(MasroofDatabase.NAME in before)
            val worker = TestListenableWorkerBuilder<ParsedEventFactsBackfillWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(before, snapshot())
        } finally { context.deleteDatabase(MasroofDatabase.NAME) }
    }
}
