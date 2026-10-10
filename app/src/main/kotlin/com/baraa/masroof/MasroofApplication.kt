package com.baraa.masroof

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.backup.DatabaseRestoreRecovery
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
        DatabaseRestoreRecovery.recover(this)
        container = AppContainer(this)
        if (!isRobolectricUnitTest()) {
            container.runStartupMaintenance()
            UpdateCheckScheduler.schedule(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
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
