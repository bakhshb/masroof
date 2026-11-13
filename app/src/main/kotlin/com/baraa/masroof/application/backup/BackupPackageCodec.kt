package com.baraa.masroof.application.backup

import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.serialization.json.Json

/**
 * Pure encode/decode helpers for the inner ZIP payload (no Android APIs).
 * New exports wrap that ZIP in [BackupEnvelope].
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

    /** Throws unless the preferences are valid. */
    fun decodePreferences(raw: String): BackupPreferencesSnapshot =
        json.decodeFromString(BackupPreferencesSnapshot.serializer(), raw).also {
            com.baraa.masroof.application.analytics.AnalyticsBackup.validate(it.labAnalysis)
        }

    /** Accepts only the current format with a known (version, identity hash) pair from [identityHashes]. */
    fun validateManifest(
        manifest: BackupManifest,
        identityHashes: Map<Int, String>,
    ): Boolean = manifest.formatVersion == BackupPackageFormat.FORMAT_VERSION &&
        identityHashes[manifest.roomVersion] == manifest.identityHash
}
