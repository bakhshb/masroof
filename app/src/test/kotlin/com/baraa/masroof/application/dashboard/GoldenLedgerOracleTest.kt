package com.baraa.masroof.application.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Small golden ledger. Active scenarios hard-assert independent totals through
 * [DashboardLedgerWorld].
 *
 * Re-import plus stored-SMS processing is called reprocessing here. It is not an
 * Android process restart. Active oracles require both steps to complete
 * successfully, then a stable ledger fingerprint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenLedgerOracleTest {
    @Test
    fun corpus_keepsActiveOraclesIndependent_andNamesPendingOwners() {
        val scenarios = GoldenLedgerCorpus.loadAll()
        val active = scenarios.filter { it.status == "ACTIVE" }
        val pending = scenarios.filter { it.status == "PENDING" }
        assertTrue("expected 7 active baselines, was ${active.map { it.id }}", active.size == 7)
        assertTrue("expected no pending oracles, was ${pending.map { it.id }}", pending.isEmpty())
        active.forEach { scenario ->
            assertNull(scenario.ownerMilestone)
            assertNull(scenario.knownDefect)
            if (scenario.id == "m5_cross_bank_suffix") {
                assertTrue(scenario.seedRows.isNotEmpty())
                assertTrue(scenario.messages.isEmpty())
            } else {
                assertTrue(scenario.messages.isNotEmpty())
                assertTrue(scenario.seedRows.isEmpty())
            }
        }
    }

    @Test
    fun oneIntraPair_matchesOracle_acrossReprocessing() = runBlocking {
        assertActive("one_intra_pair")
    }

    @Test
    fun repeatedSeparateNotices_matchOracle_acrossReprocessing() = runBlocking {
        assertActive("repeated_separate_notices")
    }

    @Test
    fun liveInboxTwin_matchesOracle_acrossReprocessing() = runBlocking {
        assertActive("live_inbox_twin")
    }

    @Test
    fun twoDaySameAmount_matchesOracle_acrossReprocessing() = runBlocking {
        assertActive("m1_two_day_same_amount")
    }

    @Test
    fun crossBankSuffix_matchesOracle() = runBlocking {
        assertActiveSeeded("m5_cross_bank_suffix")
    }

    @Test
    fun accountRefund_matchesOracle_acrossReprocessing() = runBlocking {
        assertActive("m6_account_refund")
    }

    @Test
    fun asOfFx_matchesOracle_acrossReprocessing() = runBlocking {
        assertActive("m8_asof_fx")
    }

    private suspend fun assertActive(id: String) {
        val scenario = GoldenLedgerCorpus.load(id)
        assertEquals("ACTIVE", scenario.status)
        DashboardLedgerWorld(context()).use { world ->
            GoldenLedgerRunner.prepare(world, scenario)
            val first = GoldenLedgerRunner.read(world, scenario)
            assertMatches(scenario, first)
            GoldenLedgerRunner.requireSuccessfulImport(
                id = id,
                imported = world.importProviderRows(GoldenLedgerRunner.providerRows(scenario)),
                label = "reimport",
            )
            GoldenLedgerRunner.requireSuccessfulReprocess(
                id = id,
                results = world.reprocessStoredEvidence(),
            )
            val afterReprocessing = GoldenLedgerRunner.read(world, scenario)
            assertEquals(
                "$id reprocessing changed the ledger fingerprint",
                first.fingerprint(),
                afterReprocessing.fingerprint(),
            )
            assertMatches(scenario, afterReprocessing)
        }
    }

    /**
     * OTHER_BANK has no SMS adapter. Reprocessing this fixture through the sole
     * AlJazira adapter would replace the stored bank. The oracle checks the
     * posted, bank-qualified ledger twice; the fingerprint must stay put.
     */
    private suspend fun assertActiveSeeded(id: String) {
        val scenario = GoldenLedgerCorpus.load(id)
        assertEquals("ACTIVE", scenario.status)
        DashboardLedgerWorld(context()).use { world ->
            GoldenLedgerRunner.prepare(world, scenario)
            val first = GoldenLedgerRunner.read(world, scenario)
            assertMatches(scenario, first)
            val again = GoldenLedgerRunner.read(world, scenario)
            assertEquals(
                "$id projection changed the ledger fingerprint",
                first.fingerprint(),
                again.fingerprint(),
            )
            assertMatches(scenario, again)
        }
    }

    private fun assertMatches(scenario: GoldenLedgerScenario, snapshot: GoldenLedgerSnapshot) {
        if (!snapshot.matches(scenario.expected)) {
            fail(
                "${scenario.id} diverged from the oracle\n" +
                    "expected raw=${scenario.expected.rawSmsCount} " +
                    "parsed=${scenario.expected.parsed.map(::parsedLine).sorted()} " +
                    "tx=${scenario.expected.transactions.map(::movementLine).sorted()} " +
                    "rows=${scenario.expected.displayedRows.map(::movementLine).sorted()} " +
                    "accounts=${scenario.expected.accounts.map(::accountLine).sorted()}\n" +
                    "actual\n${snapshot.canonical()}",
            )
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
