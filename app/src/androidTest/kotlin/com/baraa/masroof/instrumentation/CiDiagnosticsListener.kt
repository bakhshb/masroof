package com.baraa.masroof.instrumentation

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.MasroofApplication
import org.junit.runner.Description
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.Timer
import java.util.TimerTask

/** Test-only diagnostics. Does not change application scheduling or treat a timeout as success. */
class CiDiagnosticsListener : RunListener() {
    private var timer: Timer? = null
    override fun testStarted(description: Description) {
        timer = Timer("ci-test-diagnostics", true).also { clock ->
            clock.schedule(object : TimerTask() {
                override fun run() {
                    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MasroofApplication
                    Log.e("CiDiagnostics", "pending=${description.className}#${description.methodName} shutdown=${app.container.shutdownStage} startupCancelled=${app.container.maintenance.startupCancelled}")
                    Thread.getAllStackTraces().forEach { (thread, stack) ->
                        Log.e("CiDiagnostics", "${thread.name} ${thread.state}\n${stack.joinToString("\n")}")
                    }
                }
            }, 60_000)
        }
    }

    override fun testFinished(description: Description) {
        timer?.cancel()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        val name = (description.className + "-" + description.methodName).replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        // Copy before Orchestrator clears this case's app data. Shell owns the
        // destination, so the next fresh app uid can still publish its own evidence.
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        root.walkTopDown().filter { it.isFile && it.extension in setOf("png", "json", "txt") }.forEach { file ->
            if (!file.absolutePath.matches(Regex("[a-zA-Z0-9/._-]+"))) return@forEach
            val target = "/sdcard/masroof-ci/$name/${file.relativeTo(root).path}"
            if (!target.matches(Regex("[a-zA-Z0-9/._-]+"))) return@forEach
            shell("mkdir -p ${File(target).parent}")
            shell("cp ${file.absolutePath} $target")
        }
    }
}
