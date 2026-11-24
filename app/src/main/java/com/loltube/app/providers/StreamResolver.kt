package com.loltube.app.providers

import android.content.Context
import com.loltube.app.player.ManifestCapInterceptor
import com.loltube.app.util.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/**
 * Orchestrates stream resolution across providers:
 * embed page in offscreen WebView -> harvest manifests -> verify natively -> hand to ExoPlayer.
 */
class StreamResolver(private val context: Context) {

    data class Attempt(val provider: ProviderId, val error: String?)

    /**
     * Tries each provider in [order] until one yields a verified stream.
     * Returns the stream plus per-provider error info for the diagnostics UI.
     */
    suspend fun resolve(
        request: PlaybackRequest,
        order: List<ProviderId>,
        onStatus: suspend (ProviderId, String) -> Unit = { _, _ -> },
    ): Pair<ResolvedStream?, List<Attempt>> {
        val attempts = mutableListOf<Attempt>()
        for (id in order) {
            val embed = Providers.embedUrl(id, request)
            if (embed == null) {
                attempts.add(Attempt(id, "No TMDB id available (required by Mapple)"))
                continue
            }
            onStatus(id, "Loading player page…")
            AppLog.d("Resolver", "=== ${id.label}: $embed")

            val harvester = WebViewHarvester(context)
            val harvest = try {
                harvester.harvest(embed)
            } catch (t: Throwable) {
                AppLog.e("Resolver", "${id.label} harvest crashed: ${t.message}")
                attempts.add(Attempt(id, "Harvester error: ${t.message}"))
                continue
            }
            val candidates = harvest.candidates

            if (candidates.isEmpty()) {
                attempts.add(Attempt(id, "No video requests captured (player blocked or slow)"))
                continue
            }

            onStatus(id, "Verifying ${candidates.size} stream candidate(s)…")
            val headers = Providers.playbackHeaders(id)
            val verified = StreamVerifier.pickBest(candidates, headers, id.label)
            if (verified == null) {
                attempts.add(Attempt(id, "Found ${candidates.size} candidate(s) but none playable (expired/blocked)"))
                continue
            }

            return ResolvedStream(
                url = verified.url,
                kind = verified.kind,
                provider = id,
                headers = headers,
                allCandidates = candidates,
                subs = harvest.subs,
            ) to attempts
        }
        return null to attempts
    }
}

/**
 * OkHttp interceptor that transparently handles Mapple's /client-stream fragment proxy:
 * such a request returns JSON {url, headers} and the real bytes must then be fetched
 * from the returned URL with the returned headers.
 */
class MappleStreamInterceptor : Interceptor {

    @Serializable
    private data class ProxyPayload(
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val path = request.url.encodedPath
        val isProxy = host == "mapple.fun" && path.startsWith("/client-stream")
        if (!isProxy) return chain.proceed(request)

        val response = chain.proceed(request)
        val contentType = response.header("Content-Type") ?: ""
        val body = response.body ?: return response

        // only JSON bodies are proxy redirections; anything else passes through
        if (!contentType.contains("json", ignoreCase = true)) return response

        val text = body.string()
        val parsed = runCatching { json.decodeFromString(ProxyPayload.serializer(), text) }.getOrNull()
        if (parsed == null || !parsed.url.startsWith("http")) {
            return response.newBuilder().body(text.toResponseBody(body.contentType())).build()
        }

        AppLog.d("MappleProxy", "resolving ${request.url.encodedPath.take(80)} -> ${parsed.url.take(120)}")
        val realRequest = request.newBuilder()
            .url(parsed.url)
            .apply { parsed.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return chain.proceed(realRequest)
    }

    companion object {
        /**
         * Client for playback: keep-alive + mapple fragment proxy support +
         * master-playlist resolution capping.
         *
         * @param parallel raises per-host concurrency to 16 with a 16-connection
         * pool so the player's loader can always fetch big 4K segments without
         * queuing behind other requests on the same CDN host (OkHttp's default
         * 5-per-host limit serializes them and starves throughput).
         *
         * Long read timeout: 4K segments are big and some CDN nodes are slow —
         * a short timeout kills in-flight segment loads and surfaces as a
         * rebuffer.
         */
        fun playbackClient(headers: Map<String, String>, parallel: Boolean = false): OkHttpClient {
            val builder = NetBase.newBuilder()
                .addInterceptor(MappleStreamInterceptor())
                .addInterceptor(ManifestCapInterceptor())
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(okhttp3.ConnectionPool(if (parallel) 16 else 5, 5, TimeUnit.MINUTES))
            if (parallel) {
                val dispatcher = okhttp3.Dispatcher()
                dispatcher.maxRequests = 32
                dispatcher.maxRequestsPerHost = 16
                builder.dispatcher(dispatcher)
            }
            return builder.build()
        }

        private val NetBase: OkHttpClient get() = com.loltube.app.data.Net.client
    }
}
