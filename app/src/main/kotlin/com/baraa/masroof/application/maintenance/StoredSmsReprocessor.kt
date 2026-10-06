package com.baraa.masroof.application.maintenance

import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.domain.repository.RawSmsRepository

/**
 * Bulk parser refresh driven by stored RawSms evidence (not by existing
 * ParsedEvent rows), so messages that previously ended Unsupported / Invalid /
 * failed are retried after parser improvements.
 *
 * Each row goes through [ProcessRawSmsUseCase.reparseStored], which replaces the
 * ParsedEvent keyed by rawSmsId without duplicating RawSms. [refreshDerivedState]
 * then re-runs ownership discovery, reconciliation, and review refresh once for
 * the whole backlog. Re-running is idempotent.
 */
class StoredSmsReprocessor(
    private val rawSmsRepository: RawSmsRepository,
    private val processRawSms: ProcessRawSmsUseCase,
    private val refreshDerivedState: suspend () -> Unit,
    private val appLogService: AppLogService? = null,
) {
    suspend fun reprocessAll(): ReparseAllStoredEventsResult {
        appLogService?.info(AppLogCategories.PARSE, "Reparse started")
        var refreshedCount = 0
        var failedCount = 0
        for (rawSmsId in rawSmsRepository.listIdsByReceivedAt()) {
            val raw = rawSmsRepository.getById(rawSmsId) ?: continue
            when (processRawSms.reparseStored(raw)) {
                is SmsIngestionResult.Duplicate,
                is SmsIngestionResult.NotRelevant,
                -> Unit
                is SmsIngestionResult.Failed -> failedCount++
                else -> refreshedCount++
            }
        }
        refreshDerivedState()
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
