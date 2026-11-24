package com.loltube.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.loltube.app.data.LoLTubeDir
import com.loltube.app.ui.AppNav
import com.loltube.app.ui.theme.LoLTubeTheme
import com.loltube.app.util.StorageJanitor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LoLTubeDir.init(this)
        setContent {
            LoLTubeTheme {
                AppNav()
            }
        }
    }

    override fun onDestroy() {
        // Real app close (back-out / finish) — NOT a config change/recreate:
        // isFinishing is false for rotation, true only when the activity is
        // genuinely going away. The janitor erases the caches on a background
        // thread so the exit is instant.
        if (isFinishing) {
            Thread {
                StorageJanitor.sweepOnExit(applicationContext, LoLTubeApp.autoCleanOnExit)
            }.apply { name = "loltube-janitor"; isDaemon = true }.start()
        }
        super.onDestroy()
    }
}
