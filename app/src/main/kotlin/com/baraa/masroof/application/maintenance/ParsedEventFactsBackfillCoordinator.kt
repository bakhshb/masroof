package com.baraa.masroof.application.maintenance

import android.content.SharedPreferences
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.data.room.MasroofDatabase

/**
 * One-time maintenance after schema upgrades that add nullable parse-fact columns.
 *
 * Migrations only alter the table shape; existing rows keep NULL facts until the
 * parser runs again. This coordinator re-parses the stored RawSms backlog once per
 * schema version (including evidence that never produced a ParsedEvent) so
 * dashboard and reconciliation see populated facts.
 */
class ParsedEventFactsBackfillCoordinator(
    private val prefs: SharedPreferences,
    private val appLogService: AppLogService,
    private val reparseAllStoredEvents: suspend () -> ReparseAllStoredEventsResult,
) {
    suspend fun runIfNeeded(currentSchemaVersion: Int = MasroofDatabase.VERSION) {
        val lastReparsedVersion = prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0)
        if (currentSchemaVersion <= lastReparsedVersion) return

        appLogService.info(
            AppLogCategories.PARSE,
            "Schema facts backfill started: v$lastReparsedVersion -> v$currentSchemaVersion",
        )
        val result = reparseAllStoredEvents()
        if (!result.succeeded) {
            appLogService.warn(
                AppLogCategories.PARSE,
                "Schema facts backfill incomplete: ${result.failedCount} messages failed; will retry on next launch",
            )
            return
        }
        prefs.edit()
            .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, currentSchemaVersion)
            .apply()
        appLogService.info(
            AppLogCategories.PARSE,
            "Schema facts backfill finished: ${result.refreshedCount} messages refreshed",
        )
    }
}
