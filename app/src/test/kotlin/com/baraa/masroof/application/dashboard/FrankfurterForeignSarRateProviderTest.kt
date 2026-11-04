package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate

/** Robolectric supplies the real org.json; the plain JVM stub returns null for everything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FrankfurterForeignSarRateProviderTest {
    private val date = LocalDate.parse("2026-08-03")

    private var calls = 0
    private var now = 0L

    private fun provider(body: String, code: Int = 200, failure: IOException? = null) =
        FrankfurterForeignSarRateProvider(
            OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                failure?.let { throw it }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("x").body(body.toResponseBody()).build()
            }.build(),
            nanoTime = { now },
        )

    @Test
    fun positiveRate_isReturned() = runBlocking {
        assertEquals(0, BigDecimal("3.7505").compareTo(provider("""{"rate":3.7505}""").rateFor(Currency.USD, date)))
    }

    @Test
    fun zeroNegativeMissingOrNonNumericRate_isRejected() = runBlocking {
        for (body in listOf("""{"rate":0}""", """{"rate":-1.2}""", """{"rate":"x"}""", """{"rate":null}""", """{}""")) {
            assertNull(body, provider(body).rateFor(Currency.USD, date))
        }
    }

    @Test
    fun nonSuccessResponse_isNullAndCachedAsMiss() = runBlocking {
        val provider = provider("""{"rate":3.75}""", code = 503)
        assertNull(provider.rateFor(Currency.USD, date))
        assertNull(provider.rateFor(Currency.USD, date))
        assertEquals(1, calls)
    }

    @Test
    fun networkFailure_isReusedBrieflyThenRetried() = runBlocking {
        val provider = provider("", failure = IOException("offline"))
        assertNull(provider.rateFor(Currency.USD, date))
        now += FrankfurterForeignSarRateProvider.FAILURE_RETRY_NANOS - 1
        assertNull(provider.rateFor(Currency.USD, date))
        assertEquals(1, calls)
        now += 1
        assertNull(provider.rateFor(Currency.USD, date))
        assertEquals(2, calls)
    }

    @Test
    fun definitiveMiss_staysCachedPastTheRetryWindow() = runBlocking {
        val provider = provider("""{"rate":0}""")
        assertNull(provider.rateFor(Currency.USD, date))
        now += FrankfurterForeignSarRateProvider.FAILURE_RETRY_NANOS * 10
        assertNull(provider.rateFor(Currency.USD, date))
        assertEquals(1, calls)
    }

    @Test
    fun hit_isCachedAndOtherDateStillFetches() = runBlocking {
        val provider = provider("""{"rate":3.75}""")
        provider.rateFor(Currency.USD, date)
        provider.rateFor(Currency.USD, date)
        provider.rateFor(Currency.USD, date.plusDays(1))
        assertEquals(2, calls)
    }
}
