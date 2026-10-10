package com.baraa.masroof.data.room

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DatabaseAccessGateTest {
    @Test
    fun restoreDrainsExistingAccess_andRejectsQueuedOldProcessAccessAfterRetirement() = runTest {
        val gate = DatabaseAccessGate()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val running = launch {
            gate.withAccess { events += "running"; release.await(); events += "drained" }
        }
        runCurrent()
        val restoring = launch {
            gate.withRestore { events += "restore"; gate.retire() }
        }
        runCurrent()
        val queued = async {
            try {
                gate.withAccess { events += "unsafe" }
                false
            } catch (_: DatabaseRestartRequiredException) {
                true
            }
        }
        runCurrent()
        assertEquals(listOf("running"), events)
        assertFalse(queued.isCompleted)
        release.complete(Unit)
        running.join()
        restoring.join()
        assertTrue(queued.await())
        assertEquals(listOf("running", "drained", "restore"), events)
    }

    @Test
    fun cancellationWhileDraining_reopensAdmissionWithoutLosingAnActiveLease() = runTest {
        val gate = DatabaseAccessGate()
        val release = CompletableDeferred<Unit>()
        val running = launch { gate.withAccess { release.await() } }
        runCurrent()
        val restoring = launch { gate.withRestore { error("must still be draining") } }
        runCurrent()
        restoring.cancelAndJoin()
        assertEquals(7, gate.withAccess { 7 })
        release.complete(Unit)
        running.join()
        assertEquals(8, gate.withRestore { 8 })
    }

    @Test
    fun nestedRepositoryAccess_usesTheSameLease_andNormalReadersRemainConcurrent() = runTest {
        val gate = DatabaseAccessGate()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.withAccess { release.await(); gate.withAccess { 1 } } }
        runCurrent()
        assertEquals(2, gate.withAccess { gate.withAccess { 2 } })
        release.complete(Unit)
        assertEquals(1, first.await())
    }

    @Test
    fun failedValidationBeforeRetirement_keepsRepositoryAccessAvailable() = runTest {
        val gate = DatabaseAccessGate()
        try {
            gate.withRestore { error("invalid import") }
        } catch (_: IllegalStateException) {
            assertEquals(4, gate.withAccess { 4 })
        }
    }
}
