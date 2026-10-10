package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Merchant + currency → dated exchange-rate evidence from persisted parse-time facts.
 *
 * The merchant key is [MerchantNameNormalizer.key] only. A similar name is not evidence:
 * `STC` does not borrow a rate stored for `STC PAY`. SAR is not a foreign currency and
 * has no historical rate.
 *
 * A lookup at instant T uses evidence whose instant is at or before T and no older than
 * [MAX_EVIDENCE_AGE]. A later SMS rate never applies to an earlier purchase. Among
 * eligible rows, the latest evidence instant wins. When several eligible rows share
 * that instant, the lexicographically greatest parsed-event id wins.
 *
 * Evidence time prefers the SMS wall clock ([com.baraa.masroof.parsing.model.ParsedEventDetails.occurredAtLocal])
 * converted with [TransactionTiming.bankLocalInstant], which follows [com.baraa.masroof.domain.assembly.BankTransactionTimePolicy].
 * AlJazira is `Asia/Riyadh`. The handset zone is not used. When the wall clock is
 * absent, the event's stored [com.baraa.masroof.domain.model.ParsedEvent.occurredAt] is used.
 * The inbox [RawSms.receivedAt] is only a last resort when both are absent; it is already
 * an instant and is not a reinterpretation of a bank-local clock. A wall clock that
 * cannot be zoned is ineligible rather than converted with the handset zone.
 */
class HistoricalExchangeRateIndex private constructor(
    private val evidenceByMerchant: Map<String, Map<Currency, List<RateEvidence>>>,
) {
    fun rateForMerchant(merchant: String?, currency: Currency, asOf: Instant): BigDecimal? {
        if (!currency.convertsToSar()) return null
        val normalized = merchant?.let(MerchantNameNormalizer::key)?.takeIf { it.isNotBlank() } ?: return null
        val rows = evidenceByMerchant[normalized]?.get(currency).orEmpty()
        if (rows.isEmpty()) return null
        val earliest = asOf.minus(MAX_EVIDENCE_AGE)
        return rows.asSequence()
            .filter { !it.at.isAfter(asOf) && !it.at.isBefore(earliest) }
            .maxWithOrNull(ELIGIBLE_ORDER)
            ?.rate
    }

    private data class RateEvidence(
        val rate: BigDecimal,
        val at: Instant,
        val eventId: String,
    )

    companion object {
        /**
         * Maximum age of merchant-rate evidence. Compared on instants, so one day
         * is 24 hours. Evidence older than this is ineligible even when it is the
         * only past rate for that merchant and currency.
         */
        val MAX_EVIDENCE_AGE: Duration = Duration.ofDays(30)

        private val ELIGIBLE_ORDER = compareBy<RateEvidence> { it.at }.thenBy { it.eventId }

        fun build(
            parsedRecords: List<ParsedEventRecord>,
            rawSmsById: Map<String, RawSms>,
            persistedZoneIdByEventId: Map<String, String> = emptyMap(),
        ): HistoricalExchangeRateIndex {
            val byMerchant = mutableMapOf<String, MutableMap<Currency, MutableMap<String, RateEvidence>>>()
            for (record in parsedRecords) {
                val merchant = record.event.merchant
                    ?.let(MerchantNameNormalizer::key)
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                val currency = record.event.amount?.currency?.takeIf { it.convertsToSar() }
                    ?: record.details.labeledForeignAmount?.currency?.takeIf { it.convertsToSar() }
                    ?: continue
                val rate = record.details.exchangeRate ?: continue
                val at = evidenceInstant(
                    record = record,
                    rawSmsById = rawSmsById,
                    persistedZoneId = persistedZoneIdByEventId[record.event.id],
                ) ?: continue
                val byEvent = byMerchant.getOrPut(merchant) { mutableMapOf() }
                    .getOrPut(currency) { mutableMapOf() }
                byEvent.putIfAbsent(record.event.id, RateEvidence(rate, at, record.event.id))
            }
            return HistoricalExchangeRateIndex(
                byMerchant.mapValues { (_, byCurrency) ->
                    byCurrency.mapValues { (_, byEvent) -> byEvent.values.toList() }
                },
            )
        }

        private fun evidenceInstant(
            record: ParsedEventRecord,
            rawSmsById: Map<String, RawSms>,
            persistedZoneId: String?,
        ): Instant? {
            val local = record.details.occurredAtLocal
            if (local != null) {
                return TransactionTiming.bankLocalInstant(record.event.bank, local, persistedZoneId)
            }
            return record.event.occurredAt ?: rawSmsById[record.event.rawSmsId]?.receivedAt
        }
    }
}
