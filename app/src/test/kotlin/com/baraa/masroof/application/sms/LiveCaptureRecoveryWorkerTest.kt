package com.baraa.masroof.application.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The inbox re-read after a lost live capture retries until the SMS is stored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveCaptureRecoveryWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val after = Instant.parse("2026-08-03T14:22:00Z")
    private var finished = 0
    private val requeued = mutableListOf<Pair<Instant, Int>>()

    @Test
    fun cleanScan_succeedsFromTheInputBound() = runBlocking {
        val bounds = mutableListOf<Instant>()

        val result = worker(scan = { bounds += it; SmsScanResult(inserted = 1) }).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(after), bounds)
        assertEquals(1, finished)
    }

    @Test
    fun captureThatFailsAgain_retriesThenHandsOverToALaterRequest() = runBlocking {
        val failed: suspend (Instant) -> SmsScanResult = { SmsScanResult(failed = 1) }

        assertEquals(ListenableWorker.Result.retry(), worker(scan = failed).doWork())
        // Success, so a scan appended behind this one still runs; a later request keeps trying.
        assertEquals(
            ListenableWorker.Result.success(),
            worker(scan = failed, attempt = LiveCaptureRecoveryWorker.MAX_ATTEMPTS - 1).doWork(),
        )
        assertEquals(listOf(after to 1), requeued)
    }

    @Test
    fun afterAboutAWeekOfRequests_givesUp() = runBlocking {
        val result = worker(
            scan = { SmsScanResult(failed = 1) },
            attempt = LiveCaptureRecoveryWorker.MAX_ATTEMPTS - 1,
            requeues = LiveCaptureRecoveryWorker.MAX_REQUEUES,
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList<Pair<Instant, Int>>(), requeued)
    }

    @Test
    fun captureThatFailsAgainInAnIncompleteBatch_stillRetries() = runBlocking {
        val result = worker(scan = {
            SmsScanResult(failed = 1, failure = SmsScanFailure.DerivedIncomplete(DerivedProcessingStage.entries.first()))
        }).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun requeueDelay_backsOffToSixHours() {
        assertEquals(listOf(900L, 1800L, 3600L, 7200L, 14400L, 21600L, 21600L),
            (1..7).map(LiveCaptureRecoveryWorker::requeueDelaySeconds))
    }

    @Test
    fun startupNotReady_requeuesAFreshRequestWithoutScanning() = runBlocking {
        var scanned = false

        val result = worker(scan = { scanned = true; SmsScanResult() }, ready = false, attempt = 3).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(after to 1), requeued)
        assertEquals(false, scanned)
    }

    @Test
    fun failuresMapToTheirResults() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker(scan = { SmsScanResult(failure = SmsScanFailure.PermissionDenied) }).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(scan = { SmsScanResult(failure = SmsScanFailure.ProviderError("x")) }).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(scan = { throw DatabaseRestartRequiredException() }).doWork())
        assertEquals(3, finished)
    }

    private fun worker(
        scan: suspend (Instant) -> SmsScanResult,
        ready: Boolean = true,
        attempt: Int = 0,
        requeues: Int = 0,
    ): LiveCaptureRecoveryWorker =
        TestListenableWorkerBuilder<LiveCaptureRecoveryWorker>(context)
            .setInputData(LiveCaptureRecoveryWorker.workRequest(after, requeues = requeues).workSpec.input)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    LiveCaptureRecoveryWorker(appContext, workerParameters, scan, { ready }, onFinished = { finished++ }) { at, waits -> requeued += at to waits }
            })
            .build()
}
