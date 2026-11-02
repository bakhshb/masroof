package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

fun interface ForeignSarMarketRateProvider {
    suspend fun rateFor(currency: Currency, date: LocalDate): BigDecimal?
}

/**
 * Fetches foreign→SAR from Frankfurter v2 (no API key). Caches per currency and date in memory.
 */
class FrankfurterForeignSarRateProvider(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = "https://api.frankfurter.dev",
) : ForeignSarMarketRateProvider {
    private val cache = ConcurrentHashMap<Pair<Currency, LocalDate>, BigDecimal>()

    override suspend fun rateFor(currency: Currency, date: LocalDate): BigDecimal? {
        if (!currency.convertsToSar()) return null
        val key = currency to date
        cache[key]?.let { return it }
        val fetched = fetchRate(currency, date) ?: return null
        cache[key] = fetched
        return fetched
    }

    private suspend fun fetchRate(currency: Currency, date: LocalDate): BigDecimal? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$apiBaseUrl/v2/rate/${currency.name}/SAR?date=$date")
                .get()
                .build()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val body = response.body?.string() ?: return@runCatching null
                    val json = JSONObject(body)
                    // A stored rate is never replaced, so only a positive number may be returned.
                    json.optString("rate").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                }
            }.getOrNull()
        }
}
