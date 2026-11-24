package com.loltube.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.loltube.app.player.PlaybackCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The storage janitor — the fix for the 500+ MB cache buildup.
 *
 * LoLTube NEEDS its caches while playing (that is the no-buffer engine: the
 * disk segment cache + RAM buffers). What was missing was cleanup when the
 * app CLOSES: the media cache, downloaded subtitle files, Coil images and the
 * WebView profile (cookies/localStorage/HTTP cache) accumulated forever.
 *
 * [sweep] erases all of it:
 *  - the SimpleCache is released and its directory deleted;
 *  - everything under cacheDir is deleted (media_cache, loltube/subs, image
 *    caches, WebView HTTP cache leftovers);
 *  - WebView cookies + localStorage are wiped (via the main thread — WebView
 *    APIs need a Looper; a fresh WebView profile every session is also what
 *    fixes vidcore/vidfast suddenly refusing to load until "clear data").
 *
 * Settings (DataStore) and watch progress are NOT touched — they live in
 * filesDir and are tiny by design.
 *
 * Never called while a player exists: the player releases everything before
 * the app exit paths run, and the in-settings button is only reachable from
 * the Home screen.
 */
object StorageJanitor {

    private val running = AtomicBoolean(false)

    /** Total cache bytes on disk right now (media cache + whole cacheDir). */
    fun usedBytes(context: Context): Long {
        val app = context.applicationContext
        return runCatching {
            (app.cacheDir).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /**
     * Erase every cache. Safe to call multiple times (guarded), and every
     * step is individually runCatching'd — a failed sub-step can never
     * break the others or crash the app.
     */
    fun sweep(context: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            val app = context.applicationContext
            val t0 = android.os.SystemClock.elapsedRealtime()

            // 1. WebView data — must run on the main thread (needs a Looper).
            clearWebViewData()
            AppLog.d(TAG, "webview cookies/localStorage cleared")

            // 2. The media segment cache (SimpleCache released + files deleted).
            runCatching { PlaybackCache.wipe() }

            // 3. Every remaining file under cacheDir (subs, images, webview
            //    http-cache leftovers). The directory itself is kept (the OS
            //    expects it to exist) but emptied.
            runCatching {
                app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }

            // 4. Coil's disk cache, if it exists anywhere else.
            runCatching {
                val loader = coil.Coil.imageLoader(app)
                loader.diskCache?.clear()
            }

            AppLog.d(
                TAG,
                "storage swept in ${android.os.SystemClock.elapsedRealtime() - t0}ms — app closes clean"
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "sweep guard", t)
        } finally {
            running.set(false)
        }
    }

    /** Wrapper for the exit paths: honors the user's auto-clean setting. */
    fun sweepOnExit(context: Context, enabled: Boolean) {
        if (!enabled) {
            AppLog.d(TAG, "auto-clean disabled — keeping caches for the next session")
            return
        }
        sweep(context)
    }

    /** CookieManager / WebStorage need a thread with a Looper (the main one). */
    private fun clearWebViewData() {
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        main.post {
            runCatching {
                android.webkit.CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    removeSessionCookies(null)
                    flush()
                }
            }
            runCatching { android.webkit.WebStorage.deleteAllData() }
            done.countDown()
        }
        // never block the exit path for more than 2s
        runCatching { done.await(2, TimeUnit.SECONDS) }
    }

    private const val TAG = "Janitor"
}
