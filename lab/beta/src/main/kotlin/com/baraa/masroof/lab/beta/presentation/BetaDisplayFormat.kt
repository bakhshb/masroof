package com.baraa.masroof.lab.beta.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.baraa.masroof.ui.components.MasroofMoneyDisplay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun betaMoney(amount: String, currency: String?): String =
    if(amount.toBigDecimalOrNull()==null || com.baraa.masroof.lab.finance.value.Currency.entries.none {it.name==currency})
        androidx.compose.ui.res.stringResource(com.baraa.masroof.lab.beta.R.string.beta_approved_dashboard_value_unavailable)
    else MasroofMoneyDisplay.format(amount.toBigDecimal(),checkNotNull(currency),LocalConfiguration.current.locales[0].toLanguageTag())
@Composable internal fun betaDate(at: Instant, zone: ZoneId = ZoneId.of("Asia/Riyadh")): String =
    DateTimeFormatter.ofPattern("d MMM yyyy، HH:mm",LocalConfiguration.current.locales[0]).format(at.atZone(zone))
