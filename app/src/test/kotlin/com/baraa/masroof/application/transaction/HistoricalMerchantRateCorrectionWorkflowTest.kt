package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoricalMerchantRateCorrectionWorkflowTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val secretBody = "do-not-log-this-sms-account-9999"

    @Test
    fun unconfirmedCandidate_staysFrozen_andEnrichPendingCannotReplaceIt() = runBlocking<Unit> {
        seeded().use { harness ->
            val workflow = harness.workflow
            val before = harness.world.ftRepo.getById(CORRECTABLE)!!
            assertEquals(0, BigDecimal("3.50").compareTo(before.appliedExchangeRate))

            val report = workflow.listCandidates()
            assertEquals(before, harness.world.ftRepo.getById(CORRECTABLE))
            assertTrue(report.candidates.any { it.transactionId == CORRECTABLE && !it.requiresManualFollowUp })
            assertTrue(report.candidates.any { it.transactionId == UNCONFIRMED && !it.requiresManualFollowUp })
            assertTrue(report.candidates.any { it.transactionId == MANUAL && it.requiresManualFollowUp })

            assertFalse(
                harness.world.ftRepo.updateAppliedExchangeRate(
                    CORRECTABLE,
                    BigDecimal("1.11"),
                    ExchangeRateSource.SMS,
                ),
            )
            harness.world.exchangeRateEnrichmentWorkflow().enrichPending()

            val afterEnrichment = harness.world.ftRepo.getById(CORRECTABLE)!!
            assertEquals(0, BigDecimal("3.50").compareTo(afterEnrichment.appliedExchangeRate))
            assertEquals(ExchangeRateSource.HISTORICAL_MERCHANT, afterEnrichment.exchangeRateSource)
            assertTrue(harness.logs.readAll().none { secretBody in it.message })
        }
    }

    @Test
    fun confirm_logsAndReplacesOnlyTheRequestedPair() = runBlocking<Unit> {
        seeded().use { harness ->
            val workflow = harness.workflow
            val result = workflow.confirm(listOf(CORRECTABLE))

            assertEquals(listOf(CORRECTABLE), result.updated)
            assertTrue(result.manualFollowUp.isEmpty())
            val corrected = harness.world.ftRepo.getById(CORRECTABLE)!!
            assertEquals(0, BigDecimal("4.00").compareTo(corrected.appliedExchangeRate))
            assertEquals(ExchangeRateSource.HISTORICAL_MERCHANT, corrected.exchangeRateSource)

            val unconfirmed = harness.world.ftRepo.getById(UNCONFIRMED)!!
            assertEquals(0, BigDecimal("3.10").compareTo(unconfirmed.appliedExchangeRate))
            assertEquals(ExchangeRateSource.HISTORICAL_MERCHANT, unconfirmed.exchangeRateSource)

            val manual = harness.world.ftRepo.getById(MANUAL)!!
            assertEquals(0, BigDecimal("4.00").compareTo(manual.appliedExchangeRate))

            val masked = AppLogFormatting.maskId(CORRECTABLE)
            val messages = harness.logs.readAll().map { it.message }
            assertTrue(messages.any { line ->
                "correction before" in line &&
                    masked in line &&
                    "old_rate=3.50" in line &&
                    "new_rate=4.00" in line &&
                    "old_source=HISTORICAL_MERCHANT" in line &&
                    "new_source=HISTORICAL_MERCHANT" in line
            })
            assertTrue(messages.any { line ->
                "correction after" in line &&
                    masked in line &&
                    "old_rate=3.50" in line &&
                    "new_rate=4.00" in line &&
                    "old_source=HISTORICAL_MERCHANT" in line &&
                    "new_source=HISTORICAL_MERCHANT" in line &&
                    "applied=true" in line
            })
            assertTrue(messages.none { CORRECTABLE in it || secretBody in it || "9999" in it })

            val followUp = workflow.confirm(listOf(MANUAL))
            assertEquals(listOf(MANUAL), followUp.manualFollowUp)
            assertTrue(followUp.updated.isEmpty())
            val stillFrozen = harness.world.ftRepo.getById(MANUAL)!!
            assertEquals(0, BigDecimal("4.00").compareTo(stillFrozen.appliedExchangeRate))
            assertEquals(ExchangeRateSource.HISTORICAL_MERCHANT, stillFrozen.exchangeRateSource)
        }
    }

    private suspend fun seeded(): Harness {
        val world = DashboardLedgerWorld(context())
        val logs = AppLogService(context()).also { it.clear() }
        saveRateEvidence(world)
        saveFrozen(world, CORRECTABLE, "2026-10-08T10:00", BigDecimal("3.50"))
        saveFrozen(world, UNCONFIRMED, "2026-10-09T10:00", BigDecimal("3.10"))
        saveFrozen(world, MANUAL, "2026-10-01T10:00", BigDecimal("4.00"))
        val workflow = HistoricalMerchantRateCorrectionWorkflow(
            financialTransactionRepository = world.ftRepo,
            parsedEventRepository = world.parsedRepo,
            rawSmsRepository = world.rawRepo,
            sarEquivalentResolver = TransactionSarEquivalentResolver(DashboardLedgerWorld.NO_MARKET_RATE),
            appLogService = logs,
        )
        return Harness(world, logs, workflow)
    }

    private suspend fun saveRateEvidence(world: DashboardLedgerWorld) {
        val rawId = "sms-oct7-rate"
        world.rawRepo.insertIfAbsent(
            RawSms(
                id = rawId,
                sender = "AlJazira",
                body = secretBody,
                receivedAt = local("2026-10-01T10:00"),
                deviceMessageId = rawId,
                bodyHash = "hash-$rawId",
            ),
        )
        world.parsedRepo.save(
            parsed("pe-oct7-rate", rawId, local("2026-10-01T10:00")),
            ParsedEventDetails(
                exchangeRate = BigDecimal("4.00"),
                occurredAtLocal = LocalDateTime.parse("2026-10-07T10:00"),
            ),
        )
    }

    private suspend fun saveFrozen(
        world: DashboardLedgerWorld,
        id: String,
        localText: String,
        rate: BigDecimal,
    ) {
        val rawId = "sms-$id"
        val at = local(localText)
        world.rawRepo.insertIfAbsent(
            RawSms(
                id = rawId,
                sender = "AlJazira",
                body = secretBody,
                receivedAt = at,
                deviceMessageId = rawId,
                bodyHash = "hash-$rawId",
            ),
        )
        world.parsedRepo.save(
            parsed("pe-$id", rawId, at),
            ParsedEventDetails(occurredAtLocal = LocalDateTime.parse(localText)),
        )
        world.ftRepo.save(
            FinancialTransaction(
                id = id,
                type = FinancialTransactionType.EXPENSE,
                amount = Money.of("10.00", Currency.USD),
                occurredAt = at,
                sourceContainerId = "card:BANK_ALJAZIRA:7271",
                destinationContainerId = null,
                merchant = "TEST_FX_SHOP",
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = listOf("pe-$id"),
                appliedExchangeRate = rate,
                exchangeRateSource = ExchangeRateSource.HISTORICAL_MERCHANT,
                occurredAtZone = zone.id,
            ),
            listOf(rawId),
        )
    }

    private fun parsed(id: String, rawSmsId: String, at: Instant) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = MessageFamily.PURCHASE,
        direction = null,
        amount = Money.of("10.00", Currency.USD),
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = "TEST_FX_SHOP",
        counterparty = null,
        occurredAt = at,
        bankNetworkType = null,
        confidence = Confidence(1.0),
        parseStatus = ParseStatus.SUCCESS,
    )

    private fun local(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private class Harness(
        val world: DashboardLedgerWorld,
        val logs: AppLogService,
        val workflow: HistoricalMerchantRateCorrectionWorkflow,
    ) : AutoCloseable {
        override fun close() {
            world.close()
        }
    }

    private companion object {
        const val CORRECTABLE = "tx-correctable-oct8-aaaa"
        const val UNCONFIRMED = "tx-unconfirmed-oct9-bbbb"
        const val MANUAL = "tx-unconverted-oct1-cccc"
    }
}
