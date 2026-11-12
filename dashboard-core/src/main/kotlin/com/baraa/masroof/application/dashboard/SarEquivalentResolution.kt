package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.ExchangeRateSource
import java.math.BigDecimal

data class SarEquivalentResolution(
    val sarAmount: Money,
    val exchangeRate: BigDecimal,
    val source: ExchangeRateSource,
)
