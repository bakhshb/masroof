package com.baraa.masroof.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardSectionEntry
import com.baraa.masroof.application.dashboard.DashboardSectionId
import com.baraa.masroof.application.dashboard.DashboardSectionSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedPrefsDashboardLayoutPreferencesRepositoryTest {
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var repository: SharedPrefsDashboardLayoutPreferencesRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        repository = SharedPrefsDashboardLayoutPreferencesRepository(prefs)
    }

    @Test
    fun load_persistsMergedLayoutWhenLoansMissing() {
        val legacy = DashboardLayoutSnapshot(
            sections = listOf(
                section(DashboardSectionId.HERO, DashboardSectionSize.LARGE),
                section(DashboardSectionId.QUICK),
                section(DashboardSectionId.ACCOUNTS),
                section(DashboardSectionId.CARDS, DashboardSectionSize.LARGE),
                section(DashboardSectionId.TRANSACTIONS),
            ),
        )
        repository.save(legacy)

        val loaded = repository.load()

        assertTrue(loaded.sections.any { it.id == DashboardSectionId.LOANS })
        val reloaded = repository.load()
        assertEquals(loaded, reloaded)
    }

    private fun section(
        id: DashboardSectionId,
        size: DashboardSectionSize = DashboardSectionSize.MEDIUM,
    ): DashboardSectionEntry =
        DashboardSectionEntry(id = id, visible = true, size = size)

    companion object {
        private const val PREFS_NAME = "dashboard_layout_prefs_test"
    }
}
