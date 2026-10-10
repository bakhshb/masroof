package com.baraa.masroof.instrumentation

import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.baraa.masroof.presentation.common.rememberSensitiveInput
import org.junit.Rule
import org.junit.Test

class SensitiveInputRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typedCredential_isNotRestoredFromSavedUiState() {
        val restoration = StateRestorationTester(compose)
        lateinit var input: MutableState<String>
        restoration.setContent {
            input = rememberSensitiveInput()
            Text(input.value)
        }
        compose.runOnIdle { input.value = "test-only-sensitive-input" }
        compose.onNodeWithText("test-only-sensitive-input").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("test-only-sensitive-input").assertDoesNotExist()
    }
}
