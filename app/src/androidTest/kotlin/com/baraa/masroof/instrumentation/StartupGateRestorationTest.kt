package com.baraa.masroof.instrumentation

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.presentation.startup.rememberStartupMaintenanceOutcome
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class StartupGateRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savedReady_doesNotExposeFinancialContentWhileNewMaintenanceIsPending() {
        var maintenance = CompletableDeferred(StartupMaintenanceOutcome.READY)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val outcome by rememberStartupMaintenanceOutcome({ maintenance.await() }, {})
            if (outcome == StartupMaintenanceOutcome.READY) Text("financial content")
        }
        compose.onNodeWithText("financial content").assertExists()
        compose.runOnIdle { maintenance = CompletableDeferred() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("financial content").assertDoesNotExist()
        compose.runOnIdle { maintenance.complete(StartupMaintenanceOutcome.BLOCKED) }
        compose.onNodeWithText("financial content").assertDoesNotExist()
    }
}
