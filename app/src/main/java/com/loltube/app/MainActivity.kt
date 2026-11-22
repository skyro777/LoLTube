package com.loltube.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.loltube.app.data.LoLTubeDir
import com.loltube.app.ui.AppNav
import com.loltube.app.ui.theme.LoLTubeTheme

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
}
