package com.baraa.masroof.presentation.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogEntry
import com.baraa.masroof.application.logging.AppLogLevel
import com.baraa.masroof.application.logging.AppLogService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class SettingsLogFilter {
    ALL,
    ERRORS,
    WARNINGS,
    SMS_PIPELINE,
    UPDATES,
}

internal sealed interface SettingsLogListItem {
    data class DayHeader(val label: String) : SettingsLogListItem

    data class Entry(val entry: AppLogEntry) : SettingsLogListItem
}

internal fun filterLogEntries(
    entries: List<AppLogEntry>,
    filter: SettingsLogFilter,
): List<AppLogEntry> =
    when (filter) {
        SettingsLogFilter.ALL -> entries
        SettingsLogFilter.ERRORS -> entries.filter { it.level == AppLogLevel.ERROR }
        SettingsLogFilter.WARNINGS -> entries.filter { it.level == AppLogLevel.WARN }
        SettingsLogFilter.SMS_PIPELINE ->
            entries.filter {
                it.category in SMS_PIPELINE_CATEGORIES
            }
        SettingsLogFilter.UPDATES -> entries.filter { it.category == AppLogCategories.UPDATE }
    }

internal fun groupLogEntriesByDay(
    entries: List<AppLogEntry>,
    zoneId: ZoneId,
    todayLabel: String,
    yesterdayLabel: String,
    dateFormatter: DateTimeFormatter,
    today: LocalDate = LocalDate.now(zoneId),
): List<SettingsLogListItem> {
    if (entries.isEmpty()) return emptyList()
    val yesterday = today.minusDays(1)
    val grouped = entries
        .sortedByDescending { it.timestampEpochMs }
        .groupBy { entry ->
            Instant.ofEpochMilli(entry.timestampEpochMs).atZone(zoneId).toLocalDate()
        }
        .toSortedMap(compareByDescending { it })

    return buildList {
        grouped.forEach { (date, dayEntries) ->
            val header = when (date) {
                today -> todayLabel
                yesterday -> yesterdayLabel
                else -> dateFormatter.format(date)
            }
            add(SettingsLogListItem.DayHeader(header))
            dayEntries.forEach { add(SettingsLogListItem.Entry(it)) }
        }
    }
}

internal data class LogTimestampLabels(
    val justNow: String,
    val minutesAgoFormat: String,
    val hoursAgoFormat: String,
)

internal fun formatLogTimestamp(
    timestampEpochMs: Long,
    zoneId: ZoneId,
    locale: Locale,
    labels: LogTimestampLabels,
    nowEpochMs: Long = System.currentTimeMillis(),
): String {
    val instant = Instant.ofEpochMilli(timestampEpochMs)
    val now = Instant.ofEpochMilli(nowEpochMs)
    val minutes = ChronoUnit.MINUTES.between(instant, now).coerceAtLeast(0)
    return when {
        minutes < 1 -> labels.justNow
        minutes < 60 -> String.format(locale, labels.minutesAgoFormat, minutes)
        ChronoUnit.HOURS.between(instant, now) < 24 -> {
            val hours = ChronoUnit.HOURS.between(instant, now)
            String.format(locale, labels.hoursAgoFormat, hours)
        }
        else -> {
            val formatter = DateTimeFormatter.ofPattern("d MMM · HH:mm", locale)
            formatter.format(instant.atZone(zoneId))
        }
    }
}

@StringRes
internal fun logLevelLabelRes(level: AppLogLevel): Int =
    when (level) {
        AppLogLevel.INFO -> R.string.settings_log_level_info
        AppLogLevel.WARN -> R.string.settings_log_level_warn
        AppLogLevel.ERROR -> R.string.settings_log_level_error
    }

@StringRes
internal fun logCategoryLabelRes(category: String): Int =
    when (category) {
        AppLogCategories.SMS -> R.string.settings_log_category_sms
        AppLogCategories.INGEST -> R.string.settings_log_category_ingest
        AppLogCategories.SCAN -> R.string.settings_log_category_scan
        AppLogCategories.PARSE -> R.string.settings_log_category_parse
        AppLogCategories.OWNERSHIP -> R.string.settings_log_category_ownership
        AppLogCategories.TRANSACTION -> R.string.settings_log_category_transaction
        AppLogCategories.REVIEW -> R.string.settings_log_category_review
        AppLogCategories.DASHBOARD -> R.string.settings_log_category_dashboard
        AppLogCategories.BACKUP -> R.string.settings_log_category_backup
        AppLogCategories.SETTINGS -> R.string.settings_log_category_settings
        AppLogCategories.UPDATE -> R.string.settings_log_category_update
        AppLogCategories.LOGS -> R.string.settings_log_category_logs
        else -> R.string.settings_log_category_general
    }

@Composable
internal fun logCategoryLabel(category: String): String =
    stringResource(logCategoryLabelRes(category))

internal val LOG_MAX_ENTRIES: Int = AppLogService.DEFAULT_MAX_ENTRIES
internal val LOG_RETENTION_DAYS: Long = AppLogService.DEFAULT_RETENTION_DAYS

private val SMS_PIPELINE_CATEGORIES = setOf(
    AppLogCategories.SMS,
    AppLogCategories.INGEST,
    AppLogCategories.SCAN,
    AppLogCategories.PARSE,
)
