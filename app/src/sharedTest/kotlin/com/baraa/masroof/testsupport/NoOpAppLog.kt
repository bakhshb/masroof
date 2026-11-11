package com.baraa.masroof.testsupport

import com.baraa.masroof.application.logging.AppLog
import com.baraa.masroof.application.logging.AppLogLevel
import com.baraa.masroof.application.sms.ExchangeRateEnrichmentScheduler
import com.baraa.masroof.application.sms.HistoricalBatchRecoveryScheduler
import com.baraa.masroof.application.sms.LiveCaptureRecoveryScheduler

/** Stand-ins for a collaborator a test does not exercise; they do nothing. */
object NoOpAppLog : AppLog {
    override fun log(level: AppLogLevel, category: String, message: String) = Unit
}

val NoOpExchangeRateEnrichmentScheduler = ExchangeRateEnrichmentScheduler { }

val NoOpHistoricalBatchRecoveryScheduler = HistoricalBatchRecoveryScheduler { }

val NoOpLiveCaptureRecoveryScheduler = LiveCaptureRecoveryScheduler { }
