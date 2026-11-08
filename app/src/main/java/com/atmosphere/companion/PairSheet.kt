package com.atmosphere.companion

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lembar pairing: petunjuk singkat → konfirmasi domain + hitung mundur → loading → sukses/error.
 * Konfigurasi hanya disimpan (lewat onPaired) setelah server menjawab sukses.
 */
object PairSheet {
    /** Langkah awal: petunjuk singkat + tombol buka kamera / tempel tautan. */
    fun show(ctx: Context, scope: LifecycleCoroutineScope, replacing: Boolean, scan: () -> Unit, paste: () -> Unit, onPaired: (Pairing.Result.Ok) -> Unit) {
        val ui = Sheet(ctx)
        ui.state(R.drawable.ic_send, Level.NEUTRAL, "Hubungkan ke server", "Dashboard → Hubungkan Aplikasi, lalu scan QR (berlaku 2 menit)." + if (replacing) "\nPengaturan lama akan diganti." else "")
        ui.buttons(
            "Scan QR" to { ui.dismiss(); scan() },
            "Tempel tautan" to { ui.dismiss(); paste() },
        )
        ui.show()
    }

    fun confirm(ctx: Context, scope: LifecycleCoroutineScope, link: Pairing.Link, url: String, replacing: Boolean, deviceId: String, rescan: () -> Unit, onPaired: (Pairing.Result.Ok) -> Unit) {
        val ui = Sheet(ctx)
        val host = Uri.parse(url).host ?: url
        var job: Job? = null
        var timer: CountDownTimer? = null

        fun attempt() {
            timer?.cancel()
            ui.loading("Menghubungkan…", host)
            job = scope.launch {
                when (val r = withContext(Dispatchers.IO) { Pairing.claim(url, link.code, deviceId) }) {
                    is Pairing.Result.Ok -> {
                        onPaired(r) // simpan konfigurasi hanya setelah sukses
                        ui.state(R.drawable.ic_check_circle, Level.OK, "Terhubung", "Tersambung ke $host.\nBerikutnya: izinkan akses notifikasi.")
                        ui.buttons("Selesai" to { ui.dismiss() })
                    }
                    is Pairing.Result.Fail -> {
                        // Fail dari penolakan server (400) = kode mati: perlu QR baru. Selain itu (jaringan) = boleh coba lagi.
                        if (r.permanent) {
                            ui.state(R.drawable.ic_error, Level.BAD, "QR tidak bisa dipakai", "${r.message}\nQR kedaluwarsa/terpakai. Buat QR baru di dashboard.")
                            ui.buttons("Scan Ulang" to { ui.dismiss(); rescan() }, "Tutup" to { ui.dismiss() })
                        } else {
                            ui.state(R.drawable.ic_error, Level.BAD, "Gagal terhubung", "${r.message}\nKode belum terpakai; coba lagi.")
                            ui.buttons("Coba Lagi" to { attempt() }, "Tutup" to { ui.dismiss() })
                        }
                    }
                }
            }
        }

        val body = "Notifikasi DANA akan dikirim ke server ini. Lanjutkan hanya jika ini server Anda." +
            if (replacing) "\nPengaturan lama akan DIGANTI." else ""
        ui.state(R.drawable.ic_cloud, Level.NEUTRAL, host, body)
        val countdown = ui.countdownView()
        ui.buttons("Hubungkan" to { attempt() }, "Batal" to { ui.dismiss() })
        if (link.expiresAt > 0) {
            val left = link.expiresAt - System.currentTimeMillis()
            timer = object : CountDownTimer(left.coerceAtLeast(1), 1000) {
                override fun onTick(ms: Long) {
                    val s = ms / 1000
                    countdown.text = "QR berlaku ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
                }
                // Jam HP bisa meleset: jangan blokir, server yang menentukan.
                override fun onFinish() { countdown.text = "QR mungkin sudah kedaluwarsa; server yang memastikan" }
            }.start()
        }
        ui.onDismiss { timer?.cancel(); job?.cancel() }
        ui.show()
    }

    /** Kerangka tampilan: ikon bulat, judul, teks, baris tombol; transisi memudar halus. */
    private class Sheet(val ctx: Context) {
        private val dialog = BottomSheetDialog(ctx, R.style.ThemeOverlay_AtmospherePG_BottomSheetDialog)
        private val d = ctx.resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()
        private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(20)) }
        private val icon = ImageView(ctx).apply { setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundResource(R.drawable.bg_icon_circle) }
        private val progress = ProgressBar(ctx).apply { isIndeterminate = true; visibility = View.GONE }
        private val iconBox = FrameLayout(ctx).apply { addView(icon, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)); addView(progress, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)) }
        private val title = TextView(ctx).apply { setTextAppearance(R.style.Text_AtmospherePG_Title); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6)) }
        private val text = TextView(ctx).apply { setTextAppearance(R.style.Text_AtmospherePG_Body); gravity = Gravity.CENTER_HORIZONTAL; setLineSpacing(0f, 1.15f) }
        private val extra = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        private val btns = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }

        init {
            root.addView(iconBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            root.addView(title, LinearLayout.LayoutParams(-1, -2)); root.addView(text, LinearLayout.LayoutParams(-1, -2))
            root.addView(extra, LinearLayout.LayoutParams(-1, -2)); root.addView(btns, LinearLayout.LayoutParams(-1, -2))
            val scroll = android.widget.ScrollView(ctx).apply { addView(root) } // layar kecil / font besar: bisa digulir
            dialog.setContentView(scroll)
        }

        fun show() = dialog.show()
        fun dismiss() = dialog.dismiss()
        fun onDismiss(f: () -> Unit) = dialog.setOnDismissListener { f() }

        private fun fade() { root.alpha = 0f; root.animate().alpha(1f).setDuration(180).start() }

        fun state(res: Int, level: Level, t: String, msg: String) {
            progress.visibility = View.GONE; dialog.setCancelable(true); extra.removeAllViews()
            icon.visibility = View.VISIBLE
            icon.setImageResource(res)
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(ContextCompat.getColor(ctx, Ui.textColor(level))))
            icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, Ui.containerColor(level)))
            title.text = t; text.text = msg; fade()
        }

        /** Loading: tidak bisa ditutup agar penukaran kode tidak terputus di tengah jalan. */
        fun loading(t: String, sub: String) {
            icon.visibility = View.INVISIBLE; progress.visibility = View.VISIBLE; dialog.setCancelable(false); extra.removeAllViews()
            title.text = t; text.text = sub; btns.removeAllViews(); fade()
        }

        fun countdownView(): TextView = TextView(ctx).apply {
            setTextAppearance(R.style.Text_AtmospherePG_Label); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            extra.addView(this)
        }

        fun buttons(vararg items: Pair<String, () -> Unit>) {
            btns.removeAllViews()
            items.forEachIndexed { i, (label, f) ->
                val b = if (i == 0) MaterialButton(ctx) else MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                b.text = label; b.setOnClickListener { f() }
                btns.addView(b, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) })
            }
        }
    }
}
