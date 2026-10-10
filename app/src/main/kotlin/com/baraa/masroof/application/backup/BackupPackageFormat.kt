package com.baraa.masroof.application.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val appVersionName: String,
    val roomVersion: Int,
    val identityHash: String,
    val exportedAtEpochMillis: Long,
)

@Serializable
data class BackupPreferencesSnapshot(
    val onboardingStarted: Boolean,
    val onboardingCompleted: Boolean,
    val historicalImportStartEpochMillis: Long? = null,
    val historicalImportCompleted: Boolean,
    val languageTag: String,
    val themeMode: String,
)

enum class BackupImportOutcome {
    SuccessNeedsRestart,
    InvalidPackage,
    Failed,
    /** Plaintext v1 ZIP. Nothing was imported. The caller must ask, then retry with confirmation. */
    LegacyConfirmationRequired,
    AuthenticationFailed,
}

enum class BackupPackageKind {
    ENCRYPTED,
    LEGACY_PLAINTEXT,
    UNRECOGNIZED,
}

/** Stable log tokens. Messages built from these must not include secrets or file bytes. */
enum class BackupFailureCategory(val logToken: String) {
    PASSPHRASE_REQUIRED("passphrase_required"),
    AUTHENTICATION_FAILED("authentication_failed"),
    INVALID_ENVELOPE("invalid_envelope"),
    ARCHIVE_REJECTED("archive_rejected"),
    ENVELOPE_TOO_LARGE("envelope_too_large"),
    INVALID_PACKAGE("invalid_package"),
    EXPORT_FAILED("export_failed"),
    IMPORT_FAILED("import_failed"),
}

class BackupFailureException(
    val category: BackupFailureCategory,
) : Exception(category.logToken)

object BackupPackageFormat {
    /** Inner ZIP manifest version. Legacy plaintext backups are this version. */
    const val FORMAT_VERSION: Int = 1

    /** Outer authenticated envelope version. Not a ZIP. */
    const val ENVELOPE_VERSION: Int = 1
    const val FILE_EXTENSION: String = "masroof"
    const val MIME_TYPE: String = "application/octet-stream"
    const val MANIFEST_ENTRY: String = "manifest.json"
    const val DATABASE_ENTRY: String = "masroof.db"
    const val PREFERENCES_ENTRY: String = "preferences.json"

    private val ZIP_LOCAL_HEADER: ByteArray = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

    fun looksLikeZip(prefix: ByteArray, length: Int): Boolean {
        if (length < ZIP_LOCAL_HEADER.size) return false
        return prefix[0] == ZIP_LOCAL_HEADER[0] &&
            prefix[1] == ZIP_LOCAL_HEADER[1] &&
            prefix[2] == ZIP_LOCAL_HEADER[2] &&
            prefix[3] == ZIP_LOCAL_HEADER[3]
    }

    fun defaultExportFileName(exportedAtEpochMillis: Long): String =
        "masroof-backup-$exportedAtEpochMillis.$FILE_EXTENSION"
}
