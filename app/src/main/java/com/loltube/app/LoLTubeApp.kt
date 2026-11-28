package com.loltube.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.loltube.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LoLTubeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Keep a cheap cached copy of the auto-clean setting so the exit path
        // (MainActivity.onDestroy when finishing) can read it synchronously —
        // DataStore is suspend-only and that callback can't await.
        appScope.launch {
            SettingsStore.flow(this@LoLTubeApp).collect { s -> autoCleanOnExit = s.autoCleanOnExit }
        }
    }

    /**
     * App-wide Coil image loader — the poster-speed fix. The catalog poster CDN
     * (images.metahub.space) does not ship usable cache headers, and Coil's
     * default disk cache RESPECTS them, so every cold start re-downloaded all
     * ~120 posters of the home rows. This loader:
     *  - ignores the response cache headers and caches posters on disk anyway
     *    (second launch renders the home screen from disk — instant),
     *  - gives the memory cache a generous share (TV boxes have RAM to spare;
     *    row focus-scrolling then never re-decodes),
     *  - keeps a subtle crossfade so late poster pop-ins feel deliberate.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .respectCacheHeaders(false) // posters disk-cache regardless of CDN headers
            .crossfade(140)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this).maxSizePercent(0.30).build()
            }
            .okHttpClient(
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
            .build()

    /** App-scope work that must outlive any activity (settings collection). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        @Volatile
        lateinit var instance: LoLTubeApp
            private set

        /** Latest value of Settings.autoCleanOnExit (true until DataStore loads). */
        @Volatile
        var autoCleanOnExit: Boolean = true
    }
}
