package com.tether.app.mirror

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T13.1 (SYNC_DESIGN §8.1): the row cipher, on the JVM. */
class MirrorCipherTest {
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val cipher = MirrorCipher(key)
    private val secret = "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCY in a diff".toByteArray()
    private val aad = MirrorCipher.aad("0123456789abcdef", "journal_event", "s1", "7")

    private fun expectReject(block: () -> Unit) {
        try {
            block()
            fail("expected MirrorBlobException")
        } catch (e: MirrorBlobException) {
            // The failure never carries plaintext.
            assertFalse(e.message.orEmpty().contains("AWS_SECRET"))
        }
    }

    @Test
    fun roundTripAndFraming() {
        val blob = cipher.seal(secret, aad)
        assertArrayEquals(secret, cipher.open(blob, aad))
        // [v1][12-byte IV][ct || 16-byte tag]: the CredentialCipher framing.
        assertEquals(MirrorCipher.VERSION, blob[0])
        assertEquals(1 + 12 + secret.size + 16, blob.size)
    }

    @Test
    fun plaintextNeverAppearsInTheBlob() {
        val blob = cipher.seal(secret, aad)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("AWS_SECRET"))
        val compressed = cipher.sealCompressed(String(secret), aad)
        assertFalse(String(compressed, Charsets.ISO_8859_1).contains("AWS_SECRET"))
        assertEquals(String(secret), cipher.openCompressed(compressed, aad))
    }

    @Test
    fun everyBlobGetsAFreshRandomNonce() {
        // 2^17 seals of the SAME plaintext under the same key: no IV repeats, and so no
        // ciphertext repeats. (A 96-bit random IV collides with probability ~2^-63 here.)
        val ivs = HashSet<String>()
        val bodies = HashSet<String>()
        repeat(1 shl 17) {
            val blob = cipher.seal(secret, aad)
            assertTrue("IV repeated", ivs.add(blob.copyOfRange(1, 13).toHex()))
            assertTrue("ciphertext repeated", bodies.add(blob.copyOfRange(13, blob.size).toHex()))
        }
    }

    @Test
    fun nonceComesFromTheInjectedSecureRandomNotTheProvider() {
        // A deterministic source proves the IV is ours and explicit (not provider-chosen).
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0x5A)
        }
        val blob = MirrorCipher(key, fixed).seal(secret, aad)
        assertArrayEquals(ByteArray(12) { 0x5A }, blob.copyOfRange(1, 13))
    }

    @Test
    fun aadBindsTheBlobToItsRow() {
        val blob = cipher.seal(secret, aad)
        // Another seq, session, table or origin: rejected (a blob moved between rows).
        expectReject { cipher.open(blob, MirrorCipher.aad("0123456789abcdef", "journal_event", "s1", "8")) }
        expectReject { cipher.open(blob, MirrorCipher.aad("0123456789abcdef", "journal_event", "s2", "7")) }
        expectReject { cipher.open(blob, MirrorCipher.aad("0123456789abcdef", "turn_detail", "s1", "7")) }
        expectReject { cipher.open(blob, MirrorCipher.aad("fedcba9876543210", "journal_event", "s1", "7")) }
        // The NUL separators keep the fields apart: "s1"+"7" is not "s17"+"".
        assertNotEquals(
            MirrorCipher.aad("o", "t", "s1", "7").toHex(),
            MirrorCipher.aad("o", "t", "s17", "").toHex(),
        )
    }

    @Test
    fun anyTamperedByteIsRejected() {
        val blob = cipher.seal(secret, aad)
        for (i in blob.indices) {
            val copy = blob.copyOf()
            copy[i] = (copy[i].toInt() xor 0x01).toByte()
            expectReject { cipher.open(copy, aad) }
        }
        expectReject { cipher.open(blob.copyOf(blob.size - 1), aad) }
        expectReject { cipher.open(blob.copyOf(20), aad) }
    }

    @Test
    fun anotherKeyCannotOpen() {
        val blob = cipher.seal(secret, aad)
        val other = MirrorCipher(ByteArray(32).also(SecureRandom()::nextBytes))
        expectReject { other.open(blob, aad) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyAes256Keys() {
        MirrorCipher(ByteArray(16))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
