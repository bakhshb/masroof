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
import java.math.BigDecimal
import java.time.LocalDate

/** Robolectric supplies the real org.json; the plain JVM stub returns null for everything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FrankfurterForeignSarRateProviderTest {
    private val date = LocalDate.parse("2026-08-03")

    private fun provider(body: String) = FrankfurterForeignSarRateProvider(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build(),
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
}
