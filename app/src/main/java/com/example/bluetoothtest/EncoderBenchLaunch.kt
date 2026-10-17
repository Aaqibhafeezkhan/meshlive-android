package com.example.bluetoothtest

import android.app.Activity
import android.content.Intent

/**
 * Hand-off from [MainActivity] to the DEBUG-only [EncoderBenchActivity] (`test_encoder_bench`, see
 * [EncoderBenchSpec]). The Activity lives in the debug source set and is reached by class name, so release
 * builds carry no bench code path: [requested] is false there because BuildConfig.DEBUG is false.
 */
internal object EncoderBenchLaunch {
    const val ACTIVITY_CLASS = "com.example.bluetoothtest.EncoderBenchActivity"

    /** The launch extras as a plain map, for the pure parser. */
    fun extras(intent: Intent): Map<String, Any?> {
        val bundle = intent.extras ?: return emptyMap()
        @Suppress("DEPRECATION")
        return bundle.keySet().associateWith { key -> bundle.get(key) }
    }

    fun requested(intent: Intent): Boolean = EncoderBenchSpec.requested(BuildConfig.DEBUG, extras(intent))

    /** Starts the bench Activity with this launch's extras. The caller then finishes without opening any radio. */
    fun forward(from: Activity, intent: Intent) {
        from.startActivity(Intent().setClassName(from.packageName, ACTIVITY_CLASS).putExtras(intent))
    }
}
