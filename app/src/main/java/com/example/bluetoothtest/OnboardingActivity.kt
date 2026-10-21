package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
 * First run, one idea per screen (2026-10-17 redesign): welcome, what you can do, permissions (each with why and
 * its state), optionally keep working with the screen off ([BatteryExemptionPrompt], design decision Q38), and ready.
 * Shown once, only for a plain user launch (never for benchmark intents, see [shouldShowOnboarding]), and again after
 * an emergency wipe.
 */
class OnboardingActivity : AppCompatActivity() {
    /** The pages in order; Background only when Android may still stop the app in the background. */
    private enum class Page { WELCOME, HOW, PERMISSIONS, FULL_SCREEN_CALLS, BACKGROUND, READY }

    private lateinit var pages: List<Page>
    private var index = 0
    private var requested = false
    private var backgroundPromptLaunched = false
    private lateinit var content: LinearLayout
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requested = true
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugTunables.playBatteryGuidance = intent.getBooleanExtra("test_play_battery_guidance", true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_onboarding_flow)
        val root = findViewById<View>(R.id.onboardingRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pages = buildList {
            add(Page.WELCOME)
            // Copy audit 2026-10-21: one idea per page; the welcome page says what the app does (HOW is kept, not shown).
            add(Page.PERMISSIONS)
            if (FullScreenIntentAccess.showGuidance(Build.VERSION.SDK_INT, DebugTunables.fullScreenCallGuidance, FullScreenIntentAccess.canUse(this@OnboardingActivity))) add(Page.FULL_SCREEN_CALLS)
            if (BatteryExemptionPrompt.shouldOffer(this@OnboardingActivity, BatteryOfferTrigger.ONBOARDING)) add(Page.BACKGROUND)
            add(Page.READY)
        }
        index = savedInstanceState?.getInt(STATE_PAGE, 0)?.coerceIn(0, pages.size - 1) ?: 0
        requested = savedInstanceState?.getBoolean(STATE_REQUESTED, false) ?: false
        backgroundPromptLaunched = savedInstanceState?.getBoolean(STATE_BG_LAUNCHED, false) ?: false
        content = findViewById(R.id.onboardingPage)
        primary = findViewById(R.id.onboardingPrimary)
        secondary = findViewById(R.id.onboardingSecondary)
        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { onSecondary() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (index > 0) {
                    index--
                    render()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, index)
        outState.putBoolean(STATE_REQUESTED, requested)
        outState.putBoolean(STATE_BG_LAUNCHED, backgroundPromptLaunched)
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's background prompt or app settings: show the new state.
        if (::content.isInitialized) render()
    }

    private val page: Page get() = pages[index]

    private fun onPrimary() {
        when (page) {
            Page.PERMISSIONS -> {
                val missing = permissions(this).filterNot(::granted)
                if (missing.isNotEmpty() && !requested) permissionRequest.launch(missing.toTypedArray()) else next()
            }
            Page.FULL_SCREEN_CALLS -> {
                if (FullScreenIntentAccess.canUse(this)) next() else runCatching { FullScreenIntentAccess.openSettings(this) }
            }
            Page.BACKGROUND -> {
                if (!backgroundPromptLaunched && !BatteryExemptionPrompt.isExempt(this)) {
                    backgroundPromptLaunched = true
                    BatteryExemptionPrompt.launch(this)
                } else {
                    next()
                }
            }
            Page.READY -> finishOnboarding()
            else -> next()
        }
    }

    /** Skip on the intro pages jumps to permissions; "Not now" moves on without asking. */
    private fun onSecondary() {
        when (page) {
            Page.WELCOME, Page.HOW -> {
                index = pages.indexOf(Page.PERMISSIONS)
                render()
            }
            else -> next()
        }
    }

    private fun next() {
        // The first-run explanation counts as shown once the permission page is passed (as before).
        if (page == Page.PERMISSIONS) markDone(this)
        if (index < pages.size - 1) {
            index++
            render()
        } else {
            finishOnboarding()
        }
    }

    private fun render() {
        content.removeAllViews()
        when (page) {
            Page.WELCOME -> {
                hero(R.drawable.ic_stat_meshlive)
                title(R.string.ob_welcome_title)
                body(R.string.ob_welcome_text)
                body(R.string.ob_welcome_mesh)
            }
            Page.HOW -> {
                title(R.string.ob_how_title)
                point(R.drawable.ic_call, R.string.ob_how_call_title, R.string.ob_how_call_text)
                point(R.drawable.ic_live, R.string.ob_how_live_title, R.string.ob_how_live_text)
                point(R.drawable.ic_relay, R.string.ob_how_relay_title, R.string.ob_how_relay_text)
                point(R.drawable.ic_lock, R.string.ob_how_private_title, R.string.ob_how_private_text)
            }
            Page.PERMISSIONS -> {
                title(R.string.ob_perm_title)
                body(R.string.ob_perm_text)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permission(R.drawable.ic_bluetooth, R.string.ob_perm_nearby_title, R.string.ob_perm_nearby_text, Manifest.permission.BLUETOOTH_SCAN)
                } else {
                    permission(R.drawable.ic_bluetooth, R.string.ob_perm_location_title, R.string.ob_perm_location_text, Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (!DebugTunables.callReadinessPermissions) permission(R.drawable.ic_videocam, R.string.ob_perm_camera_title, R.string.ob_perm_camera_text, Manifest.permission.CAMERA)
                permission(R.drawable.ic_mic, R.string.ob_perm_mic_title, R.string.ob_perm_mic_text, Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission(R.drawable.ic_phone_in_talk, R.string.ob_perm_notif_title, R.string.ob_perm_notif_text, Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Page.FULL_SCREEN_CALLS -> {
                hero(R.drawable.ic_phone_in_talk)
                title(R.string.ob_full_screen_title)
                body(R.string.ob_full_screen_text)
            }
            Page.BACKGROUND -> {
                hero(R.drawable.ic_relay)
                title(R.string.ob_bg_title)
                body(FlavourBattery.guidanceText(R.string.ob_bg_text))
                if (backgroundPromptLaunched) {
                    body(if (BatteryExemptionPrompt.isExempt(this)) R.string.ob_bg_allowed else R.string.ob_bg_declined, primaryColour = true)
                }
            }
            Page.READY -> {
                hero(R.drawable.ic_check_circle)
                title(R.string.ob_ready_title)
                body(R.string.ob_ready_text)
            }
        }
        val missing = permissions(this).filterNot(::granted)
        val bgPending = !backgroundPromptLaunched && !BatteryExemptionPrompt.isExempt(this)
        primary.setText(
            when (page) {
                Page.PERMISSIONS -> if (missing.isNotEmpty() && !requested) R.string.ob_allow else R.string.ob_next
                Page.FULL_SCREEN_CALLS -> if (FullScreenIntentAccess.canUse(this)) R.string.ob_next else R.string.ob_full_screen_settings
                Page.BACKGROUND -> if (bgPending) R.string.ob_allow else R.string.ob_next
                Page.READY -> R.string.ob_start
                else -> R.string.ob_next
            },
        )
        val secondaryText = when (page) {
            Page.WELCOME -> 0
            Page.HOW -> R.string.ob_skip
            Page.PERMISSIONS -> if (missing.isNotEmpty() && !requested) R.string.ob_not_now else 0
            Page.FULL_SCREEN_CALLS -> R.string.ob_not_now
            Page.BACKGROUND -> if (bgPending) R.string.ob_not_now else 0
            Page.READY -> 0
        }
        secondary.visibility = if (secondaryText == 0) View.INVISIBLE else View.VISIBLE
        if (secondaryText != 0) secondary.setText(secondaryText)
        renderDots()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun hero(icon: Int) {
        content.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_primary)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(24) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    private fun title(text: Int) {
        content.addView(TextView(this).apply {
            setTextAppearance(R.style.TextAppearance_App_Headline)
            setText(text)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
        })
    }

    private fun body(text: Int, primaryColour: Boolean = false) {
        content.addView(TextView(this).apply {
            setTextAppearance(R.style.TextAppearance_App_BodyLarge)
            if (primaryColour) setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setText(text)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(16) }
        })
    }

    /** One row: icon, title and one plain line; [trailing] is a status mark on the permissions page. */
    private fun point(icon: Int, title: Int, text: Int, trailing: View? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(20) }
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_secondary)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            setTextAppearance(R.style.TextAppearance_App_RowLabel)
            setText(title)
        })
        texts.addView(TextView(this).apply {
            setTextAppearance(R.style.TextAppearance_App_Body)
            setText(text)
        })
        row.addView(texts)
        trailing?.let(row::addView)
        content.addView(row)
    }

    private fun permission(icon: Int, title: Int, text: Int, permission: String) {
        val allowed = granted(permission)
        val mark = ImageView(this).apply {
            setImageResource(if (allowed) R.drawable.ic_check_circle else R.drawable.ic_error)
            imageTintList = ContextCompat.getColorStateList(context, if (allowed) R.color.success else R.color.text_disabled)
            contentDescription = getString(if (allowed) R.string.ob_perm_allowed else R.string.ob_perm_missing)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(12) }
            visibility = if (allowed || requested) View.VISIBLE else View.GONE
        }
        point(icon, title, text, mark)
    }

    private fun renderDots() {
        val dots = findViewById<LinearLayout>(R.id.onboardingDots)
        dots.removeAllViews()
        dots.contentDescription = getString(R.string.ob_page_of, index + 1, pages.size)
        pages.indices.forEach { i ->
            dots.addView(View(this).apply {
                background = ContextCompat.getDrawable(context, R.drawable.icon_circle_background)
                backgroundTintList = ContextCompat.getColorStateList(context, if (i == index) R.color.text_primary else R.color.outline_strong)
                val size = dp(if (i == index) 8 else 6)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
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
        private const val STATE_PAGE = "onboarding_page"
        private const val STATE_REQUESTED = "onboarding_requested"
        private const val STATE_BG_LAUNCHED = "onboarding_bg_launched"
        /** UI-only preferences file; listed in EmergencyWipe.KNOWN_PREFERENCES. */
        const val PREFERENCES = OnboardingState.PREFERENCES

        fun isDone(context: Context): Boolean = OnboardingState.isDone(context)

        fun markDone(context: Context) = OnboardingState.markDone(context)

        /** After an emergency wipe the app starts from onboarding again. */
        fun reset(context: Context) = OnboardingState.reset(context)

        /** Everything the app's features use; MainActivity still asks per feature as before. */
        @Suppress("UNUSED_PARAMETER")
        fun permissions(context: Context): List<String> = RuntimePermissionPlan.onboarding(Build.VERSION.SDK_INT, DebugTunables.callReadinessPermissions)
    }
}
