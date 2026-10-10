package com.baraa.masroof.application.sms

import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import kotlinx.coroutines.CancellationException

/**
 * Application boundary for live SMS received from [com.baraa.masroof.sms.receiver.IncomingSmsReceiver].
 *
 * Captures durable RawSms evidence, then schedules processing by rawSmsId and returns.
 * Parse, ownership, reconciliation, and review work run later in [LiveSmsProcessingWorker],
 * outside the broadcast lifetime.
 */
class LiveSmsIntake(
    private val captureBankSms: CaptureBankSmsUseCase,
    private val scheduler: LiveSmsWorkScheduler,
    private val rawSmsRepository: RawSmsRepository,
    private val reviewRepository: ReviewRepository,
    private val processingRetryRepository: ProcessingRetryRepository,
    private val appLogService: AppLogService,
    private val batchRecoveryScheduler: HistoricalBatchRecoveryScheduler? = null,
    private val debugProcessHalt: DebugProcessHaltProbe = DebugProcessHaltProbe.NONE,
) {
    suspend fun ingest(rawSms: RawSms): BankSmsCaptureResult {
        appLogService.info(
            AppLogCategories.SMS,
            "Live SMS received from ${AppLogFormatting.maskSender(rawSms.sender)}",
        )
        val result = captureBankSms.capture(rawSms)
        if (result is BankSmsCaptureResult.Captured) {
            // Park before scheduling so a killed process still has only the RawSms row.
            debugProcessHalt.afterDurableWrite(DebugProcessHalt.CAPTURE)
            schedule(result.rawSmsId)
        }
        return result
    }

    /**
     * Reschedules captured evidence that still needs processing.
     *
     * Per message: rows with no outcome yet, REQUIRED `processing_error` reviews, and
     * processing-retry rows whose mode is [ProcessingRetryMode.LIVE]. Historical retry rows
     * stay [ProcessingRetryMode.HISTORICAL_BATCH] even when a review row exists, and enqueue
     * one batch recovery. A non-financial resolution is not rescheduled.
     * Returns how many per-message ids were scheduled.
     */
    suspend fun schedulePendingProcessing(): Int {
        val listed = try {
            val historical = processingRetryRepository.listRetryableRawSmsIds(
                ProcessingRetryMode.HISTORICAL_BATCH,
            )
            val historicalIds = historical.toSet()
            val live = (
                rawSmsRepository.listIdsAwaitingProcessing() +
                    reviewRepository.listRetryableProcessingErrorRawSmsIds() +
                    processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.LIVE)
                ).filter { it !in historicalIds }.distinct()
            live to historical
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.error(
                AppLogCategories.SMS,
                "Listing SMS awaiting processing failed (${e::class.java.simpleName})",
            )
            return 0
        }
        val (pending, historical) = listed
        val scheduled = pending.count(::schedule)
        if (pending.isNotEmpty()) {
            appLogService.info(
                AppLogCategories.SMS,
                "Rescheduled $scheduled of ${pending.size} captured SMS awaiting processing",
            )
        }
        scheduleHistoricalBatchRecovery(historical)
        return scheduled
    }

    private fun scheduleHistoricalBatchRecovery(pending: List<String>) {
        val scheduler = batchRecoveryScheduler ?: return
        if (pending.isEmpty()) return
        try {
            scheduler.schedule()
            appLogService.info(
                AppLogCategories.SMS,
                "Scheduled one historical recovery for ${pending.size} SMS",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.error(
                AppLogCategories.SMS,
                "Scheduling historical recovery failed (${e::class.java.simpleName}); kept for the next startup",
            )
        }
    }

    private fun schedule(rawSmsId: String): Boolean =
        try {
            scheduler.schedule(rawSmsId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.error(
                AppLogCategories.SMS,
                "Scheduling SMS processing failed (${e::class.java.simpleName}); kept for startup recovery",
            )
            false
        }
}
