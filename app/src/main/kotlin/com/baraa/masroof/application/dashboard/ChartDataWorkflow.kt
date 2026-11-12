package com.baraa.masroof.application.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class ChartDataPreferences(
    val selections: Map<String, List<ChartDataCategory>> = emptyMap(),
    val homeScope: String = "purchases",
)

interface ChartDataPreferencesRepository {
    fun load(): ChartDataPreferences
    fun save(value: ChartDataPreferences)
}

class InMemoryChartDataPreferencesRepository : ChartDataPreferencesRepository {
    private var value = ChartDataPreferences()
    override fun load() = value
    override fun save(value: ChartDataPreferences) { this.value = value }
}

/** Durable choices are separate from read-only financial projections and entity totals. */
class ChartDataWorkflow(private val repository: ChartDataPreferencesRepository) {
    private val mutable = MutableStateFlow(repository.load())
    val preferences: StateFlow<ChartDataPreferences> = mutable.asStateFlow()

    fun categories(trend: DailySpendingTrend, key: String): Set<ChartDataCategory> {
        val allowed = trend.categoryTrends.keys
        val saved = mutable.value.selections[key] ?: return allowed
        return saved.toSet().intersect(allowed).ifEmpty { allowed }
    }

    fun save(key: String, categories: Set<ChartDataCategory>, allowed: Set<ChartDataCategory>) {
        val valid = categories.intersect(allowed)
        require(valid.isNotEmpty()) { "At least one chart category is required" }
        val selections = if (valid == allowed) mutable.value.selections - key
            else mutable.value.selections + (key to valid.toList())
        update(mutable.value.copy(selections = selections))
    }

    fun setHomeScope(scope: String) {
        require(scope == "purchases" || scope == "overall")
        update(mutable.value.copy(homeScope = scope))
    }

    private fun update(value: ChartDataPreferences) {
        repository.save(value)
        mutable.value = value
    }

    fun selected(trend: DailySpendingTrend, key: String): DailySpendingTrend =
        select(trend, categories(trend, key))

    companion object {
        /** The same category contribution owns the line, mean, gross/refunds and drill-down IDs. */
        fun select(trend: DailySpendingTrend, categories: Set<ChartDataCategory>): DailySpendingTrend {
            if (trend.categoryTrends.isEmpty() || categories == trend.categoryTrends.keys) return trend
            val selected = categories.mapNotNull(trend.categoryTrends::get)
            val currency = trend.averageDailySpending.currency
            val points = trend.points.map { original ->
                val rows = selected.mapNotNull { part -> part.points.firstOrNull { it.date == original.date } }
                fun amount(value: (DailySpendingPoint) -> SignedMoneyAmount) = SignedMoneyAmount(
                    rows.fold(BigDecimal.ZERO) { sum, row -> sum + value(row).amount }.setScale(2), currency)
                DailySpendingPoint(original.date, amount { it.spending }, amount { it.beforeRefunds }, amount { it.refunds })
            }
            val total = points.fold(BigDecimal.ZERO) { sum, point -> sum + point.spending.amount }
            val average = if (points.isEmpty()) BigDecimal.ZERO.setScale(2)
                else total.divide(points.size.toBigDecimal(), 2, RoundingMode.HALF_EVEN)
            return trend.copy(points = points, averageDailySpending = SignedMoneyAmount(average, currency),
                transactionIdsByDate = points.associate { point -> point.date to selected.flatMap {
                    it.transactionIdsByDate[point.date].orEmpty() }.toSet() })
        }
    }
}
