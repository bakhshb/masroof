package com.baraa.masroof.integration.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.analytics.RecurringAnswerUpgradeContract
import com.baraa.masroof.testsupport.TestStorageContext
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite run of [RecurringAnswerUpgradeContract] on the committed v18 fixture (shared from test resources). */
@RunWith(AndroidJUnit4::class)
class RecurringAnswerUpgradeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun v18InstallWithAnalyticsAnswers_opensWithTheSameAnswersInTheDecisionStore() = RecurringAnswerUpgradeContract.upgradeKeepsEveryAnswer(
        TestStorageContext(instrumentation.targetContext, "recurring-upgrade"),
    ) { instrumentation.context.assets.open("ledger_fixtures/v18/masroof.db") }
}
