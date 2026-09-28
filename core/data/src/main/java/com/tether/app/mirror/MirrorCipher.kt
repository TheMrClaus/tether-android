package com.tether.app.mirror

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * T13.1 (SYNC_DESIGN §8.1): row-level AES-256-GCM under the mirror's software data key.
 *
 * Blob layout is the [com.tether.app.client.AesGcmCredentialCipher] framing:
 * `[version=1][12-byte IV][ciphertext || 16-byte tag]`. Unlike the credential cipher (whose
 * Keystore key generates the IV itself), the IV here is drawn from [SecureRandom] for every
 * blob and passed explicitly, so a fresh random 96-bit nonce per blob does not depend on
 * provider behaviour. The data key is rotated long before the NIST SP 800-38D random-IV bound
 * (2^32 encryptions per key): see [JournalMirror.rotateAfterWrites].
 *
 * [aad] binds a blob to its row (table, session, row key and the DB's origin), so a blob moved
 * to another row, session or server fails the tag check. Failures throw [MirrorBlobException],
 * whose message never carries plaintext or key material.
 */
class MirrorCipher(key: ByteArray, private val random: SecureRandom = SecureRandom()) {
    private val secretKey: SecretKey

    init {
        require(key.size == KEY_BYTES) { "mirror data key must be $KEY_BYTES bytes" }
        // SecretKeySpec keeps its own copy: the caller may zero [key] after this.
        secretKey = SecretKeySpec(key, "AES")
    }

    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext)
        val out = ByteArray(1 + IV_BYTES + sealed.size)
        out[0] = VERSION
        System.arraycopy(iv, 0, out, 1, IV_BYTES)
        System.arraycopy(sealed, 0, out, 1 + IV_BYTES, sealed.size)
        return out
    }

    fun open(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BYTES) throw MirrorBlobException("blob too short")
        if (blob[0] != VERSION) throw MirrorBlobException("unknown blob version")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BYTES * 8, blob, 1, IV_BYTES))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        } catch (e: Exception) {
            // Only the exception type: never the blob or the key.
            throw MirrorBlobException("open failed (${e.javaClass.simpleName})")
        }
    }

    fun sealText(text: String, aad: ByteArray): ByteArray = seal(text.toByteArray(Charsets.UTF_8), aad)

    fun openText(blob: ByteArray, aad: ByteArray): String = String(open(blob, aad), Charsets.UTF_8)

    /** gzip, then seal: for the (up to ~1 MB) projection bases. */
    fun sealCompressed(text: String, aad: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return seal(buffer.toByteArray(), aad)
    }

    fun openCompressed(blob: ByteArray, aad: ByteArray): String {
        val plain = open(blob, aad)
        return try {
            GZIPInputStream(ByteArrayInputStream(plain)).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            throw MirrorBlobException("inflate failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** v2: the length-prefixed AAD below. A v1 blob is rejected (and the mirror wiped at bind). */
        const val VERSION: Byte = 2
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BYTES = 16

        /**
         * `tether.mirror.v2`, then each of origin, table, session and row key as a 4-byte
         * big-endian length followed by its UTF-8 bytes. Session ids and turn ids come from the
         * server and may contain any character, so a separator could be forged; a length prefix
         * cannot (security review L4).
         */
        fun aad(originKey: String, table: String, sessionId: String, rowKey: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(AAD_LABEL)
            for (field in arrayOf(originKey, table, sessionId, rowKey)) {
                val bytes = field.toByteArray(Charsets.UTF_8)
                out.write(bytes.size ushr 24)
                out.write(bytes.size ushr 16)
                out.write(bytes.size ushr 8)
                out.write(bytes.size)
                out.write(bytes)
            }
            return out.toByteArray()
        }

        private val AAD_LABEL = "tether.mirror.v2".toByteArray(Charsets.UTF_8)
    }
}

class MirrorBlobException(message: String) : Exception(message)
