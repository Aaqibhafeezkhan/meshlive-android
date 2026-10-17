package com.example.bluetoothtest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.security.MessageDigest

/** Version, build and the signing certificate SHA-256 read at runtime, to check a genuine build. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_about)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.aboutRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.aboutBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.aboutVersionText).text = getString(
            R.string.about_version_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.BUILD_TYPE,
        )
        val fingerprints = signingCertificateSha256()
        val shown = fingerprints.joinToString("\n\n").ifEmpty { getString(R.string.about_signing_unknown) }
        // Short form first (2026-10-17 redesign); a tap shows the full fingerprint, Copy always copies it in full.
        val short = fingerprints.joinToString("\n") { CertificateText.short(it) }.ifEmpty { shown }
        findViewById<TextView>(R.id.aboutSigningText).apply {
            text = short
            var full = false
            setOnClickListener { full = !full; text = if (full) shown else short }
        }
        findViewById<View>(R.id.aboutCopyButton).apply {
            isEnabled = fingerprints.isNotEmpty()
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.about_signing), shown))
                Toast.makeText(this@AboutActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** SHA-256 of each current signing certificate, as colon-separated uppercase hex. */
    private fun signingCertificateSha256(): List<String> = runCatching {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return emptyList()
        // A single signer's history runs from the original to the current certificate.
        val certificates = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners.orEmpty().toList()
        } else {
            listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
        }
        certificates.map { signature -> certificateFingerprint(signature.toByteArray()) }.distinct()
    }.getOrDefault(emptyList())

    companion object {
        internal fun certificateFingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
    }
}
