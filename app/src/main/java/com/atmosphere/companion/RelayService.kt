package com.atmosphere.companion

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service opsional ("Mode andal") agar proses tidak dimatikan sistem. */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannel(this)
        val first = buildNotification(summary(RelayEngine.state.value))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, first)
        }
        RelayEngine.start(this)
        if (observer == null) {
            observer = scope.launch {
                RelayEngine.state.map { summary(it) }.distinctUntilChanged().collect { text ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification(text))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun summary(s: RelayEngine.State): String {
        val cfg = AppConfig.get(this)
        if (!cfg.isConfigured) return "Belum dikonfigurasi"
        if (!cfg.liveEnabled) return "Mode diagnostik • tidak ada yang dikirim"
        val server = when {
            s.tokenRejected -> "token ditolak"
            s.serverOk == true -> "server OK"
            s.serverOk == false -> "server tidak terjangkau"
            else -> "server: menghubungkan"
        }
        val listener = when {
            !Perms.listenerEnabled(this) -> "akses notifikasi MATI"
            s.listenerConnected -> "listener aktif"
            else -> "listener belum tersambung"
        }
        return "$server • $listener • antrean ${s.queueSize}"
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java))
            } catch (e: Exception) {
                // Mis. dibatasi sistem saat di latar belakang; engine tetap jalan lewat listener/WorkManager.
                Log.w("RelayService", "tidak bisa memulai foreground service", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RelayService::class.java))
        }
    }
}
