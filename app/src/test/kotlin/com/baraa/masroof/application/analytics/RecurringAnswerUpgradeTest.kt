package com.baraa.masroof.application.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.testsupport.TestStorageContext
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM run of [RecurringAnswerUpgradeContract]; the device twin is `RecurringAnswerUpgradeDeviceTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringAnswerUpgradeTest {
    private val fixture = File(if (File("src/test").isDirectory) "." else "app", "src/test/resources/testdata/ledger_fixtures/v18/masroof.db")

    @Test
    fun v18InstallWithAnalyticsAnswers_opensWithTheSameAnswersInTheDecisionStore() = RecurringAnswerUpgradeContract.upgradeKeepsEveryAnswer(
        TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "recurring-upgrade-${java.util.UUID.randomUUID()}"),
    ) { fixture.inputStream() }
}
