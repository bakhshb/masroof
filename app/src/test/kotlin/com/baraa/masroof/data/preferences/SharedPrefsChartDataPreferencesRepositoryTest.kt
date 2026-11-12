package com.baraa.masroof.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedPrefsChartDataPreferencesRepositoryTest {
    private fun prefs() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("chart-test", Context.MODE_PRIVATE)
    @Test fun choicesAndLastScopeSurviveNewRepositoryAndWorkflow() {
        val prefs = prefs(); prefs.edit().clear().commit()
        val workflow = ChartDataWorkflow(SharedPrefsChartDataPreferencesRepository(prefs))
        workflow.save("card:BANK_A:1234", setOf(ChartDataCategory.FEES), setOf(ChartDataCategory.FEES, ChartDataCategory.CREDIT_PURCHASES))
        workflow.setHomeScope("overall")
        val loaded = ChartDataWorkflow(SharedPrefsChartDataPreferencesRepository(prefs)).preferences.value
        assertEquals("overall", loaded.homeScope)
        assertEquals(listOf(ChartDataCategory.FEES), loaded.selections["card:BANK_A:1234"])
    }
    /** The stored format is the enum constant name; a value written before `ChartDataCategory` moved modules still loads. */
    @Test fun aStoredValueFromAnEarlierVersionStillLoads() {
        val prefs = prefs()
        prefs.edit().clear().putString("choices_json", """{"selections":{"card:BANK_A:1234":["FEES"]},"homeScope":"overall"}""").commit()
        val loaded = SharedPrefsChartDataPreferencesRepository(prefs).load()
        assertEquals("overall", loaded.homeScope)
        assertEquals(listOf(ChartDataCategory.FEES), loaded.selections["card:BANK_A:1234"])
    }
    @Test fun corruptPreferencesFallBackWithoutFinancialWrites() {
        val prefs = prefs(); prefs.edit().clear().putString("choices_json", "invalid").commit()
        assertEquals(ChartDataPreferences(), SharedPrefsChartDataPreferencesRepository(prefs).load())
    }
}
