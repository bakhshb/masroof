package com.baraa.masroof.application.container

import com.baraa.masroof.BuildConfig
import com.baraa.masroof.application.backup.DatabaseBackupService

/** Backup export and restore. [beforeValidatedInstall] stops the work that would write while a restore installs. */
class BackupContainer(
    private val storage: StorageContainer,
    private val restartAfterRestore: (() -> Unit)?,
    private val beforeValidatedInstall: () -> Unit,
) {
    val databaseBackupService: DatabaseBackupService by lazy {
        DatabaseBackupService(
            appContext = storage.appContext,
            database = storage.database,
            closeDatabase = { storage.database.close() },
            databaseAccessGate = storage.databaseAccessGate,
            appVersionName = BuildConfig.VERSION_NAME,
            appLogService = storage.appLogService,
            restartProcess = restartAfterRestore,
            beforeValidatedInstall = beforeValidatedInstall,
        )
    }
}
