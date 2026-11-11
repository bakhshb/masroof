package com.baraa.masroof.application.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import java.io.IOException

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
    private val onboardingCompleted: () -> Boolean,
    private val coordinator: () -> UpdateCheckCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!onboardingCompleted()) {
            return Result.success()
        }

        return coordinator().checkForUpdate("periodic").fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                if (error is IOException) Result.retry() else Result.success()
            },
        )
    }

    /** Creates [UpdateCheckWorker]; returns null for any other worker so WorkManager falls back to its default factory. */
    class Factory(
        private val onboardingCompleted: () -> Boolean,
        private val coordinator: () -> UpdateCheckCoordinator,
    ) : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            if (workerClassName == UpdateCheckWorker::class.java.name) {
                UpdateCheckWorker(appContext, workerParameters, onboardingCompleted, coordinator)
            } else {
                null
            }
    }
}
