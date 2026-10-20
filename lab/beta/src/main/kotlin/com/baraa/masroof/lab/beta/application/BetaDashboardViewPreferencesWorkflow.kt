package com.baraa.masroof.lab.beta.application

import android.content.Context
import com.baraa.masroof.lab.beta.BetaEnvironment
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.finance.value.Environment
import kotlinx.coroutines.withContext

/** Nonfinancial preferences in the same isolated, backed-up Beta preference file. */
class BetaDashboardViewPreferencesWorkflow(context: Context) {
    private val context = context.applicationContext
    internal val chartData by lazy {BetaChartDataWorkflow(com.baraa.masroof.lab.beta.data.SharedBetaChartDataPreferencesRepository(this.context))}
    private fun preferences() = context.getSharedPreferences(BetaEnvironment.PREFS_NAME.also(BetaEnvironment::requireBetaPreferenceName), Context.MODE_PRIVATE)
    /** Restore/reset may replace preferences while this same workflow is still alive. */
    suspend fun refresh() = withContext(BetaIo.dispatcher) {chartData.reload()}
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
