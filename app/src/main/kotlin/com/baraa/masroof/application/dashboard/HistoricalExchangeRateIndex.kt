package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.math.BigDecimal
import java.time.Instant

/**
 * Merchant + currency → exchange-rate evidence from persisted parse-time facts.
 *
 * The merchant key is [MerchantNameNormalizer.key] only. A similar name is not evidence:
 * `STC` does not borrow a rate stored for `STC PAY`.
 */
class HistoricalExchangeRateIndex private constructor(
    private val ratesByMerchant: Map<String, Map<Currency, BigDecimal>>,
) {
    fun rateForMerchant(merchant: String?, currency: Currency): BigDecimal? {
        if (!currency.convertsToSar()) return null
        val normalized = merchant?.let(MerchantNameNormalizer::key)?.takeIf { it.isNotBlank() } ?: return null
        return ratesByMerchant[normalized]?.get(currency)
    }

    companion object {
        fun build(
            parsedRecords: List<ParsedEventRecord>,
            rawSmsById: Map<String, RawSms>,
        ): HistoricalExchangeRateIndex {
            val latest = mutableMapOf<String, MutableMap<Currency, Pair<BigDecimal, Instant?>>>()
            for (record in parsedRecords) {
                val merchant = record.event.merchant
                    ?.let(MerchantNameNormalizer::key)
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                val currency = record.event.amount?.currency?.takeIf { it.convertsToSar() }
                    ?: record.details.labeledForeignAmount?.currency?.takeIf { it.convertsToSar() }
                    ?: continue
                val rate = record.details.exchangeRate ?: continue
                val occurredAt = record.details.occurredAtLocal
                    ?.atZone(java.time.ZoneId.systemDefault())
                    ?.toInstant()
                    ?: record.event.occurredAt
                    ?: rawSmsById[record.event.rawSmsId]?.receivedAt
                val merchantRates = latest.getOrPut(merchant) { mutableMapOf() }
                val existing = merchantRates[currency]
                if (existing == null || isNewer(occurredAt, existing.second)) {
                    merchantRates[currency] = rate to occurredAt
                }
            }
            return HistoricalExchangeRateIndex(
                latest.mapValues { (_, byCurrency) ->
                    byCurrency.mapValues { it.value.first }
                },
            )
        }

        private fun isNewer(candidate: Instant?, current: Instant?): Boolean {
            if (candidate == null) return current == null
            if (current == null) return true
            return candidate.isAfter(current)
        }
    }
}
