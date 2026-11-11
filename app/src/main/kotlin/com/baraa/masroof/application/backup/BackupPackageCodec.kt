package com.baraa.masroof.application.backup

import kotlinx.serialization.json.Json

/**
 * Pure encode/decode helpers for the inner ZIP payload (no Android APIs).
 * New exports wrap that ZIP in [BackupEnvelope]. [BackupPackageFormat.FORMAT_VERSION]
 * stays 1 so a confirmed legacy plaintext ZIP still validates.
 */
object BackupPackageCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeManifest(manifest: BackupManifest): String =
        json.encodeToString(BackupManifest.serializer(), manifest)

    fun decodeManifest(raw: String): BackupManifest =
        json.decodeFromString(BackupManifest.serializer(), raw)

    fun encodePreferences(snapshot: BackupPreferencesSnapshot): String =
        json.encodeToString(BackupPreferencesSnapshot.serializer(), snapshot)

    fun decodePreferences(raw: String): BackupPreferencesSnapshot =
        json.decodeFromString(BackupPreferencesSnapshot.serializer(), raw).also {
            com.baraa.masroof.application.analytics.AnalyticsBackup.validate(it.labAnalysis)
            com.baraa.masroof.application.backup.MessagePatternBackup.validate(it.messagePatterns)
        }

    /** Accepts only a known (version, identity hash) pair from [identityHashes]. */
    fun validateManifest(
        manifest: BackupManifest,
        identityHashes: Map<Int, String>,
    ): Boolean = manifest.formatVersion == BackupPackageFormat.FORMAT_VERSION &&
        identityHashes[manifest.roomVersion] == manifest.identityHash
}
