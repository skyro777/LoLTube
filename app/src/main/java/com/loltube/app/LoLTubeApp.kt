package com.loltube.app

import android.app.Application
import com.loltube.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LoLTubeApp : Application() {
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
