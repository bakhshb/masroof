package com.baraa.masroof.application.container

import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkerFactory
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.analytics.AnalyticsWorker
import com.baraa.masroof.application.interpretation.ModelRefreshWorker
import com.baraa.masroof.application.sms.ExchangeRateEnrichmentWorker
import com.baraa.masroof.application.sms.HistoricalDerivedRecoveryWorker
import com.baraa.masroof.application.sms.LiveCaptureRecoveryWorker
import com.baraa.masroof.application.sms.LiveSmsProcessingWorker
import com.baraa.masroof.application.sms.PendingExchangeRateEnricher
import com.baraa.masroof.application.update.UpdateCheckWorker
import com.baraa.masroof.data.room.DatabaseRestartRequiredException

/**
 * The one place WorkManager gets worker dependencies: all seven workers are created here, never by
 * reaching for the application. Dependencies are read when a worker is created, so a lazy one stays lazy.
 * An unknown class falls through to WorkManager's default reflective factory.
 *
 * [current] is the application's container when the worker runs. The model, update and analytics workers
 * always looked it up at run time, and device tests replace the application's container after WorkManager
 * has taken this factory; the other four stay bound to [app], as they were.
 */
internal fun appWorkerFactory(app: AppContainer, current: () -> AppContainer = { app }): WorkerFactory =
    DelegatingWorkerFactory().apply {
        addFactory(
            LiveSmsProcessingWorker.Factory(
                processStoredSms = { app.sms.processStoredSmsUseCase },
                appLogService = app.storage.appLogService,
                startupReady = app.maintenance::startupFinancialReady,
                isRestartRequired = { it is DatabaseRestartRequiredException },
            ) { app.maintenance.completionSignal.notifyCompleted() },
        )
        addFactory(
            ExchangeRateEnrichmentWorker.Factory {
                PendingExchangeRateEnricher {
                    // A thrown error makes the worker retry once integrity repair has succeeded.
                    check(app.maintenance.startupFinancialReady()) { "Startup maintenance is not ready" }
                    if (app.dashboard.exchangeRateEnrichmentWorkflow.enrichPending().persisted > 0) app.maintenance.completionSignal.notifyCompleted()
                }
            },
        )
        addFactory(
            HistoricalDerivedRecoveryWorker.Factory(
                recovery = { app.sms.historicalDerivedRecovery },
                appLogService = app.storage.appLogService,
                startupReady = app.maintenance::startupFinancialReady,
                onFinished = { app.maintenance.completionSignal.notifyCompleted() },
            ),
        )
        addFactory(
            LiveCaptureRecoveryWorker.Factory(
                scanner = { app.sms.historicalSmsScanner },
                startupReady = app.maintenance::startupFinancialReady,
                appLogService = app.storage.appLogService,
            ) { app.maintenance.completionSignal.notifyCompleted() },
        )
        addFactory(ModelRefreshWorker.Factory(current))
        addFactory(
            UpdateCheckWorker.Factory(
                onboardingCompleted = { current().storage.onboardingPreferencesRepository.isOnboardingCompleted() },
                coordinator = { current().update.updateCheckCoordinator },
            ),
        )
        addFactory(
            AnalyticsWorker.Factory(
                awaitStartup = { current().maintenance.awaitStartupMaintenance() },
                analytics = { current().analytics },
            ),
        )
    }
