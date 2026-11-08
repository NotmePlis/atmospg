package com.atmosphere.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Mesin pengirim: heartbeat tiap 60 dtk + pengiriman antrean sesuai kontrak MacroDroid lama.
 * - Event dihapus HANYA jika HTTP 200 dan stored:true.
 * - Gagal sementara -> kirim ulang dengan event_id yang sama (backoff eksponensial 5 dtk..5 mnt).
 * - 401 -> berhenti sampai token diganti. 400 -> event dibuang (ditolak permanen), dicatat di log.
 */
object RelayEngine {
    /** Versi aplikasi, diisi CompanionApp saat start; dikirim di heartbeat agar dashboard tahu versi terpasang. */
    @Volatile var appVersion: String = ""
    private const val TAG = "RelayEngine"
    private const val HEARTBEAT_INTERVAL_MS = 60_000L
    private const val MAX_BACKOFF_MS = 300_000L
    private const val IDLE_WAIT_MS = 60_000L
    private const val MAX_QUEUE = 1000
    private const val GAP_WARN_MS = 5 * 60_000L
    private const val REBIND_INTERVAL_MS = 2 * 60_000L
    private const val SEEN_RETENTION_MS = 2 * 24 * 3600_000L
    private const val PRUNE_INTERVAL_MS = 6 * 3600_000L

    data class State(
        val running: Boolean = false,
        val listenerConnected: Boolean = false,
        val serverOk: Boolean? = null,
        val tokenRejected: Boolean = false,
        val lastHeartbeatAt: Long = 0L,
        val lastSentAt: Long = 0L,
        val lastResult: String? = null,
        val lastError: String? = null,
        val queueSize: Int = 0,
        /** Kapan listener terakhir terputus (0 = belum pernah terputus di proses ini). */
        val listenerDisconnectedAt: Long = 0L,
        /** Kapan notifikasi DANA terakhir diterima listener (bukti pembacaan, terlepas dari filter). */
        val lastDanaSeenAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var callbackManager: ConnectivityManager? = null

    @Volatile
    private var lastHeartbeatAttempt = 0L

    @Volatile
    private var lastRebindAttempt = 0L

    @Volatile
    private var lastPrune = 0L

    @Volatile
    private var appCtx: Context? = null

    /** Pengiriman antrean diserialkan: loop dalam proses dan SendWorker tidak pernah mengirim event yang sama bersamaan. */
    private val flushLock = Any()

    /** Backoff eksponensial 5 dtk..5 mnt (atau Retry-After server) + jitter 0..20% agar tidak serempak. */
    internal fun backoffMs(attempts: Int, afterSec: Long?, rand: Double): Long {
        val base = afterSec?.let { (it * 1000L).coerceIn(1_000L, MAX_BACKOFF_MS) }
            ?: minOf(MAX_BACKOFF_MS, 5_000L shl minOf(maxOf(attempts - 1, 0), 6))
        return base + (base * 0.2 * rand.coerceIn(0.0, 1.0)).toLong()
    }

    // ---- kontrol ----

    @Synchronized
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (_state.value.lastDanaSeenAt == 0L) {
            val saved = AppConfig.get(app).lastDanaSeenAt
            if (saved > 0L) _state.update { it.copy(lastDanaSeenAt = saved) }
        }
        if (job?.isActive == true) {
            kick()
            return
        }
        registerNetworkCallback(app)
        _state.update { it.copy(running = true) }
        job = scope.launch { runLoop(app) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        unregisterNetworkCallback()
        _state.update { it.copy(running = false) }
    }

    fun kick() {
        wake.trySend(Unit)
    }

    fun setListenerConnected(connected: Boolean) {
        _state.update {
            if (connected) it.copy(listenerConnected = true)
            else it.copy(listenerConnected = false, listenerDisconnectedAt = System.currentTimeMillis())
        }
    }

    /** Listener menerima notifikasi dari paket DANA (sebelum filter isi). Dicatat permanen. */
    fun noteDanaSeen(ctx: Context) {
        val now = System.currentTimeMillis()
        AppConfig.get(ctx).lastDanaSeenAt = now
        _state.update { it.copy(lastDanaSeenAt = now) }
    }

    /**
     * Tes Koneksi manual (satu heartbeat). Hanya menyentuh status perangkat di server,
     * tidak pernah mengirim /hook/payment sehingga aman di mode diagnostik. Panggil dari thread IO.
     */
    fun testConnection(ctx: Context): Pair<Boolean, String> {
        val cfg = AppConfig.get(ctx)
        if (!cfg.isConfigured) return false to "Belum dikonfigurasi. Buka Pengaturan."
        val body = JSONObject().put("device_id", cfg.deviceId).put("queue", Store.get(ctx).count()).put("app_version", appVersion).toString()
        return when (val r = ApiClient.post(cfg.serverUrl, cfg.token, "/hook/heartbeat", body)) {
            is ApiResult.Ok ->
                if (r.body.optBoolean("ok", false)) true to "Server terjangkau dan token valid."
                else false to "Respons server tidak dikenal. Cek URL."
            ApiResult.Unauthorized -> false to "Token ditolak (401). Buat token baru di dashboard."
            is ApiResult.Rejected -> false to "Ditolak server: ${r.message}"
            is ApiResult.Retry -> false to "Server belum terjangkau: ${r.message}"
        }
    }

    data class TestOutcome(val level: Level, val message: String)

    /**
     * Kirim Event Uji: SATU kali ke /hook/payment, TERPISAH dari pembayaran asli:
     * event_id berawalan "uji-", tanpa nominal/angka, tidak masuk antrean durable, tidak dicoba ulang,
     * tidak masuk tabel "seen". Hanya dicatat di Log lokal. Panggil dari thread IO.
     * Jika server ternyata mencocokkannya ke order, hasilnya diberi peringatan keras.
     */
    fun sendTestEvent(ctx: Context): TestOutcome {
        val cfg = AppConfig.get(ctx)
        val store = Store.get(ctx)
        if (!cfg.isConfigured) return TestOutcome(Level.BAD, "Belum dikonfigurasi. Buka Pengaturan.")
        val now = System.currentTimeMillis()
        val eventId = AppConfig.sanitizeDeviceId("uji-${cfg.deviceId.take(16)}-$now")
        val payload = JSONObject()
            .put("event_id", eventId)
            .put("device_id", cfg.deviceId)
            .put("timestamp", now)
            .put("package", cfg.danaPackage)
            .put("title", "[UJI] Tes koneksi Companion")
            .put("text", "Event uji koneksi, bukan pembayaran. Abaikan.")
            .toString()
        val outcome = when (val r = ApiClient.post(cfg.serverUrl, cfg.token, "/hook/payment", payload)) {
            is ApiResult.Ok -> {
                val order = r.body.optString("order_id", "")
                val result = r.body.optString("result", "")
                when {
                    order.isNotBlank() || result.equals("matched", true) || result.equals("paid", true) ->
                        TestOutcome(Level.BAD, "PERINGATAN: server mencocokkan event uji ke order $order. Periksa dashboard SEKARANG dan hubungi admin backend.")
                    r.body.optBoolean("stored", false) ->
                        TestOutcome(Level.OK, "Event uji diterima server (${describe(r.body)}). Tidak cocok dengan invoice mana pun.")
                    else -> TestOutcome(Level.WARN, "Server menjawab 200 tetapi tanpa stored:true. Cek URL/versi server.")
                }
            }
            ApiResult.Unauthorized -> TestOutcome(Level.BAD, "Token ditolak (401).")
            is ApiResult.Rejected -> TestOutcome(Level.BAD, "Ditolak server: ${r.message}")
            is ApiResult.Retry -> TestOutcome(Level.BAD, "Server belum terjangkau: ${r.message} (tidak dicoba ulang otomatis)")
        }
        store.addLog(null, "[UJI] $eventId", "uji: ${outcome.message}".take(200))
        return outcome
    }

    /** Ganti mode diagnostik/live. Event yang tertahan di antrean baru dikirim setelah live. */
    fun setLive(ctx: Context, live: Boolean) {
        AppConfig.get(ctx).liveEnabled = live
        _state.update { it.copy(tokenRejected = false, lastError = null) }
        lastHeartbeatAttempt = 0L
        start(ctx)
        kick()
        if (live && Store.get(ctx).count() > 0) SendWorker.enqueue(ctx, "live-${System.currentTimeMillis()}")
    }

    /** Dipanggil setelah pengaturan (URL/token) berubah. */
    fun onConfigChanged(ctx: Context) {
        _state.update { it.copy(tokenRejected = false, serverOk = null, lastError = null) }
        lastHeartbeatAttempt = 0L
        start(ctx)
        kick()
    }

    /** Tombol "Coba kirim sekarang": hapus backoff dan paksa satu siklus penuh. */
    fun retryNow(ctx: Context) {
        Store.get(ctx).resetBackoff()
        _state.update { it.copy(tokenRejected = false, lastError = null) }
        lastHeartbeatAttempt = 0L
        start(ctx)
        kick()
        SendWorker.enqueue(ctx, "manual-${System.currentTimeMillis()}")
    }

    /** "Coba Lagi" satu event dari bottom sheet: event_id yang sama, dikirim segera. */
    fun retryEvent(ctx: Context, eventId: String): Boolean {
        if (!Store.get(ctx).retryEvent(eventId)) return false
        _state.update { it.copy(tokenRejected = false, lastError = null) }
        lastHeartbeatAttempt = 0L
        start(ctx)
        kick()
        SendWorker.enqueue(ctx, "retry-$eventId")
        return true
    }

    /**
     * Masukkan event ke antrean durable lalu bangunkan pengirim.
     * @return false jika event_id sudah pernah dicatat (mis. replay notifikasi yang sama).
     */
    fun submit(ctx: Context, eventId: String, payload: String, summary: String): Boolean {
        val store = Store.get(ctx)
        val inserted = store.enqueue(eventId, payload, System.currentTimeMillis())
        if (inserted) {
            store.addLog(eventId, summary, "antre", Stage.QUEUED)
            val dropped = store.trimQueue(MAX_QUEUE)
            if (dropped > 0) store.addLog(null, "Antrean penuh, $dropped event tertua dibuang", "peringatan")
        }
        start(ctx)
        kick()
        // Jalur andal: WorkManager (expedited, menunggu jaringan, retry otomatis) meski UI/app tidak terbuka.
        if (inserted) SendWorker.enqueue(ctx, eventId)
        return inserted
    }

    /**
     * Dipanggil SendWorker. Kirim semua event yang jatuh tempo.
     * @return true bila tidak ada lagi yang perlu dicoba oleh worker (antrean kosong / mode uji / token ditolak);
     *         false bila masih ada event tertunda -> WorkManager retry dengan backoff.
     */
    fun deliverPending(ctx: Context): Boolean {
        val app = ctx.applicationContext
        appCtx = app
        val cfg = AppConfig.get(app)
        val store = Store.get(app)
        if (!cfg.isConfigured || !cfg.liveEnabled || _state.value.tokenRejected) return true
        flush(cfg, store)
        _state.update { it.copy(queueSize = store.count()) }
        return store.count() == 0 || _state.value.tokenRejected
    }

    // ---- loop ----

    private suspend fun runLoop(app: Context) {
        val cfg = AppConfig.get(app)
        val store = Store.get(app)
        reportGap(cfg, store)
        while (currentCoroutineContext().isActive) {
            try {
                cycle(cfg, store)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "cycle gagal", e)
                _state.update { it.copy(lastError = "Kesalahan internal: ${e.message ?: e.javaClass.simpleName}") }
            }
            val wait = nextWaitMs(cfg, store)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /**
     * Android tidak memberi tahu aplikasi bahwa ia pernah di-force-stop/dimatikan/reboot.
     * Jejaknya hanya jeda sejak terakhir hidup; catat jujur bahwa notifikasi di rentang itu bisa terlewat.
     */
    private fun reportGap(cfg: AppConfig, store: Store) {
        if (!cfg.isConfigured) return
        val last = cfg.lastAliveAt
        val now = System.currentTimeMillis()
        if (last > 0L && now - last > GAP_WARN_MS) {
            val mins = (now - last) / 60_000L
            store.addLog(
                null,
                "Aplikasi tidak berjalan ±$mins mnt (reboot, dimatikan sistem, atau Paksa berhenti). " +
                    "Notifikasi DANA pada rentang ini mungkin tidak terbaca; cocokkan dengan riwayat DANA/dashboard.",
                "peringatan",
            )
        }
        cfg.lastAliveAt = now
    }

    private fun maintainListener(app: Context) {
        val now = System.currentTimeMillis()
        if (_state.value.listenerConnected || !Perms.listenerEnabled(app)) return
        if (now - lastRebindAttempt < REBIND_INTERVAL_MS) return
        lastRebindAttempt = now
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(app, DanaNotificationListener::class.java),
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind gagal", e)
        }
    }

    private fun cycle(cfg: AppConfig, store: Store) {
        val now0 = System.currentTimeMillis()
        cfg.lastAliveAt = now0
        appCtx?.let { maintainListener(it) }
        if (now0 - lastPrune > PRUNE_INTERVAL_MS) {
            lastPrune = now0
            store.pruneSeen(now0 - SEEN_RETENTION_MS)
        }
        _state.update { it.copy(queueSize = store.count()) }
        if (!cfg.isConfigured) {
            _state.update { it.copy(serverOk = null, lastError = "Belum dikonfigurasi") }
            return
        }
        if (!cfg.liveEnabled) {
            _state.update { it.copy(serverOk = null, lastError = null) }
            return // mode diagnostik: tidak ada jaringan otomatis; antrean (jika ada) ditahan
        }
        if (_state.value.tokenRejected) return
        if (System.currentTimeMillis() - lastHeartbeatAttempt >= HEARTBEAT_INTERVAL_MS) {
            sendHeartbeat(cfg, store)
        }
        if (!_state.value.tokenRejected) flush(cfg, store)
        _state.update { it.copy(queueSize = store.count()) }
    }

    private fun nextWaitMs(cfg: AppConfig, store: Store): Long {
        if (!cfg.isConfigured || !cfg.liveEnabled || _state.value.tokenRejected) return IDLE_WAIT_MS
        val now = System.currentTimeMillis()
        val untilHeartbeat = (lastHeartbeatAttempt + HEARTBEAT_INTERVAL_MS - now).coerceAtLeast(500L)
        val head = store.head() ?: return untilHeartbeat
        val untilHead = (head.nextAt - now).coerceAtLeast(500L)
        return minOf(untilHeartbeat, untilHead)
    }

    private fun sendHeartbeat(cfg: AppConfig, store: Store) {
        lastHeartbeatAttempt = System.currentTimeMillis()
        val body = JSONObject()
            .put("device_id", cfg.deviceId)
            .put("queue", store.count())
            .put("app_version", appVersion)
            .toString()
        when (val r = ApiClient.post(cfg.serverUrl, cfg.token, "/hook/heartbeat", body)) {
            is ApiResult.Ok -> {
                if (r.body.optBoolean("ok", false)) {
                    _state.update { it.copy(serverOk = true, lastHeartbeatAt = System.currentTimeMillis(), lastError = null) }
                } else {
                    _state.update { it.copy(serverOk = false, lastError = "Respons heartbeat tidak dikenal") }
                }
            }
            ApiResult.Unauthorized -> markUnauthorized()
            is ApiResult.Rejected -> _state.update { it.copy(serverOk = false, lastError = "Heartbeat ditolak: ${r.message}") }
            is ApiResult.Retry -> _state.update { it.copy(serverOk = false, lastError = r.message) }
        }
    }

    private fun flush(cfg: AppConfig, store: Store) {
        synchronized(flushLock) { flushLocked(cfg, store) }
    }

    private fun flushLocked(cfg: AppConfig, store: Store) {
        while (true) {
            val ev = store.head() ?: break
            if (ev.nextAt > System.currentTimeMillis()) break
            store.setStage(ev.eventId, Stage.SENDING, "mengirim…")
            val sentAt = System.currentTimeMillis()
            val notifAt = try { JSONObject(ev.payload).optLong("timestamp", 0L) } catch (e: Exception) { 0L }
            Log.i(TAG, "webhook dikirim event=${ev.eventId} notif_at=$notifAt sent_at=$sentAt " +
                "delay_ms=${if (notifAt > 0) sentAt - notifAt else -1} percobaan=${ev.attempts + 1}")
            when (val r = ApiClient.post(cfg.serverUrl, cfg.token, "/hook/payment", ev.payload)) {
                is ApiResult.Ok -> {
                    if (r.body.optBoolean("stored", false)) {
                        val desc = describe(r.body)
                        Log.i(TAG, "backend memproses event=${ev.eventId} hasil=${r.body.optString("result", "?")} " +
                            "ack_at=${System.currentTimeMillis()} total_ms=${System.currentTimeMillis() - sentAt}")
                        store.remove(ev.eventId)
                        store.setStage(ev.eventId, Stage.ACKED, desc)
                        _state.update {
                            it.copy(serverOk = true, lastSentAt = System.currentTimeMillis(), lastResult = desc, lastError = null)
                        }
                    } else {
                        scheduleRetry(store, ev, "Respons server tanpa stored:true", null)
                        break
                    }
                }
                ApiResult.Unauthorized -> {
                    store.markRetry(ev.eventId, ev.attempts, ev.nextAt, "Token ditolak (401)")
                    store.setStage(ev.eventId, Stage.SENDING, "gagal: token ditolak (401)")
                    markUnauthorized()
                    break
                }
                is ApiResult.Rejected -> {
                    // Ditolak permanen: simpan (bukan hapus) supaya "Coba Lagi" bisa memakai event_id yang sama.
                    store.markDead(ev.eventId, r.message)
                    store.setStage(ev.eventId, Stage.REJECTED, "ditolak server: ${r.message}")
                    _state.update { it.copy(lastError = "Event ditolak server: ${r.message}") }
                }
                is ApiResult.Retry -> {
                    scheduleRetry(store, ev, r.message, r.afterSec)
                    break
                }
            }
            _state.update { it.copy(queueSize = store.count()) }
        }
    }

    private fun scheduleRetry(store: Store, ev: QueuedEvent, message: String, afterSec: Long?) {
        val attempts = ev.attempts + 1
        val backoff = backoffMs(attempts, afterSec, Math.random())
        store.markRetry(ev.eventId, attempts, System.currentTimeMillis() + backoff, message)
        store.setStage(ev.eventId, Stage.SENDING, "gagal: $message")
        _state.update { it.copy(serverOk = false, lastError = "$message (percobaan $attempts, ulang ${backoff / 1000} dtk)") }
    }

    private fun markUnauthorized() {
        _state.update {
            it.copy(
                tokenRejected = true,
                serverOk = false,
                lastError = "Token perangkat ditolak server (401). Buat token baru di dashboard lalu isi ulang di Pengaturan.",
            )
        }
    }

    private fun describe(body: JSONObject): String {
        val result = body.optString("result", "?")
        val order = body.optString("order_id", "")
        val dup = body.optBoolean("duplicate", false)
        return buildString {
            append("server: ").append(result)
            if (order.isNotBlank()) append(" • ").append(order)
            if (dup) append(" (duplikat)")
            // outcome_label dari server dipakai sebagai teks; UI memetakan result -> label yang sama dengan web.
        }
    }

    // ---- jaringan ----

    private fun registerNetworkCallback(app: Context) {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lastHeartbeatAttempt = 0L
                    kick()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
            callbackManager = cm
        } catch (e: Exception) {
            Log.w(TAG, "network callback tidak tersedia", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback
        val cm = callbackManager
        if (cb != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "unregister network callback gagal", e)
            }
        }
        netCallback = null
        callbackManager = null
    }
}
