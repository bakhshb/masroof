package com.baraa.masroof.lab.beta
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.verification.BetaReportedMessagesChecks
import org.junit.Test

class BetaReportedMessagesTest {
 @Test fun restoreExplicitClassificationClearsIgnoredState() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).ignoredMessageCanBeRestoredWithExplicitClassification()
 @Test fun reportedLayoutsPostAndReachDashboard() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).allReportedLayoutsPostAfterSetup(false)
 @Test fun storedPreviousEngineLayoutsRecoverWithoutReset() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).allReportedLayoutsPostAfterSetup(true)
}
