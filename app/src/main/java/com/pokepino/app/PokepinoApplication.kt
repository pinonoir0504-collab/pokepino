package com.pokepino.app

import android.app.Application
import android.system.Os

class PokepinoApplication : Application() {
    override fun onCreate() {
        // Set this before the native runtime loads, including its initialization event.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        super.onCreate()
    }
}
