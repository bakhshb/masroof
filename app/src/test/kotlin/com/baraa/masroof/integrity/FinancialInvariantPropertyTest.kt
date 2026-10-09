package com.baraa.masroof.integrity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.GoldenLedgerCorpus
import com.baraa.masroof.application.dashboard.GoldenLedgerRunner
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persisted-ledger invariants over the golden corpus and seeded import orders.
 * Totals come from [GoldenLedgerRunner], which reads the dashboard projection.
 * The pending M8 as-of rate oracle is not activated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinancialInvariantPropertyTest {
    @Test
    fun activeGoldenScenarios_matchOracle_inMoreThanOneOrder() = runBlocking {
        val active = GoldenLedgerCorpus.loadAll().filter { it.status == "ACTIVE" }
        assertEquals(6, active.size)
        for (scenario in active) {
            val orders = if (scenario.messages.isEmpty()) {
                listOf(IntegritySeeds.FORWARD to emptyList())
            } else {
                seededOrders(IntegritySeeds.FORWARD, GoldenLedgerRunner.providerRows(scenario))
            }
            assertTrue(
                "seed=${IntegritySeeds.FORWARD} ${scenario.id} needs two orders",
                scenario.messages.isEmpty() || orders.size >= 2,
            )
            for ((seed, rows) in orders) {
                DashboardLedgerWorld(context()).use { world ->
                    prepareShuffled(world, scenario, seed, rows)
                    val first = GoldenLedgerRunner.read(world, scenario)
                    if (!first.matches(scenario.expected)) {
                        fail(
                            "seed=$seed ${scenario.id} diverged\n${first.canonical()}",
                        )
                    }
                    assertFinancialInvariants(world, seed, scenario.id)
                    if (scenario.messages.isNotEmpty() && seed == IntegritySeeds.FORWARD) {
                        GoldenLedgerRunner.requireSuccessfulReprocess(
                            id = "${scenario.id} seed=$seed",
                            results = world.reprocessStoredEvidence(),
                        )
                        val replayed = GoldenLedgerRunner.read(world, scenario)
                        assertEquals(
                            "seed=$seed ${scenario.id} replay changed the ledger",
                            first.fingerprint(),
                            replayed.fingerprint(),
                        )
                        assertFinancialInvariants(world, seed, "${scenario.id} replay")
                    }
                }
            }
        }
    }

    @Test
    fun duplicateLiveInboxCapture_postsOneMovement_inBothOrders() = runBlocking {
        val scenario = GoldenLedgerCorpus.load("live_inbox_twin")
        val rows = GoldenLedgerRunner.providerRows(scenario)
        for ((seed, ordered) in seededOrders(IntegritySeeds.LIVE_INBOX, rows)) {
            DashboardLedgerWorld(context()).use { world ->
                prepareShuffled(world, scenario, seed, ordered)
                val snapshot = GoldenLedgerRunner.read(world, scenario)
                if (!snapshot.matches(scenario.expected)) {
                    fail("seed=$seed live/inbox twin diverged\n${snapshot.canonical()}")
                }
                assertEquals("seed=$seed raw sms", 1, world.rawRepo.listIdsByReceivedAt().size)
                assertEquals("seed=$seed movements", 1, world.ftRepo.listAll().size)
                assertFinancialInvariants(world, seed, "live_inbox_twin")
            }
        }
    }

    @Test
    fun sameAmountInsideWindow_isFleetNeutral_andAfterWindow_staysDistinct() = runBlocking {
        val inside = GoldenLedgerCorpus.load("one_intra_pair")
        for ((seed, rows) in seededOrders(IntegritySeeds.INSIDE_WINDOW, GoldenLedgerRunner.providerRows(inside))) {
            DashboardLedgerWorld(context()).use { world ->
                prepareShuffled(world, inside, seed, rows)
                val snapshot = GoldenLedgerRunner.read(world, inside)
                if (!snapshot.matches(inside.expected)) {
                    fail("seed=$seed inside-window pair diverged\n${snapshot.canonical()}")
                }
                assertEquals("seed=$seed self-transfers", 1, world.ftRepo.listAll().size)
                assertEquals(
                    "seed=$seed verified self-transfer links",
                    2,
                    world.ftRepo.listRawSmsIds(world.ftRepo.listAll().single().id).size,
                )
                assertEquals("seed=$seed fleet inflow", "0.00", snapshot.accountsFleetInflow)
                assertEquals("seed=$seed fleet outflow", "0.00", snapshot.accountsFleetOutflow)
                assertEquals("seed=$seed salary fabricated", "0.00", snapshot.metrics["account:BANK_ALJAZIRA:3001:salary"])
                assertEquals(
                    "seed=$seed outside spending",
                    "0.00",
                    snapshot.metrics["account:BANK_ALJAZIRA:3001:externalTransfersOut"],
                )
                assertFinancialInvariants(world, seed, "inside_window")
            }
        }

        DashboardLedgerWorld(context()).use { world ->
            ownAccounts(world, listOf("3001", "3002"))
            val seed = IntegritySeeds.AFTER_WINDOW
            val rows = listOf(
                providerRow("after-out", intraOut("2026-09-03 10:38"), "2026-09-03T07:38:00Z"),
                providerRow("after-in", intraIn("2026-09-03 10:49"), "2026-09-03T07:49:00Z"),
            )
            GoldenLedgerRunner.requireSuccessfulImport(
                id = "after-window seed=$seed",
                imported = world.importProviderRows(rows),
                label = "import",
            )
            val transactions = world.ftRepo.listAll()
            assertEquals("seed=$seed outside-window movements", 2, transactions.size)
            for (transaction in transactions) {
                assertEquals(
                    "seed=$seed outside-window movement merged both SMS",
                    1,
                    world.ftRepo.listRawSmsIds(transaction.id).size,
                )
            }
            val snapshot = GoldenLedgerRunner.read(world, periodScenario("after_window"))
            assertEquals("seed=$seed salary", "0.00", snapshot.metrics["account:BANK_ALJAZIRA:3001:salary"])
            assertEquals("seed=$seed other income", "0.00", snapshot.metrics["account:BANK_ALJAZIRA:3001:otherIncome"])
            assertEquals("seed=$seed spending", "0.00", snapshot.spendingGross)
            assertFinancialInvariants(world, seed, "after_window")
        }

        val separated = GoldenLedgerCorpus.load("m1_two_day_same_amount")
        for ((seed, rows) in seededOrders(IntegritySeeds.FORWARD, GoldenLedgerRunner.providerRows(separated))) {
            DashboardLedgerWorld(context()).use { world ->
                prepareShuffled(world, separated, seed, rows)
                val snapshot = GoldenLedgerRunner.read(world, separated)
                if (!snapshot.matches(separated.expected)) {
                    fail("seed=$seed two-day same amount diverged\n${snapshot.canonical()}")
                }
                assertEquals("seed=$seed distinct self-transfers", 2, world.ftRepo.listAll().size)
                assertEquals("seed=$seed fleet inflow", "0.00", snapshot.accountsFleetInflow)
                assertEquals("seed=$seed fleet outflow", "0.00", snapshot.accountsFleetOutflow)
                assertFinancialInvariants(world, seed, "two_day_same_amount")
            }
        }
    }

    @Test
    fun unresolvableSameMinuteTie_staysReviewable_acrossOrders() = runBlocking {
        val rows = listOf(
            providerRow("tie-out-a", intraOut("2026-09-02 10:00"), "2026-09-02T07:00:00Z"),
            providerRow("tie-out-b", intraOut("2026-09-02 10:00"), "2026-09-02T07:00:01Z"),
            providerRow("tie-in-a", intraIn("2026-09-02 10:00"), "2026-09-02T07:00:02Z"),
            providerRow("tie-in-b", intraIn("2026-09-02 10:00"), "2026-09-02T07:00:03Z"),
        )
        for ((seed, ordered) in seededOrders(IntegritySeeds.AMBIGUOUS_TIE, rows)) {
            DashboardLedgerWorld(context()).use { world ->
                ownAccounts(world, listOf("3001", "3002"))
                GoldenLedgerRunner.requireSuccessfulImport(
                    id = "ambiguous-tie seed=$seed",
                    imported = world.importProviderRows(ordered),
                    label = "import",
                )
                assertTrue("seed=$seed tie posted a movement", world.ftRepo.listAll().isEmpty())
                val required = world.reviewRepo.listRequired()
                assertEquals("seed=$seed pending reviews", rows.size, required.size)
                assertTrue(
                    "seed=$seed tie review kind ${required.map { it.kind }}",
                    required.all { it.kind == ReviewKind.PENDING_MATCH && it.status == ReviewStatus.REQUIRED },
                )
                val snapshot = GoldenLedgerRunner.read(world, periodScenario("ambiguous_tie"))
                assertEquals("seed=$seed fleet inflow", "0.00", snapshot.accountsFleetInflow)
                assertEquals("seed=$seed fleet outflow", "0.00", snapshot.accountsFleetOutflow)
                assertEquals("seed=$seed other income", "0.00", snapshot.metrics["account:BANK_ALJAZIRA:3001:otherIncome"])
                assertFinancialInvariants(world, seed, "ambiguous_tie")
            }
        }
    }

    @Test
    fun informationalSms_stayUnposted_andUnrecognizedSender_isOutsideCoverage() = runBlocking {
        val seed = IntegritySeeds.FORWARD
        DashboardLedgerWorld(context()).use { world ->
            ownAccounts(world, listOf("3001"))
            ownCard(world, "2210", CardType.DEBIT)
            val recognized = listOf(
                providerRow("cov-purchase", bakeryPurchaseBody(), "2026-09-05T06:15:00Z"),
                providerRow("cov-otp", otpBody(), "2026-09-05T06:20:00Z"),
                providerRow("cov-balance", balanceNoticeBody(), "2026-09-04T05:00:00Z"),
                providerRow("cov-unknown", unknownBankNoticeBody(), "2026-09-04T05:05:00Z"),
            )
            val ignored = providerRow("cov-friend", "See you at 7", "2026-09-04T05:06:00Z", sender = "Personal")
            val batch = world.importProviderRows(recognized + ignored)
            assertTrue(
                "seed=$seed unrecognized sender was captured",
                batch.ingest.any { it is com.baraa.masroof.application.ingestion.SmsIngestionResult.NotRelevant },
            )
            GoldenLedgerRunner.requireSuccessfulImport(
                id = "coverage seed=$seed",
                imported = batch,
                label = "import",
            )
            assertNull(
                "seed=$seed unrecognized raw row",
                world.rawRepo.listIdsByReceivedAt().firstOrNull { id ->
                    world.rawRepo.getById(id)?.sender == "Personal"
                },
            )
            val families = world.parsedRepo.listAll().associate { it.event.rawSmsId to it.event.messageFamily }
            assertTrue("seed=$seed otp missing", families.containsValue(MessageFamily.OTP))
            assertTrue("seed=$seed balance missing", families.containsValue(MessageFamily.BALANCE_NOTICE))
            assertTrue("seed=$seed unknown missing", families.containsValue(MessageFamily.UNKNOWN))
            val postedFamilies = world.ftRepo.listAll().flatMap { tx ->
                world.ftRepo.listRawSmsIds(tx.id).map { rawId ->
                    world.parsedRepo.findByRawSmsId(rawId)?.event?.messageFamily
                }
            }
            assertEquals(
                "seed=$seed only the purchase posts",
                listOf(MessageFamily.PURCHASE),
                postedFamilies,
            )
            assertFinancialInvariants(world, seed, "coverage")

            val again = AndroidSmsMapper.toRawSms(ignored)
            assertTrue(
                "seed=$seed second unrecognized capture persisted",
                world.captureBankSms.capture(again) is BankSmsCaptureResult.NotRelevant,
            )
            assertEquals("seed=$seed raw count changed", recognized.size, world.rawRepo.listIdsByReceivedAt().size)
        }
    }

    @Test
    fun sarAndForeignAmounts_areNotAddedTogether() = runBlocking {
        val seed = IntegritySeeds.CURRENCY
        val sarScenario = GoldenLedgerCorpus.load("live_inbox_twin")
        DashboardLedgerWorld(context()).use { world ->
            ownCard(world, "7271", CardType.CREDIT)
            val rows = GoldenLedgerRunner.providerRows(sarScenario) + providerRow(
                providerMessageId = "fx-sep-6",
                body = usdPurchaseBody(),
                receivedAt = "2026-09-06T07:00:00Z",
            )
            prepareShuffled(world, sarScenario, seed, rows)
            ownCard(world, "7271", CardType.CREDIT)
            val snapshot = GoldenLedgerRunner.read(world, sarScenario)
            val stored = world.ftRepo.listAll()
            val sar = stored.single { it.amount.currency == Currency.SAR }
            val usd = stored.single { it.amount.currency == Currency.USD }
            assertEquals("seed=$seed SAR movement", Money.of("15.50", Currency.SAR), sar.amount)
            assertEquals("seed=$seed USD movement", Money.of("10.00", Currency.USD), usd.amount)
            assertEquals("seed=$seed SAR account outflow", "15.50", snapshot.accountsFleetOutflow)
            assertEquals("seed=$seed SAR spending", sarScenario.expected.spendingGross, snapshot.spendingGross)
            assertEquals("seed=$seed excluded foreign rows", 1, snapshot.excludedOtherCurrencyCount)
            assertNotEquals(
                "seed=$seed fleet outflow combined 15.50 SAR with 10.00 USD",
                "25.50",
                snapshot.accountsFleetOutflow,
            )
            assertNotEquals(
                "seed=$seed spending combined 15.50 SAR with 10.00 USD",
                "25.50",
                snapshot.spendingGross,
            )
            assertFinancialInvariants(world, seed, "currency_separation")
        }
    }

    @Test
    fun pendingM8_keepsStoredCurrency_andDoesNotUseThePendingOracle() = runBlocking {
        val scenario = GoldenLedgerCorpus.load("m8_asof_fx")
        assertEquals("PENDING", scenario.status)
        assertEquals("M8", scenario.ownerMilestone)
        val seed = IntegritySeeds.CURRENCY
        DashboardLedgerWorld(context()).use { world ->
            GoldenLedgerRunner.prepare(world, scenario)
            val stored = world.ftRepo.listAll()
            assertEquals("seed=$seed m8 movements", scenario.expected.transactions.size, stored.size)
            assertTrue(
                "seed=$seed m8 stored a non-USD amount ${stored.map { it.amount }}",
                stored.all { it.amount == Money.of("10.00", Currency.USD) },
            )
            assertTrue(
                "seed=$seed m8 type",
                stored.all { it.type == FinancialTransactionType.EXPENSE },
            )
            assertFinancialInvariants(world, seed, "pending_m8_structure")
            val snapshot = GoldenLedgerRunner.read(world, scenario)
            assertTrue(
                "seed=$seed m8 must stay pending until its owner activates the oracle",
                scenario.knownDefect!!.signals.any { (key, broken) -> snapshot.metrics[key] == broken },
            )
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
