package com.example.bluetoothtest

import android.app.Application

/** The process entry point: installs [CrashReports] before any screen, service or receiver runs. */
class MeshLiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { CrashReports.install(this) }
    }
}
