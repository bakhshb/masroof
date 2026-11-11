package com.baraa.masroof.application.sms

import com.baraa.masroof.testsupport.TestSmsBodies.PURCHASE_BODY
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
import com.baraa.masroof.testsupport.engineFixtureAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.time.Instant
import com.baraa.masroof.testsupport.NoOpAppLog
import com.baraa.masroof.testsupport.NoOpHistoricalBatchRecoveryScheduler

/** A live SMS that could not be stored must not crash the receiver, and must leave a way back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveCaptureFailureTest {
    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private val processingScheduled = mutableListOf<String>()
    private val recoveryScheduled = mutableListOf<Instant>()
    private val catchUpScheduled = mutableListOf<Instant>()
    private val now = Instant.parse("2026-08-03T14:40:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun adapterThatThrowsWhileRouting_returnsFailedAndStoresNothing() = runBlocking {
        val result = CaptureBankSmsUseCase(rawRepo, BankSmsRegistry(listOf(throwingAdapter())), appLogService = NoOpAppLog).capture(live())

        assertTrue(result is BankSmsCaptureResult.Failed)
        assertEquals(0, db.rawSmsDao().count())
    }

    @Test
    fun routingFailure_schedulesAnInboxReReadThatCoversBothClocks() = runBlocking {
        val raw = live(at = "2026-08-03T14:32:00Z")

        val result = intake(CaptureBankSmsUseCase(rawRepo, BankSmsRegistry(listOf(throwingAdapter())), appLogService = NoOpAppLog)).ingest(raw)

        assertTrue(result is BankSmsCaptureResult.Failed)
        assertEquals(listOf(raw.receivedAt.minus(Duration.ofMinutes(10))), recoveryScheduled)
        assertTrue(processingScheduled.isEmpty())
    }

    @Test
    fun serviceCenterTimeAheadOfDevice_windowStartsBeforeDeviceArrival() = runBlocking {
        val raw = live(at = "2026-08-03T15:30:00Z")

        intake(CaptureBankSmsUseCase(rawRepo, BankSmsRegistry(listOf(throwingAdapter())), appLogService = NoOpAppLog)).ingest(raw)

        assertEquals(listOf(now.minus(Duration.ofMinutes(10))), recoveryScheduled)
    }

    @Test
    fun insertFailure_schedulesAnInboxReRead() = runBlocking {
        val failingInsert = object : RawSmsRepository by rawRepo {
            override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult = throw IOException("disk full")
        }
        val capture = CaptureBankSmsUseCase(failingInsert, BankSmsRegistry(listOf(engineFixtureAdapter())), appLogService = NoOpAppLog)

        val result = intake(capture).ingest(live())

        assertTrue(result is BankSmsCaptureResult.Failed)
        assertEquals(1, recoveryScheduled.size)
        assertEquals(0, db.rawSmsDao().count())
    }

    @Test
    fun retiredDatabaseDuringRestore_schedulesAnInboxReRead() = runBlocking {
        val retired = object : RawSmsRepository by rawRepo {
            override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult = throw DatabaseRestartRequiredException()
        }

        val result = intake(CaptureBankSmsUseCase(retired, BankSmsRegistry(listOf(engineFixtureAdapter())), appLogService = NoOpAppLog)).ingest(live())

        assertTrue(result is BankSmsCaptureResult.Failed)
        assertEquals(1, recoveryScheduled.size)
    }

    @Test
    fun capturedSms_schedulesProcessingAndNoReRead() = runBlocking {
        val raw = live()

        val result = intake(CaptureBankSmsUseCase(rawRepo, BankSmsRegistry(listOf(engineFixtureAdapter())), appLogService = NoOpAppLog)).ingest(raw)

        assertTrue(result is BankSmsCaptureResult.Captured)
        assertEquals(listOf(raw.id), processingScheduled)
        assertTrue(recoveryScheduled.isEmpty())
    }

    @Test
    fun missedSmsCatchUp_reReadsTheInboxFromTheNewestStoredSmsNotAfterNow() = runBlocking {
        val capture = CaptureBankSmsUseCase(rawRepo, BankSmsRegistry(listOf(engineFixtureAdapter())), appLogService = NoOpAppLog)
        intake(capture).scheduleMissedSmsCatchUp()
        assertTrue("nothing stored: no catch-up", catchUpScheduled.isEmpty())
        intake(capture).ingest(live("2026-08-03T18:00:00Z"))
        intake(capture).scheduleMissedSmsCatchUp()
        assertTrue("only a receipt in the future: no anchor", catchUpScheduled.isEmpty())

        intake(capture).ingest(live("2026-08-03T14:00:00Z"))
        intake(capture).ingest(live("2026-08-03T14:32:00Z"))
        // The receipt hours ahead of the device (stored above) must not hide what arrived before it.
        intake(capture).scheduleMissedSmsCatchUp()

        assertEquals(listOf(Instant.parse("2026-08-02T14:32:00Z")), catchUpScheduled)
        assertTrue(recoveryScheduled.isEmpty())
    }

    private fun intake(capture: CaptureBankSmsUseCase) = LiveSmsIntake(
        captureBankSms = capture,
        scheduler = { processingScheduled += it },
        rawSmsRepository = rawRepo,
        reviewRepository = RoomReviewRepository(db.reviewItemDao()),
        processingRetryRepository = RoomProcessingRetryRepository(db.processingRetryDao()),
        appLogService = AppLogService(ApplicationProvider.getApplicationContext()),
        captureRecoveryScheduler = { recoveryScheduled += it },
        clock = InstantClock { now },
        missedSmsScheduler = { catchUpScheduled += it },
        batchRecoveryScheduler = NoOpHistoricalBatchRecoveryScheduler,
    )

    private fun throwingAdapter(): BankSmsAdapter {
        val base = engineFixtureAdapter()
        return object : BankSmsAdapter by base {
            override fun detect(sender: String, body: String) = throw IllegalStateException("adapter bug")
        }
    }

    private fun live(at: String = "2026-08-03T14:32:00Z"): RawSms =
        AndroidSmsMapper.toRawSms(ProviderSmsRecord(null, "AlJazira", PURCHASE_BODY, Instant.parse(at)))
}
