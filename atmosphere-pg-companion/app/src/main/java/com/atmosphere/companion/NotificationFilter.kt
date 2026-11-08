package com.atmosphere.companion

import java.util.regex.Pattern

/** Data mentah satu notifikasi, sudah diekstrak dari StatusBarNotification. */
data class RawNotification(
    val pkg: String,
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val infoText: String,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
)

sealed class Decision {
    /** Bukan urusan aplikasi ini (aplikasi lain, ringkasan grup, notifikasi ongoing): tidak dibaca/dicatat. */
    object Ignore : Decision()

    /** Notifikasi DANA yang tidak dikirim. [sensitive]=true -> isi tidak boleh disimpan/ditampilkan. */
    data class Drop(val reason: String, val sensitive: Boolean, val title: String, val text: String) : Decision()

    /** Lolos filter: kirim ke backend. */
    data class Send(val title: String, val text: String) : Decision()
}

/**
 * Filter lokal. Hanya memutuskan APA yang boleh dikirim; pencocokan invoice
 * dan penetapan Paid tetap sepenuhnya urusan backend.
 */
object NotificationFilter {
    const val DEFAULT_PACKAGE = "id.dana"
    const val DEFAULT_KEYWORDS = "terima|nerima|diterima|masuk|received|pembayaran|payment"

    // Selaras dengan SENSITIVE di server/routes/hook.js, ditambah frasa umum pesan kode rahasia.
    private val SENSITIVE: Pattern = Pattern.compile(
        "\\b(otp|pin|kode\\s*verifikasi|verification\\s*code|kata\\s*sandi|password|cvv|kode\\s*rahasia|" +
            "one[-\\s]?time|jangan\\s*(bagikan|beritahu|berikan)|do\\s*not\\s*share)\\b",
        Pattern.CASE_INSENSITIVE,
    )
    // Titik = ribuan ("Rp1.018" = 1018), koma = desimal. Cocok dengan server/dana.js.
    private val AMOUNT: Pattern = Pattern.compile("Rp\\.?\\s?(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,\\d{1,2})?(?!\\d)", Pattern.CASE_INSENSITIVE)

    /** Nominal rupiah dari isi (lalu judul). Null bila tidak ada atau ambigu (>1 nominal berbeda). */
    fun parseAmount(title: String, body: String): Long? {
        for (src in listOf(body, title)) {
            val m = AMOUNT.matcher(src)
            val found = LinkedHashSet<Long>()
            while (m.find()) m.group(1)?.replace(".", "")?.toLongOrNull()?.takeIf { it > 0 }?.let { found.add(it) }
            if (found.size == 1) return found.first()
            if (found.size > 1) return null
        }
        return null
    }

    fun compileKeywords(pattern: String): Pattern =
        Pattern.compile(pattern.ifBlank { DEFAULT_KEYWORDS }, Pattern.CASE_INSENSITIVE)

    fun evaluate(n: RawNotification, danaPackage: String, keywords: Pattern): Decision {
        if (n.pkg != danaPackage) return Decision.Ignore
        if (n.isGroupSummary || n.isOngoing) return Decision.Ignore

        val fields = listOf(n.title, n.text, n.bigText, n.subText, n.infoText)
        if (fields.any { SENSITIVE.matcher(it).find() }) {
            return Decision.Drop("berisi kata sensitif (OTP/PIN/sandi)", true, "", "")
        }

        val body = n.bigText.ifBlank { n.text }
        val combined = n.title + " " + body
        if (parseAmount(n.title, body) == null) {
            return Decision.Drop("tanpa nominal Rp (atau lebih dari satu nominal)", false, n.title, body)
        }
        if (!keywords.matcher(combined).find()) {
            return Decision.Drop("bukan notifikasi pembayaran", false, n.title, body)
        }
        return Decision.Send(n.title.take(200), body.take(1000))
    }
}
