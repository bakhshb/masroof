package com.baraa.masroof.application.container

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.testsupport.TestStorageContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production pattern store holds the access gate, so a read during a restore never reopens the live file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PatternStoreRetirementTest {
    @Test fun afterTheGateRetiresAndTheFileMovesAwayAPatternReadOrWriteFailsAndCreatesNothing() = runBlocking {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "pattern-retire-${java.util.UUID.randomUUID()}")
        val live = context.getDatabasePath(MasroofDatabase.NAME)
        try {
            val c = AppContainer(context)
            val store = c.storage.messagePatternStore
            val gate = c.storage.databaseAccessGate
            val generation = store.load().generation
            // Leased callers, exclusive edits and the financial transaction keep working as before.
            assertEquals(generation, gate.withAccess { store.load().generation })
            assertEquals(generation, gate.withAccess { c.storage.database.withTransaction { store.load().generation } })
            val edited = gate.withExclusiveAccess { store.saveBank(generation, BankMessagePatterns("PROBE_BANK", listOf("Probe Sender"))) }
            assertEquals(generation + 1, edited.generation)

            // What an import does before it moves files: retire the gate, close Room.
            gate.retire()
            c.storage.database.close()
            val parked = File(live.path + ".parked")
            assertTrue(live.renameTo(parked))
            assertTrue(runCatching { store.load() }.exceptionOrNull() is DatabaseRestartRequiredException)
            assertTrue(runCatching { store.saveBank(edited.generation, BankMessagePatterns("OTHER_BANK", listOf("Other"))) }
                .exceptionOrNull() is DatabaseRestartRequiredException)
            assertTrue(runCatching { store.captureRaw() }.exceptionOrNull() is DatabaseRestartRequiredException)
            assertFalse(c.storage.database.isOpen)
            assertEquals(emptyList<String>(), live.parentFile!!.listFiles()!!.map { it.name }.filter { it.startsWith(MasroofDatabase.NAME) && !it.contains(".parked") })
            c.close()
        } finally { live.parentFile?.deleteRecursively() }
    }
}
