package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baraa.masroof.R
import com.baraa.masroof.presentation.startup.StartupBlockedScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StartupBlockedRestoreTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restorePageSurvivesRecreationAndBackReturnsToTheBlockedScreen() {
        var retries = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StartupBlockedScreen(onRetry = { retries++ }) { onBack ->
                Text("restore page")
                Text("leave", modifier = Modifier.clickable(onClick = onBack))
            }
        }
        val restore = compose.activity.getString(R.string.reference_restore_title)
        compose.onNodeWithText(compose.activity.getString(R.string.dashboard_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }

        compose.onNodeWithText(restore).performClick()
        compose.onNodeWithText("restore page").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("restore page").assertExists()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("restore page").assertDoesNotExist()
        compose.onNodeWithText(restore).performClick()
        compose.onNodeWithText("leave").performClick()
        compose.onNodeWithText(restore).assertExists()
    }
}
