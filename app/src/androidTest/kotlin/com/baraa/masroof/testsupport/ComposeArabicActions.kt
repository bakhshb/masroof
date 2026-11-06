package com.baraa.masroof.testsupport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.baraa.masroof.presentation.locale.AppLocaleContext

/** The Arabic label for [id], as the app shows it. */
fun AndroidComposeTestRule<*, *>.ar(id: Int): String = AppLocaleContext.wrap(activity, "ar").getString(id)

/** Waits for the Arabic label [id], scrolls to it when hidden, clicks it and waits for idle. */
fun AndroidComposeTestRule<*, *>.clickAr(id: Int) {
    // Screens load in the background, outside Compose idling; wait for the node before acting on it.
    waitUntil(10_000) { onAllNodesWithText(ar(id)).fetchSemanticsNodes().isNotEmpty() }
    val node = onNodeWithText(ar(id))
    if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
    node.performClick()
    waitForIdle()
}
