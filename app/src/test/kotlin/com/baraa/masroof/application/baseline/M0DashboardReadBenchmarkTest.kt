package com.baraa.masroof.application.baseline

import com.baraa.masroof.application.dashboard.GoldenLedgerCorpus
import com.baraa.masroof.application.dashboard.GoldenLedgerRunner
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repeated dashboard reads of the smallest active golden scenario.
 * The numbers describe this JVM run; they are not a device startup sample.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class M0DashboardReadBenchmarkTest {
    @Test
    fun oneIntraPair_readPercentilesStayOrdered() = runBlocking {
        val scenario = GoldenLedgerCorpus.load("one_intra_pair")
        DashboardLedgerWorld(context()).use { world ->
            GoldenLedgerRunner.prepare(world, scenario)
            val warmup = GoldenLedgerRunner.read(world, scenario)
            assertTrue(warmup.matches(scenario.expected))
            val samples = MutableList(SAMPLE_COUNT) {
                val started = System.nanoTime()
                val snapshot = GoldenLedgerRunner.read(world, scenario)
                val elapsed = System.nanoTime() - started
                assertTrue(snapshot.matches(scenario.expected))
                elapsed
            }.sorted()
            val p50 = samples[samples.size / 2]
            val p95 = samples[((samples.size - 1) * 95) / 100]
            println("M0_DASHBOARD_READ scenario=one_intra_pair n=$SAMPLE_COUNT p50_ns=$p50 p95_ns=$p95")
            assertTrue(p50 > 0L)
            assertTrue(p95 >= p50)
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val SAMPLE_COUNT: Int = 8
    }
}
