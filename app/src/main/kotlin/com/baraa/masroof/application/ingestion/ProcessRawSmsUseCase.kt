package com.baraa.masroof.application.ingestion

import com.baraa.masroof.domain.model.RawSms

/**
 * Bulk-maintenance wrapper: production only calls [reparseAndStore]. Capture and processing use
 * [CaptureBankSmsUseCase] and [ProcessStoredSmsUseCase] directly; tests capture-then-process with the `ingest` helper.
 */
class ProcessRawSmsUseCase(
    internal val capture: CaptureBankSmsUseCase,
    internal val processStored: ProcessStoredSmsUseCase,
) {
    /** Bulk-maintenance parse-only path; derived processing is intentionally deferred. */
    suspend fun reparseAndStore(rawSms: RawSms, historicalBatch: Boolean = false): SmsIngestionResult =
        processStored.reparseAndStore(rawSms, historicalBatch)
}
