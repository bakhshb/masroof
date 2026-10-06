package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.testsupport.AlJaziraFixtureInbox
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExchangeRateEnrichmentWorkflowTest {
    private val marketRate = ForeignSarMarketRateProvider { _, _ -> BigDecimal("3.80") }

    @Test
    fun enrichPending_persistsTheRatesTheDashboardShows() = runBlocking<Unit> {
        seededWorld().use { world ->
            val service = world.dashboardService(marketRateProvider = marketRate)
            val periods = DashboardLedgerWorld.SYNTHETIC_MONTHS
                .map { FinancialPeriodPolicy.periodContaining(it.atDay(10)) }
                .distinct()
            val beforeEnrichment = periods.map { service.loadProjection(it) }
            val shownRates = beforeEnrichment.flatMap { it.transactions }
                .filter { it.appliedExchangeRate != null }
                .associate { it.id to (it.appliedExchangeRate to it.exchangeRateSource) }
            assertEquals(ExchangeRateSource.entries.toSet(), shownRates.values.mapNotNull { it.second }.toSet())

            val result = world.exchangeRateEnrichmentWorkflow(marketRate).enrichPending()

            assertTrue(result.pending >= shownRates.size)
            assertEquals(result.pending, result.persisted)
            shownRates.forEach { (id, shown) ->
                val stored = world.ftRepo.getById(id)!!
                assertEquals(id, shown, stored.appliedExchangeRate to stored.exchangeRateSource)
            }
            assertTrue(world.ftRepo.listAwaitingAppliedExchangeRate(Currency.SAR).isEmpty())
            assertEquals(beforeEnrichment, periods.map { service.loadProjection(it) })
        }
    }

    @Test
    fun enrichPending_isIdempotent_andNeverOverwritesPersistedRates() = runBlocking<Unit> {
        seededWorld().use { world ->
            val preset = world.ftRepo.listAwaitingAppliedExchangeRate(Currency.SAR).first()
            world.ftRepo.updateAppliedExchangeRate(preset.id, BigDecimal("9.99"), ExchangeRateSource.MARKET)
            val workflow = world.exchangeRateEnrichmentWorkflow(marketRate)

            val first = workflow.enrichPending()
            val second = workflow.enrichPending()

            assertTrue(first.persisted > 0)
            assertEquals(ExchangeRateEnrichmentResult(pending = 0, persisted = 0), second)
            assertEquals(BigDecimal("9.99"), world.ftRepo.getById(preset.id)!!.appliedExchangeRate)
        }
    }

    @Test
    fun unresolvedRows_stayPendingForTheNextRun() = runBlocking<Unit> {
        seededWorld().use { world ->
            val offline = world.exchangeRateEnrichmentWorkflow(DashboardLedgerWorld.NO_MARKET_RATE).enrichPending()
            val remaining = world.ftRepo.listAwaitingAppliedExchangeRate(Currency.SAR)

            assertTrue(offline.persisted in 1 until offline.pending)
            assertEquals(offline.pending - offline.persisted, remaining.size)

            val online = world.exchangeRateEnrichmentWorkflow(marketRate).enrichPending()

            assertEquals(ExchangeRateEnrichmentResult(pending = remaining.size, persisted = remaining.size), online)
            remaining.forEach {
                assertEquals(ExchangeRateSource.MARKET, world.ftRepo.getById(it.id)!!.exchangeRateSource)
            }
        }
    }

    @Test
    fun failingResolution_writesNothing() = runBlocking<Unit> {
        seededWorld().use { world ->
            val stored = world.ftRepo.listAll()
            val workflow = world.exchangeRateEnrichmentWorkflow(
                marketRateProvider = { _, _ -> error("rate service unavailable") },
            )

            assertThrows(IllegalStateException::class.java) { runBlocking { workflow.enrichPending() } }
            assertEquals(stored, world.ftRepo.listAll())
        }
    }

    @Test
    fun historicalBatch_persistsRatesOnceAfterImport() = runBlocking<Unit> {
        DashboardLedgerWorld(context()).use { world ->
            world.importFixtureCorpus()
            assertTrue(world.ftRepo.listAwaitingAppliedExchangeRate(Currency.SAR).isNotEmpty())
        }
        DashboardLedgerWorld(context()).use { world ->
            world.importFixtureCorpus(exchangeRateEnrichment = world.exchangeRateEnrichmentWorkflow(marketRate))

            assertTrue(world.ftRepo.listAwaitingAppliedExchangeRate(Currency.SAR).isEmpty())
            assertTrue(world.ftRepo.listAll().any { it.exchangeRateSource == ExchangeRateSource.MARKET })
        }
    }

    @Test
    fun storedSmsProcessing_persistsRatesAfterReconciliation() = runBlocking<Unit> {
        DashboardLedgerWorld(context()).use { world ->
            world.ownFixtureInstruments()
            val row = AlJaziraFixtureInbox.rows().first { "USD" in it.body }
            val captured = world.captureBankSms.capture(AndroidSmsMapper.toRawSms(row))
                as BankSmsCaptureResult.Captured

            world.processStoredSms(world.exchangeRateEnrichmentWorkflow(marketRate)).process(captured.rawSms.id)

            val transaction = world.ftRepo.findByRawSmsId(captured.rawSms.id)
            assertNotNull(transaction)
            assertEquals(Currency.USD, transaction!!.amount.currency)
            assertEquals(BigDecimal("3.80"), transaction.appliedExchangeRate)
            assertEquals(ExchangeRateSource.MARKET, transaction.exchangeRateSource)
        }
    }

    private suspend fun seededWorld(): DashboardLedgerWorld =
        DashboardLedgerWorld(context()).apply {
            importFixtureCorpus()
            seedSyntheticHistory(DashboardLedgerWorld.SYNTHETIC_MONTHS)
        }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
