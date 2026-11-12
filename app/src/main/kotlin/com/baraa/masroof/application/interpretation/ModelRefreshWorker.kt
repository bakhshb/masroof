package com.baraa.masroof.application.interpretation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.application.transaction.ReconciliationIncompleteException
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import kotlinx.coroutines.CancellationException

/** Refresh unresolved interpretations in one parse batch, then one durable derived pass. */
class ModelRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val appContainer: () -> AppContainer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = appContainer()
        if (container.maintenance.awaitStartupMaintenance() != StartupMaintenanceOutcome.READY) {
            Result.retry()
        } else container.storage.databaseAccessGate.withAccess {
            // Keep this container alive across all stages, including the separate learning database.
            val service = container.learning.interpretationService
            service?.synchronizeFeedback()
            val expected = inputData.getString("interpretationVersion")
            val versionMatches = service != null && if (expected != null) expected == service.refreshVersion
                else inputData.getString("modelVersion") == service.modelVersion
            val complete = if (service != null && versionMatches) ModelRefreshWorkflow(
                reviews = container.storage.reviewRepository,
                parsed = container.storage.parsedEventRepository,
                raw = container.storage.rawSmsRepository,
                retries = container.storage.processingRetryRepository,
                processor = container.sms.processStoredSmsUseCase,
                mayRefresh = service::mayRefresh,
                needsRefresh = service.refreshFilter(readHistory = false),
                inTransaction = { container.storage.withDatabaseTransaction(it) },
                recoverDerived = { container.sms.historicalDerivedRecovery.recoverPending() },
                clock = container.storage.clock,
                extra = { service.revokedMemories() },
            ).refresh() else {
                // Committed derived work survives model changes or unavailable assistance.
                container.sms.historicalDerivedRecovery.recoverPending()
                true
            }
            container.maintenance.completionSignal.notifyCompleted()
            if (complete) Result.success() else Result.retry()
        }
    } catch (_: DatabaseRestartRequiredException) {
        // Rejected admission to an old container is retryable; genuine coroutine cancellation is not swallowed.
        Result.retry()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val incomplete = e as? ReconciliationIncompleteException
        val details = incomplete?.let { " failures=${it.failureCount} id=${it.maskedRawSmsId}" }.orEmpty()
        appContainer().storage.appLogService.warn(
            AppLogCategories.INGEST,
            "ModelRefreshWorker stage=${incomplete?.stage ?: "refresh"} incomplete$details " +
                "attempt=${runAttemptCount + 1} retry_state=retained (${e.javaClass.simpleName})",
        )
        Result.retry()
    }

    /** Creates [ModelRefreshWorker]; returns null for any other worker so WorkManager falls back to its default factory. */
    class Factory(private val appContainer: () -> AppContainer) : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            if (workerClassName == ModelRefreshWorker::class.java.name) ModelRefreshWorker(appContext, workerParameters, appContainer) else null
    }

    companion object {
        const val TAG = "masroof:model-refresh"
        fun enqueue(context: Context, version: String) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "$TAG:$version",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ModelRefreshWorker>()
                    .setInputData(workDataOf("interpretationVersion" to version))
                    .addTag(TAG)
                    .build(),
            )
        }
    }
}
