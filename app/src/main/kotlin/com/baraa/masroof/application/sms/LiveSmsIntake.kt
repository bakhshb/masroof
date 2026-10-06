package com.baraa.masroof.application.sms

import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.domain.model.RawSms

/**
 * Application boundary for live SMS received from [com.baraa.masroof.sms.receiver.IncomingSmsReceiver].
 *
 * Captures durable RawSms evidence first, then processes it by the captured row.
 */
class LiveSmsIntake(
    private val captureBankSms: CaptureBankSmsUseCase,
    private val processStoredSms: ProcessStoredSmsUseCase,
    private val appLogService: AppLogService,
) {
    suspend fun ingest(rawSms: RawSms) {
        appLogService.info(
            AppLogCategories.SMS,
            "Live SMS received from ${AppLogFormatting.maskSender(rawSms.sender)}",
        )
        val captured = captureBankSms.capture(rawSms) as? BankSmsCaptureResult.Captured ?: return
        processStoredSms.process(captured.rawSms, captured.route)
    }
}
