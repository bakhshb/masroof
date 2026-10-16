package com.baraa.masroof.application.beta

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.presentation.beta.BetaActivity
import com.baraa.masroof.presentation.beta.BetaEntry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BetaContainerIsolationTest {
    @Test
    fun betaMaintenance_leavesProductionAndParsingLabUntouched() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MasroofApplication>()
        BetaContainer.resetOffline(context)
        val production = context.container
        val sentinelId = "m0-beta-isolation-${System.nanoTime()}"
        val before = production.rawSmsRepository.listIdsByReceivedAt().toSet()
        val inserted = production.rawSmsRepository.insertIfAbsent(
            RawSms(
                id = sentinelId,
                sender = "BankAlJazira",
                body = "beta isolation sentinel",
                receivedAt = Instant.parse("2026-01-01T00:00:00Z"),
                deviceMessageId = sentinelId,
                bodyHash = sentinelId,
            ),
        )
        assertEquals(RawSmsInsertResult.Inserted, inserted)
        val productionIds = before + sentinelId
        BetaEnvironment.productionPreferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .putString(SENTINEL_KEY, "production-$name")
                .commit()
        }
        val lab = context.getDatabasePath(BetaEnvironment.PARSING_LAB_DATABASE_NAME)
        lab.parentFile?.mkdirs()
        lab.writeBytes("parsing-lab-sentinel".toByteArray())
        val labHash = sha256(lab)

        BetaContainer(context).use { beta ->
            assertEquals(0, beta.evidenceCount())
            beta.putPreferenceProbe("A")
            beta.backup()
            val backupHash = sha256(beta.backupDatabaseFile())
            assertTrue(
                String(beta.backupDatabaseFile().readBytes(), 0, 15, Charsets.US_ASCII) == "SQLite format 3",
            )
            beta.close()
            context.getDatabasePath(BetaEnvironment.DATABASE_NAME).writeBytes("junk".toByteArray())
            context.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(BetaContainer.PREFERENCE_PROBE_KEY, "B")
                .commit()
            assertTrue(BetaContainer.restoreOffline(context))
            assertEquals(backupHash, sha256(context.getDatabasePath(BetaEnvironment.DATABASE_NAME)))
            BetaContainer(context).use { restored ->
                assertEquals("A", restored.preferenceProbe())
                assertEquals(0, restored.evidenceCount())
                restored.reset()
                assertEquals(null, restored.preferenceProbe())
                assertEquals(0, restored.evidenceCount())
            }
        }

        assertEquals(productionIds, production.rawSmsRepository.listIdsByReceivedAt().toSet())
        BetaEnvironment.productionPreferenceNames.forEach { name ->
            val value = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString(SENTINEL_KEY, null)
            assertEquals("production-$name", value)
        }
        assertEquals(labHash, sha256(lab))
        assertNotEquals(
            context.getDatabasePath(MasroofDatabase.NAME).absolutePath,
            context.getDatabasePath(BetaEnvironment.DATABASE_NAME).absolutePath,
        )
    }

    @Test
    fun guardsRejectProductionNames() {
        assertFails { BetaEnvironment.requireBetaDatabaseName(MasroofDatabase.NAME) }
        assertFails { BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.PARSING_LAB_DATABASE_NAME) }
        assertFails { BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.MIGRATION_STAGING_DATABASE_NAME) }
        assertFails { BetaEnvironment.requireBackupDatabaseName(MasroofDatabase.NAME) }
        BetaEnvironment.productionPreferenceNames.forEach { name ->
            assertFails { BetaEnvironment.requireBetaPreferenceName(name) }
        }
        BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.DATABASE_NAME)
        BetaEnvironment.requireBetaPreferenceName(BetaEnvironment.PREFS_NAME)
    }

    @Test
    fun entryIntent_doesNotClearTheCaller_andBackFinishesBetaOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val background = BetaEntry.intent(context)
        assertEquals(BetaActivity::class.java.name, background.component?.className)
        assertTrue(background.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, background.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(0, background.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val intent = BetaEntry.intent(host.get())
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        host.get().startActivity(intent)
        assertFalse(host.get().isFinishing)

        val beta = Robolectric.buildActivity(BetaActivity::class.java).setup()
        beta.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(beta.get().isFinishing)
        assertFalse(host.get().isFinishing)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        error("Expected the beta guard to reject this name")
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SENTINEL_KEY: String = "m0_sentinel"
    }
}
