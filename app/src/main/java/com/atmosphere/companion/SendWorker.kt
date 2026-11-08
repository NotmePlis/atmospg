package com.atmosphere.companion

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pengirim antrean lewat WorkManager (expedited). Dijadwalkan SEGERA saat notifikasi masuk, sehingga
 * pengiriman tidak bergantung pada UI/aplikasi yang terbuka maupun loop di dalam proses:
 * - tetap jalan saat Doze / proses di-cache (WorkManager memegang wakelock + izin jaringan expedited),
 * - ditunda otomatis sampai ada jaringan, lalu retry eksponensial bila server belum menjawab,
 * - antreannya sendiri tetap SQLite (Store); event hanya dihapus setelah HTTP 200 + stored:true.
 * Idempoten: event_id sama + dedupe server, dan pengiriman diserialkan di RelayEngine.
 */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AppConfig.get(app).isConfigured) return Result.success()
        val done = withContext(Dispatchers.IO) { RelayEngine.deliverPending(app) }
        if (done) return Result.success()
        // Sisa event dicoba lagi oleh WorkManager (backoff). Setelah banyak percobaan, serahkan ke loop/SyncWorker.
        return if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    /** Dipakai WorkManager untuk expedited work di Android 11 ke bawah. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val app = applicationContext
        Notifications.ensureChannel(app)
        val n = NotificationCompat.Builder(app, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(app.getString(R.string.app_name))
            .setContentText("Mengirim pembayaran…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        private const val NOTIF_ID = 1002
        private const val MAX_ATTEMPTS = 12

        /** Jadwalkan pengiriman segera. Satu pekerjaan per [key]; semuanya menguras antrean yang sama. */
        fun enqueue(ctx: Context, key: String) {
            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork("send-$key", ExistingWorkPolicy.KEEP, req)
        }
    }
}
