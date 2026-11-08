package com.atmosphere.companion

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat

enum class Level { OK, BAD, WARN, NEUTRAL, INFO }

object Ui {
    /** Baris "Label: nilai" dengan label tebal dan nilai berwarna sesuai status. */
    fun line(ctx: Context, label: String, value: String, level: Level): CharSequence {
        val sb = SpannableStringBuilder("$label: ")
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        sb.append(value)
        val color = when (level) {
            Level.OK -> R.color.status_ok
            Level.BAD -> R.color.status_bad
            Level.WARN -> R.color.status_warn
            Level.NEUTRAL, Level.INFO -> null
        }
        if (color != null) {
            sb.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(ctx, color)),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    fun ago(ts: Long): String {
        if (ts <= 0L) return "belum pernah"
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 5 -> "baru saja"
            s < 60 -> "$s dtk lalu"
            s < 3600 -> "${s / 60} mnt lalu"
            s < 86400 -> "${s / 3600} jam lalu"
            else -> "${s / 86400} hari lalu"
        }
    }

    /** Nada warna untuk satu baris riwayat berdasarkan teks hasilnya. */
    fun levelOfLog(result: String): Level {
        val r = result.lowercase()
        return when {
            r.startsWith("peringatan") -> Level.WARN
            r.startsWith("diabaikan") || r.startsWith("antre") -> Level.NEUTRAL
            r.contains("gagal") || r.contains("ditolak") || r.contains("error") || r.contains("401") -> Level.BAD
            r.startsWith("server:") -> if (r.contains("ignored") || r.contains("needs_review")) Level.WARN else Level.OK
            r.startsWith("diagnostik") || r.startsWith("uji") -> Level.WARN
            else -> Level.NEUTRAL
        }
    }

    /** Status PENGIRIMAN notifikasi ke server (bukan status pembayaran invoice). */
    fun delivery(row: LogRow): Pair<String, Level> = when {
        row.stage == Stage.ACKED -> "Diterima Server" to Level.NEUTRAL
        row.stage == Stage.REJECTED -> "Gagal" to Level.BAD
        row.stage == Stage.SENDING && row.result.startsWith("gagal") -> "Gagal" to Level.BAD
        row.stage >= Stage.QUEUED -> "Mengantre" to Level.NEUTRAL
        else -> row.result.substringBefore(":").replaceFirstChar { it.uppercase() } to levelOfLog(row.result)
    }

    /** Status INVOICE menurut jawaban server; hanya ada jika server sudah mengakui event. */
    fun invoice(row: LogRow): Pair<String, Level>? {
        if (row.stage != Stage.ACKED) return null
        if (row.result.endsWith("(duplikat)")) return "Duplikat" to Level.NEUTRAL
        return when (row.result.removePrefix("server: ").substringBefore(" ")) {
            "matched" -> "Cocok dengan Invoice" to Level.OK
            "needs_review" -> "Perlu Diperiksa" to Level.WARN
            "duplicate_content" -> "Duplikat" to Level.NEUTRAL
            else -> null // diabaikan (bukan pembayaran): cukup "Diterima Server"
        }
    }

    private val AMOUNT = Regex("Rp\\.?\\s?\\d[\\d.,]*", RegexOption.IGNORE_CASE)

    /** Nominal pertama yang tampak di ringkasan notifikasi, jika ada. */
    fun amountOf(summary: String): String? = AMOUNT.find(summary)?.value

    fun textColor(level: Level) = when (level) {
        Level.OK -> R.color.status_ok
        Level.BAD -> R.color.status_bad
        Level.WARN -> R.color.status_warn
        Level.NEUTRAL -> R.color.status_neutral
        Level.INFO -> R.color.status_info
    }

    fun containerColor(level: Level) = when (level) {
        Level.OK -> R.color.container_ok
        Level.BAD -> R.color.container_bad
        Level.WARN -> R.color.container_warn
        Level.NEUTRAL -> R.color.container_neutral
        Level.INFO -> R.color.container_info
    }

    // ---- Transaksi (daftar utama): hanya event pembayaran; duplikat/diabaikan/diagnostik hanya di log teknis ----

    /** Status transaksi untuk pengguna. "Berhasil" hanya bila SERVER menjawab matched (bukan dari nominal). */
    enum class TxStatus(val label: String, val level: Level, val icon: Int) {
        SUCCESS("Berhasil", Level.OK, R.drawable.ic_check_circle),
        PENDING("Pending", Level.INFO, R.drawable.ic_schedule),
        REVIEW("Perlu Diperiksa", Level.WARN, R.drawable.ic_warning),
    }

    class Tx(val row: LogRow, val status: TxStatus, val amount: Long?)

    /** null = bukan transaksi untuk daftar utama (duplikat, diabaikan, peringatan, diagnostik, uji). */
    fun toTx(row: LogRow): Tx? {
        if (row.eventId == null || row.stage < Stage.QUEUED) return null
        val st = when (row.stage) {
            Stage.ACKED -> {
                if (row.result.endsWith("(duplikat)")) return null
                when (row.result.removePrefix("server: ").substringBefore(" ")) {
                    "matched" -> TxStatus.SUCCESS
                    "needs_review" -> TxStatus.REVIEW
                    else -> return null
                }
            }
            Stage.REJECTED -> TxStatus.REVIEW
            else -> TxStatus.PENDING
        }
        return Tx(row, st, amountValue(row.summary))
    }

    /** "Rp1.060" / "Rp 1.060,50" -> 1060. */
    fun amountValue(summary: String): Long? {
        val raw = amountOf(summary) ?: return null
        var d = raw.replace(Regex("(?i)rp\\.?"), "").trim()
        d = d.replace(Regex("[.,]\\d{1,2}$"), "")
        return d.filter { it.isDigit() }.toLongOrNull()
    }

    fun rupiah(n: Long): String = "Rp " + String.format(java.util.Locale.US, "%,d", n).replace(',', '.')

    private val dayFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("id", "ID"))
    private val dateFmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("id", "ID"))

    fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun shortTime(ts: Long): String = if (ts >= startOfToday()) dayFmt.format(java.util.Date(ts)) else dateFmt.format(java.util.Date(ts))
}
