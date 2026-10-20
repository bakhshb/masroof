package com.baraa.masroof.lab.beta
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.verification.BetaStartupReplayChecks
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class BetaStartupReplayTest {
    @Test fun cardEditsAndRepeatedStartupKeepPairedTransfers() = BetaStartupReplayChecks(ApplicationProvider.getApplicationContext<Context>()).pairedTransferSurvivesCardEditsAndRepeatedEntry()
}
