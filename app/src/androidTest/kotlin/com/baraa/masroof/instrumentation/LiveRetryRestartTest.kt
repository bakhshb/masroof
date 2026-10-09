package com.baraa.masroof.instrumentation

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.sms.LiveSmsIntake
import com.baraa.masroof.application.sms.LiveSmsProcessingWorker
import com.baraa.masroof.application.sms.WorkManagerLiveSmsWorkScheduler
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device proof of M17: a nonthrowing reconciliation failure stays retryable across a
 * worker restart, and the replay posts the movement once.
 *
 * The failing attempts run the real [LiveSmsProcessingWorker] against the app database.
 * Restart is the production startup call [LiveSmsIntake.schedulePendingProcessing],
 * which enqueues a new worker. That worker is created by the application WorkManager
 * factory, not the failed in-memory instance.
 */
@RunWith(AndroidJUnit4::class)
class LiveRetryRestartTest {
    @Test(timeout = 120_000)
    fun failedFinancialProcessing_survivesWorkerRestart_andReplaysOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MasroofApplication
        val container = app.container
        val conflicts = AtomicInteger(Int.MAX_VALUE)
        val ledger = object : FinancialTransactionRepository by container.financialTransactionRepository {
            override suspend fun save(
                transaction: FinancialTransaction,
                rawSmsIds: Collection<String>,
            ): FinancialTransactionSaveResult {
                if (conflicts.getAndDecrement() > 0) {
                    return FinancialTransactionSaveResult.Conflict(
                        rawSmsId = rawSmsIds.firstOrNull().orEmpty(),
                        existingTransactionId = "device-conflict",
                    )
                }
                return container.financialTransactionRepository.save(transaction, rawSmsIds)
            }
        }
        val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))
        container.cardRegistryRepository.setOwnership(
            CardReference(Bank.BANK_ALJAZIRA, "7271"),
            OwnershipStatus.OWNED,
        )
        val raw = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(
                providerMessageId = null,
                sender = "AlJazira",
                body = PURCHASE_BODY,
                receivedAt = Instant.parse("2026-08-03T14:32:00Z"),
            ),
        )
        val captured = CaptureBankSmsUseCase(container.rawSmsRepository, registry).capture(raw)
        assertTrue(captured is com.baraa.masroof.application.ingestion.BankSmsCaptureResult.Captured)
        val failing = processStored(container, registry, ledger)
        val exhausted = worker(
            context,
            raw.id,
            attempt = LiveSmsProcessingWorker.MAX_ATTEMPTS - 1,
            processStored = failing,
        ).doWork()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), exhausted)
        assertNull(container.financialTransactionRepository.findByRawSmsId(raw.id))
        assertEquals(
            listOf(raw.id),
            container.processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.LIVE),
        )

        val restarted = LiveSmsIntake(
            captureBankSms = CaptureBankSmsUseCase(container.rawSmsRepository, registry),
            scheduler = WorkManagerLiveSmsWorkScheduler { WorkManager.getInstance(context) },
            rawSmsRepository = container.rawSmsRepository,
            reviewRepository = container.reviewRepository,
            processingRetryRepository = container.processingRetryRepository,
            appLogService = container.appLogService,
        )
        assertEquals(1, restarted.schedulePendingProcessing())
        val replay = awaitFinishedWork(
            WorkManager.getInstance(context),
            WorkManagerLiveSmsWorkScheduler.uniqueWorkName(raw.id),
        )
        assertEquals(WorkInfo.State.SUCCEEDED, replay.state)

        val posted = container.financialTransactionRepository.findByRawSmsId(raw.id)
        assertNotNull(posted)
        assertEquals(listOf(raw.id), container.financialTransactionRepository.listRawSmsIds(posted!!.id))
        assertTrue(container.processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())
        val linked = container.financialTransactionRepository.listAll().count { transaction ->
            container.financialTransactionRepository.listRawSmsIds(transaction.id).contains(raw.id)
        }
        assertEquals(1, linked)
    }

    private fun processStored(
        container: com.baraa.masroof.application.AppContainer,
        registry: BankSmsRegistry,
        ledger: FinancialTransactionRepository,
    ): ProcessStoredSmsUseCase =
        ProcessStoredSmsUseCase(
            rawSmsRepository = container.rawSmsRepository,
            parsedEventRepository = container.parsedEventRepository,
            bankSmsRegistry = registry,
            ownershipDiscovery = container.ownershipDiscoveryService,
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = container.parsedEventRepository,
                rawSmsRepository = container.rawSmsRepository,
                financialTransactionRepository = ledger,
                ownershipResolver = container.ownershipResolver,
                effectiveParsedEventProvider = container.effectiveParsedEventProvider,
                reviewRepository = container.reviewRepository,
            ),
            reviewQueueUpdater = ReviewQueueUpdater(
                container.reviewRepository,
                container.financialTransactionRepository,
                container.clock,
            ),
            ingestionReviewService = container.ingestionReviewService,
            appLogService = container.appLogService,
            processingRecovery = container.processingRecovery,
            reviewRepository = container.reviewRepository,
        )

    private fun worker(
        context: android.content.Context,
        rawSmsId: String,
        attempt: Int,
        processStored: ProcessStoredSmsUseCase,
    ): LiveSmsProcessingWorker =
        TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(LiveSmsProcessingWorker.Factory({ processStored }, null))
            .build()

    private fun awaitFinishedWork(workManager: WorkManager, uniqueName: String): WorkInfo {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(60)
        var latest: WorkInfo? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get(5, TimeUnit.SECONDS)
            latest = infos.maxByOrNull { it.runAttemptCount }
            if (latest != null && latest.state.isFinished) return latest
            SystemClock.sleep(250)
        }
        error("Work $uniqueName did not finish. Last state=${latest?.state}")
    }

    private companion object {
        val PURCHASE_BODY = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
        """.trimIndent()
    }
}
