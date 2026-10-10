package com.baraa.masroof.application.sms

import com.baraa.masroof.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Debug-only latch used by emulator process-death tests.
 *
 * A file named [REQUEST_FILE_NAME] in the application files directory, containing
 * [CAPTURE], [PARSED], [RECONCILED], or [REVIEW], parks the live receiver or worker
 * thread after that stage's durable write. The parked thread writes [MARKER_FILE_NAME]
 * and stays parked until process death. Release builds no-op. An absent request file
 * does not change capture, parse, reconciliation, or review.
 *
 * [HOLD_RECONCILE_FILE_NAME] keeps reconciliation incomplete so an M17 LIVE
 * processing-retry row is not cleared. Absent, reconciliation is unchanged.
 * Neither path logs SMS bodies.
 */
object DebugProcessHalt {
    const val REQUEST_FILE_NAME: String = "m13-halt-after"
    const val MARKER_FILE_NAME: String = "m13-halted"
    const val RESUME_FILE_NAME: String = "m13-resume-next"
    const val HOLD_RECONCILE_FILE_NAME: String = "m13-hold-reconcile"
    const val RECONCILE_HELD_MARKER_FILE_NAME: String = "m13-reconcile-held"

    const val IGNORE_PENDING = "IGNORE_PENDING"
    const val CAPTURE: String = "CAPTURE"
    const val PARSED: String = "PARSED"
    const val RECONCILED: String = "RECONCILED"
    const val REVIEW: String = "REVIEW"

    fun requestedStage(filesDir: File): String? {
        if (!BuildConfig.DEBUG) return null
        val request = File(filesDir, REQUEST_FILE_NAME)
        if (!request.isFile) return null
        return runCatching { request.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun afterDurableWrite(filesDir: File, stage: String) {
        if (requestedStage(filesDir) != stage) return
        File(filesDir, MARKER_FILE_NAME).writeText(stage)
        while (requestedStage(filesDir) == stage) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
        }
    }

    fun holdReconcileRequested(filesDir: File): Boolean {
        if (!BuildConfig.DEBUG) return false
        return File(filesDir, HOLD_RECONCILE_FILE_NAME).isFile
    }

    fun markReconcileHeld(filesDir: File) {
        if (!BuildConfig.DEBUG) return
        File(filesDir, RECONCILE_HELD_MARKER_FILE_NAME).writeText("held")
    }

    /** Removes the halt request. Does not remove the reconcile-hold file. */
    fun clearRequest(filesDir: File) {
        if (!BuildConfig.DEBUG) return
        File(filesDir, REQUEST_FILE_NAME).delete()
        File(filesDir, MARKER_FILE_NAME).delete()
    }

    /** The arm phase plants this so the next process can drop the halt before startup. */
    fun scheduleResume(filesDir: File) {
        if (!BuildConfig.DEBUG) return
        File(filesDir, RESUME_FILE_NAME).writeText("resume")
    }

    /**
     * Deletes the halt request when [RESUME_FILE_NAME] is present.
     * Call before startup maintenance. No-op when the resume file is absent.
     */
    fun consumeScheduledResume(filesDir: File) {
        if (!BuildConfig.DEBUG) return
        val resume = File(filesDir, RESUME_FILE_NAME)
        if (!resume.isFile) return
        resume.delete()
        clearRequest(filesDir)
    }
}

/**
 * Shared by live intake and stored-SMS processing. [NONE] is the production default
 * for tests that build those types directly.
 */
class DebugProcessHaltProbe(
    private val filesDir: File? = null,
) {
    private val reconcileHeld = AtomicBoolean(false)

    fun afterDurableWrite(stage: String) {
        val dir = filesDir ?: return
        DebugProcessHalt.afterDurableWrite(dir, stage)
    }

    fun holdReconcileIncomplete(): Boolean {
        val dir = filesDir ?: return false
        val held = DebugProcessHalt.holdReconcileRequested(dir)
        if (held) reconcileHeld.set(true)
        return held
    }

    /** Call only after the attempt has decided whether to clear a processing-retry row. */
    fun markIfReconcileHeld() {
        val dir = filesDir ?: return
        if (reconcileHeld.getAndSet(false)) {
            DebugProcessHalt.markReconcileHeld(dir)
        }
    }

    companion object {
        val NONE: DebugProcessHaltProbe = DebugProcessHaltProbe()
    }
}
