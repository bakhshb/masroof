package com.baraa.masroof.testsupport

import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.domain.model.RawSms

/** Tests only: capture then process in one call. Duplicates are not re-parsed. */
suspend fun ProcessRawSmsUseCase.ingest(rawSms: RawSms, logOutcome: Boolean = true): SmsIngestionResult =
    when (val captured = capture.capture(rawSms, logOutcome)) {
        is BankSmsCaptureResult.NotRelevant -> SmsIngestionResult.NotRelevant(reason = captured.reason)
        BankSmsCaptureResult.Duplicate -> SmsIngestionResult.Duplicate
        is BankSmsCaptureResult.Failed -> SmsIngestionResult.Failed(
            rawSmsId = null,
            message = captured.message,
            cause = captured.cause,
        )
        is BankSmsCaptureResult.Captured -> processStored.process(captured.rawSms, captured.route, logOutcome)
    }
