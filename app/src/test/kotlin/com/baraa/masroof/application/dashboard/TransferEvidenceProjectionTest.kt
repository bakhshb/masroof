package com.baraa.masroof.application.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Dashboard projection of real AlJazira transfer SMS. Expected totals are the
 * reviewed movements below, not a copy of whatever the deduplicator returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferEvidenceProjectionTest {
    @Test
    fun repeatedSelfTransfers_bothAffectMovement_andReplayStaysIdempotent() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            val rows = listOf(
                sms("self-out-aug-03", "2026-08-03T07:38:00Z", intraOut("3001", "3003", "2,000.00", "2026-08-03 10:38")),
                sms("self-in-aug-03", "2026-08-03T07:38:30Z", intraIn("3003", "3001", "2,000.00", "2026-08-03 10:38")),
                sms("self-out-aug-10", "2026-08-10T12:12:00Z", intraOut("3001", "3003", "2,000.00", "2026-08-10 15:12")),
                sms("self-in-aug-10", "2026-08-10T12:12:30Z", intraIn("3003", "3001", "2,000.00", "2026-08-10 15:12")),
            )
            world.importFixtureCorpus(rows)
            val beforeLoad = storedIds(world)
            val projection = load(world, "2026-08-10")
            assertEquals(beforeLoad, storedIds(world))
            assertEquals(beforeLoad, projection.transactions.map { it.id }.sorted())

            val selfTransfers = projection.transactions.filter { it.type == FinancialTransactionType.SELF_TRANSFER }
            assertEquals(2, selfTransfers.size)
            assertEquals(setOf(Money.of("2000.00", Currency.SAR)), selfTransfers.map { it.amount }.toSet())
            val source = account(projection, "3001")
            val destination = account(projection, "3003")
            assertEquals(Money.of("4000.00", Currency.SAR), source.summary.outflow.selfTransfersOut)
            assertEquals(Money.of("4000.00", Currency.SAR), destination.summary.inflow.selfTransfersIn)
            assertEquals(Money.zero(Currency.SAR), projection.accountsFleet.totalOutflow)

            val stored = fingerprint(world)
            world.importFixtureCorpus(rows)
            assertEquals(stored, fingerprint(world))
            world.rawRepo.listIdsByReceivedAt().forEach { rawSmsId ->
                world.processStoredSms().process(rawSmsId, logOutcome = false)
            }
            assertEquals(stored, fingerprint(world))
            assertEquals(beforeLoad, load(world, "2026-08-10").transactions.map { it.id }.sorted())
        }
    }

    @Test
    fun closeRepeatedSelfTransfers_areNotCollapsedInsideTheMatchWindow() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            val rows = listOf(
                sms("near-out-a", "2026-08-03T07:38:00Z", intraOut("3001", "3003", "2,000.00", "2026-08-03 10:38")),
                sms("near-in-a", "2026-08-03T07:38:20Z", intraIn("3003", "3001", "2,000.00", "2026-08-03 10:38")),
                sms("near-out-b", "2026-08-03T07:46:00Z", intraOut("3001", "3003", "2,000.00", "2026-08-03 10:46")),
                sms("near-in-b", "2026-08-03T07:46:20Z", intraIn("3003", "3001", "2,000.00", "2026-08-03 10:46")),
            )
            world.importFixtureCorpus(rows)
            val persisted = storedIds(world)
            val projection = load(world, "2026-08-10")
            // Two movements inside the match window are not a mutually unique pair, so
            // reconciliation leaves each SMS posted. Amount and endpoints must not hide any of them.
            assertEquals(persisted, projection.transactions.map { it.id }.sorted())
            assertEquals(4, projection.transactions.size)
            assertEquals(
                setOf(FinancialTransactionType.SELF_TRANSFER),
                projection.transactions.map { it.type }.toSet(),
            )
            assertEquals(
                setOf(Money.of("2000.00", Currency.SAR)),
                projection.transactions.map { it.amount }.toSet(),
            )
        }
    }

    @Test
    fun unrelatedExternalTransfer_sameAmount_staysInFleetOutflow() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            val rows = listOf(
                sms("self-out", "2026-08-03T07:38:00Z", intraOut("3001", "3003", "2,000.00", "2026-08-03 10:38")),
                sms("self-in", "2026-08-03T07:38:30Z", intraIn("3003", "3001", "2,000.00", "2026-08-03 10:38")),
                sms(
                    "external-out",
                    "2026-08-04T09:26:00Z",
                    """
                    عملية حوالة مالية صادرة مقبولة
                    خصمت من حساب: 3001
                    الى: TEST_BENEFICIARY
                    مبلغ العملية: 2,000.00 SAR
                    المعرف البديل \الايبان : 0593
                    [البنك العربي الوطني]
                    في: 2026-08-04 12:26
                    رقم المعاملة: TEST_REFERENCE_EXT
                    """.trimIndent(),
                ),
            )
            world.importFixtureCorpus(rows)
            val persisted = world.ftRepo.listAll()
            val projection = load(world, "2026-08-10")
            assertEquals(persisted.map { it.id }.sorted(), storedIds(world))
            assertEquals(persisted.map { it.id }.sorted(), projection.transactions.map { it.id }.sorted())
            assertEquals(1, projection.transactions.count { it.type == FinancialTransactionType.SELF_TRANSFER })
            assertEquals(1, projection.transactions.count { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT })
            val source = account(projection, "3001")
            assertEquals(Money.of("2000.00", Currency.SAR), source.summary.outflow.selfTransfersOut)
            assertEquals(Money.of("2000.00", Currency.SAR), source.summary.outflow.externalTransfersOut)
            assertEquals(Money.of("2000.00", Currency.SAR), projection.accountsFleet.totalOutflow)
        }
    }

    @Test
    fun oneMovement_twoSmsLegs_shownOnce_acrossReimportAndRestart() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            val rows = listOf(
                sms("leg-out", "2026-08-11T06:00:00Z", intraOut("3001", "3002", "5,500.00", "2026-08-11 09:00")),
                sms("leg-in", "2026-08-11T06:00:20Z", intraIn("3002", "3001", "5,500.00", "2026-08-11 09:00")),
            )
            world.importFixtureCorpus(rows)
            val stored = fingerprint(world)
            val projection = load(world, "2026-08-15")
            assertEquals(listOf(FinancialTransactionType.SELF_TRANSFER), projection.transactions.map { it.type })
            assertEquals(Money.of("5500.00", Currency.SAR), projection.transactions.single().amount)
            assertEquals(Money.of("5500.00", Currency.SAR), account(projection, "3001").summary.outflow.selfTransfersOut)
            assertEquals(Money.of("5500.00", Currency.SAR), account(projection, "3002").summary.inflow.selfTransfersIn)
            assertEquals(Money.zero(Currency.SAR), projection.accountsFleet.totalOutflow)

            world.importFixtureCorpus(rows)
            assertEquals(stored, fingerprint(world))
            world.rawRepo.listIdsByReceivedAt().forEach { rawSmsId ->
                world.processStoredSms().process(rawSmsId, logOutcome = false)
            }
            assertEquals(stored, fingerprint(world))
            assertEquals(1, load(world, "2026-08-15").transactions.size)
        }
    }

    private suspend fun load(world: DashboardLedgerWorld, periodAnchor: String) =
        world.dashboardService().loadProjection(
            FinancialPeriodPolicy.periodContaining(LocalDate.parse(periodAnchor)),
        )

    private suspend fun storedIds(world: DashboardLedgerWorld): List<String> =
        world.ftRepo.listAll().map { it.id }.sorted()

    private suspend fun fingerprint(world: DashboardLedgerWorld): List<String> =
        world.ftRepo.listAll()
            .sortedBy { it.id }
            .map { tx -> movementKey(tx) }

    private fun movementKey(tx: FinancialTransaction): String =
        listOf(
            tx.id,
            tx.type.name,
            tx.amount.amount.toPlainString(),
            tx.sourceContainerId ?: "-",
            tx.destinationContainerId ?: "-",
            tx.linkedParsedEventIds.sorted().joinToString("+"),
        ).joinToString("|")

    private fun account(projection: DashboardProjection, masked: String) =
        projection.perAccount.single { it.maskedNumber == masked }

    private fun sms(id: String, receivedAt: String, body: String) = ProviderSmsRecord(
        providerMessageId = id,
        sender = "AlJazira",
        body = body,
        receivedAt = Instant.parse(receivedAt),
    )

    private fun intraOut(source: String, destination: String, amount: String, at: String) = """
        حوالة صادرة الى حسابك الجاري
        من: $source
        مبلغ: SAR $amount
        إلى: $destination
        في: $at
    """.trimIndent()

    private fun intraIn(destination: String, source: String, amount: String, at: String) = """
        حوالة واردة داخلية
        مبلغ: SAR $amount
        إلى: $destination
        اسم المرسل: TEST_PERSON
        رقم حساب المرسل: $source
        البنك المرسل: بنك الجزيرة
        في: $at
    """.trimIndent()

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
