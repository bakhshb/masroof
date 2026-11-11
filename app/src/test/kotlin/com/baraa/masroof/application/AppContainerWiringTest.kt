package com.baraa.masroof.application

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.analytics.AnalyticsWorker
import com.baraa.masroof.application.interpretation.ModelRefreshWorker
import com.baraa.masroof.application.sms.ExchangeRateEnrichmentWorker
import com.baraa.masroof.application.sms.HistoricalDerivedRecoveryWorker
import com.baraa.masroof.application.sms.LiveCaptureRecoveryWorker
import com.baraa.masroof.application.sms.LiveSmsProcessingWorker
import com.baraa.masroof.application.update.UpdateCheckWorker
import com.baraa.masroof.testsupport.uniqueStorageContext
import com.baraa.masroof.testsupport.withAppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The root builds each feature container once, shares one set of singletons, and creates every worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppContainerWiringTest {
    class PlainWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    @Test fun featureContainersShareTheRootsInstancesInsteadOfMakingTheirOwn() = runBlocking {
        withAppContainer(uniqueStorageContext("wiring-shared")) { c ->
            val shared = listOf<Any>(c.storage, c.maintenance, c.learning, c.review, c.dashboard, c.sms)
            val sharedNames = shared.map { it.javaClass.name }.toSet()
            var checked = 0
            listOf<Any>(c.maintenance, c.update, c.learning, c.review, c.dashboard, c.sms, c.patterns, c.backup).forEach { owner ->
                owner.javaClass.declaredFields.filter { it.type.name in sharedNames }.forEach { field ->
                    field.isAccessible = true
                    // A kept constructor argument must be the root's instance, never a second copy.
                    assertSame("${owner.javaClass.simpleName}.${field.name}", shared.first { it.javaClass == field.type }, field.get(owner))
                    checked++
                }
            }
            assertTrue("no shared reference was inspected", checked >= 8)
        }
    }

    @Test fun theFactoryCreatesAllSevenWorkers() = runBlocking {
        val workers = listOf(
            LiveSmsProcessingWorker::class.java, ExchangeRateEnrichmentWorker::class.java,
            HistoricalDerivedRecoveryWorker::class.java, LiveCaptureRecoveryWorker::class.java,
            ModelRefreshWorker::class.java, UpdateCheckWorker::class.java, AnalyticsWorker::class.java,
        )
        withAppContainer(uniqueStorageContext("wiring-workers")) { c ->
            val context = c.storage.appContext
            workers.forEach { type ->
                val worker = TestListenableWorkerBuilder.from(context, type)
                    .setWorkerFactory(c.workerFactory).build()
                assertEquals(type, worker.javaClass)
            }
        }
    }

    @Test fun anUnknownWorkerStillFallsBackToTheDefaultFactory() = runBlocking {
        withAppContainer(uniqueStorageContext("wiring-fallback")) { c ->
            val worker = TestListenableWorkerBuilder<PlainWorker>(c.storage.appContext)
                .setWorkerFactory(c.workerFactory).build()
            assertEquals(PlainWorker::class.java, worker.javaClass)
        }
    }
}
