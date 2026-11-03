package com.baraa.masroof.presentation.navigation

import androidx.compose.runtime.saveable.listSaver

/** One visit to a home screen. The id is unique per visit and keys that visit's saved screen state. [tab] marks a visit opened from the tab bar. */
internal data class HomeBackStackEntry(val id: Int, val destination: HomeDestination, val tab: Boolean = false)

/** [nextId] only grows, so a later visit never reuses a popped visit's id and inherits its saved state. */
internal data class HomeBackStack(val entries: List<HomeBackStackEntry>, val nextId: Int)

internal fun homeRootStack() = HomeBackStack(listOf(HomeBackStackEntry(0, HomeDestination.Dashboard)), 1)

/** The visible visit. Every operation and the restore keep the stack non-empty. */
internal fun HomeBackStack.top(): HomeBackStackEntry = entries.last()

/** Saved as the next id, then `"<id>:<HomeDestination>"` (`:tab` appended for a tab visit) per visit; entries naming a removed screen are dropped on restore. */
internal val HomeBackStackSaver = listSaver<HomeBackStack, Any>(
    save = { stack -> listOf(stack.nextId) + stack.entries.map { "${it.id}:${it.destination.name}" + if (it.tab) ":tab" else "" } },
    restore = { saved ->
        val entries = saved.drop(1).mapNotNull { raw ->
            val text = raw as? String ?: return@mapNotNull null
            val id = text.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val name = text.substringAfter(':')
            HomeDestination.entries.firstOrNull { it.name == name.substringBefore(':') }?.let { HomeBackStackEntry(id, it, name.endsWith(":tab")) }
        }
        if (entries.isEmpty()) homeRootStack()
        else HomeBackStack(entries, maxOf(saved.firstOrNull() as? Int ?: 0, entries.maxOf { it.id } + 1))
    },
)

/** Opens [destination] on top; a repeated tap on the current screen changes nothing. */
internal fun pushHome(stack: HomeBackStack, destination: HomeDestination): HomeBackStack =
    if (stack.top().destination == destination) stack else withVisit(stack, destination)

/** The visible screen was opened above another one (not from the tab bar), so its toolbar shows a back arrow. */
internal fun HomeBackStack.topShowsBack(): Boolean = entries.size > 1 && !top().tab

/** `null` means the stack is at its root and back should be consumed. */
internal fun popHome(stack: HomeBackStack): HomeBackStack? =
    if (stack.entries.size <= 1) null else stack.copy(entries = stack.entries.dropLast(1))

/** Home clears history and reopens a fresh dashboard; the current tab restarts fresh; another tab is pushed like any link. */
internal fun switchHomeTab(stack: HomeBackStack, tab: HomeDestination): HomeBackStack = when {
    tab == HomeDestination.Dashboard -> HomeBackStack(listOf(HomeBackStackEntry(stack.nextId, tab)), stack.nextId + 1)
    stack.top().destination == tab -> withVisit(stack.copy(entries = stack.entries.dropLast(1)), tab, fromTab = true)
    else -> withVisit(stack, tab, fromTab = true)
}

/** Saved state for every visit lives in one bundle with a size limit, so only the newest visits are kept behind the root. */
internal const val MAX_HOME_VISITS = 30

private fun withVisit(stack: HomeBackStack, destination: HomeDestination, fromTab: Boolean = false): HomeBackStack {
    val next = stack.entries + HomeBackStackEntry(stack.nextId, destination, fromTab)
    val kept = if (next.size <= MAX_HOME_VISITS) next else listOf(next.first()) + next.takeLast(MAX_HOME_VISITS - 1)
    return HomeBackStack(kept, stack.nextId + 1)
}
