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
    fun retiredAccess_cancelsObsoleteJobsWithoutFailingTheirParent() = runTest {
        val gate = DatabaseAccessGate()
        gate.retire()
        val obsolete = launch { gate.withAccess { error("must not reach old Room") } }
        obsolete.join()
        assertTrue(obsolete.isCancelled)
        assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun exclusiveEditsQueue_whileARealRestoreStillFailsFast() = runTest {
        val gate = DatabaseAccessGate()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.withExclusiveAccess { release.await(); 1 } }
        runCurrent()
        val second = async { gate.withExclusiveAccess { 2 } }
        runCurrent()
        val restore = runCatching { gate.withRestore { 3 } }
        assertTrue(restore.exceptionOrNull() is IllegalStateException)
        release.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(2, second.await())
    }

    @Test
    fun nestedExclusiveEdit_failsFastInsteadOfWaitingOnItself() = runTest {
        val gate = DatabaseAccessGate()
        val inside = runCatching { gate.withExclusiveAccess { gate.withExclusiveAccess { 1 } } }
        assertTrue(inside.exceptionOrNull() is IllegalStateException)
        val fromAccess = runCatching { gate.withAccess { gate.withExclusiveAccess { 2 } } }
        assertTrue(fromAccess.exceptionOrNull() is IllegalStateException)
        assertEquals(3, gate.withExclusiveAccess { 3 })
    }

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
    fun retirementReleasesQueuedReadersBeforeTheRestoreBlockCanFinish() = runTest {
        val gate = DatabaseAccessGate()
        gate.withRestore {
            val queued = launch { gate.withAccess { error("A queued reader must not enter retired Room") } }
            runCurrent()
            assertFalse(queued.isCompleted)
            gate.retire()
            runCurrent()
            assertTrue("Restore must be able to drain readers before leaving its exclusive block", queued.isCompleted)
            assertTrue(queued.isCancelled)
        }
    }

    @Test
    fun shutdownDrainsTheWholeWorkerLease_includingLaterRepositoryOperations() = runTest {
        val gate = DatabaseAccessGate()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val worker = launch {
            gate.withAccess {
                events += "parsed"
                release.await()
                gate.withAccess { events += "reconciled" }
            }
        }
        runCurrent()
        val closing = launch { gate.retireAndDrain(); events += "closed" }
        runCurrent()
        assertFalse(closing.isCompleted)
        val rejected = runCatching { gate.withAccess { error("new work entered during close") } }.exceptionOrNull()
        assertTrue(rejected is DatabaseRestartRequiredException)
        release.complete(Unit)
        worker.join()
        closing.join()
        assertEquals(listOf("parsed", "reconciled", "closed"), events)
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
        val failure = runCatching { gate.withRestore { error("invalid import") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(4, gate.withAccess { 4 })
    }
}
