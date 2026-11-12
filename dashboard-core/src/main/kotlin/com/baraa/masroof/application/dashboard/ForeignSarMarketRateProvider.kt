package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import java.math.BigDecimal
import java.time.LocalDate

fun interface ForeignSarMarketRateProvider {
    suspend fun rateFor(currency: Currency, date: LocalDate): BigDecimal?
}
