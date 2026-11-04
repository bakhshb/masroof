package com.baraa.masroof.presentation.settings

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogEntry
import com.baraa.masroof.application.logging.AppLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class SettingsLogPresentationTest {
    @Test
    fun filter_errors_returnsOnlyErrorLevel() {
        val entries = listOf(
            entry(level = AppLogLevel.INFO),
            entry(level = AppLogLevel.ERROR),
            entry(level = AppLogLevel.WARN),
        )

        val filtered = filterLogEntries(entries, SettingsLogFilter.ERRORS)

        assertEquals(1, filtered.size)
        assertEquals(AppLogLevel.ERROR, filtered.single().level)
    }

    @Test
    fun filter_smsPipeline_matchesSmsIngestScanParseCategories() {
        val entries = listOf(
            entry(category = AppLogCategories.SMS),
            entry(category = AppLogCategories.UPDATE),
            entry(category = AppLogCategories.PARSE),
        )

        val filtered = filterLogEntries(entries, SettingsLogFilter.SMS_PIPELINE)

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.category in setOf(AppLogCategories.SMS, AppLogCategories.PARSE) })
    }

    @Test
    fun groupLogEntriesByDay_addsHeadersInDescendingDateOrder() {
        val zoneId = ZoneId.of("UTC")
        val today = java.time.LocalDate.parse("2026-11-04")
        val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli() + 3_600_000L
        val yesterdayMs = today.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() + 3_600_000L
        val entries = listOf(
            entry(id = 1, timestampEpochMs = todayMs),
            entry(id = 2, timestampEpochMs = yesterdayMs),
        )

        val grouped = groupLogEntriesByDay(
            entries = entries,
            zoneId = zoneId,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
            dateFormatter = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"),
            today = today,
        )

        assertEquals(4, grouped.size)
        assertTrue(grouped[0] is SettingsLogListItem.DayHeader)
        assertEquals("Today", (grouped[0] as SettingsLogListItem.DayHeader).label)
        assertEquals(1L, (grouped[1] as SettingsLogListItem.Entry).entry.id)
        assertEquals("Yesterday", (grouped[2] as SettingsLogListItem.DayHeader).label)
    }

    @Test
    fun formatLogTimestamp_usesRelativeLabels() {
        val now = 1_700_000_000_000L
        val labels = LogTimestampLabels(
            justNow = "Just now",
            minutesAgoFormat = "%1\$d min ago",
            hoursAgoFormat = "%1\$d h ago",
        )

        val justNow = formatLogTimestamp(
            timestampEpochMs = now - 30_000L,
            zoneId = ZoneId.of("UTC"),
            locale = Locale.ENGLISH,
            labels = labels,
            nowEpochMs = now,
        )
        val minutesAgo = formatLogTimestamp(
            timestampEpochMs = now - 5 * 60_000L,
            zoneId = ZoneId.of("UTC"),
            locale = Locale.ENGLISH,
            labels = labels,
            nowEpochMs = now,
        )

        assertEquals("Just now", justNow)
        assertEquals("5 min ago", minutesAgo)
    }

    private fun entry(
        id: Long = 1L,
        timestampEpochMs: Long = 1_700_000_000_000L,
        category: String = AppLogCategories.INGEST,
        level: AppLogLevel = AppLogLevel.INFO,
    ) = AppLogEntry(
        id = id,
        timestampEpochMs = timestampEpochMs,
        category = category,
        level = level,
        message = "sample",
    )
}
