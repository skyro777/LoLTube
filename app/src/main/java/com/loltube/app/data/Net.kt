package com.loltube.app.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared networking primitives for the whole app. */
object Net {
    const val UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    class HttpException(val code: Int, message: String) : IOException(message)

    /** Blocking GET. Runs text only - call from Dispatchers.IO. */
    fun get(url: String, headers: Map<String, String> = emptyMap(), range: String? = null): String {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                range?.let { header("Range", it) }
            }
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for ${url.take(140)}")
            return body
        }
    }

    /** Blocking ranged GET returning raw bytes (max limit). */
    fun getBytes(url: String, headers: Map<String, String> = emptyMap(), limit: Int = 8192): ByteArray =
        fetchBytes(url, headers, limit, useRange = true)

    /** Blocking GET without Range header (some servers reject Range on manifests). */
    fun getBytesPlain(url: String, headers: Map<String, String> = emptyMap(), limit: Int = 8192): ByteArray =
        fetchBytes(url, headers, limit, useRange = false)

    private fun fetchBytes(url: String, headers: Map<String, String>, limit: Int, useRange: Boolean): ByteArray {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .apply {
                if (useRange) header("Range", "bytes=0-${limit - 1}")
                headers.forEach { (k, v) -> header(k, v) }
            }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for ${url.take(140)}")
            val bytes = resp.body?.bytes() ?: throw IOException("Empty body")
            return if (bytes.size > limit) bytes.copyOf(limit) else bytes
        }
    }
}
