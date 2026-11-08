package com.atmosphere.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_ID = "relay_status"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Status koneksi", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Menjaga Atmosphere PG Companion tetap berjalan di latar belakang"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }
}
