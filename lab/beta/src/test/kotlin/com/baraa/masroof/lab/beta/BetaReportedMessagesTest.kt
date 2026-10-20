package com.baraa.masroof.lab.beta
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.verification.BetaReportedMessagesChecks
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class BetaReportedMessagesTest {
 @Test fun correctionReviewCanBeIgnoredAndResolved() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).correctionReviewCanBeIgnoredAndResolved()
 @Test fun manualUpgradeWriteFailureRemainsRetryable() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).previousEngineKeepsManualClassificationAndSuppression(true)
 @Test fun previousEngineKeepsManualDecisions() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).previousEngineKeepsManualClassificationAndSuppression()
 @Test fun restoreExplicitClassificationClearsIgnoredState() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).ignoredMessageCanBeRestoredWithExplicitClassification()
 @Test fun reportedLayoutsPostAndReachDashboard() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).allReportedLayoutsPostAfterSetup(false)
 @Test fun storedPreviousEngineLayoutsRecoverWithoutReset() = BetaReportedMessagesChecks(ApplicationProvider.getApplicationContext<Context>()).allReportedLayoutsPostAfterSetup(true)
}
