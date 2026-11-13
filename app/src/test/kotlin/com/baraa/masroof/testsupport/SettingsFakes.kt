package com.baraa.masroof.testsupport

import android.net.Uri
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.notification.NotificationPreferencesRepository
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.theme.ThemePreferencesRepository

/** Backup gateway that reports [kind], answers imports with [outcome] and records calls. */
internal class FakeDatabaseBackupGateway(
    private val kind: BackupPackageKind = BackupPackageKind.UNRECOGNIZED,
    var outcome: BackupImportOutcome = BackupImportOutcome.InvalidPackage,
) : DatabaseBackupGateway {
    var importCalls: Int = 0
    var exportCalls: Int = 0
    var exportPhrase: CharArray? = null

    override suspend fun exportTo(destination: Uri, passphrase: CharArray): Result<Unit> {
        exportCalls++
        exportPhrase = passphrase
        return Result.success(Unit)
    }

    override suspend fun inspect(source: Uri): BackupPackageKind = kind

    override suspend fun importFrom(source: Uri, passphrase: CharArray): BackupImportOutcome {
        importCalls++
        return outcome
    }
}

internal class FakeThemePreferencesRepository(
    private var mode: ThemeMode = ThemeMode.SYSTEM,
) : ThemePreferencesRepository {
    override fun getThemeMode(): ThemeMode = mode

    override fun setThemeMode(mode: ThemeMode) {
        this.mode = mode
    }
}

internal class FakeNotificationPreferencesRepository : NotificationPreferencesRepository {
    private var readIds: Set<String> = emptySet()

    override fun getReadIds(): Set<String> = readIds

    override fun markRead(id: String) {
        readIds = readIds + id
    }

    override fun setReadIds(ids: Set<String>) {
        readIds = ids.toSet()
    }
}
