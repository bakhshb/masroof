package com.baraa.masroof.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeBackStackTest {
    private fun destinations(stack: HomeBackStack?) = stack?.entries?.map { it.destination }

    @Test
    fun pushThenPop_returnsToPreviousScreen() {
        val settings = pushHome(homeRootStack(), HomeDestination.Settings)
        val notifications = pushHome(settings, HomeDestination.NotificationCenter)

        assertEquals(listOf(HomeDestination.Dashboard, HomeDestination.Settings, HomeDestination.NotificationCenter), destinations(notifications))
        assertEquals(settings.entries, popHome(notifications)?.entries)
        assertEquals(homeRootStack().entries, popHome(settings)?.entries)
    }

    @Test
    fun pushSameScreenTwice_changesNothing() {
        val notifications = pushHome(homeRootStack(), HomeDestination.NotificationCenter)
        assertSame(notifications, pushHome(notifications, HomeDestination.NotificationCenter))
    }

    @Test
    fun popAtRoot_isConsumed() {
        assertNull(popHome(homeRootStack()))
    }

    @Test
    fun idsStayUniqueAcrossRevisits() {
        var stack = homeRootStack()
        repeat(3) {
            stack = pushHome(stack, HomeDestination.Settings)
            stack = pushHome(stack, HomeDestination.NotificationCenter)
        }
        val ids = stack.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun popThenPush_neverReusesThePoppedVisitId() {
        val settings = pushHome(homeRootStack(), HomeDestination.Settings)
        val again = pushHome(requireNotNull(popHome(settings)), HomeDestination.Settings)

        assertEquals(settings.top().id + 1, again.top().id)
        // The counter survives a save, so a restore cannot hand the id out again either.
        val restored = HomeBackStackSaver.restore(save(requireNotNull(popHome(settings))))!!
        assertEquals(again.top().id, pushHome(restored, HomeDestination.Settings).top().id)
    }

    @Test
    fun otherTab_isPushedAndBackReturnsToPreviousScreen() {
        val settings = pushHome(homeRootStack(), HomeDestination.Settings)
        val review = switchHomeTab(settings, HomeDestination.Review)

        assertEquals(listOf(HomeDestination.Dashboard, HomeDestination.Settings, HomeDestination.Review), destinations(review))
        assertEquals(settings.entries, popHome(review)?.entries)
    }

    @Test
    fun currentTabAgain_restartsWithFreshVisit() {
        val settings = pushHome(homeRootStack(), HomeDestination.Settings)
        val again = switchHomeTab(settings, HomeDestination.Settings)

        assertEquals(destinations(settings), destinations(again))
        assertEquals(settings.top().id + 1, again.top().id)
    }

    @Test
    fun homeTab_clearsHistoryAndOpensFreshDashboard() {
        val deep = switchHomeTab(pushHome(pushHome(homeRootStack(), HomeDestination.Settings), HomeDestination.NotificationCenter), HomeDestination.Review)
        val home = switchHomeTab(deep, HomeDestination.Dashboard)

        assertEquals(listOf(HomeDestination.Dashboard), destinations(home))
        // A new visit id drops the old dashboard's saved sub-pages (analytics, flow detail).
        assertEquals(deep.top().id + 1, home.top().id)
        assertEquals(1, switchHomeTab(homeRootStack(), HomeDestination.Dashboard).top().id)
    }

    @Test
    fun longTabSwitching_keepsRootAndNewestVisitsOnly() {
        var stack = homeRootStack()
        repeat(100) { stack = switchHomeTab(stack, if (it % 2 == 0) HomeDestination.Settings else HomeDestination.Review) }

        assertEquals(MAX_HOME_VISITS, stack.entries.size)
        assertEquals(homeRootStack().top(), stack.entries.first())
        assertEquals(HomeDestination.Review, stack.top().destination)
        assertEquals(100, stack.top().id)
    }

    @Test
    fun backArrow_showsOnlyForScreensOpenedAboveAnother() {
        assertFalse(homeRootStack().topShowsBack())
        val reviewTab = switchHomeTab(homeRootStack(), HomeDestination.Review)
        assertFalse(reviewTab.topShowsBack())
        val reviewLink = pushHome(pushHome(homeRootStack(), HomeDestination.Settings), HomeDestination.Review)
        assertTrue(reviewLink.topShowsBack())
        // A tab visit stays a tab root after a save and restore.
        assertEquals(reviewTab, HomeBackStackSaver.restore(save(reviewTab)))
        assertFalse(HomeBackStackSaver.restore(save(reviewTab))!!.topShowsBack())
    }

    @Test
    fun savedStackRoundTripsAndDropsRemovedScreens() {
        val stack = pushHome(pushHome(homeRootStack(), HomeDestination.Settings), HomeDestination.NotificationCenter)

        assertEquals(stack, HomeBackStackSaver.restore(save(stack)))
        assertEquals(HomeBackStack(homeRootStack().entries, 8), HomeBackStackSaver.restore(listOf(8, "0:Dashboard", "7:RemovedScreen", "junk")))
        assertEquals(homeRootStack(), HomeBackStackSaver.restore(listOf(3, "junk")))
    }

    private fun save(stack: HomeBackStack) = with(HomeBackStackSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stack) }!!
}
