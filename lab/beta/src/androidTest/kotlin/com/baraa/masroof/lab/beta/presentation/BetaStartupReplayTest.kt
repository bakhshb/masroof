package com.baraa.masroof.lab.beta.presentation
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.beta.verification.BetaStartupReplayChecks
import org.junit.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class BetaStartupReplayTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun cardEditsAndRepeatedStartupReachTheHomeScreen() = BetaStartupReplayChecks(ApplicationProvider.getApplicationContext<Context>()).pairedTransferSurvivesCardEditsAndRepeatedEntry {
        try {ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
            repeat(2) {pass ->
                if(pass>0) scenario.recreate()
                compose.waitUntil(20000) {compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
                compose.onNodeWithTag("beta-startup-failure").assertDoesNotExist()
            }
        }} finally {val done=CountDownLatch(1);BetaIo.execute {done.countDown()};Assert.assertTrue(done.await(20,TimeUnit.SECONDS))}
    }
}
