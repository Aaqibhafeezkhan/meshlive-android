package com.example.bluetoothtest

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

class AndroidNodeIdentityStore(
    private val alias: String = DEFAULT_ALIAS,
) {
    fun getOrCreate(): SigningIdentity {
        // During or after an emergency wipe this process neither reads nor recreates the identity.
        WipeGuard.check("identity key")
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generateKeyPair()
            }
        }
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val publicKey = keyStore.getCertificate(alias).publicKey
        return object : SigningIdentity {
            override val publicKeyEncoded: ByteArray = publicKey.encoded.copyOf()
            override val nodeId: NodeId = NodeId.fromPublicKey(publicKeyEncoded)

            override fun sign(message: ByteArray): ByteArray = signEcdsa(privateKey, message)
        }
    }

    fun delete() {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "bluetooth-video-mesh-identity-v1"
    }
}
