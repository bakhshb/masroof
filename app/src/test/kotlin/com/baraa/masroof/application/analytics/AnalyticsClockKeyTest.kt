package com.baraa.masroof.application.analytics

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.testsupport.TestStorageContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnalyticsClockKeyTest {
    @Test
    fun clockKeyChangesWhenSmsPermissionChanges() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = TestStorageContext(app as Context, "analytics-clock-${UUID.randomUUID()}")
        val container = AppContainer(context)
        val clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneId.of("Asia/Riyadh"))
        val workflow = AnalyticsWorkflow(container, context, clock)
        try {
            shadowOf(app).denyPermissions(Manifest.permission.READ_SMS)
            val denied = workflow.clockKey()
            assertEquals(denied, workflow.clockKey())

            shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
            assertNotEquals(denied, workflow.clockKey())
        } finally {
            workflow.close()
            container.close()
        }
    }

    @Test
    fun budgetAcceptsTheGroupedAmountsTheCommitmentEditorAccepts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = TestStorageContext(app as Context, "analytics-budget-${UUID.randomUUID()}")
        val container = AppContainer(context)
        val workflow = AnalyticsWorkflow(container, context)
        try {
            assertEquals(500_000L, workflow.parseBudget("5,000"))
            assertEquals(500_000L, workflow.parseBudget("٥٬٠٠٠"))
            assertEquals(1_250L, workflow.parseBudget("١٢٫٥"))
            assertTrue(runCatching { workflow.parseBudget("12,50") }.isFailure)
        } finally {
            workflow.close()
            container.close()
        }
    }
}
