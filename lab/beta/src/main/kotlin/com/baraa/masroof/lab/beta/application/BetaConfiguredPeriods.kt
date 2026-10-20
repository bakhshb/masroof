package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardPeriods
import com.baraa.masroof.lab.finance.value.Environment
import java.time.*

/** Current cycle contains the atomic read date; calendar navigation shifts that cycle by whole months. */
internal object BetaConfiguredPeriods {
    fun periods(draft: BetaDashboardConfigurationDraft, query: DashboardQuery, asOf: Instant): DashboardPeriods {
        val anchor = asOf.atZone(query.zone).toLocalDate()
        val offset = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(query.until.minusNanos(1).atZone(query.zone)))
        fun period(day: Int, salary: Boolean): DisplayPeriod {
            fun start(month: YearMonth): LocalDate {
                val date = month.atDay(minOf(day, month.lengthOfMonth()))
                return if (salary && day == 27) when (date.dayOfWeek) {
                    DayOfWeek.FRIDAY -> date.minusDays(1)
                    DayOfWeek.SATURDAY -> date.plusDays(1)
                    else -> date
                } else date
            }
            val month = YearMonth.from(anchor)
            val first = start(month)
            val chosen = (if (anchor < first) month.minusMonths(1) else month).plusMonths(offset)
            return DisplayPeriod(start(chosen).atStartOfDay(query.zone).toInstant(),
                start(chosen.plusMonths(1)).atStartOfDay(query.zone).toInstant(), query.zone,
                if (salary) PeriodKind.SALARY else PeriodKind.STATEMENT, null)
        }
        require(draft.salary == null || draft.salary.zone == query.zone) { "Configured salary zone differs from the query" }
        val calendarMonth = YearMonth.from(query.until.minusNanos(1).atZone(query.zone))
        return DashboardPeriods(draft.salary?.let { period(it.day, true) },
            DisplayPeriod(calendarMonth.atDay(1).atStartOfDay(query.zone).toInstant(), calendarMonth.plusMonths(1).atDay(1).atStartOfDay(query.zone).toInstant(), query.zone, PeriodKind.CALENDAR_MONTH, null),
            draft.statements.associate { it.facilityId to period(it.closingDay, false) })
    }

    fun occurrences(draft: BetaDashboardConfigurationDraft, salary: DisplayPeriod?): List<CommitmentRow> =
        com.baraa.masroof.lab.beta.application.dashboard.BetaCommitmentOccurrenceCalculator.rows(draft,salary)
}
