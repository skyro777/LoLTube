package com.loltube.app

import android.app.Application

class LoLTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        lateinit var instance: LoLTubeApp
            private set
    }
}
