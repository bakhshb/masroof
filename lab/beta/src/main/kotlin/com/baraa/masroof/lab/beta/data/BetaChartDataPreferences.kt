package com.baraa.masroof.lab.beta.data

import android.content.Context
import com.baraa.masroof.lab.beta.BetaEnvironment
import com.baraa.masroof.lab.beta.application.BetaChartDataPreferences
import com.baraa.masroof.lab.beta.application.BetaChartDataPreferencesRepository
import com.baraa.masroof.lab.finance.dashboard.ChartCategory
import org.json.JSONArray
import org.json.JSONObject

/** String JSON preserves the lab backup's preference-value contract. No financial tables are written. */
internal class SharedBetaChartDataPreferencesRepository(context: Context): BetaChartDataPreferencesRepository {
    private val preferences=context.applicationContext.getSharedPreferences(
        BetaEnvironment.PREFS_NAME.also(BetaEnvironment::requireBetaPreferenceName),Context.MODE_PRIVATE)
    override fun load(): BetaChartDataPreferences = runCatching {
        val json=JSONObject(preferences.getString("dashboard_chart_categories",null) ?: "{}")
        BetaChartDataPreferences(json.keys().asSequence().associateWith {key ->
            val values=json.getJSONArray(key)
            (0 until values.length()).mapNotNull {index -> runCatching {ChartCategory.valueOf(values.getString(index))}.getOrNull()}.distinct()
        })
    }.getOrDefault(BetaChartDataPreferences())
    override fun save(value: BetaChartDataPreferences) {
        val json=JSONObject()
        value.selections.forEach {(key,categories) -> json.put(key,JSONArray(categories.map {it.name}))}
        check(preferences.edit().putString("dashboard_chart_categories",json.toString()).commit()) {"Beta chart preferences were not saved"}
    }
}
