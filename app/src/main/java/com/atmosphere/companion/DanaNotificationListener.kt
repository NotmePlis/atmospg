package com.atmosphere.companion

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Membaca notifikasi DANA lewat NotificationListenerService resmi (tanpa root, tanpa klik otomatis).
 * Paket dicek PERTAMA; notifikasi aplikasi lain tidak pernah dibaca isinya.
 * Hanya title + text yang lolos filter yang dikirim; OTP/PIN/sandi dibuang di perangkat.
 */
class DanaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        RelayEngine.setListenerConnected(true)
        RelayEngine.start(this)
        replayRecentActive()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        RelayEngine.setListenerConnected(false)
        if (Perms.listenerEnabled(this)) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, DanaNotificationListener::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind gagal", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "gagal memproses notifikasi", e)
        }
    }

    /**
     * Setelah service sempat mati, ambil notifikasi DANA yang masih tampil dan baru (<= 15 menit).
     * Aman dari duplikat: event_id deterministik + pengecekan log + dedupe server.
     */
    private fun replayRecentActive() {
        try {
            val now = System.currentTimeMillis()
            activeNotifications?.forEach { sbn ->
                if (now - sbn.postTime <= REPLAY_WINDOW_MS) onNotificationPosted(sbn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "replay notifikasi aktif gagal", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val cfg = AppConfig.get(this)
        if (sbn.packageName != cfg.danaPackage) return // paket lain: berhenti sebelum membaca isi apa pun
        RelayEngine.noteDanaSeen(this) // bukti listener benar-benar menerima notifikasi DANA
        if (!cfg.isConfigured) return

        val n = sbn.notification ?: return
        val ex = n.extras
        fun str(key: String): String = ex?.getCharSequence(key)?.toString().orEmpty().trim()

        val raw = RawNotification(
            pkg = sbn.packageName,
            title = str(Notification.EXTRA_TITLE_BIG).ifBlank { str(Notification.EXTRA_TITLE) },
            text = str(Notification.EXTRA_TEXT),
            bigText = str(Notification.EXTRA_BIG_TEXT),
            subText = str(Notification.EXTRA_SUB_TEXT),
            infoText = str(Notification.EXTRA_INFO_TEXT),
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0,
        )

        val store = Store.get(this)
        when (val d = NotificationFilter.evaluate(raw, cfg.danaPackage, cfg.keywordPattern())) {
            Decision.Ignore -> Unit
            is Decision.Drop -> {
                val summary = if (d.sensitive) "[isi disembunyikan]" else clip("${d.title} — ${d.text}", 160)
                store.addLog(null, summary, "diabaikan: ${d.reason}")
            }
            is Decision.Send -> {
                if (!cfg.liveEnabled) {
                    // Diagnostik: tampilkan apa yang AKAN dikirim; tidak diantrekan dan tidak dicatat sebagai "seen".
                    store.addLog(null, clip("${d.title} — ${d.text}", 160), "diagnostik: lolos filter, TIDAK dikirim")
                    return
                }
                val now = System.currentTimeMillis()
                val key = sbn.key ?: sbn.packageName
                val whenMs = n.`when`
                val content = "${d.title}|${d.text}"
                val label = clip("${d.title} — ${d.text}", 160)
                // Duplikat: notifikasi identik (key + waktu notifikasi + isi), mis. replay setelah listener tersambung ulang,
                // atau repost cepat (<5 dtk) dengan isi sama. Transaksi lain dengan nominal sama punya waktu berbeda -> tetap dikirim.
                val hash = sha256Hex("$key|$whenMs|$content")
                if (!store.claimContent("n:$hash", now, DUP_WINDOW_MS) || !store.claimContent("r:" + sha256Hex("$key|$content"), now, REPOST_MS)) {
                    store.addLog(null, label, "diabaikan: duplikat")
                    return
                }
                // Identitas transaksi = notifikasi + waktunya. Sudah pernah terlihat tapi isi berubah = update; belum = baru.
                val txId = sha256Hex("$key|$whenMs").take(24)
                val kind = if (store.claimContent("t:$txId", now, DUP_WINDOW_MS)) "new" else "update"
                val eventId = "${cfg.deviceId}-${sbn.postTime}-${hash.take(8)}"
                Log.i(TAG, "notifikasi $kind event=$eventId post_time=${sbn.postTime} received_at=$now")
                val payload = JSONObject()
                    .put("event_id", eventId)
                    .put("device_id", cfg.deviceId)
                    .put("timestamp", sbn.postTime)
                    .put("package", sbn.packageName)
                    .put("tx_id", txId)
                    .put("kind", kind)
                    .put("title", d.title)
                    .put("text", d.text)
                    .toString()
                RelayEngine.submit(this, eventId, payload, (if (kind == "update") "[update] " else "") + label)
            }
        }
    }

    private fun clip(s: String, n: Int): String = if (s.length <= n) s else s.take(n - 1) + "…"

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "DanaListener"
        private const val REPLAY_WINDOW_MS = 15 * 60_000L
        private const val DUP_WINDOW_MS = 6 * 3600_000L // ingatan duplikat/identitas transaksi
        private const val REPOST_MS = 5_000L // repost cepat isi sama
    }
}
