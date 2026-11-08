package com.atmosphere.companion

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class QueuedEvent(val eventId: String, val payload: String, val attempts: Int, val nextAt: Long)
data class LogRow(
    val ts: Long,
    val summary: String,
    val result: String,
    val eventId: String? = null,
    val stage: Int = 0,
    /** Percobaan kirim terakhir (0 = belum pernah). */
    val sentAt: Long = 0L,
    /** Null jika event sudah tidak ada di antrean (sudah diakui server). */
    val attempts: Int? = null,
    val lastError: String? = null,
    /** Kapan server mengakui event (HTTP 200 + stored:true); 0 = belum. */
    val ackedAt: Long = 0L,
) {
    /** "Coba Lagi" hanya untuk event yang masih di antrean dan belum diakui server (ditolak atau gagal kirim). */
    val canRetry: Boolean
        get() = eventId != null && attempts != null &&
            (stage == Stage.REJECTED || (stage == Stage.SENDING && result.startsWith("gagal")))
}

/** Tahap pengiriman sebuah event pembayaran (disimpan di kolom log.stage). */
object Stage {
    const val QUEUED = 2 // diterima listener, lolos filter, tersimpan di antrean
    const val SENDING = 3 // percobaan kirim sudah dilakukan/sedang berjalan, BELUM ada pengakuan server
    const val ACKED = 4 // server menjawab 200 + stored:true
    const val REJECTED = 5 // server menolak permanen (400); disimpan, bisa dicoba lagi
}

/** Gabungan baris log + status antrean untuk event pembayaran terakhir. */
data class EventDiag(
    val eventId: String,
    val ts: Long,
    val stage: Int,
    val result: String,
    val attempts: Int,
    val lastError: String?,
)

/**
 * Antrean durable (SQLite) + log lokal. Event dihapus dari antrean hanya setelah
 * server menjawab HTTP 200 dengan stored:true (kontrak /hook/payment).
 */
class Store private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (" +
                "event_id TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, " +
                "state INTEGER NOT NULL DEFAULT 0, last_error TEXT)",
        )
        db.execSQL(
            "CREATE TABLE log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, event_id TEXT, " +
                "summary TEXT NOT NULL, result TEXT NOT NULL, stage INTEGER NOT NULL DEFAULT 0, " +
                "sent_at INTEGER NOT NULL DEFAULT 0, acked_at INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX idx_log_event ON log(event_id)")
        createSeen(db)
    }

    private fun createSeen(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS seen (k TEXT PRIMARY KEY, ts INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSeen(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE events ADD COLUMN state INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE events ADD COLUMN last_error TEXT")
            db.execSQL("ALTER TABLE log ADD COLUMN stage INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE log ADD COLUMN sent_at INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 5) db.execSQL("ALTER TABLE log ADD COLUMN acked_at INTEGER NOT NULL DEFAULT 0")
    }

    // ---- antrean ----

    /**
     * Catat event_id sebagai "pernah dilihat" lalu antrekan, dalam satu transaksi.
     * Catatan "seen" tidak ikut terhapus saat event terkirim atau log dipangkas, sehingga
     * replay notifikasi yang sama (restart proses, rebind listener) tidak diantrekan ulang.
     * @return true jika baru dimasukkan, false jika event_id sudah pernah diproses.
     */
    @Synchronized
    fun enqueue(eventId: String, payload: String, now: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val seenCv = ContentValues().apply {
                put("k", "e:$eventId")
                put("ts", now)
            }
            if (db.insertWithOnConflict("seen", null, seenCv, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return false
            val cv = ContentValues().apply {
                put("event_id", eventId)
                put("payload", payload)
                put("created_at", now)
                put("attempts", 0)
                put("next_at", 0L)
            }
            val inserted = db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Dedupe isi notifikasi (pembaruan/posting ulang dengan teks sama) yang tahan restart proses.
     * @return true jika boleh diproses; false jika hash yang sama sudah diproses dalam [windowMs].
     */
    @Synchronized
    fun claimContent(hash: String, now: Long, windowMs: Long): Boolean {
        val db = writableDatabase
        val last = db.rawQuery("SELECT ts FROM seen WHERE k=?", arrayOf("h:$hash")).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        if (last != null && now - last < windowMs) return false
        val cv = ContentValues().apply {
            put("k", "h:$hash")
            put("ts", now)
        }
        db.insertWithOnConflict("seen", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    @Synchronized
    fun pruneSeen(olderThan: Long) {
        writableDatabase.delete("seen", "ts < ?", arrayOf(olderThan.toString()))
    }

    /** Event tertua. Urutan dijaga: event berikutnya menunggu sampai kepala antrean terkirim. */
    @Synchronized
    fun head(): QueuedEvent? = readableDatabase.rawQuery(
        "SELECT event_id, payload, attempts, next_at FROM events WHERE state=0 ORDER BY created_at ASC, rowid ASC LIMIT 1",
        null,
    ).use { c ->
        if (c.moveToFirst()) QueuedEvent(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)) else null
    }

    @Synchronized
    fun count(): Int = DatabaseUtils.queryNumEntries(readableDatabase, "events", "state=0").toInt()

    /** Event yang ditolak permanen oleh server (400) dan menunggu keputusan "Coba Lagi". */
    @Synchronized
    fun deadCount(): Int = DatabaseUtils.queryNumEntries(readableDatabase, "events", "state=1").toInt()

    @Synchronized
    fun markRetry(eventId: String, attempts: Int, nextAt: Long, error: String? = null) {
        val cv = ContentValues().apply {
            put("attempts", attempts)
            put("next_at", nextAt)
            put("last_error", error)
        }
        writableDatabase.update("events", cv, "event_id=?", arrayOf(eventId))
    }

    @Synchronized
    fun remove(eventId: String) {
        writableDatabase.delete("events", "event_id=?", arrayOf(eventId))
    }

    /** Ditolak permanen: jangan hapus dan jangan blokir antrean; payload & event_id tetap utuh untuk Coba Lagi. */
    @Synchronized
    fun markDead(eventId: String, reason: String) {
        val cv = ContentValues().apply {
            put("state", 1)
            put("last_error", reason)
        }
        writableDatabase.update("events", cv, "event_id=?", arrayOf(eventId))
    }

    /**
     * "Coba Lagi": semua event yang belum diakui server (tertunda maupun ditolak) dikirim lagi
     * SEKARANG dengan event_id dan payload yang sama. Tidak ada event baru yang dibuat.
     */
    @Synchronized
    fun resetBackoff() {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("next_at", 0L)
            put("state", 0)
        }
        db.update("events", cv, null, null)
        db.execSQL("UPDATE log SET stage=${Stage.QUEUED}, result='antre ulang' WHERE stage=${Stage.REJECTED}")
    }

    /** "Coba Lagi" untuk SATU event: event_id & payload sama, dikirim segera. @return false jika event tidak ada di antrean. */
    @Synchronized
    fun retryEvent(eventId: String): Boolean {
        val cv = ContentValues().apply {
            put("next_at", 0L)
            put("state", 0)
        }
        if (writableDatabase.update("events", cv, "event_id=?", arrayOf(eventId)) == 0) return false
        writableDatabase.execSQL(
            "UPDATE log SET stage=${Stage.QUEUED}, result='antre ulang' WHERE event_id=? AND stage=${Stage.REJECTED}",
            arrayOf(eventId),
        )
        return true
    }

    @Synchronized
    fun setStage(eventId: String, stage: Int, result: String) {
        val cv = ContentValues().apply {
            put("stage", stage)
            put("result", result)
            if (stage == Stage.SENDING) put("sent_at", System.currentTimeMillis())
            if (stage == Stage.ACKED) put("acked_at", System.currentTimeMillis())
        }
        writableDatabase.update("log", cv, "event_id=?", arrayOf(eventId))
    }

    /** Event pembayaran terakhir beserta tahapnya (null jika belum ada). */
    @Synchronized
    fun lastEventDiag(): EventDiag? = readableDatabase.rawQuery(
        "SELECT l.event_id, l.ts, l.stage, l.result, e.attempts, e.last_error " +
            "FROM log l LEFT JOIN events e ON e.event_id = l.event_id " +
            "WHERE l.event_id IS NOT NULL AND l.stage > 0 ORDER BY l.id DESC LIMIT 1",
        null,
    ).use { c ->
        if (c.moveToFirst()) {
            EventDiag(
                c.getString(0), c.getLong(1), c.getInt(2), c.getString(3),
                if (c.isNull(4)) 0 else c.getInt(4),
                if (c.isNull(5)) null else c.getString(5),
            )
        } else null
    }

    /** Batasi ukuran antrean; yang terlama dibuang. @return jumlah yang dibuang. */
    @Synchronized
    fun trimQueue(max: Int): Int = writableDatabase.delete(
        "events",
        "event_id IN (SELECT event_id FROM events ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET ?)",
        arrayOf(max.toString()),
    )

    // ---- log ----

    @Synchronized
    fun addLog(eventId: String?, summary: String, result: String, stage: Int = 0) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("event_id", eventId)
            put("summary", summary)
            put("result", result)
            put("stage", stage)
        }
        db.insert("log", null, cv)
        db.execSQL("DELETE FROM log WHERE id <= (SELECT MAX(id) FROM log) - $MAX_LOG")
    }

    @Synchronized
    fun hasLog(eventId: String): Boolean = DatabaseUtils.longForQuery(
        readableDatabase,
        "SELECT COUNT(*) FROM log WHERE event_id=?",
        arrayOf(eventId),
    ) > 0

    @Synchronized
    fun recentLogs(limit: Int): List<LogRow> = readableDatabase.rawQuery(
        "SELECT l.ts, l.summary, l.result, l.event_id, l.stage, l.sent_at, e.attempts, e.last_error, l.acked_at " +
            "FROM log l LEFT JOIN events e ON e.event_id = l.event_id ORDER BY l.id DESC LIMIT $limit",
        null,
    ).use { c ->
        val out = ArrayList<LogRow>()
        while (c.moveToNext()) {
            out.add(
                LogRow(
                    c.getLong(0), c.getString(1), c.getString(2),
                    if (c.isNull(3)) null else c.getString(3), c.getInt(4), c.getLong(5),
                    if (c.isNull(6)) null else c.getInt(6),
                    if (c.isNull(7)) null else c.getString(7),
                    c.getLong(8),
                ),
            )
        }
        out
    }

    companion object {
        private const val DB_NAME = "companion.db"
        private const val DB_VERSION = 5
        private const val MAX_LOG = 200

        @Volatile
        private var instance: Store? = null

        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx).also { instance = it }
        }
    }
}
