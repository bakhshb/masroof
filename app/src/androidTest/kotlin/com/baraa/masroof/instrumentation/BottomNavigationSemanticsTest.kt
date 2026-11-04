package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import com.baraa.masroof.presentation.common.MasroofBottomNavigation
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofNavigationItem
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Rule
import org.junit.Test

/** Each tab is one TalkBack stop: description, selected state and click on the same node. */
class BottomNavigationSemanticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun eachTabIsOneSelectableNode() {
        compose.setContent { MasroofTheme {
            MasroofBottomNavigation(listOf(
                MasroofNavigationItem("Home", MasroofIcons.dashboard, selected = true),
                MasroofNavigationItem("Settings", MasroofIcons.settings, selected = false, description = "Open settings"),
            )) {}
        } }
        compose.onAllNodesWithContentDescription("Home", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithContentDescription("Home").assertIsSelected().assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithContentDescription("Open settings").assert(hasClickAction())
    }
}
