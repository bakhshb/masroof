package com.baraa.masroof.domain.model

/**
 * Who retries a processing-retry row.
 *
 * This is independent of whether a review row exists. A historical batch can
 * write some reviews and then fail; those rows stay [HISTORICAL_BATCH].
 */
enum class ProcessingRetryMode {
    LIVE,
    HISTORICAL_BATCH,
}
