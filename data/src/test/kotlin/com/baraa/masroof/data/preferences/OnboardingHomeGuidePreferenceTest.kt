package com.baraa.masroof.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnboardingHomeGuidePreferenceTest {
    @Test fun homeGuideIsUnseenUntilDismissedAndCanBeReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("onboarding-home-guide-test", Context.MODE_PRIVATE)
        val repository = SharedPrefsOnboardingPreferencesRepository(prefs)
        assertFalse(repository.isHomeGuideSeen())
        repository.setHomeGuideSeen(true)
        assertTrue(SharedPrefsOnboardingPreferencesRepository(prefs).isHomeGuideSeen())
        repository.setHomeGuideSeen(false)
        assertFalse(repository.isHomeGuideSeen())
    }
}
