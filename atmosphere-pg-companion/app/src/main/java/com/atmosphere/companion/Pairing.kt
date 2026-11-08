package com.atmosphere.companion

import android.net.Uri
import org.json.JSONObject

/** Pairing lewat QR dashboard (terpisah dari QRIS pembayaran): tautan sekali pakai → token perangkat. */
object Pairing {
    data class Link(val serverUrl: String, val code: String, val expiresAt: Long = 0L)

    /** Format: atmpair://v1?u=<url server>&c=<kode>[&e=<kedaluwarsa ms>]. @return null bila bukan tautan pairing yang sah. */
    fun parse(raw: String?): Link? {
        val text = raw?.trim().orEmpty()
        if (text.length > 600 || !text.startsWith("atmpair://", ignoreCase = true)) return null
        val uri = try { Uri.parse(text) } catch (e: Exception) { return null }
        if (uri.host != "v1") return null
        // Hanya HTTPS: kode pairing tidak boleh lewat HTTP polos.
        if (uri.getQueryParameter("u")?.trim()?.startsWith("https://", ignoreCase = true) != true) return null
        val url = uri.getQueryParameter("u")?.trim().orEmpty()
        val code = uri.getQueryParameter("c")?.trim().orEmpty()
        if (url.isEmpty() || code.length < 16 || !code.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        val exp = uri.getQueryParameter("e")?.toLongOrNull()?.takeIf { it > 0 } ?: 0L // hanya petunjuk hitung mundur; server yang menentukan
        return Link(url, code, exp)
    }

    sealed class Result {
        data class Ok(val serverUrl: String, val token: String) : Result()
        /** permanent=true: server menolak kode (kedaluwarsa/terpakai/salah) → perlu QR baru; false: gangguan jaringan, boleh coba lagi. */
        data class Fail(val message: String, val permanent: Boolean = false) : Result()
    }

    /** Tukar kode di server. Panggil dari thread IO. */
    fun claim(serverUrl: String, code: String, deviceId: String): Result {
        val body = JSONObject().put("code", code).put("device_id", deviceId).toString()
        return when (val r = ApiClient.post(serverUrl, "", "/api/pair/claim", body)) {
            is ApiResult.Ok -> {
                val token = r.body.optString("token", "")
                if (r.body.optBoolean("ok", false) && token.startsWith("dvc_")) Result.Ok(serverUrl, token)
                else Result.Fail("Respons server tidak dikenal. Cek URL.")
            }
            is ApiResult.Rejected -> Result.Fail(r.message, permanent = true)
            ApiResult.Unauthorized -> Result.Fail("Ditolak server.", permanent = true)
            is ApiResult.Retry -> Result.Fail(r.message)
        }
    }
}
