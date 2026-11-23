package com.loltube.app.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.loltube.app.util.AppLog
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Crash-proof DataSource wrapper — the seatbelt that guarantees playback can
 * never die from a runtime exception in the data pipeline.
 *
 * `ERROR_CODE_FAILED_RUNTIME_CHECK` (what the user saw) is Media3's fatal
 * "unexpected RuntimeException" bucket: anything non-IOException that escapes
 * a DataSource on a loader thread is wrapped into an UNRETRIABLE error that
 * kills playback instantly. The classic producers are races between
 * concurrent close/read/cancel (OkHttp body, CacheDataSource span writes, the
 * prefetcher writing the same span the player wants).
 *
 * This wrapper makes every such failure survivable:
 *  - `open()` RuntimeException -> one fresh re-open attempt, then a RETRIABLE
 *    network error (ExoPlayer backs off and resumes the load);
 *  - `read()`  RuntimeException -> RETRIABLE network error (the loader resumes
 *    with a ranged request, the position is kept);
 *  - interrupts from load cancellation pass through untouched (so the player
 *    can still cancel seeks/switches);
 *  - every unexpected Throwable is logged with its stack so the in-app log
 *    shows the true root cause.
 */
@UnstableApi
class SafeDataSource(
    private val baseFactory: DataSource.Factory,
) : DataSource {

    private var source: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        try {
            baseFactory.addTransferListener(transferListener)
        } catch (_: Throwable) {
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val ds = baseFactory.createDataSource()
        source = ds
        try {
            return ds.open(dataSpec)
        } catch (e: InterruptedIOException) {
            // A genuine thread interrupt = the player cancelling this load
            // (seek/quality switch/release). Pass it through untouched.
            if (Thread.currentThread().isInterrupted) throw e
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            AppLog.e(TAG, "open() crashed (${t.javaClass.simpleName}: ${t.message?.take(120)}) — re-opening once", t)
            runCatching { ds.close() }
            // Transient race: retry once with a brand new source before giving up.
            try {
                val retry = baseFactory.createDataSource()
                source = retry
                return retry.open(dataSpec)
            } catch (t2: Throwable) {
                if (t2 is IOException) throw t2
                AppLog.e(TAG, "re-open failed (${t2.javaClass.simpleName}) — surfacing retriable network error", t2)
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ds = source ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        try {
            return ds.read(buffer, offset, length)
        } catch (e: InterruptedIOException) {
            if (Thread.currentThread().isInterrupted) throw e
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            AppLog.e(TAG, "read() crashed (${t.javaClass.simpleName}: ${t.message?.take(120)}) — surfacing retriable network error", t)
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
    }

    override fun getUri(): Uri? = try {
        source?.uri
    } catch (_: Throwable) {
        null
    }

    override fun close() {
        val ds = source
        source = null
        if (ds != null) {
            try {
                ds.close()
            } catch (t: Throwable) {
                AppLog.d(TAG, "close() ignored: ${t.javaClass.simpleName}: ${t.message?.take(80)}")
            }
        }
    }

    /** Factory wrapper so [androidx.media3.exoplayer.source.DefaultMediaSourceFactory] builds us. */
    class Factory(private val base: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SafeDataSource(base)

        override fun addTransferListener(transferListener: TransferListener) {
            try {
                base.addTransferListener(transferListener)
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        private const val TAG = "SafeDS"
    }
}
