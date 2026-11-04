package com.baraa.masroof.instrumentation

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToNode

internal fun ComposeTestRule.nodeCount(matcher: SemanticsMatcher): Int =
    onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size

internal fun ComposeTestRule.nodeExists(matcher: SemanticsMatcher): Boolean = nodeCount(matcher) > 0

internal fun ComposeTestRule.contentDescriptionExists(value: String): Boolean =
    nodeExists(hasContentDescription(value))

/** Scroll the first scrollable container that can reach [matcher]; does nothing when none can. */
internal fun ComposeTestRule.scrollTo(matcher: SemanticsMatcher) {
    val scrollables = onAllNodes(hasScrollAction()).fetchSemanticsNodes(atLeastOneRootRequired = false)
    for (index in scrollables.indices) {
        val scrolled = runCatching { onAllNodes(hasScrollAction())[index].performScrollToNode(matcher) }.isSuccess
        if (scrolled) return
    }
}
