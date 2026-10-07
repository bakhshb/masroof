package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.ZoneId

class TransactionSarEquivalentResolver(
    private val marketRateProvider: ForeignSarMarketRateProvider,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun resolve(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        primaryCurrency: Currency = Currency.SAR,
    ): Map<String, SarEquivalentResolution> {
        val parsedByEventId = parsedRecords.associateBy { it.event.id }
        val rateIndex = HistoricalExchangeRateIndex.build(parsedRecords, rawSmsById)
        val result = mutableMapOf<String, SarEquivalentResolution>()
        for (tx in transactions) {
            if (tx.amount.currency == primaryCurrency) continue
            if (!tx.amount.currency.convertsToSar()) continue

            val linkedRecord = linkedParsedRecord(tx, parsedByEventId)
            val includeFee = tx.type != FinancialTransactionType.REFUND

            if (tx.appliedExchangeRate != null && tx.exchangeRateSource != null) {
                resolutionFromRate(
                    tx = tx,
                    exchangeRate = tx.appliedExchangeRate,
                    source = tx.exchangeRateSource,
                    primaryCurrency = primaryCurrency,
                    internationalFee = if (includeFee && tx.exchangeRateSource == ExchangeRateSource.SMS) {
                        linkedRecord?.details?.internationalFee
                    } else {
                        null
                    },
                    includeFee = includeFee,
                )?.let { result[tx.id] = it }
                continue
            }

            val smsFacts = linkedRecord?.details
            if (smsFacts?.exchangeRate != null) {
                ForeignPurchaseSarConverter.foreignToSar(
                    foreignAmount = tx.amount,
                    exchangeRate = smsFacts.exchangeRate,
                    internationalFee = if (includeFee) smsFacts.internationalFee else null,
                    targetCurrency = primaryCurrency,
                )?.let { sar ->
                    result[tx.id] = SarEquivalentResolution(
                        sarAmount = sar,
                        exchangeRate = smsFacts.exchangeRate,
                        source = ExchangeRateSource.SMS,
                    )
                }
                continue
            }

            val merchant = tx.merchant
                ?: tx.linkedParsedEventIds.firstNotNullOfOrNull { parsedByEventId[it]?.event?.merchant }
            val historicalRate = rateIndex.rateForMerchant(merchant, tx.amount.currency)
            if (historicalRate != null) {
                ForeignPurchaseSarConverter.foreignToSar(
                    foreignAmount = tx.amount,
                    exchangeRate = historicalRate,
                    internationalFee = null,
                    targetCurrency = primaryCurrency,
                )?.let { sar ->
                    result[tx.id] = SarEquivalentResolution(
                        sarAmount = sar,
                        exchangeRate = historicalRate,
                        source = ExchangeRateSource.HISTORICAL_MERCHANT,
                    )
                }
                continue
            }

            val onDate = tx.occurredAt.atZone(zoneId).toLocalDate()
            val marketRate = marketRateProvider.rateFor(tx.amount.currency, onDate) ?: continue
            ForeignPurchaseSarConverter.foreignToSar(
                foreignAmount = tx.amount,
                exchangeRate = marketRate,
                internationalFee = null,
                targetCurrency = primaryCurrency,
            )?.let { sar ->
                result[tx.id] = SarEquivalentResolution(
                    sarAmount = sar,
                    exchangeRate = marketRate,
                    source = ExchangeRateSource.MARKET,
                )
            }
        }
        return result
    }

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
