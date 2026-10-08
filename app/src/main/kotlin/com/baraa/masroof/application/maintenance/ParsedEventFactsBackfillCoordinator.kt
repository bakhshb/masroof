package com.baraa.masroof.application.maintenance

import android.content.SharedPreferences
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BackfillOutcome {
    UP_TO_DATE,
    COMPLETED,

    /** Rows failed or the run threw; the schema version stays unrecorded so the backlog is retried. */
    INCOMPLETE,
}

/**
 * Maintenance after schema upgrades that may add nullable parse-fact columns.
 *
 * Migrations only alter the table shape; existing rows keep NULL facts until the
 * parser runs again. This coordinator re-parses the stored RawSms backlog once per
 * schema version (including evidence that never produced a ParsedEvent) so
 * dashboard and reconciliation see populated facts. [pendingRequirement] says whether the
 * pending re-parse must block startup ([SchemaFactsBackfillPolicy]).
 *
 * Runs are serialized, so startup and the background worker never re-parse concurrently.
 */
class ParsedEventFactsBackfillCoordinator(
    private val prefs: SharedPreferences,
    private val appLogService: AppLogService,
    private val reparseAllStoredEvents: suspend () -> ReparseAllStoredEventsResult,
    private val completionSignal: MaintenanceCompletionSignal? = null,
) {
    private val mutex = Mutex()

    fun pendingRequirement(currentSchemaVersion: Int = MasroofDatabase.VERSION): MaintenanceRequirement? =
        SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion(), currentSchemaVersion)

    suspend fun runIfNeeded(currentSchemaVersion: Int = MasroofDatabase.VERSION): BackfillOutcome = mutex.withLock {
        val lastReparsedVersion = lastReparsedVersion()
        if (currentSchemaVersion <= lastReparsedVersion) return@withLock BackfillOutcome.UP_TO_DATE
        if (SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion, currentSchemaVersion) ==
            MaintenanceRequirement.NOT_REQUIRED
        ) {
            recordSchemaVersion(currentSchemaVersion)
            appLogService.info(
                AppLogCategories.PARSE,
                "Schema v$currentSchemaVersion requires no parse-fact backfill",
            )
            return@withLock BackfillOutcome.COMPLETED
        }

        appLogService.info(
            AppLogCategories.PARSE,
            "Schema facts backfill started: v$lastReparsedVersion -> v$currentSchemaVersion",
        )
        val result = try {
            reparseAllStoredEvents()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.warn(
                AppLogCategories.PARSE,
                "Schema facts backfill failed: ${e.javaClass.simpleName}; will retry",
            )
            null
        } finally {
            completionSignal?.notifyCompleted()
        }
        if (result == null) return@withLock BackfillOutcome.INCOMPLETE
        if (!result.succeeded) {
            appLogService.warn(
                AppLogCategories.PARSE,
                "Schema facts backfill incomplete: ${result.failedCount} messages failed; will retry",
            )
            return@withLock BackfillOutcome.INCOMPLETE
        }
        recordSchemaVersion(currentSchemaVersion)
        appLogService.info(
            AppLogCategories.PARSE,
            "Schema facts backfill finished: ${result.refreshedCount} messages refreshed",
        )
        BackfillOutcome.COMPLETED
    }

    private fun recordSchemaVersion(currentSchemaVersion: Int) {
        prefs.edit()
            .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, currentSchemaVersion)
            .apply()
    }

    private fun lastReparsedVersion(): Int =
        prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0)
}
