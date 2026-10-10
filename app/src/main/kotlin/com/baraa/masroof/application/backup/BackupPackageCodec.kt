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
        json.decodeFromString(BackupPreferencesSnapshot.serializer(), raw)

    fun validateManifest(
        manifest: BackupManifest,
        expectedRoomVersion: Int,
        expectedIdentityHash: String,
    ): Boolean = validateManifestForImport(
        manifest = manifest,
        targetRoomVersion = expectedRoomVersion,
        targetIdentityHash = expectedIdentityHash,
    )

    /**
     * Accepts backups at [targetRoomVersion] or any [importableVersions] entry (e.g. v5 → v6 upgrade).
     */
    fun validateManifestForImport(
        manifest: BackupManifest,
        targetRoomVersion: Int,
        targetIdentityHash: String,
        importableVersions: Map<Int, String> = emptyMap(),
    ): Boolean {
        if (manifest.formatVersion != BackupPackageFormat.FORMAT_VERSION) return false
        if (manifest.roomVersion == targetRoomVersion) {
            return manifest.identityHash == targetIdentityHash
        }
        val expectedHash = importableVersions[manifest.roomVersion] ?: return false
        return manifest.identityHash == expectedHash
    }

    fun expectedIdentityHashForImport(
        manifest: BackupManifest,
        targetRoomVersion: Int,
        targetIdentityHash: String,
        importableVersions: Map<Int, String> = emptyMap(),
    ): String? = when {
        manifest.roomVersion == targetRoomVersion -> targetIdentityHash
        else -> importableVersions[manifest.roomVersion]
    }
}
