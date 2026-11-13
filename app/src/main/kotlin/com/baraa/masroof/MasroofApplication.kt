package com.baraa.masroof

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.container.appWorkerFactory
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.sms.DebugProcessHalt
import com.baraa.masroof.application.update.UpdateCheckScheduler
import com.baraa.masroof.presentation.locale.AppLocaleContext

/**
 * Application entry and composition root holder for Masroof.
 */
class MasroofApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(
            AppLocaleContext.wrap(base, AppLocaleContext.readStoredLanguageTag(base)),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            DebugProcessHalt.consumeScheduledResume(filesDir)
        }
        // AppContainer runs restore recovery before it opens the database.
        container = AppContainer(this)
        // The log write is synchronous, so the entry survives the process dying right after.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { container.storage.appLogService.failure(AppLogCategories.CRASH, "Uncaught on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
        if (!isRobolectricUnitTest()) {
            container.maintenance.runStartupMaintenance()
            UpdateCheckScheduler.schedule(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(appWorkerFactory(container) { container })
            .build()

    override fun onTerminate() {
        if (::container.isInitialized) {
            container.close()
        }
        super.onTerminate()
    }

    private fun isRobolectricUnitTest(): Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
}
