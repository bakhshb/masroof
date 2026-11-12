package com.baraa.masroof.application.container

import android.content.Context
import com.baraa.masroof.application.commitment.CommitmentFromTransactionService
import com.baraa.masroof.application.dashboard.ChartDataWorkflow
import com.baraa.masroof.application.dashboard.DashboardCommitmentsWorkflow
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardPeriodWorkflow
import com.baraa.masroof.application.dashboard.DashboardRegistryWorkflow
import com.baraa.masroof.application.dashboard.DashboardService
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.notification.NotificationCenterMetricsWorkflow
import com.baraa.masroof.application.notification.NotificationCenterService
import com.baraa.masroof.application.notification.NotificationPreferencesRepository
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.HistoricalMerchantRateCorrectionWorkflow
import com.baraa.masroof.data.exchange.FrankfurterForeignSarRateProvider
import com.baraa.masroof.data.preferences.SharedPrefsChartDataPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsDashboardLayoutPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsNotificationPreferencesRepository
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.engine.bank.patterns.PatternAccountRole
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Dashboard reads, notification and layout preferences, registries, commitments and the exchange-rate
 * writers. Dashboard loads never call the network; market rates are written by enrichment.
 */
class DashboardContainer(
    private val storage: StorageContainer,
    private val maintenance: MaintenanceContainer,
    review: ReviewContainer,
    updateHttpClient: OkHttpClient,
) {
    private val appContext = storage.appContext

    val dashboardLayoutPreferencesRepository: DashboardLayoutPreferencesRepository =
        SharedPrefsDashboardLayoutPreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsDashboardLayoutPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val chartDataWorkflow = ChartDataWorkflow(
        SharedPrefsChartDataPreferencesRepository(
            appContext.getSharedPreferences(SharedPrefsChartDataPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE)))

    val notificationPreferencesRepository: NotificationPreferencesRepository =
        SharedPrefsNotificationPreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsNotificationPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val notificationCenterService: NotificationCenterService =
        NotificationCenterService(
            preferencesRepository = notificationPreferencesRepository,
        )

    private suspend fun patternAccountBindings() =
        storage.databaseAccessGate.withAccess { storage.messagePatternStore.load() }.banks.flatMap { bank -> bank.currentPatterns }
            .flatMap { it.factContract?.accountBindings.orEmpty() }

    val settingsRegistryWorkflow: SettingsRegistryWorkflow =
        SettingsRegistryWorkflow(
            cardRegistryRepository = storage.cardRegistryRepository,
            accountRegistryRepository = storage.accountRegistryRepository,
            loanRegistryRepository = storage.loanRegistryRepository,
            ownershipConfirmationService = review.ownershipConfirmationService,
            parsedEvents = storage.parsedEventRepository,
            inTransaction = { block -> storage.withDatabaseTransaction(block) },
            transactions = storage.financialTransactionRepository,
            patternBoundAccounts = {
                patternAccountBindings().filter { it.role != PatternAccountRole.CARD }
                    .map { AccountReference(Bank(it.bank.value), it.last4) }.toSet()
            },
            patternBoundCards = {
                patternAccountBindings().filter { it.role == PatternAccountRole.CARD }
                    .map { CardReference(Bank(it.bank.value), it.last4) }.toSet()
            },
        )

    val settingsCommitmentsWorkflow: SettingsCommitmentsWorkflow =
        SettingsCommitmentsWorkflow(
            commitmentRepository = storage.commitmentRepository,
            clock = storage.clock,
        )

    val dashboardPeriodWorkflow: DashboardPeriodWorkflow =
        DashboardPeriodWorkflow(clock = storage.clock)

    val dashboardRegistryWorkflow: DashboardRegistryWorkflow =
        DashboardRegistryWorkflow(
            cardRegistryRepository = storage.cardRegistryRepository,
            accountRegistryRepository = storage.accountRegistryRepository,
        )

    val notificationCenterMetricsWorkflow: NotificationCenterMetricsWorkflow =
        NotificationCenterMetricsWorkflow(
            reviewRepository = storage.reviewRepository,
            cardRegistryRepository = storage.cardRegistryRepository,
            accountRegistryRepository = storage.accountRegistryRepository,
        )

    val commitmentFromTransactionService: CommitmentFromTransactionService =
        CommitmentFromTransactionService(
            commitmentRepository = storage.commitmentRepository,
            financialTransactionRepository = storage.financialTransactionRepository,
        )

    val dashboardCommitmentsWorkflow: DashboardCommitmentsWorkflow =
        DashboardCommitmentsWorkflow(
            commitmentFromTransactionService = commitmentFromTransactionService,
            commitmentRepository = storage.commitmentRepository,
        )

    private val sarEquivalentResolver: TransactionSarEquivalentResolver =
        TransactionSarEquivalentResolver(
            marketRateProvider = FrankfurterForeignSarRateProvider(
                // The dashboard waits on this; fail fast instead of the update client's long timeouts.
                updateHttpClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build(),
            ),
        )

    /** Writer of missing applied exchange rates; dashboard loads stay read-only. */
    val exchangeRateEnrichmentWorkflow: ExchangeRateEnrichmentWorkflow =
        ExchangeRateEnrichmentWorkflow(
            financialTransactionRepository = storage.financialTransactionRepository,
            parsedEventRepository = storage.parsedEventRepository,
            rawSmsRepository = storage.rawSmsRepository,
            sarEquivalentResolver = sarEquivalentResolver,
            appLogService = storage.appLogService,
        )

    /** Confirmed replacement of a frozen historical-merchant rate. Does not run during enrichment. */
    val historicalMerchantRateCorrectionWorkflow: HistoricalMerchantRateCorrectionWorkflow =
        HistoricalMerchantRateCorrectionWorkflow(
            financialTransactionRepository = storage.financialTransactionRepository,
            parsedEventRepository = storage.parsedEventRepository,
            rawSmsRepository = storage.rawSmsRepository,
            sarEquivalentResolver = sarEquivalentResolver,
            appLogService = storage.appLogService,
        )

    val dashboardService: DashboardService =
        DashboardService(
            financialTransactionRepository = storage.financialTransactionRepository,
            reviewRepository = storage.reviewRepository,
            parsedEventRepository = storage.parsedEventRepository,
            rawSmsRepository = storage.rawSmsRepository,
            appLocaleRepository = storage.appLocaleRepository,
            accountRegistryRepository = storage.accountRegistryRepository,
            cardRegistryRepository = storage.cardRegistryRepository,
            loanRegistryRepository = storage.loanRegistryRepository,
            commitmentRepository = storage.commitmentRepository,
            // Dashboard loads never call the network; market rates are written by enrichment.
            sarEquivalentResolver = TransactionSarEquivalentResolver(ForeignSarMarketRateProvider { _, _ -> null }),
        )

    suspend fun enrichExchangeRatesBestEffort() {
        try {
            if (exchangeRateEnrichmentWorkflow.enrichPending().persisted > 0) maintenance.completionSignal.notifyCompleted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storage.appLogService.warn(AppLogCategories.TRANSACTION, "Exchange-rate enrichment failed: ${e.javaClass.simpleName}")
        }
    }
}
