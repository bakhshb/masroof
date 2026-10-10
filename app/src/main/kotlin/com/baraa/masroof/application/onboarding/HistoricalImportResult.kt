package com.baraa.masroof.application.onboarding

import com.baraa.masroof.application.sms.SmsScanFailure
import com.baraa.masroof.application.sms.SmsScanResult

/**
 * Application-facing outcome of a historical SMS import.
 *
 * Keeps SMS scanner implementation types at the application boundary.
 */
data class HistoricalImportResult(
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
    val distinctSenders: List<String> = emptyList(),
    val failure: HistoricalImportFailure? = null,
)

sealed interface HistoricalImportFailure {
    data object PermissionDenied : HistoricalImportFailure
    data class ProcessingIncomplete(val stage: String) : HistoricalImportFailure
    data class ProviderError(val message: String) : HistoricalImportFailure
}

enum class HistoricalImportUserOutcome {
    OK,
    ALREADY_UP_TO_DATE,
    NEEDS_REVIEW,
    NO_MESSAGES,
    NO_BANK_SMS,
    NO_NEW_TRANSACTIONS,
    PERMISSION_DENIED,
    FAILED,
}

fun HistoricalImportResult.userOutcome(): HistoricalImportUserOutcome =
    when (failure) {
        HistoricalImportFailure.PermissionDenied -> HistoricalImportUserOutcome.PERMISSION_DENIED
        is HistoricalImportFailure.ProviderError,
        is HistoricalImportFailure.ProcessingIncomplete,
        -> HistoricalImportUserOutcome.FAILED
        null -> when {
            failed > 0 -> HistoricalImportUserOutcome.FAILED
            scanned == 0 -> HistoricalImportUserOutcome.NO_MESSAGES
            notRelevant == scanned -> HistoricalImportUserOutcome.NO_BANK_SMS
            parsed > 0 -> HistoricalImportUserOutcome.OK
            reviewRequired > 0 -> HistoricalImportUserOutcome.NEEDS_REVIEW
            scanned - notRelevant - skippedMalformed > 0 &&
                duplicates >= scanned - notRelevant - skippedMalformed ->
                HistoricalImportUserOutcome.ALREADY_UP_TO_DATE
            else -> HistoricalImportUserOutcome.NO_NEW_TRANSACTIONS
        }
    }

fun SmsScanResult.toHistoricalImportResult() = HistoricalImportResult(
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
    distinctSenders = distinctSenders,
    failure = when (val scanFailure = failure) {
        null -> null
        SmsScanFailure.PermissionDenied -> HistoricalImportFailure.PermissionDenied
        is SmsScanFailure.DerivedIncomplete -> HistoricalImportFailure.ProcessingIncomplete(scanFailure.stage.name.lowercase())
        is SmsScanFailure.ProviderError -> HistoricalImportFailure.ProviderError(scanFailure.message)
    },
)
