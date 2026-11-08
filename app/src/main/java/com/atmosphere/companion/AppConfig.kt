package com.atmosphere.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Konfigurasi aplikasi. Semua nilai (terutama token perangkat) disimpan di
 * EncryptedSharedPreferences (kunci AES-256 di Android Keystore), tidak ikut backup.
 */
class AppConfig private constructor(private val prefs: SharedPreferences) {

    var serverUrl: String
        get() = prefs.getString(K_URL, "") ?: ""
        set(v) = prefs.edit().putString(K_URL, v).apply()

    var token: String
        get() = prefs.getString(K_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_TOKEN, v).apply()

    var deviceId: String
        get() {
            val saved = prefs.getString(K_DEVICE, null)
            if (!saved.isNullOrBlank()) return saved
            val generated = "android-" + randomHex(4)
            prefs.edit().putString(K_DEVICE, generated).apply()
            return generated
        }
        set(v) = prefs.edit().putString(K_DEVICE, v).apply()

    var danaPackage: String
        get() = prefs.getString(K_PACKAGE, null)?.takeIf { it.isNotBlank() } ?: NotificationFilter.DEFAULT_PACKAGE
        set(v) = prefs.edit().putString(K_PACKAGE, v).apply()

    var keywords: String
        get() = prefs.getString(K_KEYWORDS, null)?.takeIf { it.isNotBlank() } ?: NotificationFilter.DEFAULT_KEYWORDS
        set(v) = prefs.edit().putString(K_KEYWORDS, v).apply()

    var allowHttp: Boolean
        get() = prefs.getBoolean(K_ALLOW_HTTP, false)
        set(v) = prefs.edit().putBoolean(K_ALLOW_HTTP, v).apply()

    var keepAlive: Boolean
        get() = prefs.getBoolean(K_KEEP_ALIVE, true)
        set(v) = prefs.edit().putBoolean(K_KEEP_ALIVE, v).apply()

    /** Terakhir mesin pengirim terlihat hidup; dipakai mendeteksi jeda (reboot/force-stop/dimatikan sistem). */
    var lastAliveAt: Long
        get() = prefs.getLong(K_ALIVE, 0L)
        set(v) = prefs.edit().putLong(K_ALIVE, v).apply()

    /** Terakhir notifikasi dari paket DANA benar-benar diterima listener (bukti pembacaan, apa pun isinya). */
    var lastDanaSeenAt: Long
        get() = prefs.getLong(K_DANA_SEEN, 0L)
        set(v) = prefs.edit().putLong(K_DANA_SEEN, v).apply()

    /**
     * false = mode diagnostik: notifikasi dibaca dan disaring, hasilnya hanya dicatat di Log lokal;
     * tidak ada heartbeat berkala, tidak ada kiriman /hook/payment, invoice tidak tersentuh.
     * Default false agar APK baru tidak berjalan paralel dengan MacroDroid.
     */
    var liveEnabled: Boolean
        get() = prefs.getBoolean(K_LIVE, false)
        set(v) = prefs.edit().putBoolean(K_LIVE, v).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()

    fun keywordPattern(): Pattern = try {
        NotificationFilter.compileKeywords(keywords)
    } catch (e: PatternSyntaxException) {
        NotificationFilter.compileKeywords(NotificationFilter.DEFAULT_KEYWORDS)
    }

    fun clearToken() {
        prefs.edit().remove(K_TOKEN).putBoolean(K_SETUP, false).apply()
    }

    /**
     * Setup awal (server → izin → tes koneksi) sudah diselesaikan pengguna.
     * Instalasi lama yang sudah terkonfigurasi dianggap selesai (nilai bawaan saat kunci belum ada).
     */
    var setupDone: Boolean
        get() = prefs.getBoolean(K_SETUP, isConfigured)
        set(v) = prefs.edit().putBoolean(K_SETUP, v).apply()

    companion object {
        private const val FILE = "companion_secure_prefs"
        private const val K_URL = "server_url"
        private const val K_TOKEN = "device_token"
        private const val K_DEVICE = "device_id"
        private const val K_PACKAGE = "dana_package"
        private const val K_KEYWORDS = "keywords"
        private const val K_ALLOW_HTTP = "allow_http"
        private const val K_KEEP_ALIVE = "keep_alive"
        private const val K_LIVE = "live_enabled"
        private const val K_SETUP = "setup_done"
        private const val K_ALIVE = "last_alive_at"
        private const val K_DANA_SEEN = "last_dana_seen_at"

        @Volatile
        private var instance: AppConfig? = null

        fun get(ctx: Context): AppConfig = instance ?: synchronized(this) {
            instance ?: AppConfig(openPrefs(ctx.applicationContext)).also { instance = it }
        }

        private fun openPrefs(ctx: Context): SharedPreferences = try {
            createEncrypted(ctx)
        } catch (e: Exception) {
            // Keystore rusak/tidak cocok (mis. setelah restore): hapus berkas lama, pengguna isi ulang.
            ctx.deleteSharedPreferences(FILE)
            createEncrypted(ctx)
        }

        private fun createEncrypted(ctx: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** device_id dan event_id server: hanya A-Za-z0-9._:- ; dibatasi 40 karakter. */
        fun sanitizeDeviceId(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9._:-]"), "-").take(40)

        /**
         * Normalisasi URL dasar server. Pengguna boleh menempelkan URL lengkap
         * (.../hook/payment); bagian /hook dibuang. Melempar IllegalArgumentException berisi pesan untuk UI.
         */
        fun normalizeServerUrl(raw: String, allowHttp: Boolean): String {
            var s = raw.trim()
            if (s.isEmpty()) throw IllegalArgumentException("URL server wajib diisi")
            if (!s.contains("://")) s = "https://$s"
            s = s.trimEnd('/')
            for (suffix in listOf("/hook/payment", "/hook/heartbeat", "/hook")) {
                if (s.endsWith(suffix, ignoreCase = true)) {
                    s = s.dropLast(suffix.length).trimEnd('/')
                    break
                }
            }
            val uri = try {
                URI(s)
            } catch (e: Exception) {
                throw IllegalArgumentException("Format URL tidak valid")
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "https" && scheme != "http") throw IllegalArgumentException("URL harus diawali https://")
            if (uri.host.isNullOrBlank()) throw IllegalArgumentException("Alamat server tidak valid")
            if (!uri.rawQuery.isNullOrEmpty() || uri.userInfo != null) {
                throw IllegalArgumentException("URL tidak boleh berisi query atau kredensial; token diisi di kolom token")
            }
            if (scheme == "http" && !allowHttp) {
                throw IllegalArgumentException("HTTP tanpa enkripsi ditolak. Pakai https:// atau aktifkan opsi HTTP di Lanjutan.")
            }
            return s
        }
    }
}
