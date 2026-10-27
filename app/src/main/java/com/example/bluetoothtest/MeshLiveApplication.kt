package com.example.bluetoothtest

import android.app.Application
import android.content.Context

/** The process entry point: installs [CrashReports] before any screen, service or receiver runs. */
class MeshLiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { CrashReports.install(this) }
        // Before the installers below and any receiver or service: the person's feature snapshot (B15).
        runCatching { snapshotFeatures(this) }
        // One "is this person blocked" rule for calls, reveals, streams, message exchanges and leases (PersonPolicy).
        runCatching { PersonPolicy.install(this) }
        // B2: hide the call-offer/viewer-request sets again as soon as the Bluetooth name leaves "Phone".
        runCatching { DiscoverableWhenNeutral.install(this) }
    }

    companion object {
        /**
         * Switch audit B15: the feature snapshot existed only after MainActivity ran, so a receiver, provider or the
         * boot-resume start in a fresh process read release-ON features (BMH3, names) as off. A person's launch is
         * resolved here (stored choices, else the defaults); MainActivity resolves again with its own launch, which in a
         * DEBUG benchmark run reads the extras instead.
         */
        internal fun snapshotFeatures(context: Context) {
            AppFeatures.registerAll()
            val prefs = context.getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
            FeatureCatalog.refresh(
                benchmarkLaunch = false,
                extra = { null },
                stored = { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null },
                advancedSettings = prefs.getBoolean(PREF_ADVANCED_SETTINGS, false),
            )
        }
    }
}
