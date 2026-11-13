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
    val labAnalysis: String? = null,
    val onboardingSetup: String? = null,
)

enum class BackupImportOutcome {
    SuccessNeedsRestart,
    InvalidPackage,
    Failed,
    AuthenticationFailed,
}

enum class BackupPackageKind {
    ENCRYPTED,
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
    /** Inner ZIP manifest version. The patterns and recurring answers travel inside the database. */
    const val FORMAT_VERSION: Int = 1

    /** Outer authenticated envelope version. Not a ZIP. */
    const val ENVELOPE_VERSION: Int = 1
    const val FILE_EXTENSION: String = "masroof"
    const val MIME_TYPE: String = "application/octet-stream"
    const val MANIFEST_ENTRY: String = "manifest.json"
    const val DATABASE_ENTRY: String = "masroof.db"
    const val PREFERENCES_ENTRY: String = "preferences.json"

    fun defaultExportFileName(exportedAtEpochMillis: Long): String =
        "masroof-backup-$exportedAtEpochMillis.$FILE_EXTENSION"
}
