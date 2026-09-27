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

    override fun existingKey(): SecretKey? = key

    override fun getOrCreateKey(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }
        .generateKey().also { key = it; created++ }

    override fun destroyKey() {
        key = null
    }
}

class CredentialCipherTest {
    private val keys = SoftwareKeySource()
    private val cipher = AesGcmCredentialCipher(keys)
    private val aad = "tether.credential.v1|session_cookie".toByteArray()
    private val secret = "tthr_0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()

    private fun expectFailure(keyUnusable: Boolean, block: () -> Unit) {
        try {
            block()
            fail("expected CredentialCipherException")
        } catch (e: CredentialCipherException) {
            assertEquals(keyUnusable, e.keyUnusable)
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
    fun wrongKeyTypeIsKeyUnusable() {
        val blob = cipher.seal(secret, aad)
        keys.key = KeyGenerator.getInstance("HmacSHA256").generateKey()
        expectFailure(keyUnusable = true) { cipher.open(blob, aad) }
    }
}
