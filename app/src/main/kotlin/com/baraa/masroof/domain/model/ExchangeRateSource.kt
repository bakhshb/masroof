package com.baraa.masroof.domain.model

/**
 * Provenance for a foreign→SAR rate applied to a foreign-currency transaction.
 */
enum class ExchangeRateSource {
    /** سعر الصرف مذكور في رسالة البنك. */
    SMS,

    /** Dated past rate for the same merchant and currency, within the as-of window. */
    HISTORICAL_MERCHANT,

    /** سعر سوق من الإنترنت (Frankfurter v2) لتاريخ العملية أو أقرب يوم متاح. */
    MARKET,
}
