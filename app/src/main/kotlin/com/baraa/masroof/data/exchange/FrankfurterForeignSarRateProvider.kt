package com.baraa.masroof.data.exchange

import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.core.money.Currency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fetches foreign→SAR from Frankfurter v2 (no API key). Caches per currency and date in memory.
 * A definitive miss (4xx other than 429) stays cached for this process. A network failure, server
 * error, rate limit or a 200 without a usable rate is reused only for [FAILURE_RETRY_NANOS], so
 * enrichment retries in the same process reach the network again.
 */
class FrankfurterForeignSarRateProvider(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = "https://api.frankfurter.dev",
    private val nanoTime: () -> Long = System::nanoTime,
) : ForeignSarMarketRateProvider {
    private val cache = ConcurrentHashMap<Pair<Currency, LocalDate>, BigDecimal>()
    private val failedAtNanos = ConcurrentHashMap<Pair<Currency, LocalDate>, Long>()

    override suspend fun rateFor(currency: Currency, date: LocalDate): BigDecimal? {
        if (!currency.convertsToSar()) return null
        val key = currency to date
        cache[key]?.let { return it.takeUnless { cached -> cached === MISS } }
        failedAtNanos[key]?.let { if (nanoTime() - it < FAILURE_RETRY_NANOS) return null }
        val fetched = try {
            fetchRate(currency, date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAtNanos[key] = nanoTime()
            return null
        }
        failedAtNanos.remove(key)
        cache[key] = fetched ?: MISS
        return fetched
    }

    internal companion object {
        /** Identity sentinel for a cached definitive miss; never returned to callers. */
        private val MISS = BigDecimal("-1")

        val FAILURE_RETRY_NANOS: Long = TimeUnit.MINUTES.toNanos(2)
    }

    /** Throws on a network failure; returns null for a definitive miss. */
    private suspend fun fetchRate(currency: Currency, date: LocalDate): BigDecimal? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$apiBaseUrl/v2/rate/${currency.name}/SAR?date=$date")
                .get()
                .build()
            val body = httpClient.newCall(request).execute().use { response ->
                // A server error or rate limit is temporary: retried like a network failure.
                if (response.code == 429 || response.code >= 500) throw java.io.IOException("HTTP ${response.code}")
                // Only a client error (unknown currency or date) is a definitive miss.
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            } ?: throw java.io.IOException("Rate response has no body")
            // A stored rate is never replaced, so only a positive number may be returned.
            // A 200 that is not JSON (e.g. a captive portal page) or carries no usable rate is
            // temporary, not a definitive miss, so it is retried after FAILURE_RETRY_NANOS.
            val json = try {
                JSONObject(body)
            } catch (e: org.json.JSONException) {
                throw java.io.IOException("Rate response is not JSON", e)
            }
            json.optString("rate").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                ?: throw java.io.IOException("Rate response has no usable rate")
        }
}
