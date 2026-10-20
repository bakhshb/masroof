package com.baraa.masroof.lab.beta
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.baraa.masroof.lab.beta.verification.BetaAssetTrackingChecks
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class BetaAssetTrackingTest {
    private val checks get()=BetaAssetTrackingChecks(ApplicationProvider.getApplicationContext<Context>())
    @Test fun stoppedCardPurchasesStayExcludedAcrossImportReopenAndResume()=checks.stoppedCardPurchasesStayExcludedAcrossImportReopenAndResume()
    @Test fun identityCollisionAndStaleActionsNeverChangeAnotherAsset()=checks.identityCollisionAndStaleActionsNeverChangeAnotherAsset()
    @Test fun stoppedLoanDoesNotGetRediscoveredAndResumeRetainsItsIdentity()=checks.stoppedLoanDoesNotGetRediscoveredAndResumeRetainsItsIdentity()
    @Test fun stoppingOneAccountReconsidersPairedTransferWithoutDoubleCounting()=checks.stoppingOneAccountReconsidersPairedTransferWithoutDoubleCounting()
}
