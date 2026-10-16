package com.baraa.masroof.application.beta

import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.update.PendingUpdateStore
import com.baraa.masroof.application.update.UpdateCheckPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository
import com.baraa.masroof.data.preferences.SharedPrefsChartDataPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsDashboardLayoutPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsGitHubTokenRepository
import com.baraa.masroof.data.preferences.SharedPrefsNotificationPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsThemePreferencesRepository
import com.baraa.masroof.data.room.MasroofDatabase

/**
 * Names and guards for the isolated Masroof Beta process state.
 *
 * Beta never opens the production database, the parsing-lab database, or a
 * production preference file. Migration rehearsals use a third file later;
 * this shell does not open that file either.
 */
object BetaEnvironment {
    const val DATABASE_NAME: String = "masroof_beta.db"
    const val MIGRATION_STAGING_DATABASE_NAME: String = "masroof_beta_migration_test.db"
    const val PREFS_NAME: String = "masroof_beta_prefs"
    const val BACKUP_DATABASE_FILE_NAME: String = "masroof_beta_backup.db"
    const val BACKUP_PREFS_FILE_NAME: String = "masroof_beta_prefs_backup.txt"
    const val PARSING_LAB_DATABASE_NAME: String = "masroof_parsing_lab.db"

    val productionDatabaseNames: Set<String> = setOf(
        MasroofDatabase.NAME,
        PARSING_LAB_DATABASE_NAME,
    )

    val productionPreferenceNames: Set<String> = setOf(
        SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
        SharedPrefsAppLocaleRepository.PREFS_NAME,
        SharedPrefsThemePreferencesRepository.PREFS_NAME,
        SharedPrefsDashboardLayoutPreferencesRepository.PREFS_NAME,
        SharedPrefsChartDataPreferencesRepository.PREFS_NAME,
        SharedPrefsNotificationPreferencesRepository.PREFS_NAME,
        SharedPrefsGitHubTokenRepository.PREFS_NAME,
        PendingUpdateStore.PREFS_NAME,
        UpdateCheckPreferencesRepository.PREFS_NAME,
        MaintenancePreferences.PREFS_NAME,
    )

    fun requireBetaDatabaseName(name: String) {
        require(name == DATABASE_NAME) { "Beta opens $DATABASE_NAME only, was $name" }
        require(name !in productionDatabaseNames) { "Beta cannot open production database $name" }
        require(name != MIGRATION_STAGING_DATABASE_NAME) {
            "Beta shell does not open the migration staging database"
        }
    }

    fun requireBetaPreferenceName(name: String) {
        require(name == PREFS_NAME) { "Beta preferences are $PREFS_NAME only, was $name" }
        require(name !in productionPreferenceNames) { "Beta cannot open production preferences $name" }
    }

    fun requireBackupDatabaseName(name: String) {
        require(name == BACKUP_DATABASE_FILE_NAME) { "Beta backup file is fixed, was $name" }
        require(name !in productionDatabaseNames) { "Beta backup cannot replace $name" }
        require(name != DATABASE_NAME) { "Beta backup must not overwrite the live beta database name" }
    }

    fun requireBackupPreferenceName(name: String) {
        require(name == BACKUP_PREFS_FILE_NAME) { "Beta preference backup is fixed, was $name" }
        require(name !in productionPreferenceNames) { "Beta preference backup cannot replace $name" }
    }
}
