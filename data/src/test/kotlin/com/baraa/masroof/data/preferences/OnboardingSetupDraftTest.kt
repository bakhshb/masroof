package com.baraa.masroof.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.onboarding.OnboardingSetupDraft
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingSetupDraftTest {
    @Test fun explicitBankSelectionAndBothDates_persistWithoutChangingOriginalPreferenceKeys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("onboarding-draft-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repository = SharedPrefsOnboardingPreferencesRepository(prefs)
        repository.setHistoricalImportStartEpochMillis(42L)
        val draft = OnboardingSetupDraft(setOf("stc_bank"), "2026-09-01", "2026-09-30", true)
        repository.setSetupDraft(draft)
        val recreated = SharedPrefsOnboardingPreferencesRepository(prefs)
        assertEquals(draft, recreated.getSetupDraft())
        assertEquals(42L, recreated.getHistoricalImportStartEpochMillis())
        prefs.edit().putString(SharedPrefsOnboardingPreferencesRepository.KEY_SETUP_DRAFT, "invalid").commit()
        assertNull(recreated.getSetupDraft())
        assertFalse(recreated.isHistoricalImportCompleted())
    }
}
