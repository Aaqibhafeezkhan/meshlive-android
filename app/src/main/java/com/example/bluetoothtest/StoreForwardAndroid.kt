package com.example.bluetoothtest

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Experimental store-and-forward messages (PLAN Design A v1), off by default. Same rule as the
 * other experiments: a DEBUG benchmark launch (any `test_` extra) uses only its own
 * `test_store_forward` extra; otherwise the Advanced setting applies, and only while Advanced
 * settings are on. Off: no Messages entry, no hop type 11 accepted anywhere, no change to calls.
 */
object StoreForwardFeature {
    const val EXTRA = "test_store_forward"
    const val PREF = "experimental_store_forward"

    @Volatile var enabled = false

    fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        advancedSettings: Boolean,
        stored: (String) -> Boolean,
    ): Boolean = when {
        benchmarkLaunch -> extra(EXTRA)
        advancedSettings -> stored(PREF)
        else -> false
    }
}

/**
 * The static P-256 mailbox key. API 31+: a non-exportable Android Keystore ECDH key
 * (PURPOSE_AGREE_KEY). Below API 31, or if the Keystore refuses, a software key whose PKCS#8
 * bytes are sealed by the Keystore AES key ([cipher]). Once created, the same key is reused so the
 * contact card stays valid.
 */
internal class AndroidMailboxKeyStore(private val dir: File, private val cipher: AtRestCipher) {
    fun getOrCreate(): MailboxKey {
        WipeGuard.check("mailbox key")
        val kindFile = File(dir, "mailbox.kind")
        val kind = kindFile.takeIf(File::exists)?.readText()?.trim()
        val keystore = if (kind == MailboxKeyPlanner.KIND_KEYSTORE) runCatching { keystoreKey() }.getOrNull() else null
        val software = if (kind == MailboxKeyPlanner.KIND_SOFTWARE) runCatching { loadSoftware() }.getOrNull() else null
        when (MailboxKeyPlanner.plan(kind, keystore != null, software != null)) {
            MailboxKeyAction.USE_KEYSTORE -> return requireNotNull(keystore)
            MailboxKeyAction.USE_SOFTWARE -> return requireNotNull(software)
            MailboxKeyAction.CREATE -> Unit
            MailboxKeyAction.RESET_AND_CREATE -> {
                // The recorded key is gone (Keystore cleared, data restored elsewhere): a new key means
                // a new card, so the user is told instead of it happening silently (N7).
                Log.w("BluetoothVideo", "Store-forward: mailbox key ($kind) missing; creating a new one")
                MailboxKeyResetNotice.record(dir, System.currentTimeMillis())
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val created = runCatching { createKeystoreKey() }.onFailure {
                Log.w("BluetoothVideo", "Store-forward: Keystore ECDH unavailable, using a wrapped software key", it)
            }.getOrNull()
            if (created != null) {
                atomicWrite(kindFile, MailboxKeyPlanner.KIND_KEYSTORE.toByteArray())
                return created
            }
        }
        val pair = generateEcKeyPair()
        atomicWrite(File(dir, "mailbox.key"), cipher.seal(pair.private.encoded))
        atomicWrite(File(dir, "mailbox.pub"), pair.public.encoded)
        atomicWrite(kindFile, MailboxKeyPlanner.KIND_SOFTWARE.toByteArray())
        return SoftwareMailboxKey(pair)
    }

    private fun loadSoftware(): MailboxKey {
        val factory = KeyFactory.getInstance("EC")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(cipher.open(File(dir, "mailbox.key").readBytes())))
        val public = factory.generatePublic(X509EncodedKeySpec(File(dir, "mailbox.pub").readBytes()))
        return SoftwareMailboxKey(KeyPair(public, private))
    }

    private fun createKeystoreKey(): MailboxKey {
        if (Build.VERSION.SDK_INT < 31) error("Keystore ECDH needs API 31")
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(
                KeyGenParameterSpec.Builder(MAILBOX_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .build(),
            )
            generateKeyPair()
        }
        val key = requireNotNull(keystoreKey()) { "Keystore mailbox key missing after creation" }
        // Prove agreement works on this device before the card is published.
        key.agree(generateEcKeyPair().public)
        return key
    }

    private fun keystoreKey(): MailboxKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(MAILBOX_ALIAS)) return null
        val private = keyStore.getKey(MAILBOX_ALIAS, null) as PrivateKey
        val public = keyStore.getCertificate(MAILBOX_ALIAS).publicKey
        return object : MailboxKey {
            override val publicKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public.encoded))
            override fun agree(peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE).run {
                init(private)
                doPhase(peer, true)
                generateSecret()
            }
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MAILBOX_ALIAS = "bluetooth-video-mesh-mailbox-v1"
    }
}

/** Non-exportable Keystore AES-256-GCM key for messages at rest and the wrapped software mailbox key. */
internal object StoreForwardAtRestKey {
    private const val ALIAS = "bluetooth-video-mesh-store-forward-v1"

    fun get(): SecretKey {
        WipeGuard.check("store-and-forward key")
        val keyStore = KeyStore.getInstance(AndroidMailboxKeyStore.ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidMailboxKeyStore.ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }
}

/** Process-wide store-and-forward node, opened lazily the first time the experiment is used. */
object StoreForwardRuntime {
    @Volatile private var node: StoreForwardNode? = null
    @Volatile private var outbox: OutboxAttemptLedger? = null

    /** Refused during and after an emergency wipe in this process, so no key or directory is recreated. */
    fun node(context: Context): StoreForwardNode {
        WipeGuard.check("store-and-forward")
        return node ?: synchronized(this) {
            WipeGuard.check("store-and-forward")
            node ?: open(context.applicationContext).also { node = it }
        }
    }

    /** Emergency wipe: forget the open node so nothing in this process serves deleted keys or bundles. */
    internal fun clearForWipe() {
        synchronized(this) {
            node = null
            outbox = null
        }
    }

    /**
     * Experimental automatic sync (StoreForwardAutoSyncFeature): per-message delivery attempts and
     * the carried mark, sealed under the same Keystore AES key as the other metadata.
     */
    fun outboxLedger(context: Context): OutboxAttemptLedger {
        WipeGuard.check("store-and-forward")
        return outbox ?: synchronized(this) {
            WipeGuard.check("store-and-forward")
            outbox ?: run {
                val key = StoreForwardAtRestKey.get()
                OutboxAttemptLedger(File(root(context.applicationContext), "outbox-attempts.properties"), AesGcmAtRestCipher { key })
            }.also { outbox = it }
        }
    }

    /** noBackupFilesDir: excluded from cloud backup and device transfer by the platform. */
    fun root(context: Context): File {
        WipeGuard.check("store-and-forward storage")
        return File(context.noBackupFilesDir, "store-forward").apply { mkdirs() }
    }

    private fun open(context: Context): StoreForwardNode {
        val root = root(context)
        val key = StoreForwardAtRestKey.get()
        val cipher = AesGcmAtRestCipher { key }
        val mailbox = AndroidMailboxKeyStore(root, cipher).getOrCreate()
        return StoreForwardNode.open(root, AndroidNodeIdentityStore().getOrCreate(), mailbox, cipher)
    }
}
