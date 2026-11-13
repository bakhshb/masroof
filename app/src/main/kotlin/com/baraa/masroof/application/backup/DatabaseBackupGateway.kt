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

    /** Authenticate and validate a staged package without closing or replacing live data. */
    suspend fun verify(source: Uri, passphrase: CharArray): BackupVerificationOutcome {
        passphrase.fill('\u0000')
        return BackupVerificationOutcome.FAILED
    }

    /**
     * Imports an encrypted envelope; anything else is an invalid package. [passphrase] is wiped before return.
     * Authentication and archive failures return before the live database is closed or replaced.
     */
    suspend fun importFrom(source: Uri, passphrase: CharArray): BackupImportOutcome
}

enum class BackupVerificationOutcome { VERIFIED, AUTHENTICATION_FAILED, INVALID_PACKAGE, FAILED }
