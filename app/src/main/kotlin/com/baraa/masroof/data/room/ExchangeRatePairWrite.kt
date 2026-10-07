package com.baraa.masroof.data.room

/**
 * How a rewrite treats `appliedExchangeRate` and `exchangeRateSource`.
 *
 * A complete pair is one accepted resolution and is left untouched. A row with
 * only one half is not a resolution: the incoming rate and source replace both
 * columns together, so an old number is never paired with a newly inferred source.
 */
internal object ExchangeRatePairWrite {
    fun storedOrIncoming(
        storedRate: String?,
        storedSource: String?,
        incomingRate: String?,
        incomingSource: String?,
    ): Pair<String?, String?> =
        if (storedRate != null && storedSource != null) {
            storedRate to storedSource
        } else {
            incomingRate to incomingSource
        }
}
