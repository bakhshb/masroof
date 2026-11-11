package com.baraa.masroof.application.backup

import com.baraa.masroof.data.room.MasroofDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupPackageCodecTest {
    @Test
    fun legacyPreferencesDefaultToNoPatternsAndUnsupportedPatternsAreRejected() {
        val legacy = """{"onboardingStarted":true,"onboardingCompleted":true,"historicalImportCompleted":true,"languageTag":"ar","themeMode":"SYSTEM"}"""
        val old = BackupPackageCodec.decodePreferences(legacy)
        assertNull(old.messagePatterns)
        assertThrows(IllegalArgumentException::class.java) {
            BackupPackageCodec.decodePreferences(BackupPackageCodec.encodePreferences(old.copy(messagePatterns = "{}")))
        }
    }
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
                identityHashes = MasroofDatabase.IDENTITY_HASHES,
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
                identityHashes = MasroofDatabase.IDENTITY_HASHES,
            ),
        )
    }

    @Test
    fun validateManifest_rejectsEveryOlderSchemaAndFutureSchemas() {
        for (version in (1..17) + listOf(19, 99)) {
        val manifest = BackupManifest(
            formatVersion = BackupPackageFormat.FORMAT_VERSION,
            appVersionName = "0.2.0",
            roomVersion = version,
            identityHash = MasroofDatabase.IDENTITY_HASH,
            exportedAtEpochMillis = 1L,
        )
        assertFalse(
            BackupPackageCodec.validateManifest(
                manifest = manifest,
                identityHashes = MasroofDatabase.IDENTITY_HASHES,
            ),
        )
    }
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
