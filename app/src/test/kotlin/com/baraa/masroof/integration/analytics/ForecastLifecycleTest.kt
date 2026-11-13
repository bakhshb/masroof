package com.baraa.masroof.integration.analytics

import androidx.room.withTransaction
import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.data.analytics.AnalyticsDatabase
import com.baraa.masroof.data.room.entity.CardRegistryEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.testsupport.uniqueStorageContext
import com.baraa.masroof.testsupport.runOffMainThread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.*

/** The forecast pair over a salary period: captured once, settled, never rewritten. Moved from the device suite (plan 7.3). */
@RunWith(RobolectricTestRunner::class)
class ForecastLifecycleTest {
    private class MovingClock(var day: LocalDate, private val zone: ZoneId) : Clock() {
        override fun getZone() = zone
        override fun withZone(zone: ZoneId): Clock = MovingClock(day,zone)
        override fun instant(): Instant = day.atTime(12,0).atZone(zone).toInstant()
    }
    @Test fun repairedCoverageRestoresHistoryAndPublishedPairsAreSettledWithoutRewritingForecasts() = runOffMainThread {
        forecastJourney(7)
    }
    @Test fun missedDayEightWaitsForValidCoverageAndCapturesOnlyOneRealLateOrigin() = runOffMainThread {
        forecastJourney(10, deferForGap = true)
    }
    @Test fun anExistingDayEightPairIsNotReplacedByTheNewLateOriginPolicy() = runOffMainThread {
        forecastJourney(10, legacyPair = true)
    }
    private suspend fun forecastJourney(offset: Long, deferForGap: Boolean = false, legacyPair: Boolean = false) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.READ_SMS)
        val context=uniqueStorageContext("forecast-lifecycle")
        val c=AppContainer(context)
        // The analysis waits for startup maintenance, which the app runs at launch.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, androidx.work.Configuration.Builder()
            .setExecutor(androidx.work.testing.SynchronousExecutor()).setWorkerFactory(com.baraa.masroof.application.container.appWorkerFactory(c)).build())
        c.maintenance.runStartupMaintenance()
        c.maintenance.awaitPostStartupBackgroundWorkForTesting()
        c.stopAnalytics()
        val zone=ZoneId.systemDefault()
        val period=FinancialPeriodPolicy.periodContaining(LocalDate.now(zone))
        var origin=period.startDate.plusDays(offset)
        val clock=MovingClock(origin,zone)
        val workflow=AnalyticsWorkflow(c,context,clock)
        val sidecar=AnalyticsDatabase.open(context)
        val first=origin.minusDays(300)
        suspend fun seed(from: LocalDate,through: LocalDate) = c.storage.database.withTransaction {
            var day=from
            while(!day.isAfter(through)) {
                val index=day.toEpochDay()-first.toEpochDay()
                val amount=java.math.BigDecimal.valueOf(1000+index*50,2).toPlainString()
                c.storage.database.financialTransactionDao().insertTransactionIfAbsent(FinancialTransactionEntity("trend:$day","EXPENSE",amount,"SAR",
                    day.atTime(10,0).atZone(zone).toInstant().toEpochMilli(),"card:BANK_ALJAZIRA:1234",null,"Test",null,null))
                day=day.plusDays(1)
            }
        }
        try {
            c.storage.onboardingPreferencesRepository.setOnboardingStarted(true)
            c.storage.onboardingPreferencesRepository.setOnboardingCompleted(true)
            c.storage.onboardingPreferencesRepository.setHistoricalImportStartEpochMillis(first.atStartOfDay(zone).toInstant().toEpochMilli())
            c.storage.onboardingPreferencesRepository.setHistoricalImportCompleted(true)
            c.storage.database.cardRegistryDao().insertFacilityIfAbsent("forecast-facility","BANK_ALJAZIRA","1234")
            c.storage.database.cardRegistryDao().insertIfAbsent(CardRegistryEntity("forecast-card","BANK_ALJAZIRA","1234","OWNED",cardType="CREDIT",
                cardRole="PRIMARY",creditFacilityId="forecast-facility",firstSeenRawSmsId=null,lastSeenRawSmsId=null))
            if (deferForGap) {
                clock.day=period.startDate.plusDays(6)
                seed(first,clock.day)
                workflow.refresh()
                assertTrue(sidecar.analytics().comparisons().isEmpty())
                clock.day=origin
                seed(period.startDate.plusDays(7),origin)
                val missing=period.startDate.plusDays(8)
                val prefs=sidecar.analytics().preferences()!!
                sidecar.analytics().preferences(prefs.copy(gapDays=missing.toEpochDay().toString()))
                workflow.refresh()
                assertTrue(sidecar.analytics().comparisons().isEmpty())
                origin=origin.plusDays(1)
                clock.day=origin
                seed(origin,origin)
                AnalysisCoverageStore.recordScan(context,missing.atStartOfDay(zone).toInstant(),clock.instant(),zone)
            } else seed(first,origin)
            val legacy = if (legacyPair) com.baraa.masroof.data.analytics.ForecastComparisonRow(
                "legacy",period.startDate.toEpochDay(),period.endDateExclusive.toEpochDay(),
                period.startDate.plusDays(7).atTime(12,0).atZone(zone).toInstant().toEpochMilli(),"a".repeat(64),
                com.baraa.masroof.analytics.AnalyticsVersion.MODEL,ForecastPerformancePolicy.LEGACY_VERSION,200,300,
                originDay=period.startDate.plusDays(7).toEpochDay()) else null
            legacy?.let { sidecar.analytics().comparison(it) }
            workflow.refresh()
            val snapshot=requireNotNull(workflow.state.value.snapshot)
            assertEquals("reason=${snapshot.modelReason} used=${snapshot.quality.used} blocked=${snapshot.quality.blocked}",
                com.baraa.masroof.analytics.AnalyticsVersion.MODEL,snapshot.method)
            val original=sidecar.analytics().comparisons().single()
            assertEquals(legacy?.originDay ?: origin.toEpochDay(),original.originDay)
            if(legacy != null) assertEquals(legacy,original)
            else {
                assertEquals(clock.millis(),original.issuedAt)
                // A cached presentation snapshot must not prevent capturing a missing pair.
                sidecar.analytics().clearComparisons()
                workflow.refresh()
                assertEquals(original,sidecar.analytics().comparisons().single())
            }
            assertEquals(com.baraa.masroof.analytics.AnalyticsVersion.MODEL,workflow.state.value.snapshot!!.method)
            val gap=origin.minusDays(10)
            val prefs=sidecar.analytics().preferences()!!
            sidecar.analytics().preferences(prefs.copy(gapDays=gap.toEpochDay().toString()))
            workflow.refresh()
            assertEquals("COVERAGE_GAPS",workflow.state.value.snapshot!!.modelReason)
            AnalysisCoverageStore.recordScan(context,gap.atStartOfDay(zone).toInstant(),clock.instant(),zone)
            workflow.refresh()
            assertEquals(com.baraa.masroof.analytics.AnalyticsVersion.MODEL,workflow.state.value.snapshot!!.method)
            assertEquals(original,sidecar.analytics().comparisons().single())
            assertEquals("",sidecar.analytics().preferences()!!.gapDays)
            seed(origin.plusDays(1),period.endDateExclusive.minusDays(1))
            clock.day=period.endDateExclusive
            workflow.refresh()
            val settled=sidecar.analytics().comparisons().single()
            assertNotNull(settled.actual)
            assertEquals(original.personalTotal,settled.personalTotal)
            assertEquals(original.baselineTotal,settled.baselineTotal)
            val id="trend:${origin.plusDays(1)}"
            val tx=c.storage.financialTransactionRepository.getById(id)!!
            c.storage.financialTransactionRepository.update(tx.copy(amount=com.baraa.masroof.core.money.Money.of(tx.amount.amount.add(java.math.BigDecimal.TEN),tx.amount.currency)))
            workflow.refresh()
            val corrected=sidecar.analytics().comparisons().single()
            assertEquals(settled.actual!!+1000,corrected.actual)
            assertEquals(original.personalTotal,corrected.personalTotal)
            assertEquals(original.baselineTotal,corrected.baselineTotal)
            c.storage.database.openHelper.readableDatabase.query("PRAGMA user_version").use { it.moveToFirst();assertEquals(com.baraa.masroof.data.room.MasroofDatabase.VERSION,it.getInt(0)) }
        } finally { workflow.close();sidecar.close();c.close();androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase();context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
    }
}
