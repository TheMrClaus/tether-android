package com.tether.app.client

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/** A software AES key standing in for the Keystore one: same framing, same failure policy. */
class SoftwareKeySource : CredentialKeySource {
    var key: SecretKey? = null
    var created = 0
    var destroyed = 0

    /** When set, every key access throws it (a Keystore that is down / locked / broken). */
    var failure: Exception? = null

    override fun existingKey(): SecretKey? {
        failure?.let { throw it }
        return key
    }

    override fun getOrCreateKey(): SecretKey {
        failure?.let { throw it }
        return key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it; created++ }
    }

    override fun destroyKey() {
        key = null
        destroyed++
    }
}

class CredentialCipherTest {
    private val keys = SoftwareKeySource()
    private val cipher = AesGcmCredentialCipher(keys)
    private val aad = "tether.credential.v1|session_cookie".toByteArray()
    private val secret = "tthr_0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()

    private fun expectFailure(keyUnusable: Boolean, transient: Boolean = false, block: () -> Unit) {
        try {
            block()
            fail("expected CredentialCipherException")
        } catch (e: CredentialCipherException) {
            assertEquals(keyUnusable, e.keyUnusable)
            assertEquals(transient, e.transient)
            // The failure never carries the plaintext.
            assertFalse(e.message.orEmpty().contains(String(secret)))
        }
    }

    @Test
    fun roundTrip() {
        val blob = cipher.seal(secret, aad)
        assertArrayEquals(secret, cipher.open(blob, aad))
    }

    @Test
    fun blobLayoutIsVersionIvCiphertextTag() {
        val blob = cipher.seal(secret, aad)
        assertEquals(AesGcmCredentialCipher.VERSION, blob[0])
        assertEquals(1 + 12 + secret.size + 16, blob.size)
        // The plaintext never appears in the blob.
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(String(secret)))
    }

    @Test
    fun everySealUsesAFreshIv() {
        val ivs = (1..50).map { cipher.seal(secret, aad).copyOfRange(1, 13).toList() }.toSet()
        assertEquals(50, ivs.size)
    }

    @Test
    fun flippedCiphertextByteFails() {
        val blob = cipher.seal(secret, aad)
        blob[blob.size - 20] = (blob[blob.size - 20].toInt() xor 0x01).toByte()
        expectFailure(keyUnusable = false) { cipher.open(blob, aad) }
    }

    @Test
    fun flippedIvByteFails() {
        val blob = cipher.seal(secret, aad)
        blob[3] = (blob[3].toInt() xor 0x80).toByte()
        expectFailure(keyUnusable = false) { cipher.open(blob, aad) }
    }

    @Test
    fun blobFromAnotherSlotFails() {
        val blob = cipher.seal(secret, aad)
        expectFailure(keyUnusable = false) { cipher.open(blob, "tether.credential.v1|device_token".toByteArray()) }
    }

    @Test
    fun truncatedAndUnknownVersionFail() {
        val blob = cipher.seal(secret, aad)
        expectFailure(keyUnusable = false) { cipher.open(blob.copyOf(20), aad) }
        expectFailure(keyUnusable = false) { cipher.open(ByteArray(0), aad) }
        val v2 = blob.copyOf().also { it[0] = 2 }
        expectFailure(keyUnusable = false) { cipher.open(v2, aad) }
    }

    @Test
    fun missingKeyIsKeyUnusableAndOpenNeverCreatesOne() {
        val blob = cipher.seal(secret, aad)
        keys.destroyKey()
        expectFailure(keyUnusable = true) { cipher.open(blob, aad) }
        assertEquals(null, keys.key)
    }

    @Test
    fun blobUnderARotatedKeyFails() {
        val blob = cipher.seal(secret, aad)
        cipher.destroyKey()
        val fresh = cipher.seal(secret, aad)
        assertEquals(2, keys.created)
        expectFailure(keyUnusable = false) { cipher.open(blob, aad) }
        assertArrayEquals(secret, cipher.open(fresh, aad))
        assertNotEquals(blob.toList(), fresh.toList())
    }

    @Test
    fun aGenericInvalidKeyIsTransientNotADestroy() {
        // Only a missing / permanently invalidated / unrecoverable key is dead.
        val blob = cipher.seal(secret, aad)
        keys.key = KeyGenerator.getInstance("HmacSHA256").generateKey()
        expectFailure(keyUnusable = false, transient = true) { cipher.open(blob, aad) }
    }

    @Test
    fun keystoreErrorsWhileFetchingTheKeyAreTransient() {
        val blob = cipher.seal(secret, aad)
        for (error in listOf(java.security.KeyStoreException("locked"), java.security.ProviderException("daemon"), IllegalStateException("x"))) {
            keys.failure = error
            expectFailure(keyUnusable = false, transient = true) { cipher.open(blob, aad) }
            expectFailure(keyUnusable = false, transient = true) { cipher.seal(secret, aad) }
        }
        keys.failure = null
        assertArrayEquals(secret, cipher.open(blob, aad)) // the key survived
        assertEquals(0, keys.destroyed)
    }

    @Test
    fun anUnrecoverableKeyIsKeyUnusable() {
        val blob = cipher.seal(secret, aad)
        keys.failure = java.security.UnrecoverableKeyException("gone")
        expectFailure(keyUnusable = true) { cipher.open(blob, aad) }
        expectFailure(keyUnusable = true) { cipher.seal(secret, aad) }
    }

    @Test
    fun credentialsNeverPrintTheirValue() {
        val cookie = Credential.Cookie("s3cr3t-cookie")
        val token = Credential.DeviceToken("tthr_s3cr3t")
        assertEquals("Cookie(***)", cookie.toString())
        assertEquals("DeviceToken(***)", token.toString())
        assertFalse("$cookie $token ${listOf(cookie, token)}".contains("s3cr3t"))
        // Equality still compares the value.
        assertEquals(Credential.Cookie("s3cr3t-cookie"), cookie)
        assertNotEquals(Credential.Cookie("other"), cookie)
    }

    // ------------------------------------------------------------------
    // Cause-chain classification (Android Keystore wraps its real errors)
    // ------------------------------------------------------------------

    private fun kind(e: Throwable) = classifyCipherFailure("t", e).failure

    /** Stands in for android.security.KeyStoreException (API 33+), matched by simple name. */
    class KeyStoreException(private val transientFailure: Boolean) : Exception("keystore") {
        @Suppress("unused")
        fun isTransientFailure(): Boolean = transientFailure
    }

    @Test
    fun onlyATagMismatchIsABadBlob() {
        assertEquals(CipherFailure.BadBlob, kind(javax.crypto.AEADBadTagException("tag")))
        // Android Keystore reports provider failures as IllegalBlockSizeException.
        assertEquals(CipherFailure.Suspect, kind(javax.crypto.IllegalBlockSizeException("provider")))
        assertEquals(CipherFailure.Suspect, kind(java.security.InvalidKeyException("?")))
    }

    @Test
    fun aKeyStoreExceptionAnywhereInTheChainIsTransient() {
        val wrapped = javax.crypto.IllegalBlockSizeException("provider").apply { initCause(java.security.KeyStoreException("busy")) }
        assertEquals(CipherFailure.Transient, kind(wrapped))
        val deep = java.security.ProviderException("outer", RuntimeException("mid", java.security.KeyStoreException("inner")))
        assertEquals(CipherFailure.Transient, kind(deep))
        // API 33+: the exception says whether it is transient.
        val saysTransient = javax.crypto.IllegalBlockSizeException("p").apply { initCause(KeyStoreException(transientFailure = true)) }
        assertEquals(CipherFailure.Transient, kind(saysTransient))
        val saysPermanent = javax.crypto.IllegalBlockSizeException("p").apply { initCause(KeyStoreException(transientFailure = false)) }
        assertEquals(CipherFailure.Suspect, kind(saysPermanent))
    }

    @Test
    fun unrecoverableKeyIsDeadUnlessATransientErrorCausedIt() {
        assertEquals(CipherFailure.KeyDead, kind(java.security.UnrecoverableKeyException("gone")))
        val causedByBusy = java.security.UnrecoverableKeyException("wrapped").apply {
            initCause(java.security.KeyStoreException("system busy"))
        }
        assertEquals(CipherFailure.Transient, kind(causedByBusy))
    }

    @Test
    fun aKeyStoreExceptionFromKeyFetchKeepsTheKey() {
        val blob = cipher.seal(secret, aad)
        keys.failure = java.security.UnrecoverableKeyException("wrapped").apply { initCause(java.security.KeyStoreException("busy")) }
        expectFailure(keyUnusable = false, transient = true) { cipher.open(blob, aad) }
        keys.failure = null
        assertArrayEquals(secret, cipher.open(blob, aad))
        assertEquals(0, keys.destroyed)
    }
}
