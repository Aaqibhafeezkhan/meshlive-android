package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * First-run explanation: what the app does, what to expect offline and why each permission is
 * needed. Shown once, only for a plain user launch (never for benchmark intents, see
 * [shouldShowOnboarding]), and again after an emergency wipe.
 */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var primary: MaterialButton
    private lateinit var result: TextView
    private var requested = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requested = true
        val allGranted = permissions(this).all(::granted)
        result.visibility = View.VISIBLE
        result.setText(if (allGranted) R.string.onboarding_permissions_granted else R.string.onboarding_permissions_partial)
        updatePrimary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_onboarding)
        val root = findViewById<View>(R.id.onboardingRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        bindPoint(R.id.onboardingPointCalls, R.drawable.ic_call, R.string.onboarding_point_calls_title, R.string.onboarding_point_calls_text)
        bindPoint(R.id.onboardingPointLive, R.drawable.ic_live, R.string.onboarding_point_live_title, R.string.onboarding_point_live_text)
        bindPoint(R.id.onboardingPointRelay, R.drawable.ic_relay, R.string.onboarding_point_relay_title, R.string.onboarding_point_relay_text)
        findViewById<TextView>(R.id.onboardingPermissionList).text = permissionReasons()
        result = findViewById(R.id.onboardingPermissionResult)
        primary = findViewById(R.id.onboardingPrimary)
        primary.setOnClickListener {
            val missing = permissions(this).filterNot(::granted)
            if (missing.isEmpty() || requested) finishOnboarding() else permissionRequest.launch(missing.toTypedArray())
        }
        findViewById<View>(R.id.onboardingSecondary).setOnClickListener { finishOnboarding() }
        updatePrimary()
    }

    private fun bindPoint(id: Int, icon: Int, title: Int, text: Int) {
        val point = findViewById<View>(id)
        point.findViewById<ImageView>(R.id.pointIcon).setImageResource(icon)
        point.findViewById<TextView>(R.id.pointTitle).setText(title)
        point.findViewById<TextView>(R.id.pointText).setText(text)
    }

    private fun permissionReasons(): String = buildList {
        add(getString(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.onboarding_permission_nearby else R.string.onboarding_permission_location))
        add(getString(R.string.onboarding_permission_camera))
        add(getString(R.string.onboarding_permission_microphone))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(getString(R.string.onboarding_permission_notifications))
    }.joinToString("\n") { "• $it" }

    private fun updatePrimary() {
        val missing = permissions(this).filterNot(::granted)
        primary.setText(if (missing.isEmpty() || requested) R.string.onboarding_start else R.string.onboarding_allow)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun finishOnboarding() {
        markDone(this)
        // MainActivity is normally underneath; after a wipe the task was cleared, so start it.
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val PREF_ONBOARDING_DONE = "onboarding_done_v1"
        /** UI-only preferences file; listed in EmergencyWipe.KNOWN_PREFERENCES. */
        const val PREFERENCES = "bluetooth_video_ui"

        fun isDone(context: Context): Boolean = prefs(context).getBoolean(PREF_ONBOARDING_DONE, false)

        fun markDone(context: Context) {
            prefs(context).edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
        }

        /** After an emergency wipe the app starts from onboarding again. */
        fun reset(context: Context) {
            prefs(context).edit().clear().commit()
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        /** Everything the app's features use; MainActivity still asks per feature as before. */
        fun permissions(context: Context): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
