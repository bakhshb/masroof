package com.baraa.masroof.application.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardProjectionBuilderTest {
    private val marketRate = ForeignSarMarketRateProvider { _, _ -> BigDecimal("3.80") }

    @Test
    fun build_writesNothing_andShowsInMemoryExchangeRates() = runBlocking<Unit> {
        seededWorld().use { world ->
            val stored = world.ftRepo.listAll()
            val builder = world.projectionBuilder(marketRateProvider = marketRate)

            val shown = DashboardLedgerWorld.SYNTHETIC_MONTHS
                .map { FinancialPeriodPolicy.periodContaining(it.atDay(10)) }
                .distinct()
                .flatMap { period -> builder.build(period, transactionsIn(world, period)).transactions }
                .filter { it.amount.currency != Currency.SAR }

            assertTrue(shown.isNotEmpty())
            assertTrue(shown.all { it.appliedExchangeRate != null && it.exchangeRateSource != null })
            assertEquals(
                ExchangeRateSource.entries.toSet(),
                shown.mapNotNull { it.exchangeRateSource }.toSet(),
            )
            assertEquals(stored, world.ftRepo.listAll())
        }
    }

    @Test
    fun failedRateResolution_leavesDatabaseUntouched() = runBlocking<Unit> {
        seededWorld().use { world ->
            val period = FinancialPeriodPolicy.periodContaining(YearMonth.of(2026, 9).atDay(10))
            val requested = Collections.synchronizedList(mutableListOf<LocalDate>())
            val probe = world.projectionBuilder(
                marketRateProvider = { _, date -> requested += date; BigDecimal("3.80") },
            ).build(period, transactionsIn(world, period))
            // In-period rows resolve from SMS/merchant history; only the earlier card statement
            // window needs a market rate, so a failure there comes after in-period resolution.
            assertTrue(requested.isNotEmpty() && requested.all { it.isBefore(period.startDate) })
            assertTrue(
                probe.transactions.any { shown ->
                    shown.appliedExchangeRate != null && world.ftRepo.getById(shown.id)!!.appliedExchangeRate == null
                },
            )

            val stored = world.ftRepo.listAll()
            val failing = world.projectionBuilder(
                marketRateProvider = { _, _ -> error("rate service unavailable") },
                financialTransactionRepository = world.ftRepo,
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking { failing.build(period, transactionsIn(world, period)) }
            }
            assertEquals(stored, world.ftRepo.listAll())
        }
    }

    private suspend fun seededWorld(): DashboardLedgerWorld =
        DashboardLedgerWorld(ApplicationProvider.getApplicationContext<Context>()).apply {
            importFixtureCorpus()
            seedSyntheticHistory(DashboardLedgerWorld.SYNTHETIC_MONTHS)
        }

    private suspend fun transactionsIn(
        world: DashboardLedgerWorld,
        period: FinancialPeriod,
    ): List<FinancialTransaction> =
        world.ftRepo.listOccurredBetween(
            startInclusive = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, world.zone),
            endExclusive = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, world.zone),
        )
}
