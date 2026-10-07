package com.baraa.masroof.application.ingestion

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * Capture boundary: route bank → dedupe → persist immutable [RawSms] evidence.
 *
 * Returns as soon as the row is durable so long processing
 * ([ProcessStoredSmsUseCase]) can run later or elsewhere. Never parses, resolves
 * ownership, reconciles, or touches the dashboard. Idempotent: re-capturing the same
 * SMS (or its cross-source twin) is [BankSmsCaptureResult.Duplicate].
 */
class CaptureBankSmsUseCase(
    private val rawSmsRepository: RawSmsRepository,
    private val bankSmsRegistry: BankSmsRegistry,
    private val appLogService: AppLogService? = null,
) {
    private val insertMutex = Mutex()

    suspend fun capture(rawSms: RawSms, logOutcome: Boolean = true): BankSmsCaptureResult {
        val route = bankSmsRegistry.route(rawSms.sender, rawSms.body)
        if (route is BankRoutingResult.NotMatched) {
            if (logOutcome) {
                appLogService?.info(
                    AppLogCategories.INGEST,
                    "Ignored non-bank SMS from ${AppLogFormatting.maskSender(rawSms.sender)} (${route.reason})",
                )
            }
            return BankSmsCaptureResult.NotRelevant(reason = route.reason)
        }

        val insertOutcome = try {
            insertMutex.withLock {
                if (hasCrossSourceNearDuplicate(rawSms)) {
                    return@withLock RawSmsInsertResult.AlreadyExists
                }
                rawSmsRepository.insertIfAbsent(rawSms)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.java.simpleName
            if (logOutcome) {
                appLogService?.error(
                    AppLogCategories.INGEST,
                    "Ingest failed for ${AppLogFormatting.maskSender(rawSms.sender)}: $message",
                )
            }
            return BankSmsCaptureResult.Failed(message = message, cause = e)
        }

        return when (insertOutcome) {
            RawSmsInsertResult.AlreadyExists -> {
                if (logOutcome) {
                    appLogService?.info(
                        AppLogCategories.INGEST,
                        "Duplicate SMS from ${AppLogFormatting.maskSender(rawSms.sender)}",
                    )
                }
                BankSmsCaptureResult.Duplicate
            }
            RawSmsInsertResult.Inserted -> {
                if (logOutcome) {
                    appLogService?.info(
                        AppLogCategories.INGEST,
                        "Inserted SMS from ${AppLogFormatting.maskSender(rawSms.sender)}",
                    )
                }
                BankSmsCaptureResult.Captured(rawSms = rawSms, route = route)
            }
        }
    }

    private suspend fun hasCrossSourceNearDuplicate(rawSms: RawSms): Boolean {
        val lookingForLiveRow = rawSms.deviceMessageId != null
        val tight = crossSourceTwins(
            rawSms = rawSms,
            tolerance = CROSS_SOURCE_RECEIVED_AT_TOLERANCE,
            lookingForLiveRow = lookingForLiveRow,
        )
        if (tight.isNotEmpty()) return true
        val widened = crossSourceTwins(
            rawSms = rawSms,
            tolerance = CROSS_SOURCE_UNIQUE_SKEW_TOLERANCE,
            lookingForLiveRow = lookingForLiveRow,
        )
        return widened.size == 1
    }

    private suspend fun crossSourceTwins(
        rawSms: RawSms,
        tolerance: Duration,
        lookingForLiveRow: Boolean,
    ): List<RawSms> {
        val receivedAt = rawSms.receivedAt
        return rawSmsRepository.listCrossSourceNearDuplicates(
            sender = rawSms.sender,
            bodyHash = rawSms.bodyHash,
            fromInclusive = receivedAt.minus(tolerance),
            toInclusive = receivedAt.plus(tolerance),
            lookingForLiveRow = lookingForLiveRow,
        )
    }

    companion object {
        /**
         * Maximum |live receipt − historical DATE| that always counts as one SMS.
         * Same-source rows are never merged by this window alone.
         */
        val CROSS_SOURCE_RECEIVED_AT_TOLERANCE: Duration = Duration.ofSeconds(5)

        /**
         * Residual provider/device skew beyond the 5-second window. A match counts
         * only when exactly one opposite-source twin falls inside it. Two minutes
         * is enough for a late inbox clock and too short to treat a repeated
         * notification as the same SMS.
         */
        val CROSS_SOURCE_UNIQUE_SKEW_TOLERANCE: Duration = Duration.ofMinutes(2)
    }
}

/** Outcome of [CaptureBankSmsUseCase.capture]. Expected cases are values, not exceptions. */
sealed interface BankSmsCaptureResult {
    /** No registered bank adapter claimed the sender; nothing persisted. */
    data class NotRelevant(val reason: String) : BankSmsCaptureResult

    /** Already present as RawSms evidence (same row or cross-source twin). */
    data object Duplicate : BankSmsCaptureResult

    /**
     * Newly persisted evidence. [route] is the [BankRoutingResult.Matched] or
     * [BankRoutingResult.Ambiguous] outcome computed for this capture.
     */
    data class Captured(
        val rawSms: RawSms,
        val route: BankRoutingResult,
    ) : BankSmsCaptureResult {
        init {
            require(route !is BankRoutingResult.NotMatched) { "Captured evidence must have a bank route" }
        }

        val rawSmsId: String get() = rawSms.id
    }

    /** Persisting the RawSms failed; nothing is stored. */
    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : BankSmsCaptureResult
}
