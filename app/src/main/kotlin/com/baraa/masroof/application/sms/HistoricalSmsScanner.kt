package com.baraa.masroof.application.sms

import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogLevel
import com.baraa.masroof.application.logging.AppLog
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.sms.datasource.SmsPermissionException
import com.baraa.masroof.sms.datasource.SmsProviderException
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.domain.model.Bank
import java.time.Instant
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Operational summary of a historical inbox scan (not financial domain state).
 */
data class SmsScanResult(
    val scanned: Int = 0,
    val inserted: Int = 0,
    val duplicates: Int = 0,
    val parsed: Int = 0,
    val reviewRequired: Int = 0,
    val nonFinancial: Int = 0,
    val unsupported: Int = 0,
    val notRelevant: Int = 0,
    val skippedMalformed: Int = 0,
    val failed: Int = 0,
    /** Distinct inbox sender addresses seen during scan (for diagnostics). */
    val distinctSenders: List<String> = emptyList(),
    val failure: SmsScanFailure? = null,
)

sealed interface SmsScanFailure {
    data object PermissionDenied : SmsScanFailure
    data class DerivedIncomplete(val stage: DerivedProcessingStage) : SmsScanFailure

    data class ProviderError(
        val message: String,
    ) : SmsScanFailure
}

/**
 * Historical inbox scan → [HistoricalSmsBatchProcessor].
 *
 * Captures and parses rows oldest → newest, then runs one derived pass (ownership
 * discovery, reconciliation, review refresh) for the batch. Catches permission/provider
 * failures from both sequence creation and lazy iteration, preserving partial
 * counters when failure occurs mid-scan; evidence already stored by a failed scan
 * still gets its derived pass.
 */
class HistoricalSmsScanner(
    private val dataSource: SmsDataSource,
    private val batchProcessor: HistoricalSmsBatchProcessor,
    private val appLogService: AppLog,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coverageCompleted: suspend (Instant?, Instant) -> Unit = { _, _ -> },
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    /** A bank scope filters through the existing router, not a user-selected-bank override.
     * Unattributed suspicions remain the responsibility of the full inbox scan.
     * Scoped scans never certify whole-inbox analysis coverage.
     */
    suspend fun scan(receivedAfter: Instant? = null, receivedBefore: Instant? = null,
        bankScopes: Set<Bank>? = null, onProgress: (scanned: Int) -> Unit = {}): SmsScanResult = withContext(ioDispatcher) {
        require(receivedAfter == null || receivedBefore == null || receivedAfter < receivedBefore)
        com.baraa.masroof.application.analytics.AnalyticsImportGate.during { scanOnDispatcher(receivedAfter, receivedBefore, bankScopes, onProgress) }
    }

    private suspend fun scanOnDispatcher(receivedAfter: Instant?, receivedBefore: Instant?, bankScopes: Set<Bank>?,
        onProgress: (Int) -> Unit = {}): SmsScanResult {
        val scanStartedAt = clock.instant()
        appLogService.info(
            AppLogCategories.SCAN,
            "Historical scan started${receivedAfter?.let { " after $it" } ?: ""}",
        )
        var scanned = 0
        var inserted = 0
        var duplicates = 0
        var parsed = 0
        var reviewRequired = 0
        var nonFinancial = 0
        var unsupported = 0
        var notRelevant = 0
        var skippedMalformed = 0
        var failed = 0
        val sendersSeen = linkedSetOf<String>()

        fun snapshot(failure: SmsScanFailure?) = SmsScanResult(
            scanned = scanned,
            inserted = inserted,
            duplicates = duplicates,
            parsed = parsed,
            reviewRequired = reviewRequired,
            nonFinancial = nonFinancial,
            unsupported = unsupported,
            notRelevant = notRelevant,
            skippedMalformed = skippedMalformed,
            failed = failed,
            distinctSenders = sendersSeen.take(12),
            failure = failure,
        )

        val batch = batchProcessor.startBatch(bankScopes = bankScopes)

        suspend fun finishAfterFailure(failure: SmsScanFailure): SmsScanResult {
            if (batch.storedEventCount > 0) {
                batch.finish()
            }
            val result = snapshot(failure)
            logScanFinished(result)
            return result
        }

        try {
            dataSource.withInbox(receivedAfter) { rows -> for (row in rows) {
                if (row is InboxRow.Valid) {
                    if (receivedAfter != null && row.record.receivedAt < receivedAfter) continue
                    // Rows are ordered by arrival time but receivedAt is the carrier time, so skip rather than stop.
                    if (receivedBefore != null && row.record.receivedAt >= receivedBefore) continue
                }
                scanned++
                if (scanned % PROGRESS_EVERY == 0) onProgress(scanned)
                when (row) {
                    is InboxRow.Malformed -> {
                        skippedMalformed++
                    }
                    is InboxRow.Valid -> {
                        sendersSeen += row.record.sender
                        val rawSms = try {
                            AndroidSmsMapper.toRawSms(row.record)
                        } catch (_: IllegalArgumentException) {
                            skippedMalformed++
                            continue
                        }

                        when (val outcome = batch.ingest(rawSms)) {
                            is SmsIngestionResult.Duplicate -> duplicates++
                            is SmsIngestionResult.NotRelevant -> notRelevant++
                            is SmsIngestionResult.Parsed -> {
                                inserted++
                                parsed++
                            }
                            is SmsIngestionResult.ReviewRequired -> {
                                inserted++
                                reviewRequired++
                            }
                            is SmsIngestionResult.NonFinancial -> {
                                inserted++
                                nonFinancial++
                            }
                            is SmsIngestionResult.Unsupported -> {
                                inserted++
                                unsupported++
                            }
                            is SmsIngestionResult.Invalid -> {
                                inserted++
                                failed++
                            }
                            // rawSmsId != null → RawSms already stored; later step failed
                            is SmsIngestionResult.Failed if outcome.rawSmsId != null -> {
                                inserted++
                                failed++
                            }
                            // rawSmsId == null → RawSms never persisted
                            is SmsIngestionResult.Failed -> failed++
                            is SmsIngestionResult.DerivedIncomplete -> {
                                // Historical ingest does not run derived work; a leak is still stored evidence.
                                inserted++
                                failed++
                            }
                        }
                    }
                }
            } }
        } catch (_: SmsPermissionException) {
            return finishAfterFailure(SmsScanFailure.PermissionDenied)
        } catch (e: SmsProviderException) {
            return finishAfterFailure(SmsScanFailure.ProviderError(e.message ?: "provider_error"))
        }

        val derived = batch.finish()
        val failure = (derived as? HistoricalBatchDerivedResult.Incomplete)?.let {
            SmsScanFailure.DerivedIncomplete(it.stage)
        }
        val result = snapshot(failure)
        if(bankScopes == null && receivedBefore == null && result.failure == null && result.failed == 0 && result.skippedMalformed == 0) {
            try { coverageCompleted(receivedAfter, scanStartedAt) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: Exception) { appLogService.warn(AppLogCategories.SCAN, "Coverage receipt pending; rescan required") }
        }
        logScanFinished(result)
        return result
    }

    private fun logScanFinished(result: SmsScanResult) {
        val level = if (result.failure != null) AppLogLevel.WARN else AppLogLevel.INFO
        appLogService.log(
            level,
            AppLogCategories.SCAN,
            "Historical scan finished: ${AppLogFormatting.scanSummary(result)}",
        )
    }

    private companion object {
        const val PROGRESS_EVERY = 25
    }
}
