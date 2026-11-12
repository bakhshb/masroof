package com.baraa.masroof.application.backup

import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.serialization.json.Json

/**
 * Pure encode/decode helpers for the inner ZIP payload (no Android APIs).
 * New exports wrap that ZIP in [BackupEnvelope]. A format names what the archive holds: the legacy format is a
 * schema v18 database with its patterns in `preferences.json`, the current one a later database that holds them.
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

    /** Throws unless the preferences are valid for an archive of [formatVersion]: only the legacy format carries patterns here. */
    fun decodePreferences(raw: String, formatVersion: Int): BackupPreferencesSnapshot =
        json.decodeFromString(BackupPreferencesSnapshot.serializer(), raw).also {
            com.baraa.masroof.application.analytics.AnalyticsBackup.validate(it.labAnalysis)
            if (it.messagePatterns != null) {
                require(formatVersion == BackupPackageFormat.LEGACY_FORMAT_VERSION) { "Patterns outside the database" }
                MessagePatternConfigurationCodec.decode(it.messagePatterns)
            }
        }

    /**
     * Accepts only a known (version, identity hash) pair from [identityHashes] in the format that goes with it: the
     * legacy format with the v18 baseline, the current format with any later schema.
     */
    fun validateManifest(
        manifest: BackupManifest,
        identityHashes: Map<Int, String>,
    ): Boolean = identityHashes[manifest.roomVersion] == manifest.identityHash && when (manifest.formatVersion) {
        BackupPackageFormat.LEGACY_FORMAT_VERSION -> manifest.roomVersion == MasroofDatabase.BASELINE_VERSION
        BackupPackageFormat.FORMAT_VERSION -> manifest.roomVersion > MasroofDatabase.BASELINE_VERSION
        else -> false
    }
}
