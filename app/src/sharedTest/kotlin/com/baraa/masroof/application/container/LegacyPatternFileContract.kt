package com.baraa.masroof.application.container

import android.content.Context
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.data.patterns.LegacyMessagePatternFile
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.engine.bank.patterns.FieldChoice
import com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration
import com.baraa.masroof.engine.bank.patterns.SavedMessagePattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Plan 5.1, run on the JVM and on a device: the live database's open copies the pre-v21 pattern file before
 * anything can read the patterns, then seeds the bundled banks; a copy that fails fails the open and keeps the file.
 */
object LegacyPatternFileContract {
    fun upgradeCopiesTheFileOnOpen(context: Context) = runBlocking {
        val legacy = LegacyMessagePatternFile.of(context)
        try {
            LegacyMessagePatternFile.write(legacy, "{broken", validate = false)
            var c = AppContainer(context)
            try {
                assertTrue(runCatching { c.storage.openDatabase() }.isFailure)
                assertTrue(runCatching { c.storage.messagePatternStore.load() }.isFailure)
                assertEquals("{broken", legacy.readText())
            } finally { c.close() }

            val body = "Synthetic event\nAmount:10SAR"
            val taught = SavedMessagePattern.fromChoices("taught", "USER_BANK", "User Bank", "PURCHASE", body,
                listOf(FieldChoice("amount", body.indexOf("10SAR"), body.length)))
            val raw = MessagePatternConfigurationCodec.encode(MessagePatternConfiguration(3,
                listOf(BankMessagePatterns("USER_BANK", listOf("User Bank"), "My bank", listOf(taught)))))
            LegacyMessagePatternFile.write(legacy, raw, validate = true)
            c = AppContainer(context)
            try {
                // The first read opens the database, so it already sees the copied and seeded document.
                val loaded = c.storage.messagePatternStore.load()
                assertFalse(legacy.exists())
                assertEquals(MessagePatternConfigurationCodec.decode(raw).banks.single(), loaded.banks.single { it.bankId == "USER_BANK" })
                assertTrue("bundled banks are seeded after the copy", loaded.banks.size > 1)
            } finally { c.close() }
            // A later start finds no file and keeps the stored document as it is.
            c = AppContainer(context)
            try {
                val stored = c.storage.messagePatternStore.captureRaw()
                c.storage.openDatabase()
                assertEquals(stored, c.storage.messagePatternStore.captureRaw())
            } finally { c.close() }
        } finally {
            context.getDatabasePath(MasroofDatabase.NAME).parentFile?.deleteRecursively()
            legacy.parentFile?.deleteRecursively()
        }
    }
}
