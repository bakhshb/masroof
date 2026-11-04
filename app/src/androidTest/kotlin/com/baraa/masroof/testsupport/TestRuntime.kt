package com.baraa.masroof.testsupport

import com.baraa.masroof.presentation.navigation.AnalyticsNavigation

import android.content.Context
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Instrumentation-only synthetic storage. Never compiled into the application APK. */
object TestRuntime {
    private var current: AppContainer? = null
    private var application: MasroofApplication? = null
    fun context(context: Context): Context = TestStorageContext(context.applicationContext)
    @Synchronized fun container(context: Context): AppContainer {
        current?.let { return it }
        val app = context.applicationContext as MasroofApplication
        if (application == null) app.container.close()
        application = app
        val result = AppContainer(this.context(context), restartAfterRestore = { close(); container(context) })
        MasroofApplication::class.java.getDeclaredField("container").apply { isAccessible = true }.set(app, result)
        current = result
        result.runStartupMaintenance()
        return result
    }
    @Synchronized fun close() { current?.close(); current = null }
    suspend fun reset(context: Context) = withContext(Dispatchers.IO) {
        close()
        AnalyticsNavigation.reset()
        java.io.File(context.filesDir, "test-storage").deleteRecursively()
        java.io.File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.startsWith("test-storage:") }.forEach {
                context.getSharedPreferences(it.name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear().commit()
            }
    }
}
