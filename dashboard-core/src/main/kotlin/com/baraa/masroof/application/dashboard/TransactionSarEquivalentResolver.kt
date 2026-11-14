package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.assembly.BankTransactionTimePolicy
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.LocalDate
import java.time.ZoneId

/**
 * Resolves a foreign amount to SAR.
 *
 * Precedence for each transaction:
 * 1. A complete persisted `(appliedExchangeRate, exchangeRateSource)` pair, used as stored.
 * 2. The exchange rate on the transaction's own linked parsed event.
 * 3. A dated merchant rate from [HistoricalExchangeRateIndex] at [FinancialTransaction.occurredAt].
 * 4. A market rate for the purchase civil date in the bank zone.
 * 5. No resolution. The row stays unconverted.
 *
 * The market civil date uses [FinancialTransaction.occurredAtZone] when it is a real zone id,
 * otherwise [BankTransactionTimePolicy.RIYADH]. It is not the handset zone.
 */
class TransactionSarEquivalentResolver(
    private val marketRateProvider: ForeignSarMarketRateProvider,
) {
    suspend fun resolve(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        primaryCurrency: Currency = Currency.SAR,
    ): Map<String, SarEquivalentResolution> {
        val parsedByEventId = parsedRecords.associateBy { it.event.id }
        val rateIndex = HistoricalExchangeRateIndex.build(
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
        )
        val result = mutableMapOf<String, SarEquivalentResolution>()
        for (tx in transactions) {
            if (tx.amount.currency == primaryCurrency) continue
            if (!tx.amount.currency.convertsToSar()) continue

            val linkedRecord = linkedParsedRecord(tx, parsedByEventId)
            val includeFee = tx.type != FinancialTransactionType.REFUND

            val appliedRate = tx.appliedExchangeRate
            val appliedSource = tx.exchangeRateSource
            if (appliedRate != null && appliedSource != null) {
                resolutionFromRate(
                    tx = tx,
                    exchangeRate = appliedRate,
                    source = appliedSource,
                    primaryCurrency = primaryCurrency,
                    internationalFee = if (includeFee && appliedSource == ExchangeRateSource.SMS) {
                        linkedRecord?.details?.internationalFee
                    } else {
                        null
                    },
                    includeFee = includeFee,
                )?.let { result[tx.id] = it }
                continue
            }

            val smsFacts = linkedRecord?.details
            val smsRate = smsFacts?.exchangeRate
            if (smsRate != null) {
                resolutionFromRate(
                    tx, smsRate, ExchangeRateSource.SMS, primaryCurrency, smsFacts.internationalFee, includeFee,
                )?.let { result[tx.id] = it }
                continue
            }

            val merchant = tx.merchant
                ?: tx.linkedParsedEventIds.firstNotNullOfOrNull { parsedByEventId[it]?.event?.merchant }
            val historicalRate = rateIndex.rateForMerchant(merchant, tx.amount.currency, tx.occurredAt)
            if (historicalRate != null) {
                resolutionFromRate(
                    tx, historicalRate, ExchangeRateSource.HISTORICAL_MERCHANT, primaryCurrency, null, includeFee,
                )?.let { result[tx.id] = it }
                continue
            }

            val onDate = marketCivilDate(tx, linkedRecord) ?: continue
            val marketRate = marketRateProvider.rateFor(tx.amount.currency, onDate) ?: continue
            resolutionFromRate(tx, marketRate, ExchangeRateSource.MARKET, primaryCurrency, null, includeFee)
                ?.let { result[tx.id] = it }
        }
        return result
    }


    private fun marketCivilDate(tx: FinancialTransaction, linked: ParsedEventRecord?): LocalDate? {
        val zone = marketZone(tx, linked) ?: return null
        return tx.occurredAt.atZone(zone).toLocalDate()
    }

    /**
     * Stored transaction zone first, else the bank zone when the row has linked evidence
     * (a row with neither stays unconverted, as before). No handset-zone fallback.
     */
    private fun marketZone(tx: FinancialTransaction, linked: ParsedEventRecord?): ZoneId? =
        TransactionLocalDate.storedZoneOrNull(tx) ?: linked?.let { BankTransactionTimePolicy.RIYADH }

    private fun linkedParsedRecord(
        tx: FinancialTransaction,
        parsedByEventId: Map<String, ParsedEventRecord>,
    ): ParsedEventRecord? =
        tx.linkedParsedEventIds.firstNotNullOfOrNull { eventId -> parsedByEventId[eventId] }

    private fun resolutionFromRate(
        tx: FinancialTransaction,
        exchangeRate: java.math.BigDecimal,
        source: ExchangeRateSource,
        primaryCurrency: Currency,
        internationalFee: Money?,
        includeFee: Boolean,
    ): SarEquivalentResolution? {
        val sar = ForeignPurchaseSarConverter.foreignToSar(
            foreignAmount = tx.amount,
            exchangeRate = exchangeRate,
            internationalFee = if (includeFee) internationalFee else null,
            targetCurrency = primaryCurrency,
        ) ?: return null
        return SarEquivalentResolution(
            sarAmount = sar,
            exchangeRate = exchangeRate,
            source = source,
        )
    }
}

fun Map<String, SarEquivalentResolution>.sarAmounts(): Map<String, Money> =
    mapValues { (_, resolution) -> resolution.sarAmount }
