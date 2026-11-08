package com.atmosphere.companion

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class ApiResult {
    /** HTTP 200 dengan JSON valid. */
    data class Ok(val body: JSONObject) : ApiResult()

    /** HTTP 401: token salah/dicabut. Jangan retry tanpa token baru. */
    object Unauthorized : ApiResult()

    /** HTTP 400: permintaan ditolak permanen (mis. event_id tidak valid). */
    data class Rejected(val message: String) : ApiResult()

    /** Gagal sementara (jaringan, timeout, 5xx, 429, redirect, dll.): coba lagi nanti. */
    data class Retry(val message: String, val afterSec: Long?) : ApiResult()
}

/** Klien HTTP untuk endpoint /hook milik backend Atmosphere PG (token di header x-device-token). */
object ApiClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    fun post(baseUrl: String, token: String, path: String, jsonBody: String): ApiResult {
        var conn: HttpURLConnection? = null
        try {
            val bytes = jsonBody.toByteArray(Charsets.UTF_8)
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-device-token", token)
                setRequestProperty("User-Agent", "AtmospherePG-Companion/1.0")
            }
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = readLimited(stream)

            return when {
                code == 200 -> try {
                    ApiResult.Ok(JSONObject(text))
                } catch (e: JSONException) {
                    ApiResult.Retry("Respons 200 bukan JSON (cek URL server / proxy)", null)
                }
                code == 401 -> ApiResult.Unauthorized
                code == 400 -> ApiResult.Rejected(errorOf(text) ?: "HTTP 400")
                code == 429 -> ApiResult.Retry("Server membatasi laju permintaan (429)", conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
                code in 300..399 -> ApiResult.Retry("Server mengalihkan (HTTP $code); pakai URL final persis (https://…)", null)
                else -> ApiResult.Retry("Server membalas HTTP $code", null)
            }
        } catch (e: IOException) {
            return ApiResult.Retry("Gagal terhubung: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}", null)
        } catch (e: Exception) {
            return ApiResult.Retry("Kesalahan permintaan: ${e.message ?: e.javaClass.simpleName}", null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readLimited(stream: InputStream?): String {
        if (stream == null) return ""
        stream.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (out.size() < MAX_RESPONSE_BYTES) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun errorOf(text: String): String? = try {
        JSONObject(text).optString("error").takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }
}
