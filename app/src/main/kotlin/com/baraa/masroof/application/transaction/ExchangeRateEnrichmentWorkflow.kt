package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.dashboard.TransactionDisplayEnricher
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExchangeRateEnrichmentResult(
    /** Foreign transactions that had no persisted applied rate. */
    val pending: Int,
    /** Rows this run persisted a rate for; the rest stay pending for the next run. */
    val persisted: Int,
)

/**
 * Sole owner of persisted exchange-rate enrichment (`appliedExchangeRate` / `exchangeRateSource`).
 *
 * Resolves rates for foreign transactions with none persisted, through the same
 * [TransactionSarEquivalentResolver] and evidence rules the dashboard uses (linked SMS rate,
 * historical merchant rate, market rate), and stores them so totals stay stable across
 * reloads. The dashboard projection never writes; until this runs it shows the same
 * resolution in memory.
 *
 * Every rate is resolved before the first write, so a failing resolution writes nothing.
 * Row updates are independent and only target rows still missing a rate; rerunning is safe.
 */
class ExchangeRateEnrichmentWorkflow(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val parsedEventRepository: ParsedEventRepository,
    private val rawSmsRepository: RawSmsRepository,
    private val sarEquivalentResolver: TransactionSarEquivalentResolver,
    private val appLogService: AppLogService? = null,
    private val primaryCurrency: Currency = Currency.SAR,
) {
    private val mutex = Mutex()

    suspend fun enrichPending(): ExchangeRateEnrichmentResult = mutex.withLock {
        val pending = financialTransactionRepository.listAwaitingAppliedExchangeRate(primaryCurrency)
            .filter { it.amount.currency.convertsToSar() }
        if (pending.isEmpty()) return@withLock ExchangeRateEnrichmentResult(pending = 0, persisted = 0)

        val linkedRawSmsIds = financialTransactionRepository.listRawSmsIdsForTransactions(pending.map { it.id })
        val parsedRecords = (
            parsedEventRepository.listByRawSmsIds(linkedRawSmsIds) +
                parsedEventRepository.listExchangeRateFacts()
            )
            .distinctBy { it.event.id }
            .sortedBy { it.event.id }
        val rawSmsById = rawSmsRepository.getByIds(parsedRecords.map { it.event.rawSmsId }.distinct())
            .associateBy { it.id }
        val resolutions = sarEquivalentResolver.resolve(
            transactions = TransactionDisplayEnricher.enrichMerchants(pending, parsedRecords),
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = primaryCurrency,
        )

        var persisted = 0
        for (transaction in pending) {
            val resolution = resolutions[transaction.id] ?: continue
            val updated = financialTransactionRepository.updateAppliedExchangeRate(
                id = transaction.id,
                exchangeRate = resolution.exchangeRate,
                source = resolution.source,
            )
            if (updated) persisted++
        }
        if (persisted > 0) {
            appLogService?.info(
                AppLogCategories.TRANSACTION,
                "Applied exchange rates to $persisted of ${pending.size} foreign transactions",
            )
        }
        ExchangeRateEnrichmentResult(pending = pending.size, persisted = persisted)
    }
}
