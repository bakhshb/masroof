package com.baraa.masroof.application

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.work.WorkManager
import com.baraa.masroof.application.analytics.AnalyticsNotifications
import com.baraa.masroof.application.analytics.AnalyticsWorkflow
import com.baraa.masroof.application.container.BackupContainer
import com.baraa.masroof.application.container.DashboardContainer
import com.baraa.masroof.application.container.LearningContainer
import com.baraa.masroof.application.container.MaintenanceContainer
import com.baraa.masroof.application.container.PatternsContainer
import com.baraa.masroof.application.container.ReviewContainer
import com.baraa.masroof.application.container.SmsContainer
import com.baraa.masroof.application.container.StorageContainer
import com.baraa.masroof.application.container.UpdateContainer
import com.baraa.masroof.application.sms.HistoricalBatchRecoveryScheduler
import com.baraa.masroof.application.sms.HistoricalDerivedRecoveryWorker
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.data.room.PreservingOpenHelperFactory
import com.baraa.masroof.sms.datasource.SmsDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Manual composition root; no DI framework. It builds one feature container per area, in dependency order,
 * and each object exists once: a container receives the ones it needs and never makes a second copy.
 * Callers use the feature container that owns what they need. Does not use fallbackToDestructiveMigration.
 *
 * Build order, with restore recovery first because Room must not open until it has chosen a valid file:
 * [StorageContainer] (recovery, database with its pattern store, repositories) -> [MaintenanceContainer] ->
 * [UpdateContainer] -> [LearningContainer] -> [ReviewContainer] -> [DashboardContainer] -> [SmsContainer] ->
 * [PatternsContainer] -> [BackupContainer] -> the worker factory. Anything that would form a cycle is read
 * lazily through a lambda.
 */
class AppContainer(
    context: Context,
    bankAdapterOverride: BankSmsAdapter? = null,
    smsDataSourceOverride: SmsDataSource? = null,
    restartAfterRestore: (() -> Unit)? = null,
    databaseOpenHelperFactory: SupportSQLiteOpenHelper.Factory = PreservingOpenHelperFactory(),
) {
    val storage: StorageContainer = StorageContainer(context, installBundledPatterns = bankAdapterOverride == null, databaseOpenHelperFactory)

    @Volatile internal var shutdownStage: String = "OPEN"
        private set

    private val historicalRecoveryScheduler = HistoricalBatchRecoveryScheduler {
        HistoricalDerivedRecoveryWorker.enqueue(WorkManager.getInstance(storage.appContext))
    }

    val maintenance: MaintenanceContainer = MaintenanceContainer(
        storage,
        openFinancialStore = storage::openDatabase,
        runPostStartupBackgroundWork = { runPostStartupBackgroundWork() },
        isOpen = { shutdownStage == "OPEN" },
    )

    val update: UpdateContainer = UpdateContainer(storage)

    val learning: LearningContainer = LearningContainer(
        storage,
        enabled = bankAdapterOverride == null,
        onFeedbackChanged = { maintenance.completionSignal.notifyCompleted(); if (analyticsDelegate.isInitialized()) analytics.request() },
    )

    val review: ReviewContainer = ReviewContainer(storage, learning, historicalRecoveryScheduler)

    val dashboard: DashboardContainer = DashboardContainer(storage, maintenance, review, update.httpClient)

    val sms: SmsContainer = SmsContainer(
        storage, maintenance, learning, review, dashboard,
        bankAdapterOverride, smsDataSourceOverride, historicalRecoveryScheduler,
    )

    val patterns: PatternsContainer = PatternsContainer(storage, learning, review, sms, maintenance)

    val backup: BackupContainer = BackupContainer(
        storage,
        restartAfterRestore,
        beforeValidatedInstall = {
            stopAnalytics()
            learning.interpretationService?.invalidateForRestore()
            AnalyticsNotifications.clear(storage.appContext)
        },
    )

    private val analyticsDelegate = lazy { AnalyticsWorkflow(this, storage.appContext) }
    val analytics: AnalyticsWorkflow get() = analyticsDelegate.value
    fun stopAnalytics() { if (analyticsDelegate.isInitialized()) analytics.close() }
    internal val analyticsDiagnostics: String get() = if (analyticsDelegate.isInitialized()) analytics.diagnostics else "none"

    private suspend fun runPostStartupBackgroundWork() {
        learning.interpretationService?.startLearning(storage.database.invalidationTracker)
        analytics.start()
        // Links first, so pending live SMS are processed with them.
        patterns.messagePatternsWorkflow.linkWalletBuiltIns()
        sms.liveSmsIntake.schedulePendingProcessing()
        dashboard.enrichExchangeRatesBestEffort()
    }

    @Synchronized fun close() {
        if (shutdownStage == "CLOSED") return
        shutdownStage = "STARTUP_DRAINING"
        maintenance.drainStartup()
        shutdownStage = "ANALYTICS_DRAINING"
        stopAnalytics()
        shutdownStage = "LEARNING_DRAINING"
        learning.interpretationService?.close()
        shutdownStage = "APPLICATION_DRAINING"
        runBlocking(Dispatchers.IO) {
            storage.applicationJob.complete()
            storage.applicationJob.join()
        }
        // WorkManager jobs outlive applicationScope; finish admitted work before closing their stores.
        runBlocking(Dispatchers.IO) {
            storage.databaseAccessGate.retireAndDrain()
        }
        shutdownStage = "DATABASE_CLOSING"
        storage.database.close()
        maintenance.block()
        shutdownStage = "CLOSED"
    }
}
