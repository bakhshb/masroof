package com.baraa.masroof.application.maintenance

import android.content.Context
import android.app.NotificationManager
import androidx.work.WorkManager
import androidx.work.await
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Retires persisted jobs from the removed parallel runtime without touching its data. */
object RetiredWorkCleanup {
    fun schedule(context: Context, scope: CoroutineScope, log: AppLogService) {
        val prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("retired_lab_work_v1", false)) return
        scope.launch {
            try {
                val work = WorkManager.getInstance(context)
                listOf("lab-replica:live-sms-processing", "lab-replica:model-refresh",
                    "lab-replica:exchange-rate-enrichment", "lab-replica:analytics")
                    .forEach { work.cancelAllWorkByTag(it).await() }
                listOf("lab-replica:startup-recovery", "lab-replica:historical-derived-recovery",
                    "lab-replica:parsed-event-facts-backfill", "lab-replica:masroof_update_check")
                    .forEach { work.cancelUniqueWork(it).await() }
                val notifications = context.getSystemService(NotificationManager::class.java)
                notifications.activeNotifications.filter { it.tag == "lab-analysis" }
                    .forEach { notifications.cancel(it.tag, it.id) }
                notifications.deleteNotificationChannel("lab_replica_analysis")
                check(prefs.edit().putBoolean("retired_lab_work_v1", true).commit())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(AppLogCategories.UPDATE, "Retired work cleanup stage=cancel failed=1 retry=pending (${e.javaClass.simpleName})")
            }
        }
    }
}
