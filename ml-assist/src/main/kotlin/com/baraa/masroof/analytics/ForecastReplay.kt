package com.baraa.masroof.analytics

data class ForecastReplayRow(val originDay: Long, val periodStart: Long, val periodEnd: Long,
    val forecast: PurchaseForecast, val actual: Long?)

/** Offline evaluation only: a replay is never a published historical forecast. */
object ForecastReplay {
    fun evaluate(data: CleanData, periodStart: Long, periodEnd: Long, originDay: Long, completedPeriods: Int,
        phase: (Long) -> Int, completedPeriodsAt: (Long) -> Int = { completedPeriods }): ForecastReplayRow {
        // The complete origin day's amount was unknown at this simulated start of day.
        val prefix = data.copy(days = data.days.filter { it.day <= originDay }.map { day ->
            if (day.day == originDay) day.copy(gross = 0, refunds = 0, count = 0, irregular = 0) else day
        })
        val forecast = PurchaseForecaster.forecast(prefix, periodStart, periodEnd, originDay, completedPeriods,
            phase, completedPeriodsAt = completedPeriodsAt)
        val actualDays = data.days.filter { it.day in periodStart until periodEnd }
        val actual = if (actualDays.size.toLong() == periodEnd - periodStart && actualDays.all { it.covered })
            actualDays.fold(0L) { sum, day -> Math.addExact(sum, day.net) } else null
        return ForecastReplayRow(originDay, periodStart, periodEnd, forecast, actual)
    }
}
