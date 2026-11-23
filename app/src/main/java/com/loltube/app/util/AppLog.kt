package com.loltube.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val lines: List<String>
        get() = synchronized(buffer) { buffer.toList() }

    fun d(tag: String, msg: String) {
        android.util.Log.d("LoLTube/$tag", msg)
        val line = "[${fmt.format(Date())}] [$tag] $msg"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > 800) buffer.removeFirst()
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e("LoLTube/$tag", msg, t)
        val line = when (t) {
            null -> "[${fmt.format(Date())}] [$tag] ERROR $msg"
            else -> {
                // Full cause chain + top stack frames of each: the ONLY way the
                // copied log.txt reveals the true root cause of playback errors
                // (ERROR_CODE_FAILED_RUNTIME_CHECK carries the real exception in
                // its cause chain; without this we only ever saw the code name).
                val chain = generateSequence<Throwable>(t) { it.cause }
                    .take(4)
                    .joinToString(" <- ") { c ->
                        "${c.javaClass.simpleName}(${c.message?.take(100)})"
                    }
                val frames = generateSequence<Throwable>(t) { it.cause }
                    .take(2)
                    .flatMap { c -> c.stackTrace.take(6).asSequence() }
                    .joinToString(" ") { f ->
                        "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}"
                    }
                    .take(700)
                "[${fmt.format(Date())}] [$tag] ERROR $msg :: $chain @ $frames"
            }
        }
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > 800) buffer.removeFirst()
        }
    }

    fun clear() = synchronized(buffer) { buffer.clear() }
}

object TvHelper {
    fun isTv(context: Context): Boolean {
        val uiMode = context.resources?.configuration?.uiMode ?: return false
        return (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun hasLeanback(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

object Format {
    /** 3725_000 -> "1:02:05" */
    fun time(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s)
        else "%d:%02d".format(Locale.US, m, s)
    }

    fun bitrate(bps: Int): String =
        if (bps >= 1_000_000) "%.1f Mbps".format(Locale.US, bps / 1_000_000f)
        else "${bps / 1000} kbps"
}
