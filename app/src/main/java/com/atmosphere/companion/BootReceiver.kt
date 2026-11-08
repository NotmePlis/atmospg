package com.atmosphere.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Menjalankan ulang pengirim setelah perangkat menyala / aplikasi diperbarui. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val cfg = AppConfig.get(context)
        if (!cfg.isConfigured) return
        SyncWorker.schedule(context)
        RelayEngine.start(context)
        if (Perms.listenerEnabled(context)) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(context, DanaNotificationListener::class.java),
                )
            } catch (e: Exception) {
                // Sistem biasanya menyambungkan sendiri; SyncWorker mencoba lagi.
            }
        }
        if (cfg.keepAlive) RelayService.start(context)
    }
}
