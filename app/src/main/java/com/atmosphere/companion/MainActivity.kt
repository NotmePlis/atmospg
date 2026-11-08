package com.atmosphere.companion

import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.atmosphere.companion.databinding.ActivityMainBinding
import com.atmosphere.companion.databinding.ItemTxBinding
import com.atmosphere.companion.databinding.ViewPermsBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException

private class Snap(val queue: Int, val dead: Int, val logs: List<LogRow>, val diag: EventDiag?)

private class Hero(
    val level: Level,
    val icon: Int,
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val loading: Boolean = false,
    val onAction: (() -> Unit)? = null,
)

/**
 * Satu Activity, tiga tab (Beranda · Riwayat · Pengaturan). Setup pertama dipandu 3 langkah di Beranda.
 * Status "Aktif" hanya muncul bila semua syarat nyata terpenuhi. Transaksi "Berhasil" hanya dari jawaban server.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val h get() = b.pageHome
    private val hist get() = b.pageHistory
    private val s get() = b.pageSettings
    private val txAdapter = TxAdapter()
    private val techAdapter = TechAdapter()
    private var histFilter: Ui.TxStatus? = null
    private var techMode = false
    private var watchId: String? = null
    private var watchSince = 0L

    private var page = R.id.nav_home
    private var wizStep = 1
    private var wizTestPassed = false
    private var lastSig: String? = null
    private var keyboardUp = false
    private var shownTotal = -1L

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        page = savedInstanceState?.getInt("page") ?: R.id.nav_home
        techMode = savedInstanceState?.getBoolean("tech") ?: false
        wizStep = savedInstanceState?.getInt("wiz") ?: if (AppConfig.get(this).isConfigured) 2 else 1
        wizTestPassed = savedInstanceState?.getBoolean("wizOk") ?: false

        setupNav()
        setupLayoutAdapters()
        setupHome()
        setupSettings()
        applyHero(Hero(Level.NEUTRAL, R.drawable.ic_schedule, "Memeriksa…", "", loading = true))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != R.id.nav_home) b.bottomNav.selectedItemId = R.id.nav_home
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
        outState.putBoolean("tech", techMode)
        outState.putInt("wiz", wizStep)
        outState.putBoolean("wizOk", wizTestPassed)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render()
                    delay(1000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val cfg = AppConfig.get(this)
        if (cfg.isConfigured) {
            RelayEngine.start(this)
            if (cfg.keepAlive) RelayService.start(this)
        }
    }

    // ------------------------------------------------------------------ navigasi & layout

    private fun pageView(id: Int): View = when (id) {
        R.id.nav_history -> hist.root
        R.id.nav_settings -> s.root
        else -> h.root
    }

    /** Pengguna mematikan animasi di sistem (Skala durasi animasi = 0): semua efek diganti perubahan instan. */
    private val reduceMotion: Boolean
        get() = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun order(id: Int) = when (id) { R.id.nav_home -> 0; R.id.nav_history -> 1; else -> 2 }

    private fun setupNav() {
        listOf(R.id.nav_home, R.id.nav_history, R.id.nav_settings).forEach {
            pageView(it).visibility = if (it == page) View.VISIBLE else View.GONE
        }
        b.bottomNav.selectedItemId = page
        b.tvAppTitle.text = titleOf(page)
        b.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId != page) {
                crossFade(pageView(page), pageView(item.itemId), order(item.itemId) > order(page))
                page = item.itemId
                b.tvAppTitle.text = titleOf(page)
                lifecycleScope.launch { render() }
            }
            true
        }
        b.btnRefresh.setOnClickListener {
            b.btnRefresh.animate().rotationBy(360f).setDuration(450).start()
            if (AppConfig.get(this).isConfigured) RelayEngine.start(this)
            lifecycleScope.launch { render() }
        }
    }

    private fun titleOf(id: Int) = when (id) {
        R.id.nav_history -> "Riwayat"
        R.id.nav_settings -> "Pengaturan"
        else -> "Atmosphere PG"
    }

    /** Fade + geser pendek (±12dp, ~220 ms); arah mengikuti urutan menu. */
    private fun crossFade(from: View, to: View, forward: Boolean = true) {
        from.animate().cancel(); to.animate().cancel()
        if (reduceMotion) {
            from.visibility = View.GONE; from.alpha = 1f; from.translationX = 0f
            to.alpha = 1f; to.translationX = 0f; to.visibility = View.VISIBLE
            return
        }
        val d = dp(12) * (if (forward) 1 else -1).toFloat()
        val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        from.animate().alpha(0f).translationX(-d).setDuration(140).setInterpolator(ease).withEndAction {
            from.visibility = View.GONE; from.alpha = 1f; from.translationX = 0f
        }.start()
        to.alpha = 0f
        to.translationX = d
        to.visibility = View.VISIBLE
        to.animate().alpha(1f).translationX(0f).setStartDelay(40).setDuration(220).setInterpolator(ease).start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Layar lebar: batasi lebar konten; keyboard: sembunyikan navigasi bawah. */
    private fun setupLayoutAdapters() {
        b.root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            b.root.getWindowVisibleDisplayFrame(r)
            val full = b.root.rootView.height
            val up = full > 0 && full - r.bottom > full * 0.15
            if (up != keyboardUp) {
                keyboardUp = up
                b.navBar.visibility = if (up) View.GONE else View.VISIBLE
            }
            val extra = maxOf(0, (b.root.width - dp(640)) / 2)
            listOf(h.root, s.root, hist.historyHeader).forEach { v ->
                if (v.paddingLeft != extra) v.setPadding(extra, v.paddingTop, extra, v.paddingBottom)
            }
            val rvSide = dp(16) + extra
            if (hist.rvLogs.paddingLeft != rvSide) hist.rvLogs.setPadding(rvSide, dp(8), rvSide, dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.navBar) { v, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            wi
        }
        hist.rvLogs.layoutManager = LinearLayoutManager(this)
        hist.rvLogs.setPadding(dp(16), dp(8), dp(16), dp(16))
        hist.rvLogs.adapter = if (techMode) techAdapter else txAdapter

        hist.segGroup.check(if (techMode) R.id.segTech else R.id.segTx)
        hist.segGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            techMode = id == R.id.segTech
            hist.rvLogs.adapter = if (techMode) techAdapter else txAdapter
            hist.rvLogs.scrollToPosition(0)
            lifecycleScope.launch { render() }
        }
        hist.chipFilter.setOnCheckedStateChangeListener { _, ids ->
            histFilter = when (ids.firstOrNull()) {
                R.id.chipOk -> Ui.TxStatus.SUCCESS
                R.id.chipPending -> Ui.TxStatus.PENDING
                R.id.chipReview -> Ui.TxStatus.REVIEW
                else -> null
            }
            hist.rvLogs.scrollToPosition(0)
            lifecycleScope.launch { render() }
        }
        hist.diagHeader.setOnClickListener {
            val show = hist.tvDiag.visibility != View.VISIBLE
            TransitionManager.beginDelayedTransition(hist.historyHeader, AutoTransition().setDuration(160))
            hist.tvDiag.visibility = if (show) View.VISIBLE else View.GONE
            hist.diagChevron.animate().rotation(if (show) 180f else 0f).setDuration(160).start()
        }
    }

    private fun expander(parent: ViewGroup, header: View, body: View, chevron: View) {
        header.setOnClickListener {
            TransitionManager.beginDelayedTransition(parent, AutoTransition().setDuration(180))
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (show) 180f else 0f).setDuration(180).start()
        }
    }

    // ------------------------------------------------------------------ umpan balik

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun showResult(tv: TextView, text: String, level: Level) {
        val mark = when (level) { Level.OK -> "✓ "; Level.BAD -> "✗ "; Level.WARN -> "! "; else -> "" }
        tv.visibility = View.VISIBLE
        tv.text = mark + text
        tv.setTextColor(color(Ui.textColor(level)))
        tv.backgroundTintList = ColorStateList.valueOf(color(Ui.containerColor(level)))
    }

    fun feedback(msg: String, level: Level) = snack(msg, level)

    /** Setelah "Coba Lagi": beri tahu singkat hasilnya saat pengiriman ulang selesai. */
    fun watchRetry(eventId: String) {
        watchId = eventId
        watchSince = System.currentTimeMillis()
        snack("Mengirim ulang…", Level.NEUTRAL)
        lifecycleScope.launch { render() }
    }

    private fun checkWatch(logs: List<LogRow>) {
        val id = watchId ?: return
        val r = logs.firstOrNull { it.eventId == id }
        val failed = r != null && (r.stage == Stage.REJECTED || (r.stage == Stage.SENDING && r.result.startsWith("gagal")))
        when {
            r != null && r.sentAt >= watchSince && r.stage == Stage.ACKED -> { watchId = null; snack("Diterima server (${r.result.removePrefix("server: ")})", Level.OK) }
            r != null && r.sentAt >= watchSince && failed -> { watchId = null; snack("Gagal: ${r.result.removePrefix("gagal: ")}", Level.BAD) }
            System.currentTimeMillis() - watchSince > 20_000 -> { watchId = null; snack("Masih mengantre; dicoba otomatis.", Level.WARN) }
        }
    }

    private fun snack(msg: String, level: Level = Level.OK) {
        Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG)
            .setAnchorView(b.navBar)
            .setBackgroundTint(color(Ui.containerColor(level)))
            .setTextColor(color(Ui.textColor(level)))
            .show()
    }

    private fun runConnectionTest(btn: MaterialButton, progress: View, result: TextView, onDone: (Boolean) -> Unit = {}) {
        btn.isEnabled = false
        progress.visibility = View.VISIBLE
        showResult(result, "Menguji koneksi…", Level.NEUTRAL)
        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { RelayEngine.testConnection(this@MainActivity) }
            progress.visibility = View.GONE
            btn.isEnabled = true
            showResult(result, msg, if (ok) Level.OK else Level.BAD)
            onDone(ok)
        }
    }

    /** Tes koneksi dari Beranda: hasilnya cukup berupa snackbar. */
    private fun runHomeTest() {
        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { RelayEngine.testConnection(this@MainActivity) }
            snack(msg, if (ok) Level.OK else Level.BAD)
        }
    }

    // ------------------------------------------------------------------ Beranda + wizard

    private fun setupHome() {
        wirePerms(h.wizPerms)

        h.etWToken.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { wizardSaveServer(); true } else false
        }
        h.btnWNext1.setOnClickListener { wizardSaveServer() }
        h.btnWNext2.setOnClickListener { if (Perms.listenerEnabled(this)) goStep(3) }
        h.btnWBack.setOnClickListener { goStep(wizStep - 1) }
        h.btnWTest.setOnClickListener {
            if (wizTestPassed) {
                AppConfig.get(this).setupDone = true
                snack("Setup selesai. Mode Uji aktif; aktifkan live di Pengaturan.")
                lastSig = null
            } else {
                runConnectionTest(h.btnWTest, h.wizTestProgress, h.tvWResult) { ok ->
                    if (ok) { wizTestPassed = true; h.btnWTest.text = "Selesai" }
                }
            }
        }
        h.recentAll.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_history }

        val cfg = AppConfig.get(this)
        h.etWUrl.setText(cfg.serverUrl)
        h.etWToken.setText(cfg.token)
        applyStep(wizStep)
    }

    private fun wizardSaveServer() {
        h.tilWUrl.error = null; h.tilWToken.error = null
        if (!saveServer(h.tilWUrl, h.etWUrl, h.tilWToken, h.etWToken)) return
        s.etUrl.setText(h.etWUrl.text); s.etToken.setText(h.etWToken.text)
        goStep(2)
    }

    private fun goStep(n: Int) {
        val step = n.coerceIn(1, 3)
        if (step == wizStep) return
        val from = stepView(wizStep)
        wizStep = step
        crossFade(from, stepView(step))
        updateWizardTexts()
    }

    private fun stepView(n: Int): View = when (n) { 1 -> h.stepServer; 2 -> h.stepPerm; else -> h.stepTest }

    private fun applyStep(n: Int) {
        wizStep = n
        listOf(1, 2, 3).forEach { stepView(it).visibility = if (it == n) View.VISIBLE else View.GONE }
        updateWizardTexts()
    }

    private fun updateWizardTexts() {
        h.tvWizStep.text = "Langkah $wizStep / 3"
        h.wizBar.setProgressCompat(wizStep, true)
        val (t, d) = when (wizStep) {
            1 -> "Hubungkan server" to "Scan QR dari dashboard, atau isi manual."
            2 -> "Aktifkan izin" to "Agar notifikasi DANA terbaca terus."
            else -> "Tes koneksi" to "Satu sinyal uji. Tidak menyentuh pembayaran."
        }
        h.tvWizTitle.text = t
        h.tvWizDesc.text = d
        h.btnWBack.visibility = if (wizStep > 1) View.VISIBLE else View.GONE
    }

    private fun retry() {
        if (AppConfig.get(this).liveEnabled) {
            RelayEngine.retryNow(this)
            snack("Mengirim ulang…", Level.NEUTRAL)
        } else {
            snack("Mode uji: pengiriman ditahan.", Level.WARN)
        }
    }

    // ------------------------------------------------------------------ Pengaturan

    private fun setupSettings() {
        val cfg = AppConfig.get(this)
        s.etUrl.setText(cfg.serverUrl)
        s.etToken.setText(cfg.token)
        s.etDevice.setText(cfg.deviceId)
        s.etPackage.setText(cfg.danaPackage)
        s.etKeywords.setText(cfg.keywords)
        s.swKeepAlive.isChecked = cfg.keepAlive
        s.swHttp.isChecked = cfg.allowHttp
        s.tvVersion.text = "v${BuildConfigVersion.name(this)}"

        wirePerms(s.setPerms)
        expander(s.settingsColumn, s.advHeader, s.advBody, s.advChevron)

        s.etToken.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { saveSettings(); true } else false
        }
        s.btnSave.setOnClickListener { saveSettings() }
        s.btnScan.setOnClickListener { scanPairingQr() }
        s.btnPaste.setOnClickListener { pastePairingLink() }
        h.btnWScan.setOnClickListener { scanPairingQr() }
        h.btnWPaste.setOnClickListener { pastePairingLink() }
        s.swLive.setOnClickListener {
            s.swLive.isChecked = AppConfig.get(this).liveEnabled // status asli tetap; berubah setelah konfirmasi/aksi
            if (AppConfig.get(this).liveEnabled) {
                RelayEngine.setLive(this, false)
                snack("Mode uji. Antrean ditahan, tidak dibuang.", Level.WARN)
            } else confirmGoLive()
        }
        s.btnTestEvent.setOnClickListener { confirmTestEvent() }
        s.btnClear.setOnClickListener { confirmClear() }
    }

    /** Validasi + simpan URL & token. @return true bila berhasil. */
    private fun saveServer(
        tilU: com.google.android.material.textfield.TextInputLayout, etU: TextView,
        tilT: com.google.android.material.textfield.TextInputLayout, etT: TextView,
    ): Boolean {
        val cfg = AppConfig.get(this)
        val url = try {
            AppConfig.normalizeServerUrl(etU.text.toString(), s.swHttp.isChecked || cfg.allowHttp)
        } catch (e: IllegalArgumentException) {
            tilU.error = e.message; openAdvanced(); return false
        }
        val token = etT.text.toString().trim()
        if (!token.startsWith("dvc_") || token.length < 12) {
            tilT.error = "Isi token perangkat dari dashboard (diawali dvc_), bukan API key bot/website"
            openAdvanced(); return false
        }
        cfg.serverUrl = url
        cfg.token = token
        etU.text = url
        RelayEngine.onConfigChanged(this)
        if (cfg.keepAlive) RelayService.start(this)
        return true
    }

    private fun saveSettings() {
        listOf(s.tilUrl, s.tilToken, s.tilDevice, s.tilPackage, s.tilKeywords).forEach { it.error = null }
        val cfg = AppConfig.get(this)

        val device = AppConfig.sanitizeDeviceId(s.etDevice.text?.toString().orEmpty())
        if (device.length < 3) { s.tilDevice.error = "Minimal 3 karakter (huruf, angka, . _ : -)"; openAdvanced(); return }
        val pkg = s.etPackage.text?.toString().orEmpty().trim()
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) { s.tilPackage.error = "Nama package tidak valid (default id.dana)"; openAdvanced(); return }
        val keywords = s.etKeywords.text?.toString().orEmpty().trim().ifBlank { NotificationFilter.DEFAULT_KEYWORDS }
        try {
            NotificationFilter.compileKeywords(keywords)
        } catch (e: PatternSyntaxException) {
            s.tilKeywords.error = "Regex tidak valid: ${e.description}"; openAdvanced(); return
        }

        cfg.allowHttp = s.swHttp.isChecked
        if (!saveServer(s.tilUrl, s.etUrl, s.tilToken, s.etToken)) return
        cfg.deviceId = device
        cfg.danaPackage = pkg
        cfg.keywords = keywords
        cfg.keepAlive = s.swKeepAlive.isChecked
        s.etDevice.setText(device)
        RelayEngine.onConfigChanged(this)
        if (cfg.keepAlive) RelayService.start(this) else RelayService.stop(this)

        runConnectionTest(s.btnSave, s.saveProgress, s.tvResult)
    }

    // ------------------------------------------------------------------ pairing QR

    private fun scanPairingQr() = PairSheet.show(this, lifecycleScope, AppConfig.get(this).isConfigured, ::launchScanner, ::pastePairingLink, ::onPaired)

    private fun launchScanner() {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { handlePairing(it.rawValue) }
            .addOnFailureListener { e ->
                if (e.message?.contains("cancel", ignoreCase = true) == true) return@addOnFailureListener // pengguna menutup
                snack("Pemindai tidak tersedia. Pakai Tempel tautan.", Level.BAD)
            }
    }

    private fun pastePairingLink() {
        val input = android.widget.EditText(this).apply {
            hint = "atmpair://v1?u=…&c=…"
            setSingleLine()
            val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (Pairing.parse(t) != null) setText(t)
        }
        val pad = dp(20)
        AlertDialog.Builder(this)
            .setTitle("Tempel tautan pairing")
            .setMessage("Salin tautan dari dashboard (Hubungkan Aplikasi → Input manual). Berlaku 2 menit, sekali pakai.")
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, dp(8), pad, 0); addView(input) })
            .setPositiveButton("Hubungkan") { _, _ -> handlePairing(input.text.toString()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Hasil pindai/tempel → lembar konfirmasi (domain, hitung mundur) → tukar kode → simpan konfigurasi hanya jika berhasil. */
    private fun handlePairing(raw: String?) {
        val link = Pairing.parse(raw)
        if (link == null) { snack("Bukan QR/tautan pairing Atmosphere PG yang sah (harus HTTPS; QRIS pembayaran tidak bisa dipakai di sini).", Level.BAD); return }
        val cfg = AppConfig.get(this)
        val url = try {
            AppConfig.normalizeServerUrl(link.serverUrl, cfg.allowHttp)
        } catch (e: IllegalArgumentException) {
            snack(e.message ?: "URL server tidak valid", Level.BAD); return
        }
        PairSheet.confirm(this, lifecycleScope, link, url, cfg.isConfigured, cfg.deviceId, ::launchScanner, ::onPaired)
    }

    private fun onPaired(r: Pairing.Result.Ok) {
        val cfg = AppConfig.get(this)
        cfg.serverUrl = r.serverUrl
        cfg.token = r.token
        s.etUrl.setText(r.serverUrl); s.etToken.setText(r.token)
        h.etWUrl.setText(r.serverUrl); h.etWToken.setText(r.token)
        RelayEngine.onConfigChanged(this)
        if (cfg.keepAlive) RelayService.start(this)
        val wizard = !cfg.setupDone && wizStep == 1
        if (wizard) goStep(2)
        runConnectionTest(s.btnSave, s.saveProgress, s.tvResult) // heartbeat pertama → status web jadi Terhubung
        if (wizard) snack("Terhubung. Izinkan akses notifikasi.", Level.OK)
        // Langkah berikutnya sudah tampil di lembar sukses (PairSheet).
    }

    /** Pairing hanya menyambungkan ke server; arahkan ke izin notifikasi dan ingatkan belum ada bukti pembayaran. */
    private fun pairingNextSteps() {
        val listenerOn = Perms.listenerEnabled(this)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Pairing berhasil")
            .setMessage(
                (if (listenerOn) "Akses notifikasi sudah aktif.\n\n" else "Langkah berikutnya: izinkan Akses Notifikasi agar notifikasi DANA terbaca.\n\n") +
                    "Pairing BUKAN bukti pembayaran sudah berfungsi. Buktinya hanya Tes pembayaran DANA di dashboard.",
            )
            .setNegativeButton("Nanti", null)
        if (!listenerOn) dlg.setPositiveButton("Buka Izin Notifikasi") { _, _ -> Perms.openListenerSettings(this) }
        else dlg.setPositiveButton("Mengerti", null)
        dlg.show()
    }

    private fun openAdvanced() {
        if (s.advBody.visibility != View.VISIBLE) s.advHeader.performClick()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Hapus token & berhenti?")
            .setMessage("Pengiriman dihentikan. Antrean yang belum terkirim tetap tersimpan. Anda perlu mengisi token baru untuk melanjutkan.")
            .setPositiveButton("Hapus") { _, _ ->
                AppConfig.get(this).clearToken()
                s.etToken.setText(""); h.etWToken.setText("")
                RelayService.stop(this)
                RelayEngine.stop()
                wizStep = 1; wizTestPassed = false; applyStep(1)
                showResult(s.tvResult, "Token dihapus. Pengiriman dihentikan; antrean tetap tersimpan.", Level.WARN)
                snack("Token dihapus.", Level.WARN)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmTestEvent() {
        AlertDialog.Builder(this)
            .setTitle("Kirim Event Uji?")
            .setMessage(
                "Dikirim SEKALI ke server sebagai event uji: event_id berawalan \"uji-\", tanpa nominal, " +
                    "tidak masuk antrean pembayaran, tidak dicoba ulang. Tidak dimaksudkan mengubah invoice, saldo, " +
                    "atau laporan. Setelah itu pastikan dashboard tidak menampilkan perubahan; " +
                    "jika ada pesan PERINGATAN, hentikan dan periksa backend.",
            )
            .setPositiveButton("Kirim") { _, _ ->
                s.btnTestEvent.isEnabled = false
                s.evProgress.visibility = View.VISIBLE
                showResult(s.tvEvResult, "Mengirim event uji…", Level.NEUTRAL)
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { RelayEngine.sendTestEvent(this@MainActivity) }
                    s.evProgress.visibility = View.GONE
                    s.btnTestEvent.isEnabled = true
                    showResult(s.tvEvResult, r.message, r.level)
                    snack(r.message.take(80), r.level)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Gerbang migrasi: live hanya aktif setelah pengguna menyatakan macro lama sudah mati. */
    private fun confirmGoLive() {
        val items = arrayOf(
            "Macro pembayaran MacroDroid sudah dimatikan/dihapus",
            "Macro heartbeat MacroDroid sudah dimatikan/dihapus",
            "Tes Koneksi berhasil dan Log diagnostik menunjukkan notifikasi DANA lolos filter",
        )
        val checked = BooleanArray(items.size)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Aktifkan pengiriman live?")
            .setMultiChoiceItems(items, checked) { d, i, v ->
                checked[i] = v
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.all { it }
            }
            .setPositiveButton("Aktifkan") { _, _ ->
                RelayEngine.setLive(this, true)
                snack("Mode live aktif.", Level.OK)
            }
            .setNegativeButton("Batal", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }

    // ------------------------------------------------------------------ izin

    private fun wirePerms(p: ViewPermsBinding) {
        p.btnPermListener.setOnClickListener { Perms.openListenerSettings(this) }
        p.btnPermAppInfo.setOnClickListener { Perms.openAppDetails(this) }
        p.btnPermBattery.setOnClickListener { Perms.requestIgnoreBattery(this) }
        p.btnPermDana.setOnClickListener { Perms.openNotificationSettingsOf(this, AppConfig.get(this).danaPackage) }
        p.btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setStatus(tv: TextView, text: String, level: Level) {
        val mark = when (level) { Level.OK -> "✓ "; Level.BAD -> "✗ "; Level.WARN -> "! "; else -> "" }
        tv.text = mark + text
        tv.setTextColor(color(Ui.textColor(level)))
    }

    private fun bindPerms(p: ViewPermsBinding) {
        val l = Perms.listenerEnabled(this)
        setStatus(p.tvPermListener, if (l) "Aktif" else "Belum diizinkan", if (l) Level.OK else Level.BAD)
        p.btnPermListener.visibility = if (l) View.GONE else View.VISIBLE
        p.btnPermAppInfo.visibility = if (l) View.GONE else View.VISIBLE

        if (Build.VERSION.SDK_INT < 33) {
            setStatus(p.tvPermNotif, "Tidak diperlukan di Android ini", Level.OK)
            p.btnPermNotif.visibility = View.GONE
        } else {
            val g = Perms.postNotificationsGranted(this)
            setStatus(p.tvPermNotif, if (g) "Diizinkan" else "Belum diizinkan", if (g) Level.OK else Level.WARN)
            p.btnPermNotif.visibility = if (g) View.GONE else View.VISIBLE
        }
        val bat = Perms.batteryUnrestricted(this)
        setStatus(p.tvPermBattery, if (bat) "Tidak dibatasi" else "Dioptimalkan (rawan dimatikan)", if (bat) Level.OK else Level.WARN)
        p.btnPermBattery.visibility = if (bat) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ tampilan

    private fun applyHero(hero: Hero) {
        h.heroPill.backgroundTintList = ColorStateList.valueOf(color(Ui.containerColor(hero.level)))
        h.heroPill.contentDescription = "Status: ${hero.title}"
        h.heroIcon.setImageResource(hero.icon)
        ImageViewCompat.setImageTintList(h.heroIcon, ColorStateList.valueOf(color(Ui.textColor(hero.level))))
        h.heroIcon.visibility = if (hero.loading) View.INVISIBLE else View.VISIBLE
        h.heroTitle.text = hero.title
        h.heroTitle.setTextColor(color(Ui.textColor(hero.level)))
        h.heroMsg.text = hero.message
        h.heroMsg.visibility = if (hero.message.isBlank()) View.GONE else View.VISIBLE
        h.heroProgress.visibility = if (hero.loading) View.VISIBLE else View.GONE
        val label = hero.actionLabel
        if (label == null) {
            h.heroAction.visibility = View.GONE
        } else {
            h.heroAction.visibility = View.VISIBLE
            h.heroAction.text = label
            h.heroAction.setOnClickListener { hero.onAction?.invoke() }
        }
    }

    private var recentSig: String? = null

    /** Maksimal 3 transaksi terbaru di Beranda; digambar ulang hanya bila isinya berubah. */
    private fun renderRecent(txs: List<Ui.Tx>) {
        val rows = txs.take(3)
        val sig = rows.joinToString("|") { "${it.row.ts}/${it.status}/${it.amount}" }
        if (sig == recentSig) return
        val first = recentSig == null
        recentSig = sig
        if (!first && !reduceMotion) TransitionManager.beginDelayedTransition(h.recentList, AutoTransition().setDuration(160))
        h.recentList.removeAllViews()
        h.recentList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        h.recentEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        rows.forEach { tx ->
            val ib = ItemTxBinding.inflate(layoutInflater, h.recentList, false)
            TxRow.bind(ib, tx)
            h.recentList.addView(ib.root)
        }
    }

    /** Hitung naik halus untuk total berhasil. */
    private fun setTotal(value: Long) {
        if (value == shownTotal) return
        val from = if (shownTotal < 0 || reduceMotion) value else shownTotal
        shownTotal = value
        if (from == value) { h.tvTotal.text = Ui.rupiah(value); return }
        android.animation.ValueAnimator.ofFloat(from.toFloat(), value.toFloat()).apply {
            duration = 450
            addUpdateListener { h.tvTotal.text = Ui.rupiah((it.animatedValue as Float).toLong()) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { h.tvTotal.text = Ui.rupiah(value) }
            })
            start()
        }
    }

    private fun renderDiag(d: EventDiag?, dead: Int): CharSequence {
        if (d == null) return Ui.line(this, "Event terakhir", "belum ada", Level.NEUTRAL)
        val acked = d.stage == Stage.ACKED
        val rejected = d.stage == Stage.REJECTED
        val sentTried = acked || rejected || d.attempts > 0
        val l1 = Ui.line(this, "1. Diterima di HP", "✓ ${Ui.ago(d.ts)}", Level.OK)
        val l2 = Ui.line(this, "2. Masuk antrean", "✓ tersimpan permanen", Level.OK)
        val l3 = when {
            sentTried -> Ui.line(this, "3. Dikirim", "✓ ${maxOf(d.attempts, 1)}× percobaan", Level.OK)
            d.stage == Stage.SENDING -> Ui.line(this, "3. Dikirim", "… mengirim", Level.WARN)
            else -> Ui.line(this, "3. Dikirim", "… menunggu", Level.NEUTRAL)
        }
        val l4 = when {
            acked -> Ui.line(this, "4. Diproses server", "✓ ${d.result.removePrefix("server: ")}", Level.OK)
            rejected -> Ui.line(this, "4. Diproses server", "✗ ${d.result}", Level.BAD)
            sentTried -> Ui.line(this, "4. Diproses server", "✗ belum ada jawaban — ${d.lastError ?: "-"}", Level.WARN)
            else -> Ui.line(this, "4. Diproses server", "…", Level.NEUTRAL)
        }
        val id = Ui.line(this, "Event ID", d.eventId, Level.NEUTRAL)
        val extra = if (dead > 0) Ui.line(this, "Ditolak server", "$dead event", Level.BAD) else ""
        return android.text.TextUtils.concat(l1, "\n", l2, "\n", l3, "\n", l4, "\n", id, if (dead > 0) "\n" else "", extra)
    }

    private fun setIfChanged(tv: TextView, text: CharSequence) {
        if (tv.text.toString() != text.toString()) tv.text = text
    }

    private suspend fun render() {
        val cfg = AppConfig.get(this)
        val st = RelayEngine.state.value
        val store = Store.get(this)
        val snap = withContext(Dispatchers.IO) {
            Snap(store.count(), store.deadCount(), store.recentLogs(200), store.lastEventDiag())
        }
        val queue = snap.queue
        val dead = snap.dead
        val logs = snap.logs
        val configured = cfg.isConfigured
        val live = cfg.liveEnabled
        val listenerOn = Perms.listenerEnabled(this)
        val d = snap.diag

        bindPerms(h.wizPerms)
        bindPerms(s.setPerms)

        val txs = logs.mapNotNull { Ui.toTx(it) } // duplikat/diabaikan/diagnostik tidak masuk daftar utama

        // ---- Riwayat ----
        if (page == R.id.nav_history) {
            hist.filterScroll.visibility = if (techMode) View.GONE else View.VISIBLE
            hist.diagHeader.visibility = if (techMode) View.VISIBLE else View.GONE
            if (!techMode) hist.tvDiag.visibility = View.GONE
            val empty: Boolean
            if (techMode) {
                techAdapter.submit(logs)
                empty = logs.isEmpty()
                val lastNotif = logs.firstOrNull { it.result != "peringatan" }
                val perm = if (Perms.postNotificationsGranted(this)) Ui.line(this, "Izin notifikasi app", "Diberikan", Level.OK)
                else Ui.line(this, "Izin notifikasi app", "Perlu Izin (status Mode andal tersembunyi)", Level.WARN)
                val bat = if (Perms.batteryUnrestricted(this)) Ui.line(this, "Baterai", "Tidak dibatasi", Level.OK)
                else Ui.line(this, "Baterai", "Dioptimalkan — rawan dimatikan", Level.WARN)
                val err = st.lastError
                val errLine: CharSequence = if (err.isNullOrBlank() || (st.serverOk == true && queue == 0)) "" else Ui.line(this, "Masalah", err, Level.BAD)
                setIfChanged(
                    hist.tvDiag,
                    android.text.TextUtils.concat(
                        renderDiag(d, dead), "\n",
                        Ui.line(this, "Notifikasi terakhir", lastNotif?.let { "${Ui.ago(it.ts)} — ${it.summary}" } ?: "belum ada", Level.NEUTRAL), "\n",
                        perm, "\n", bat, if (errLine.isEmpty()) "" else "\n", errLine,
                    ),
                )
            } else {
                val shown = histFilter?.let { f -> txs.filter { it.status == f } } ?: txs
                txAdapter.submit(shown)
                empty = shown.isEmpty()
            }
            hist.emptyHistory.visibility = if (empty) View.VISIBLE else View.GONE
            hist.rvLogs.visibility = if (empty) View.GONE else View.VISIBLE
        }
        checkWatch(logs)

        // ---- Pengaturan: mode ----
        setIfChanged(
            s.tvMode,
            if (live) Ui.line(this, "Status", "Live", Level.OK) else Ui.line(this, "Status", "Uji, tidak mengirim", Level.WARN),
        )
        s.swLive.isChecked = live

        // ---- Wizard vs beranda ----
        val needWizard = !configured || !cfg.setupDone
        if (needWizard) {
            if (!configured && wizStep != 1) goStep(1)
            h.btnWNext2.isEnabled = listenerOn
            h.tvWNext2Hint.visibility = if (listenerOn) View.GONE else View.VISIBLE
            if (wizStep == 3 && wizTestPassed) h.btnWTest.text = "Selesai"
        }
        if (h.wizardCard.visibility != (if (needWizard) View.VISIBLE else View.GONE)) {
            TransitionManager.beginDelayedTransition(h.homeColumn, AutoTransition().setDuration(180))
            h.wizardCard.visibility = if (needWizard) View.VISIBLE else View.GONE
            h.homeContent.visibility = if (needWizard) View.GONE else View.VISIBLE
        }
        if (needWizard) return

        // ---- Status: "Aktif" hanya bila semua kondisi nyata terpenuhi ----
        val now = System.currentTimeMillis()
        val hbFresh = st.lastHeartbeatAt > 0 && now - st.lastHeartbeatAt < HEARTBEAT_STALE_MS
        val hero = when {
            !listenerOn -> Hero(Level.BAD, R.drawable.ic_error, "Perlu Izin", "Akses notifikasi mati", "Aktifkan") { Perms.openListenerSettings(this) }
            st.tokenRejected -> Hero(Level.BAD, R.drawable.ic_error, "Gagal Dikirim", "Token ditolak server", "Pengaturan") { b.bottomNav.selectedItemId = R.id.nav_settings }
            dead > 0 -> Hero(Level.BAD, R.drawable.ic_error, "Gagal Dikirim", "$dead ditolak server", "Coba Lagi") { retry() }
            !live -> Hero(Level.INFO, R.drawable.ic_schedule, "Mode Uji", "Belum mengirim ke server", "Live") { confirmGoLive() }
            !st.listenerConnected -> Hero(Level.WARN, R.drawable.ic_schedule, "Menunggu Koneksi", "Pembaca belum tersambung", "Izin") { Perms.openListenerSettings(this) }
            st.serverOk == false -> Hero(Level.WARN, R.drawable.ic_schedule, "Menunggu Koneksi",
                if (queue > 0) "$queue tersimpan, terkirim otomatis" else "Server tidak terjangkau", "Tes") { runHomeTest() }
            st.serverOk == null -> Hero(Level.INFO, R.drawable.ic_schedule, "Menunggu Koneksi", "Menghubungi server…", loading = true)
            !hbFresh -> Hero(Level.WARN, R.drawable.ic_schedule, "Menunggu Koneksi", "Sinyal ${Ui.ago(st.lastHeartbeatAt)}", "Tes") { runHomeTest() }
            queue > 0 && (d?.attempts ?: 0) > 0 -> Hero(Level.BAD, R.drawable.ic_error, "Gagal Dikirim", "Dicoba ulang otomatis", "Coba Lagi") { retry() }
            else -> Hero(Level.OK, R.drawable.ic_check_circle, "Aktif", if (queue > 0) "$queue menunggu dikirim" else "Semua berjalan")
        }
        val sig = listOf(hero.title, hero.message, hero.actionLabel, hero.loading, hero.level).joinToString("|")
        if (lastSig != null && lastSig != sig && page == R.id.nav_home && !reduceMotion) {
            TransitionManager.beginDelayedTransition(h.homeContent, AutoTransition().setDuration(160))
        }
        lastSig = sig
        applyHero(hero)

        // ---- Ringkasan ----
        val today = Ui.startOfToday()
        val okToday = txs.filter { it.status == Ui.TxStatus.SUCCESS && it.row.ts >= today }
        setTotal(okToday.sumOf { it.amount ?: 0L })
        setIfChanged(h.tvTotalSub, "${okToday.size} transaksi")
        val pending = txs.count { it.status == Ui.TxStatus.PENDING }
        val review = txs.count { it.status == Ui.TxStatus.REVIEW }
        setIfChanged(h.tvPending, pending.toString())
        setIfChanged(h.tvPendingSub, if (review > 0) "$review perlu diperiksa" else "menunggu")
        h.tvPendingSub.setTextColor(color(if (review > 0) R.color.status_warn else R.color.on_surface_variant))
        renderRecent(txs)
    }

    private companion object {
        /** Heartbeat berjalan tiap 60 dtk; lebih dari 3 mnt tanpa heartbeat dianggap tidak lagi terhubung. */
        const val HEARTBEAT_STALE_MS = 3 * 60_000L
    }
}

/** Nama versi dari PackageManager (tanpa BuildConfig agar tidak perlu flag buildFeatures). */
private object BuildConfigVersion {
    fun name(ctx: android.content.Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
