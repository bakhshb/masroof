package com.baraa.masroof.lab.beta.application

import android.content.Context
import com.baraa.masroof.lab.beta.BetaEnvironment
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.finance.value.Environment
import kotlinx.coroutines.withContext

data class BetaDashboardViewPreferences(val choice: BetaChartChoice = BetaChartChoice(), val bars: Boolean = true)

/** Nonfinancial preferences in the same isolated, backed-up Beta preference file. */
class BetaDashboardViewPreferencesWorkflow(context: Context) {
    private val context = context.applicationContext
    internal val chartData by lazy {BetaChartDataWorkflow(com.baraa.masroof.lab.beta.data.SharedBetaChartDataPreferencesRepository(this.context))}
    private fun preferences() = context.getSharedPreferences(BetaEnvironment.PREFS_NAME.also(BetaEnvironment::requireBetaPreferenceName), Context.MODE_PRIVATE)
    suspend fun read(): BetaDashboardViewPreferences = withContext(BetaIo.dispatcher) {
        runCatching {
            val prefs = preferences()
            val scope = TrendScope.valueOf(prefs.getString("dashboard_view_scope", TrendScope.OVERALL.name)!!)
            val category = prefs.getString("dashboard_view_category", null)?.let(ChartCategory::valueOf)
            val asset = if (scope == TrendScope.ASSET) AssetId(Environment.BETA,
                BankId(checkNotNull(prefs.getString("dashboard_view_bank", null))), checkNotNull(prefs.getString("dashboard_view_asset", null))) else null
            BetaDashboardViewPreferences(BetaChartChoice(scope, category, asset), when (prefs.getString("dashboard_view_mode", "BARS")) { "BARS" -> true; "TABLE" -> false; else -> error("Invalid view mode") })
        }.getOrDefault(BetaDashboardViewPreferences())
    }
    suspend fun save(value: BetaDashboardViewPreferences) = withContext(BetaIo.dispatcher) {
        require((value.choice.scope == TrendScope.ASSET) == (value.choice.asset != null))
        require(value.choice.asset == null || value.choice.asset.environment == Environment.BETA)
        check(preferences().edit().putString("dashboard_view_scope", value.choice.scope.name)
            .putString("dashboard_view_category", value.choice.category?.name)
            .putString("dashboard_view_bank", value.choice.asset?.bank?.value)
            .putString("dashboard_view_asset", value.choice.asset?.localId)
            .putString("dashboard_view_mode", if (value.bars) "BARS" else "TABLE").commit()) { "Beta view preferences were not saved" }
    }
    suspend fun readHomeScope(): TrendScope = withContext(BetaIo.dispatcher) {
        when (preferences().getString("dashboard_home_scope", "purchases")) {
            "overall" -> TrendScope.OVERALL
            else -> TrendScope.PURCHASES
        }
    }
    suspend fun saveHomeScope(scope: TrendScope) = withContext(BetaIo.dispatcher) {
        require(scope in setOf(TrendScope.PURCHASES, TrendScope.OVERALL))
        check(preferences().edit().putString("dashboard_home_scope", if (scope == TrendScope.OVERALL) "overall" else "purchases").commit()) {
            "Beta Home scope was not saved"
        }
    }

    suspend fun readHomeLayout(): BetaHomeLayout = withContext(BetaIo.dispatcher) {
        runCatching { BetaHomeLayout.decode(preferences().getString("dashboard_home_layout", null)) }.getOrDefault(BetaHomeLayout())
    }
    /** Compare-and-save prevents another open editor from silently overwriting the saved layout. */
    suspend fun saveHomeLayout(expected: BetaHomeLayout, next: BetaHomeLayout): Boolean = withContext(BetaIo.dispatcher) {
        val prefs = preferences()
        val current = runCatching { BetaHomeLayout.decode(prefs.getString("dashboard_home_layout", null)) }.getOrDefault(BetaHomeLayout())
        if (current != expected) false
        else { check(prefs.edit().putString("dashboard_home_layout", next.encode()).commit()); true }
    }

}
