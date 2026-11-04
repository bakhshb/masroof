package com.baraa.masroof.testsupport

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Publish synthetic test evidence through the shell, independent of MediaStore ownership after app-data clearing. */
object ScreenshotArtifactWriter {
    /** Screenshot the displayed frame (popups and dialogs included) and publish it. */
    fun capture(compose: ComposeTestRule, directory: String, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose idleness precedes the SurfaceFlinger frame; wait for the compositor to present it.
        SystemClock.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot missing: $name" }
        try { publish(bitmap, directory, name) } finally { bitmap.recycle() }
    }

    fun publish(bitmap: Bitmap, directory: String, name: String) {
        require(directory.matches(Regex("[a-z0-9-]+(/[a-z0-9-]+)*")) && name.matches(Regex("[a-z0-9-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sourceDirectory = File(instrumentation.targetContext.getExternalFilesDir(null), directory)
        check(sourceDirectory.isDirectory || sourceDirectory.mkdirs())
        val source = File(sourceDirectory, "$name.png")
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val targetDirectory = "/sdcard/Download/$directory"
        val target = "$targetDirectory/$name.png"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        // UiAutomation executes tokenized arguments directly; shell quotes would become part of the path.
        check(source.absolutePath.matches(Regex("[a-zA-Z0-9/._-]+")))
        shell("mkdir -p $targetDirectory")
        shell("cp ${source.absolutePath} $target")
        val listing = shell("ls -l $target")
        check(listing.trimStart().startsWith("-") && listing.contains("$name.png")) { "Screenshot artifact was not published: $name" }
    }
}
