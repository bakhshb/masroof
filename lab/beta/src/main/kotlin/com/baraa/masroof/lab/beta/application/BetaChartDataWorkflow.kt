package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.math.RoundingMode

internal data class BetaChartDataPreferences(val selections: Map<String,List<ChartCategory>> = emptyMap())
internal interface BetaChartDataPreferencesRepository {
    fun load(): BetaChartDataPreferences
    fun save(value: BetaChartDataPreferences)
}
internal class InMemoryBetaChartDataPreferencesRepository : BetaChartDataPreferencesRepository {
    private var value=BetaChartDataPreferences()
    override fun load()=value
    override fun save(value: BetaChartDataPreferences) {this.value=value}
}

/** Port of production ChartDataWorkflow. Preferences affect chart projections only. */
internal class BetaChartDataWorkflow(private val repository: BetaChartDataPreferencesRepository) {
    private val mutable=MutableStateFlow(repository.load())
    val preferences: StateFlow<BetaChartDataPreferences> = mutable.asStateFlow()
    fun categories(trend: SpendingTrend,key: String): Set<ChartCategory> {
        val allowed=trend.categoryTrends.keys
        return mutable.value.selections[key]?.toSet()?.intersect(allowed)?.ifEmpty {allowed} ?: allowed
    }
    fun save(key: String,categories: Set<ChartCategory>,allowed: Set<ChartCategory>) {
        val valid=categories.intersect(allowed)
        require(valid.isNotEmpty()) {"At least one chart category is required"}
        val selections=if(valid==allowed) mutable.value.selections-key else mutable.value.selections+(key to valid.toList())
        val next=BetaChartDataPreferences(selections)
        repository.save(next)
        mutable.value=next
    }
    fun selected(trend: SpendingTrend,key: String)=select(trend,categories(trend,key))
    companion object {
        /** Includes environment and full bank-qualified entity identity; display labels never enter keys. */
        fun key(scope: TrendScope,asset: AssetId?): String = "chart:${scope.name}:"+(asset?.let {
            "${it.environment.name}:${it.bank.value.length}:${it.bank.value}:${it.localId.length}:${it.localId}"
        } ?: "aggregate")

        /** The line, mean, gross/refunds and drill-down IDs use exactly the same contributions. */
        fun select(trend: SpendingTrend,categories: Set<ChartCategory>): SpendingTrend {
            val allowed=trend.categoryTrends.keys
            if(allowed.isEmpty() || categories==allowed) return trend
            val selected=categories.intersect(allowed).map(trend.categoryTrends::getValue)
            require(selected.isNotEmpty())
            fun amount(values: List<SignedAmount?>): SignedAmount? = if(values.any {it==null}) null
                else SignedAmount(values.fold(BigDecimal.ZERO) {sum,value -> sum+requireNotNull(value).amount}.setScale(2),trend.currency)
            val indexed=selected.map {part -> part to part.points.associateBy {it.date}}
            val points=trend.points.map {original ->
                val rows=indexed.map {(part,byDate) -> byDate[original.date] ?: run {
                    val zero=if(part.totalSpending==null) null else SignedAmount(BigDecimal.ZERO,trend.currency)
                    DailySpending(original.date,zero,zero,zero,emptySet())
                }}
                DailySpending(original.date,amount(rows.map {it.net}),amount(rows.map {it.beforeRefunds}),
                    amount(rows.map {it.refunds}),rows.flatMap {it.transactions}.toSet())
            }
            val total=amount(points.map {it.net})
            val average=total?.let {SignedAmount(if(points.isEmpty()) BigDecimal.ZERO.setScale(2)
                else it.amount.divide(points.size.toBigDecimal(),2,RoundingMode.HALF_EVEN),trend.currency)}
            return trend.copy(points=points,averageDailySpending=average,totalSpending=total,
                totalBeforeRefunds=amount(points.map {it.beforeRefunds}),totalRefunds=amount(points.map {it.refunds}))
        }
    }
}
