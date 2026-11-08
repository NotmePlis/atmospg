package com.atmosphere.companion

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Jaring pengaman tiap ~15 menit: hidupkan lagi proses/pengirim jika sempat dimatikan sistem
 * dan minta sistem menyambungkan ulang listener bila izinnya aktif tetapi belum terhubung.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AppConfig.get(app).isConfigured) return Result.success()
        RelayEngine.start(app)
        if (Perms.listenerEnabled(app) && !RelayEngine.state.value.listenerConnected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(app, DanaNotificationListener::class.java))
            } catch (e: Exception) {
                // Diabaikan: percobaan berikutnya 15 menit lagi.
            }
        }
        RelayEngine.kick()
        if (Store.get(app).count() > 0) SendWorker.enqueue(app, "periodic")
        return Result.success()
    }

    companion object {
        private const val NAME = "relay-sync"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
