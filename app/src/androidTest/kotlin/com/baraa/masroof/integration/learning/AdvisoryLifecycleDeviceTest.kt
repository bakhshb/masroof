package com.baraa.masroof.integration.learning

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.data.analytics.AnalysisCoverageRow
import com.baraa.masroof.data.analytics.AnalyticsArchiveStore
import com.baraa.masroof.data.analytics.AnalyticsDatabase
import com.baraa.masroof.data.analytics.ForecastComparisonRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite restore evidence in isolated device storage. */
@RunWith(AndroidJUnit4::class)
class AdvisoryLifecycleDeviceTest {
    private val context get() = TestStorageContext(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun restoreKeepsBothPublishedEstimatesImmutable() = runBlocking {
        val name="device-analysis-restore.db"
        context.deleteDatabase(name)
        val database=Room.databaseBuilder(context,AnalyticsDatabase::class.java,name).build()
        try {
            val dao=database.analytics()
            val pair=ForecastComparisonRow("pair",100,130,10,"a".repeat(64),"model","evaluation",200,300)
            dao.comparison(pair)
            assertEquals(-1L,dao.comparison(pair.copy(personalTotal=999)))
            dao.evaluateComparison(pair.id,250,"b".repeat(64),20)
            val actual=dao.comparisons().single()
            assertEquals(pair.personalTotal,actual.personalTotal)
            assertEquals(pair.baselineTotal,actual.baselineTotal)
            assertEquals(250L,actual.actual)
            val gap=AnalysisCoverageRow("gap",100,100,"LEGACY",10)
            dao.coverage(listOf(gap))
            val archive=AnalyticsArchiveStore.export(database)
            AnalyticsArchiveStore.restore(database,null)
            assertTrue(dao.coverage().isEmpty())
            AnalyticsArchiveStore.restore(database,archive)
            assertEquals(listOf(gap),dao.coverage())
            assertEquals(listOf(actual),dao.comparisons())
            assertNotNull(AnalyticsArchiveStore.validate("{\"version\":1}"))
        } finally { database.close();context.deleteDatabase(name) }
    }
}
