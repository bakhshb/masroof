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
    fun preferencesWithoutOptionalFieldsDecode() {
        val minimal = """{"onboardingStarted":true,"onboardingCompleted":true,"historicalImportCompleted":true,"languageTag":"ar","themeMode":"SYSTEM"}"""
        assertNull(BackupPackageCodec.decodePreferences(minimal).labAnalysis)
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
                identityHashes = BackupSchema.PRODUCTION.identityHashes,
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
                identityHashes = BackupSchema.PRODUCTION.identityHashes,
            ),
        )
    }

    @Test
    fun validateManifest_rejectsEveryOtherSchema() {
        for (version in listOf(0, MasroofDatabase.VERSION + 1, 18, 22, 99)) {
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
                identityHashes = BackupSchema.PRODUCTION.identityHashes,
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
    fun onlyTheCurrentFormatAndSchemaAreAccepted() {
        assertEquals(1, BackupPackageFormat.FORMAT_VERSION)
        fun accepted(format: Int, version: Int) = BackupPackageCodec.validateManifest(
            BackupManifest(format, "0.2.0", version, MasroofDatabase.IDENTITY_HASH, 1L), BackupSchema.PRODUCTION.identityHashes)
        assertTrue(accepted(BackupPackageFormat.FORMAT_VERSION, MasroofDatabase.VERSION))
        assertFalse(accepted(BackupPackageFormat.FORMAT_VERSION + 1, MasroofDatabase.VERSION))
        assertFalse(accepted(0, MasroofDatabase.VERSION))
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
