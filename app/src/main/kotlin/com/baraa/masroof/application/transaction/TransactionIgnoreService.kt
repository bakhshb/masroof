package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.domain.repository.TransactionIgnoreOutcome
import com.baraa.masroof.domain.repository.TransactionIgnoreRepository
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CancellationException

sealed interface IgnoreResult {
    data object Success : IgnoreResult
    data class Rejected(val reason: String) : IgnoreResult
}

/** The durable decision and exclusive movement deletion are one persistence operation. */
class TransactionIgnoreService(
    private val persistence: TransactionIgnoreRepository,
    private val clock: InstantClock,
    private val appLogService: AppLogService? = null,
) {
    suspend fun ignore(transactionId: String): IgnoreResult =
        try {
            when (val result = persistence.ignoreSingle(transactionId, clock.now())) {
                TransactionIgnoreOutcome.Ignored -> {
                    appLogService?.info(
                        AppLogCategories.TRANSACTION,
                        "Ignored transaction ${AppLogFormatting.maskId(transactionId)}",
                    )
                    IgnoreResult.Success
                }
                is TransactionIgnoreOutcome.Rejected -> IgnoreResult.Rejected(result.reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IgnoreResult.Rejected("ignore_failed")
        }
}
