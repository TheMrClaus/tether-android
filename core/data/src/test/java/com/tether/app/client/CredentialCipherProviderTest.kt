package com.tether.app.client

import java.security.Provider
import java.security.Security
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec
import org.conscrypt.Conscrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * ta-js0: the credential cipher's failure classes under each JCA provider it can meet on the
 * JVM, PINNED per test instead of inherited from the JVM-global provider list. Robolectric puts
 * Conscrypt first in that list once any sandbox has started, which is what made
 * CredentialCipherTest depend on test order.
 *
 * On a device the credential key is an AndroidKeyStore key, which Conscrypt never handles
 * (its AES ciphers take RAW keys only). The Keystore-shaped exceptions are covered by
 * CredentialCipherTest's cause-chain tests. This class pins what a software provider does:
 * a tag mismatch, whatever its cause, only ever drops the blob, and no failure here destroys
 * the key.
 */
@RunWith(Parameterized::class)
class CredentialCipherProviderTest(private val providerName: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers(): List<String> = listOf("SunJCE", "Conscrypt")

        fun provider(name: String): Provider = when (name) {
            "SunJCE" -> Security.getProvider("SunJCE") ?: error("SunJCE is missing")
            "Conscrypt" -> {
                // Fail, never skip: without it this class would silently test SunJCE twice.
                assertTrue("Conscrypt's native library did not load", Conscrypt.isAvailable())
                Conscrypt.newProvider()
            }
            else -> error(name)
        }
    }

    private val provider = provider(providerName)
    private val keys = SoftwareKeySource()
    private val cipher = AesGcmCredentialCipher(keys, provider)
    private val aad = "tether.credential.v1|device_token|https://tether.example:443".toByteArray()
    private val secret = "tthr_0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()

    private fun failureOf(block: () -> Unit): CipherFailure {
        try {
            block()
        } catch (e: CredentialCipherException) {
            assertFalse(e.message.orEmpty().contains(String(secret)))
            return e.failure
        }
        fail("expected CredentialCipherException")
        error("unreachable")
    }

    private fun flip(blob: ByteArray, index: Int) = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

    @Test
    fun theProviderIsTheOnePinned() {
        // The seam really routes through [provider]: a sealed blob opens under a plain
        // javax.crypto Cipher from the same provider.
        val blob = cipher.seal(secret, aad)
        val c = javax.crypto.Cipher.getInstance(AesGcmCredentialCipher.TRANSFORMATION, provider)
        c.init(javax.crypto.Cipher.DECRYPT_MODE, keys.key, javax.crypto.spec.GCMParameterSpec(128, blob, 1, 12))
        c.updateAAD(aad)
        assertEquals(providerName, c.provider.name)
        assertArrayEquals(secret, c.doFinal(blob, 13, blob.size - 13))
    }

    @Test
    fun roundTripWithA12ByteIv() {
        val blob = cipher.seal(secret, aad)
        assertEquals(1 + 12 + secret.size + 16, blob.size)
        assertArrayEquals(secret, cipher.open(blob, aad))
    }

    @Test
    fun everyTamperIsABadBlobAndTheKeySurvives() {
        val blob = cipher.seal(secret, aad)
        assertEquals(CipherFailure.BadBlob, failureOf { cipher.open(flip(blob, 3), aad) }) // IV
        assertEquals(CipherFailure.BadBlob, failureOf { cipher.open(flip(blob, 14), aad) }) // ciphertext
        assertEquals(CipherFailure.BadBlob, failureOf { cipher.open(flip(blob, blob.size - 1), aad) }) // tag
        assertEquals(CipherFailure.BadBlob, failureOf { cipher.open(blob, "tether.credential.v1|device_token|https://other.example:443".toByteArray()) })
        assertEquals(0, keys.destroyed)
        assertArrayEquals(secret, cipher.open(blob, aad))
    }

    @Test
    fun anotherAesKeyIsABadBlobNeverAKeyFailure() {
        // A software provider cannot tell another key from a tampered blob: a tag mismatch.
        // BadBlob drops the blob only; the key is never destroyed for it.
        val blob = cipher.seal(secret, aad)
        val real = keys.key
        keys.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(CipherFailure.BadBlob, failureOf { cipher.open(blob, aad) })
        keys.key = real
        assertEquals(0, keys.destroyed)
        assertArrayEquals(secret, cipher.open(blob, aad))
    }

    @Test
    fun aKeyNoProviderCanUseIsSuspectNotADestroy() {
        val blob = cipher.seal(secret, aad)
        val real = keys.key
        for (unusable in listOf(UnusableAesKey(), SecretKeySpec(ByteArray(17), "AES"))) {
            keys.key = unusable
            assertEquals(CipherFailure.Suspect, failureOf { cipher.open(blob, aad) })
            assertEquals(CipherFailure.Suspect, failureOf { cipher.seal(secret, aad) })
        }
        keys.key = real
        assertEquals(0, keys.destroyed)
        assertArrayEquals(secret, cipher.open(blob, aad))
    }

    /**
     * The root cause of ta-js0, pinned: the old test used an HMAC key to provoke an
     * InvalidKeyException. SunJCE checks the algorithm name; Conscrypt does not (it uses the
     * 32 bytes as an AES key), so the same open is a tag mismatch there. Both outcomes keep the
     * key; neither is KeyDead.
     */
    @Test
    fun aKeyOfAnotherAlgorithmDependsOnTheProviderButNeverKillsTheKey() {
        val blob = cipher.seal(secret, aad)
        keys.key = KeyGenerator.getInstance("HmacSHA256").generateKey()
        val expected = if (providerName == "Conscrypt") CipherFailure.BadBlob else CipherFailure.Suspect
        assertEquals(expected, failureOf { cipher.open(blob, aad) })
        assertEquals(0, keys.destroyed)
    }

    @Test
    fun aKeystoreFailureFetchingTheKeyIsTransientUnderEveryProvider() {
        val blob = cipher.seal(secret, aad)
        keys.failure = java.security.KeyStoreException("busy")
        assertEquals(CipherFailure.Transient, failureOf { cipher.open(blob, aad) })
        assertEquals(CipherFailure.Transient, failureOf { cipher.seal(secret, aad) })
        keys.failure = null
        assertEquals(0, keys.destroyed)
        assertArrayEquals(secret, cipher.open(blob, aad))
    }
}
