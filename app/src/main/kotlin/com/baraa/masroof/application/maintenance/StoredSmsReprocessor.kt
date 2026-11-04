package com.baraa.masroof.application.maintenance

import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.RawSmsRepository

/**
 * Bulk parser refresh driven by stored RawSms evidence (not by existing
 * ParsedEvent rows), so messages that previously ended Unsupported / Invalid /
 * failed are retried after parser improvements.
 *
 * Each row goes through [ProcessRawSmsUseCase.reparseAndStore], which replaces the
 * ParsedEvent keyed by rawSmsId without duplicating RawSms and deliberately skips
 * per-message derived processing. [refreshDerivedState] then runs ownership discovery,
 * reconciliation, review refresh, and enrichment once for the whole backlog.
 * Each reparsed row commits a HISTORICAL_BATCH retry marker with its ParsedEvent, so
 * a run stopped before the derived pass is finished by startup recovery. The markers
 * are cleared only after [refreshDerivedState] returns; it must throw when its
 * reconciliation report has failed > 0 or the review update fails.
 * Re-running is idempotent.
 */
class StoredSmsReprocessor(
    private val rawSmsRepository: RawSmsRepository,
    private val processRawSms: ProcessRawSmsUseCase,
    private val refreshDerivedState: suspend () -> Unit,
    private val processingRetryRepository: ProcessingRetryRepository,
    private val appLogService: AppLogService? = null,
) {
    suspend fun reprocessAll(): ReparseAllStoredEventsResult {
        appLogService?.info(AppLogCategories.PARSE, "Reparse started")
        var refreshedCount = 0
        var failedCount = 0
        val reparsed = mutableListOf<String>()
        for (rawSmsId in rawSmsRepository.listIdsByReceivedAt()) {
            val raw = rawSmsRepository.getById(rawSmsId) ?: continue
            when (processRawSms.reparseAndStore(raw, historicalBatch = true)) {
                is SmsIngestionResult.Duplicate,
                is SmsIngestionResult.NotRelevant,
                -> reparsed += raw.id
                // A failed reparse did not refresh its evidence, so its retry marker stays for recovery.
                is SmsIngestionResult.Failed -> failedCount++
                else -> {
                    reparsed += raw.id
                    refreshedCount++
                }
            }
        }
        refreshDerivedState()
        // The full pass covered every successfully reparsed row; a reparsed LIVE marker became HISTORICAL_BATCH and is cleared here.
        val historical = processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).toSet()
        processingRetryRepository.clear(reparsed.filter { it in historical })
        appLogService?.info(
            AppLogCategories.PARSE,
            "Reparse finished: $refreshedCount refreshed, $failedCount failed",
        )
        return ReparseAllStoredEventsResult(
            refreshedCount = refreshedCount,
            failedCount = failedCount,
        )
    }
}
