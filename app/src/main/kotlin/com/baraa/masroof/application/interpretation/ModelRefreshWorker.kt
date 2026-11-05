package com.baraa.masroof.application.interpretation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.application.transaction.ReconciliationIncompleteException
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import kotlinx.coroutines.CancellationException

/** Refresh unresolved interpretations in one parse batch, then one durable derived pass. */
class ModelRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as MasroofApplication).container
        if (container.awaitStartupMaintenance() != StartupMaintenanceOutcome.READY) {
            Result.retry()
        } else container.databaseAccessGate.withAccess {
            // Keep this container alive across all stages, including the separate learning database.
            val service = container.interpretationService
            service?.synchronizeFeedback()
            val expected = inputData.getString("interpretationVersion")
            val versionMatches = service != null && if (expected != null) expected == service.refreshVersion
                else inputData.getString("modelVersion") == service.modelVersion
            val complete = if (service != null && versionMatches) ModelRefreshWorkflow(
                reviews = container.reviewRepository,
                parsed = container.parsedEventRepository,
                raw = container.rawSmsRepository,
                retries = container.processingRetryRepository,
                processor = container.processStoredSmsUseCase,
                mayRefresh = service::mayRefresh,
                needsRefresh = service.refreshFilter(readHistory = false),
                inTransaction = { container.withDatabaseTransaction(it) },
                recoverDerived = { container.historicalDerivedRecovery.recoverPending() },
                clock = container.clock,
                extra = { service.revokedMemories() },
            ).refresh() else {
                // Committed derived work survives model changes or unavailable assistance.
                container.historicalDerivedRecovery.recoverPending()
                true
            }
            container.maintenanceCompletionSignal.notifyCompleted()
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
        (applicationContext as MasroofApplication).container.appLogService.warn(
            AppLogCategories.INGEST,
            "ModelRefreshWorker stage=${incomplete?.stage ?: "refresh"} incomplete$details " +
                "attempt=${runAttemptCount + 1} retry_state=retained (${e.javaClass.simpleName})",
        )
        Result.retry()
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
