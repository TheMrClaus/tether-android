package com.tether.app.client

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The credential key in the Android Keystore: AES-256, GCM only, no padding,
 * randomized encryption enforced (the caller can never pick an IV), and
 * non-exportable (the key material never leaves the Keystore / TEE; this process
 * only ever holds a handle).
 *
 * Deliberately WITHOUT `setUserAuthenticationRequired` or
 * `setUnlockedDeviceRequired`: the connection loop reconnects in the background
 * (network change, process restart by the system) without the user present, and
 * a lock-screen-bound key would sign the user out on every such restart.
 *
 * Keystore keys are never part of Auto Backup or device-to-device transfer, so a
 * restored ciphertext is undecryptable on another device by construction (the
 * credential files are excluded from backup as well — see data_extraction_rules).
 *
 * Robolectric has no AndroidKeyStore provider: this class is exercised on a
 * device/emulator only. Everything else (framing, failure policy, migration) runs
 * on the JVM against [AesGcmCredentialCipher] with a software key.
 */
class KeystoreCredentialKeySource(private val alias: String = DEFAULT_ALIAS) : CredentialKeySource {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    @Synchronized
    override fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    override fun destroyKey() {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "tether.credentials.aesgcm.v1"
    }
}
