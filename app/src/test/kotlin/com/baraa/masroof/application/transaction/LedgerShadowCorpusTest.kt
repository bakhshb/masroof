package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.GoldenLedgerCorpus
import com.baraa.masroof.application.dashboard.GoldenLedgerRunner
import com.baraa.masroof.application.dashboard.LedgerSnapshotCorpus
import com.baraa.masroof.data.room.UserDecisionBackfill
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import com.baraa.masroof.testsupport.testdataFile
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 3.2 shadow comparison on the snapshot corpus: each world is built as `LedgerSnapshotTest` builds it (the
 * golden scenarios, all banks with extras, and the legacy states after a full pass), the v19 → v20
 * decision backfill runs as on an upgraded device, then [LedgerShadowComparison] compares the stored ledger with
 * the projection. The report is written to `app/build/reports/shadow-ledger/shadow_comparison.txt` (a CI artifact)
 * and must equal `testdata/ledger_snapshot/shadow_comparison.expected.txt`, so a new difference fails.
 *
 * The owner decided gate 3.2 on 2026-11-12 (the projector's keep rule; docs/ARCHITECTURE.md, "Rules of the derived ledger");
 * the expected file then holds no difference. A new difference goes to the owner before regenerating, only with
 * `MASROOF_UPDATE_SHADOW_COMPARISON=1 ./gradlew :app:testDebugUnitTest --tests '*LedgerShadowCorpusTest' --rerun`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerShadowCorpusTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun corpusReportMatchesTheCommittedOne() = runBlocking {
        val actual = buildString {
            GoldenLedgerCorpus.loadAll().forEach { scenario ->
                DashboardLedgerWorld(context).use { world ->
                    GoldenLedgerRunner.prepare(world, scenario)
                    section("golden:${scenario.id}", world)
                }
            }
            LedgerSnapshotCorpus.newAllBanksWorld(context).use { world ->
                LedgerSnapshotCorpus.populateAllBanks(world)
                LedgerSnapshotCorpus.populateExtras(world)
                section("all_banks", world)
            }
            DashboardLedgerWorld(context).use { world ->
                LedgerSnapshotCorpus.populateLegacy(world)
                world.reconciliation.reconcileStoredEventsDetailed()
                section("legacy:after_repair_and_full_reconcile", world)
            }
        }
        File(moduleDir(), "build/reports/shadow-ledger/shadow_comparison.txt").apply { parentFile.mkdirs() }.writeText(actual)
        val expected = testdataFile("ledger_snapshot/shadow_comparison.expected.txt")
        if (System.getenv(UPDATE_ENV) == "1") expected.writeText(actual)
        assertEquals("shadow comparison changed; see build/reports/shadow-ledger", expected.readText(), actual)
    }

    private suspend fun StringBuilder.section(name: String, world: DashboardLedgerWorld) {
        UserDecisionBackfill.run(world.db.openHelper.writableDatabase)
        appendLine("======== $name")
        append(LedgerShadowComparison.report(world.ledgerInputLoader.load()))
    }

    private fun moduleDir(): File = File(if (File("src/test").isDirectory) "." else "app")

    private companion object {
        const val UPDATE_ENV = "MASROOF_UPDATE_SHADOW_COMPARISON"
    }
}
