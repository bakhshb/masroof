package com.baraa.masroof.application.backup

import android.net.Uri

interface DatabaseBackupGateway {
    /**
     * Writes an authenticated encrypted envelope. [passphrase] is wiped before return.
     * An empty passphrase fails and does not write a plaintext archive.
     */
    suspend fun exportTo(destination: Uri, passphrase: CharArray): Result<Unit>

    /** Reads only the file prefix. Does not decrypt, unzip, or touch the live database. */
    suspend fun inspect(source: Uri): BackupPackageKind

    /**
     * Imports an encrypted envelope, or a legacy v1 ZIP when [confirmLegacyPlaintext] is true.
     * [passphrase] is wiped before return. A legacy ZIP does not use it.
     * Authentication and archive failures return before the live database is closed or replaced.
     */
    suspend fun importFrom(
        source: Uri,
        passphrase: CharArray = CharArray(0),
        confirmLegacyPlaintext: Boolean = false,
    ): BackupImportOutcome
}
