package com.baraa.masroof.application.sms

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugProcessHaltTest {
    @Test
    fun absentRequestDoesNotParkOrWriteAMarker() {
        val dir = tempDir()
        assertNull(DebugProcessHalt.requestedStage(dir))
        DebugProcessHalt.afterDurableWrite(dir, DebugProcessHalt.CAPTURE)
        assertFalse(File(dir, DebugProcessHalt.MARKER_FILE_NAME).exists())
        assertFalse(DebugProcessHalt.holdReconcileRequested(dir))
    }

    @Test
    fun otherStageDoesNotConsumeTheRequest() {
        val dir = tempDir()
        File(dir, DebugProcessHalt.REQUEST_FILE_NAME).writeText(DebugProcessHalt.PARSED)
        DebugProcessHalt.afterDurableWrite(dir, DebugProcessHalt.CAPTURE)
        assertFalse(File(dir, DebugProcessHalt.MARKER_FILE_NAME).exists())
        assertEquals(DebugProcessHalt.PARSED, DebugProcessHalt.requestedStage(dir))
    }

    @Test
    fun parkEndsWhenTheRequestFileIsRemoved() {
        val dir = tempDir()
        File(dir, DebugProcessHalt.REQUEST_FILE_NAME).writeText(DebugProcessHalt.CAPTURE)
        val finished = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        thread(name = "m13-halt-test") {
            try {
                DebugProcessHalt.afterDurableWrite(dir, DebugProcessHalt.CAPTURE)
            } catch (_: Throwable) {
                failed.set(true)
            } finally {
                finished.countDown()
            }
        }
        val marker = File(dir, DebugProcessHalt.MARKER_FILE_NAME)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && !marker.isFile) {
            Thread.sleep(20)
        }
        assertTrue(marker.isFile)
        assertEquals(DebugProcessHalt.CAPTURE, marker.readText())
        File(dir, DebugProcessHalt.REQUEST_FILE_NAME).delete()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(failed.get())
    }

    @Test
    fun clearRequestLeavesTheReconcileHold() {
        val dir = tempDir()
        File(dir, DebugProcessHalt.REQUEST_FILE_NAME).writeText(DebugProcessHalt.REVIEW)
        File(dir, DebugProcessHalt.MARKER_FILE_NAME).writeText(DebugProcessHalt.REVIEW)
        File(dir, DebugProcessHalt.HOLD_RECONCILE_FILE_NAME).writeText("hold")
        DebugProcessHalt.clearRequest(dir)
        assertFalse(File(dir, DebugProcessHalt.REQUEST_FILE_NAME).exists())
        assertFalse(File(dir, DebugProcessHalt.MARKER_FILE_NAME).exists())
        assertTrue(DebugProcessHalt.holdReconcileRequested(dir))
    }

    private fun tempDir(): File = Files.createTempDirectory("m13-halt").toFile()
}
