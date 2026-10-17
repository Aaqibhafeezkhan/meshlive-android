package com.example.bluetoothtest

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager

/** One background-restriction problem and what the user can do about it. */
internal data class ReliabilityCardIssue(
    val title: String,
    val guidance: String,
    /** Opens the relevant system screen, or null when there is nothing to open. */
    val settingsIntent: Intent?,
)

/**
 * Adapter for the "Connection reliability" card. [CoreReliabilityUi] returns
 * BackgroundReliability.cardIssues(context) (issues, vendor guidance and settings intents) plus the
 * Bluetooth-stack health notes; if that fails it falls back to Android's battery optimization state,
 * which the platform exposes directly.
 */
internal interface ReliabilityUi {
    fun issues(context: Context): List<ReliabilityCardIssue>
}

internal object ReliabilityUiBackend {
    @Volatile var current: ReliabilityUi = CoreReliabilityUi
}

internal object CoreReliabilityUi : ReliabilityUi {
    override fun issues(context: Context): List<ReliabilityCardIssue> {
        val card = runCatching { BackgroundReliability.cardIssues(context) }
            .getOrElse { listOfNotNull(batteryExemptionIssue(context)) }
        return card + bluetoothStackHealthIssues(context)
    }
}

/**
 * Notes about the Bluetooth stack itself, from the transport layers (advertising slots held by other apps or a leaked
 * stack, a GATT pool that stays exhausted, Bluetooth audio sharing the radio). Each opens the Bluetooth settings; none
 * appears on a healthy phone.
 */
internal fun bluetoothStackHealthIssues(context: Context): List<ReliabilityCardIssue> {
    val bluetoothSettings = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
    return listOfNotNull(
        runCatching { AdvertisingBudget.shared.stuck() }.getOrNull()?.let {
            ReliabilityCardIssue("Bluetooth has no free advertising slots", it.message, bluetoothSettings)
        },
        PoolExhaustionPolicy.shared.takeIf { runCatching { it.restartGuidanceDue() }.getOrDefault(false) }?.let {
            ReliabilityCardIssue("Bluetooth may be stuck", PoolExhaustionPolicy.GUIDANCE, bluetoothSettings)
        },
        runCatching { BluetoothAudioCoexistence.advice() }.getOrNull()?.let {
            ReliabilityCardIssue("Bluetooth audio is sharing the radio", it, bluetoothSettings)
        },
    )
}

/**
 * The battery-optimisation card entry, shared with whatever maps BackgroundReliability.status later. Its button opens the
 * system's one-tap exemption prompt (user decision Q38: offered again from Settings), falling back to the battery list
 * screen on phones whose maker removed the prompt. Null when the app is already exempt.
 */
internal fun batteryExemptionIssue(context: Context): ReliabilityCardIssue? {
    val power = context.getSystemService(PowerManager::class.java) ?: return null
    if (power.isIgnoringBatteryOptimizations(context.packageName)) return null
    return ReliabilityCardIssue(
        title = context.getString(R.string.reliability_battery_title),
        guidance = context.getString(R.string.reliability_battery_text),
        settingsIntent = BatteryExemptionPrompt.intent(context),
    )
}

/**
 * Adapter for "Export diagnostics". The field contract (docs/ARCHITECTURE.md "Diagnostics block") is owned by the
 * release work; wire its builder in [CoreDiagnosticsUi]. The fallback lists only non-identifying
 * facts: no media, messages, contact names, node IDs or keys.
 */
internal interface DiagnosticsUi {
    fun build(context: Context): String
}

internal object DiagnosticsUiBackend {
    @Volatile var current: DiagnosticsUi = CoreDiagnosticsUi
}

internal object CoreDiagnosticsUi : DiagnosticsUi {
    override fun build(context: Context): String = buildString {
        // TODO(root): replace with the release patch's diagnostics builder (device_policy rules,
        // recent connection outcomes and codec choices).
        appendLine("Bluetooth Video diagnostics")
        appendLine("app_version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        appendLine("signing_sha256: ${signingFingerprint(context) ?: "unknown"}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appendLine("soc: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        } else {
            appendLine("soc: ${Build.HARDWARE}")
        }
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            appendLine("bluetooth: none")
        } else runCatching {
            appendLine("bluetooth_le: ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
            appendLine("le_2m_phy: ${adapter.isLe2MPhySupported}")
            appendLine("le_coded_phy: ${adapter.isLeCodedPhySupported}")
            appendLine("le_extended_advertising: ${adapter.isLeExtendedAdvertisingSupported}")
            appendLine("le_max_advertising_data: ${adapter.leMaximumAdvertisingDataLength}")
            appendLine("multiple_advertisement: ${adapter.isMultipleAdvertisementSupported}")
        }.onFailure { appendLine("bluetooth_capabilities: unavailable (${it.javaClass.simpleName})") }
        appendLine("device_policy: (not included in this build)")
        appendLine("recent_connections: (not included in this build)")
    }

    private fun signingFingerprint(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.signingCertificateHistory?.lastOrNull()?.toByteArray()
            ?.let(AboutActivity::certificateFingerprint)
    }.getOrNull()
}

/**
 * Adapter for "Share this app" (AppShare.shareApk / AppShare.apkInfo, built separately) and the
 * deep-sleep banner (DozeWatchdog state). Fallbacks: sharing unavailable (button hidden), no banner.
 */
internal interface DistributionUi {
    val shareAvailable: Boolean
    fun shareApk(activity: android.app.Activity)
    /** APK SHA-256 and signing certificate SHA-256, as display text; null when unknown. */
    fun apkInfo(context: Context): String?
    /** Text of a "deep sleep" warning when Android is holding the app asleep; null when fine. */
    fun deepSleepBanner(context: Context): String?
}

internal object DistributionUiBackend {
    @Volatile var current: DistributionUi = CoreDistributionUi
}

internal object CoreDistributionUi : DistributionUi {
    override val shareAvailable: Boolean = false // TODO(root): true once AppShare is merged
    override fun shareApk(activity: android.app.Activity) = Unit // TODO(root): AppShare.shareApk(activity)
    override fun apkInfo(context: Context): String? = null // TODO(root): AppShare.apkInfo()
    override fun deepSleepBanner(context: Context): String? = runCatching { DozeWatchdog.message() }.getOrNull()
}

internal object SupportLinks {
    /** The public issues page; null until the repository URL is known (Report a bug stays hidden). */
    val ISSUES_URL: String? = null

    fun reportBugIntent(): Intent? = ISSUES_URL?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
}
