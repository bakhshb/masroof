package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.dashboard.SarEquivalentResolution
import com.baraa.masroof.application.dashboard.TransactionDisplayEnricher
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import java.math.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HistoricalMerchantRateCandidate(
    val transactionId: String,
    val oldRate: BigDecimal,
    val oldSource: ExchangeRateSource,
    val correctedRate: BigDecimal?,
    val correctedSource: ExchangeRateSource?,
) {
    /** Dated resolution is missing, so the frozen pair cannot be replaced safely. */
    val requiresManualFollowUp: Boolean
        get() = correctedRate == null || correctedSource == null
}

data class HistoricalMerchantRateCorrectionReport(
    val candidates: List<HistoricalMerchantRateCandidate>,
)

data class HistoricalMerchantRateCorrectionResult(
    val updated: List<String>,
    val manualFollowUp: List<String>,
    val skipped: List<String>,
)

/**
 * Audited replacement of a frozen historical-merchant exchange-rate pair.
 *
 * [listCandidates] is read-only. A row is a candidate when its stored source is
 * [ExchangeRateSource.HISTORICAL_MERCHANT], both halves are present, and the dated
 * resolution — ignoring that frozen pair — is a different rate, a different source,
 * or unavailable. [confirm] is the only method that writes, and only for the ids
 * the caller passes. An id that is not a current candidate is left unchanged.
 *
 * A dated rate is written as one pair through
 * [FinancialTransactionRepository.replaceConfirmedHistoricalMerchantRate].
 * Unavailable has no clear-pair operation that keeps the immutable-pair rule, so
 * those rows stay stored and are reported as [HistoricalMerchantRateCorrectionResult.manualFollowUp].
 *
 * Logs name the phase, a masked transaction id, and the old and new rate and source.
 * They do not include SMS body, account numbers, or secrets.
 */
class HistoricalMerchantRateCorrectionWorkflow(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val parsedEventRepository: ParsedEventRepository,
    private val rawSmsRepository: RawSmsRepository,
    private val sarEquivalentResolver: TransactionSarEquivalentResolver,
    private val appLogService: AppLogService? = null,
    private val primaryCurrency: Currency = Currency.SAR,
) {
    private val mutex = Mutex()

    suspend fun listCandidates(): HistoricalMerchantRateCorrectionReport = mutex.withLock {
        HistoricalMerchantRateCorrectionReport(findCandidates())
    }

    suspend fun confirm(transactionIds: Collection<String>): HistoricalMerchantRateCorrectionResult = mutex.withLock {
        val requested = transactionIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (requested.isEmpty()) {
            return@withLock HistoricalMerchantRateCorrectionResult(emptyList(), emptyList(), emptyList())
        }
        val candidates = findCandidates().filter { it.transactionId in requested }
        val candidateIds = candidates.map { it.transactionId }.toSet()
        val updated = mutableListOf<String>()
        val manualFollowUp = mutableListOf<String>()
        val skipped = requested.filter { it !in candidateIds }.toMutableList()
        for (candidate in candidates) {
            if (candidate.requiresManualFollowUp) {
                manualFollowUp += candidate.transactionId
                logCorrection(phase = "before", candidate = candidate, applied = null)
                logCorrection(phase = "after", candidate = candidate, applied = false)
                continue
            }
            val rate = candidate.correctedRate ?: continue
            val source = candidate.correctedSource ?: continue
            logCorrection(phase = "before", candidate = candidate, applied = null)
            val wrote = financialTransactionRepository.replaceConfirmedHistoricalMerchantRate(
                id = candidate.transactionId,
                exchangeRate = rate,
                source = source,
            )
            if (wrote) {
                updated += candidate.transactionId
            } else {
                skipped += candidate.transactionId
            }
            logCorrection(phase = "after", candidate = candidate, applied = wrote)
        }
        HistoricalMerchantRateCorrectionResult(
            updated = updated,
            manualFollowUp = manualFollowUp,
            skipped = skipped,
        )
    }

    private suspend fun findCandidates(): List<HistoricalMerchantRateCandidate> {
        val stored = financialTransactionRepository.listAll().filter { tx ->
            tx.amount.currency != primaryCurrency &&
                tx.amount.currency.convertsToSar() &&
                tx.appliedExchangeRate != null &&
                tx.exchangeRateSource == ExchangeRateSource.HISTORICAL_MERCHANT
        }
        if (stored.isEmpty()) return emptyList()
        val resolutions = resolveIgnoringPersistedPairs(stored)
        return stored.mapNotNull { tx ->
            val oldRate = tx.appliedExchangeRate ?: return@mapNotNull null
            val oldSource = tx.exchangeRateSource ?: return@mapNotNull null
            val resolution = resolutions[tx.id]
            val unchanged = resolution != null &&
                resolution.exchangeRate.compareTo(oldRate) == 0 &&
                resolution.source == oldSource
            if (unchanged) return@mapNotNull null
            HistoricalMerchantRateCandidate(
                transactionId = tx.id,
                oldRate = oldRate,
                oldSource = oldSource,
                correctedRate = resolution?.exchangeRate,
                correctedSource = resolution?.source,
            )
        }.sortedBy { it.transactionId }
    }

    private suspend fun resolveIgnoringPersistedPairs(
        transactions: List<FinancialTransaction>,
    ): Map<String, SarEquivalentResolution> {
        val stripped = transactions.map { it.copy(appliedExchangeRate = null, exchangeRateSource = null) }
        val linkedRawSmsIds = financialTransactionRepository.listRawSmsIdsForTransactions(stripped.map { it.id })
        val parsedRecords = (
            parsedEventRepository.listByRawSmsIds(linkedRawSmsIds) +
                parsedEventRepository.listExchangeRateFacts()
            )
            .distinctBy { it.event.id }
            .sortedBy { it.event.id }
        val rawSmsById = rawSmsRepository.getByIds(parsedRecords.map { it.event.rawSmsId }.distinct())
            .associateBy { it.id }
        return sarEquivalentResolver.resolve(
            transactions = TransactionDisplayEnricher.enrichMerchants(stripped, parsedRecords),
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = primaryCurrency,
        )
    }

    private fun logCorrection(
        phase: String,
        candidate: HistoricalMerchantRateCandidate,
        applied: Boolean?,
    ) {
        val newRate = candidate.correctedRate?.toPlainString() ?: "unavailable"
        val newSource = candidate.correctedSource?.name ?: "unavailable"
        val outcome = when (applied) {
            null -> ""
            true -> " applied=true"
            false -> " applied=false"
        }
        appLogService?.info(
            AppLogCategories.TRANSACTION,
            "Historical merchant rate correction $phase " +
                "id=${AppLogFormatting.maskId(candidate.transactionId)} " +
                "old_rate=${candidate.oldRate.toPlainString()} new_rate=$newRate " +
                "old_source=${candidate.oldSource.name} new_source=$newSource$outcome",
        )
    }
}
