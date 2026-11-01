package com.baraa.masroof.integration.scale

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * Five years of history (about eight bank SMS a day) must not slow the screens or the live path,
 * and five SMS arriving together must all post. Limits carry a wide margin for slow CI machines;
 * they guard against a return to per-message history reads, not against small regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FiveYearScaleTest {
    private val bank = Bank.BANK_ALJAZIRA
    private val card = "7271"

    @Test
    fun fiveYearsOfHistory_keepDashboardAndLiveProcessingFast() = runBlocking<Unit> {
        DashboardLedgerWorld(ApplicationProvider.getApplicationContext<Context>()).use { world ->
            val months = generateSequence(YearMonth.of(2021, 10)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.of(2026, 9)) }.toList()
            // The world's varied statements, loans and commitments, then five years of everyday purchases.
            world.seedSyntheticHistory(DashboardLedgerWorld.SYNTHETIC_MONTHS)
            seedDailyPurchases(world, months)
            assertTrue(world.db.rawSmsDao().count() >= 14_000)

            val today = LocalDate.now(world.clock)
            val service = world.dashboardService()
            service.loadOverview(FinancialPeriodPolicy.periodContaining(today)) // warm-up
            val dashboardMillis = timed { service.loadOverview(FinancialPeriodPolicy.periodContaining(today)) }

            val processStored = world.processStoredSms()
            val single = captured(world, "live-single", 10)
            val singleMillis = timed { assertTrue(processStored.process(single) is SmsIngestionResult.Parsed) }

            val burst = (1..5).map { captured(world, "live-burst-$it", 20 + it) }
            val results = burst.map { id -> async(Dispatchers.IO) { processStored.process(id) } }.awaitAll()

            println("scale: rows=${world.db.rawSmsDao().count()} dashboard=${dashboardMillis}ms live=${singleMillis}ms")
            assertTrue("dashboard took ${dashboardMillis}ms", dashboardMillis < DASHBOARD_LIMIT_MS)
            assertTrue("live SMS took ${singleMillis}ms", singleMillis < LIVE_LIMIT_MS)
            assertEquals(List(5) { true }, results.map { it is SmsIngestionResult.Parsed })
            burst.forEach { id -> assertEquals(FinancialTransactionType.EXPENSE, world.ftRepo.findByRawSmsId(id)?.type) }
        }
    }

    /** Eight purchases a day on the owned card, stored already posted. */
    private suspend fun seedDailyPurchases(world: DashboardLedgerWorld, months: List<YearMonth>) {
        var n = 0
        for (month in months) for (day in 1..month.lengthOfMonth()) for (slot in 0 until 8) {
            n++
            val at = month.atDay(day).atTime(9 + slot, (n * 7) % 60).atZone(world.zone).toInstant()
            val rawId = "bulk:$n"
            val amount = Money.of(BigDecimal("${10 + n % 90}.${n % 100}".let { if (it.substringAfter('.').length == 1) "${it}0" else it }), Currency.SAR)
            world.rawRepo.insertIfAbsent(RawSms(rawId, "BankAlJazira", "bulk purchase $n", at.plusSeconds(20), null, "bulk-$n"))
            world.parsedRepo.save(
                ParsedEvent(
                    id = "evt-$rawId", rawSmsId = rawId, bank = bank, messageFamily = MessageFamily.PURCHASE,
                    direction = MoneyDirection.OUTGOING, amount = amount, purchaseChannel = null,
                    sourceAccountRef = null, destinationAccountRef = null, cardRef = CardReference(bank, card),
                    merchant = "Shop ${n % 40}", counterparty = null, occurredAt = at, bankNetworkType = null,
                    confidence = Confidence(0.95), parseStatus = ParseStatus.SUCCESS,
                ),
                ParsedEventDetails(),
            )
            world.ftRepo.save(
                FinancialTransaction(
                    id = TransactionIdFactory.fromRawSmsIds(listOf(rawId)), type = FinancialTransactionType.EXPENSE,
                    amount = amount, occurredAt = at, sourceContainerId = FinancialContainerIdFactory.cardId(bank, card),
                    destinationContainerId = null, merchant = "Shop ${n % 40}", counterparty = null, categoryId = null,
                    linkedParsedEventIds = listOf("evt-$rawId"),
                ),
                listOf(rawId),
            )
        }
    }

    private suspend fun captured(world: DashboardLedgerWorld, id: String, minute: Int): String {
        val body = """
            شراء عبر نقاط البيع
            بطاقة: $card
            لدى: Live Shop $id
            بمبلغ: 25.${minute.toString().padStart(2, '0')} SAR
            في: 10:${minute.toString().padStart(2, '0')} 14-09-2026
        """.trimIndent()
        val raw = AndroidSmsMapper.toRawSms(ProviderSmsRecord(id, "AlJazira", body, Instant.parse("2026-09-14T07:${minute.toString().padStart(2, '0')}:00Z")))
        val captured = world.captureBankSms.capture(raw)
        assertTrue("capture $id: $captured", captured is BankSmsCaptureResult.Captured)
        return raw.id
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val DASHBOARD_LIMIT_MS = 3_000L
        const val LIVE_LIMIT_MS = 2_000L
    }
}
