package com.baraa.masroof.application.backup

import com.baraa.masroof.data.room.MasroofDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPackageCodecTest {
    @Test
    fun encodeDecode_roundTripsManifest() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = MasroofDatabase.VERSION,
            identityHash = MasroofDatabase.IDENTITY_HASH,
            exportedAtEpochMillis = 1_700_000_000_000L,
        )
        val decoded = BackupPackageCodec.decodeManifest(BackupPackageCodec.encodeManifest(manifest))
        assertEquals(manifest, decoded)
    }

    @Test
    fun encodeDecode_roundTripsPreferences() {
        val prefs = BackupPreferencesSnapshot(
            onboardingStarted = true,
            onboardingCompleted = true,
            historicalImportStartEpochMillis = 123L,
            historicalImportCompleted = true,
            languageTag = "ar",
            themeMode = "DARK",
        )
        val decoded = BackupPackageCodec.decodePreferences(BackupPackageCodec.encodePreferences(prefs))
        assertEquals(prefs, decoded)
    }

    @Test
    fun validateManifest_acceptsCurrentSchema() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = MasroofDatabase.VERSION,
            identityHash = MasroofDatabase.IDENTITY_HASH,
            exportedAtEpochMillis = 1L,
        )
        assertTrue(
            BackupPackageCodec.validateManifest(
                manifest = manifest,
                expectedRoomVersion = MasroofDatabase.VERSION,
                expectedIdentityHash = MasroofDatabase.IDENTITY_HASH,
            ),
        )
    }

    @Test
    fun validateManifest_rejectsWrongIdentityHash() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = MasroofDatabase.VERSION,
            identityHash = "deadbeef",
            exportedAtEpochMillis = 1L,
        )
        assertFalse(
            BackupPackageCodec.validateManifest(
                manifest = manifest,
                expectedRoomVersion = MasroofDatabase.VERSION,
                expectedIdentityHash = MasroofDatabase.IDENTITY_HASH,
            ),
        )
    }

    @Test
    fun validateManifestForImport_acceptsPreviousRoomVersion() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = MasroofDatabase.PREVIOUS_VERSION,
            identityHash = MasroofDatabase.PREVIOUS_IDENTITY_HASH,
            exportedAtEpochMillis = 1L,
        )
        assertTrue(
            BackupPackageCodec.validateManifestForImport(
                manifest = manifest,
                targetRoomVersion = MasroofDatabase.VERSION,
                targetIdentityHash = MasroofDatabase.IDENTITY_HASH,
                importableVersions = mapOf(
                    MasroofDatabase.PREVIOUS_VERSION to MasroofDatabase.PREVIOUS_IDENTITY_HASH,
                ),
            ),
        )
    }

    @Test
    fun expectedIdentityHashForImport_mapsPreviousVersion() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = MasroofDatabase.PREVIOUS_VERSION,
            identityHash = MasroofDatabase.PREVIOUS_IDENTITY_HASH,
            exportedAtEpochMillis = 1L,
        )
        assertEquals(
            MasroofDatabase.PREVIOUS_IDENTITY_HASH,
            BackupPackageCodec.expectedIdentityHashForImport(
                manifest = manifest,
                targetRoomVersion = MasroofDatabase.VERSION,
                targetIdentityHash = MasroofDatabase.IDENTITY_HASH,
                importableVersions = mapOf(
                    MasroofDatabase.PREVIOUS_VERSION to MasroofDatabase.PREVIOUS_IDENTITY_HASH,
                ),
            ),
        )
    }

    @Test
    fun validateManifestForImport_acceptsV9RoomVersion() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = MasroofDatabase.LEGACY_VERSION_9,
            identityHash = MasroofDatabase.LEGACY_IDENTITY_HASH_9,
            exportedAtEpochMillis = 1L,
        )
        assertTrue(
            BackupPackageCodec.validateManifestForImport(
                manifest = manifest,
                targetRoomVersion = MasroofDatabase.VERSION,
                targetIdentityHash = MasroofDatabase.IDENTITY_HASH,
                importableVersions = MasroofDatabase.IMPORTABLE_BACKUP_VERSIONS,
            ),
        )
    }

    @Test
    fun validateManifestForImport_acceptsV8RoomVersion() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = MasroofDatabase.LEGACY_VERSION_8,
            identityHash = MasroofDatabase.LEGACY_IDENTITY_HASH_8,
            exportedAtEpochMillis = 1L,
        )
        assertTrue(
            BackupPackageCodec.validateManifestForImport(
                manifest = manifest,
                targetRoomVersion = MasroofDatabase.VERSION,
                targetIdentityHash = MasroofDatabase.IDENTITY_HASH,
                importableVersions = MasroofDatabase.IMPORTABLE_BACKUP_VERSIONS,
            ),
        )
    }

    @Test
    fun validateManifest_rejectsWrongRoomVersion() {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = 1,
            identityHash = MasroofDatabase.IDENTITY_HASH,
            exportedAtEpochMillis = 1L,
        )
        assertFalse(
            BackupPackageCodec.validateManifest(
                manifest = manifest,
                expectedRoomVersion = MasroofDatabase.VERSION,
                expectedIdentityHash = MasroofDatabase.IDENTITY_HASH,
            ),
        )
    }

    @Test
    fun decodeManifest_ignoresUnknownKeys() {
        val raw = """
            {
              "formatVersion": 1,
              "appVersionName": "0.2.0",
              "roomVersion": 4,
              "identityHash": "${MasroofDatabase.IDENTITY_HASH}",
              "exportedAtEpochMillis": 42,
              "futureField": "ok"
            }
        """.trimIndent()
        val decoded = BackupPackageCodec.decodeManifest(raw)
        assertEquals(42L, decoded.exportedAtEpochMillis)
    }

    @Test
    fun formatVersion_remainsTheLegacyZipManifest() {
        assertEquals(1, BackupPackageFormat.FORMAT_VERSION)
        assertTrue(BackupEnvelope.DEFAULT_ITERATIONS >= 100_000)
    }

    @Test
    fun defaultExportFileName_usesExtension() {
        assertTrue(
            BackupPackageFormat.defaultExportFileName(99)
                .endsWith(".${BackupPackageFormat.FILE_EXTENSION}"),
        )
    }
}
