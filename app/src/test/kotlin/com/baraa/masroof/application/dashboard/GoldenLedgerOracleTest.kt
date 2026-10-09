package com.baraa.masroof.application.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * Small golden ledger. Active scenarios hard-assert independent totals through
 * [DashboardLedgerWorld]. Pending scenarios belong to a named later milestone and
 * stay exempt only while the ledger still misses that milestone's oracle.
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
        assertTrue("expected 4 active baselines, was ${active.map { it.id }}", active.size == 4)
        assertEquals(setOf("M5", "M6", "M8"), pending.map { it.ownerMilestone }.toSet())
        active.forEach { scenario ->
            assertNull(scenario.ownerMilestone)
            assertNull(scenario.knownDefect)
            assertTrue(scenario.messages.isNotEmpty())
            assertTrue(scenario.seedRows.isEmpty())
        }
        pending.forEach { scenario ->
            val owner = scenario.ownerMilestone
            assertTrue(owner != null && owner.matches(OWNER))
            val defect = scenario.knownDefect
            assertTrue(defect != null && defect.signals.isNotEmpty())
            assertEquals(owner, defect!!.ownerMilestone)
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
    fun pendingM5_crossBankSuffix_staysExemptUntilOwnerActivates() = runBlocking {
        assertPending("m5_cross_bank_suffix")
    }

    @Test
    fun pendingM6_accountRefund_staysExemptUntilOwnerActivates() = runBlocking {
        assertPending("m6_account_refund")
    }

    @Test
    fun pendingM8_asOfFx_staysExemptUntilOwnerActivates() = runBlocking {
        assertPending("m8_asof_fx")
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

    private suspend fun assertPending(id: String) {
        val scenario = GoldenLedgerCorpus.load(id)
        val owner = scenario.ownerMilestone
        val defect = scenario.knownDefect
        check(owner != null && defect != null) {
            "$id pending exemption requires an owner milestone and a known defect"
        }
        DashboardLedgerWorld(context()).use { world ->
            GoldenLedgerRunner.prepare(world, scenario)
            val snapshot = GoldenLedgerRunner.read(world, scenario)
            if (snapshot.matches(scenario.expected)) {
                fail(
                    "$id now matches its oracle. Owner $owner must activate the hard assertions " +
                        "and remove the pending exemption.",
                )
            }
            for ((key, broken) in defect.signals) {
                val actual = snapshot.metrics[key]
                    ?: fail("$id has no metric $key\n${snapshot.canonical()}")
                assertEquals(
                    "$id known defect $key\n${snapshot.canonical()}",
                    broken,
                    actual,
                )
                val truth = GoldenLedgerRunner.expectedMetric(scenario.expected, key)
                    ?: fail("$id oracle has no truth for $key")
                assertNotEquals(
                    "$id oracle $key must stay different from the known defect until $owner activates it",
                    truth,
                    broken,
                )
            }
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

    private companion object {
        val OWNER = Regex("""M(?:1|5|6|8)""")
    }
}
