package com.tether.app.mirror

import com.tether.app.client.CipherFailure
import com.tether.app.client.CredentialCipher
import com.tether.app.client.CredentialCipherException
import com.tether.app.client.KeystoreCredentialKeySource
import java.io.File
import java.security.SecureRandom

/** The mirror's data key: [bytes] seal every blob; [id] names it in the DB's `meta` (key check). */
class MirrorDataKey(val id: String, val bytes: ByteArray) {
    override fun toString(): String = "MirrorDataKey($id)" // never the key bytes
}

/**
 * Envelope encryption for the mirror (SYNC_DESIGN §8.1): a random 256-bit data key, generated
 * in software, sealed by [kek] (in production an [com.tether.app.client.AesGcmCredentialCipher]
 * over a non-exportable Keystore key [KEK_ALIAS], built like the credential key: not
 * user-auth-bound, so background work can use it after first unlock). The sealed key lives in
 * [file], under `noBackupFilesDir`, which is never backed up or transferred.
 *
 * The data key is unwrapped once per process ([load]) and held by [MirrorCipher]; no per-row
 * Keystore IPC.
 */
class MirrorKeyStore(
    private val file: File,
    private val kek: CredentialCipher,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Loaded {
        data class Present(val key: MirrorDataKey) : Loaded

        /** No key file: nothing sealed under a previous key can be read (fresh install or wiped). */
        data object Absent : Loaded

        /** The file or the Keystore key is unusable for good: the mirror must be deleted (§8.2). */
        data object Lost : Loaded

        /** The Keystore said "not right now": keep everything, run without the mirror this time. */
        data object Unavailable : Loaded
    }

    @Synchronized
    fun load(): Loaded {
        if (!file.isFile) return Loaded.Absent
        val blob = try {
            file.readBytes()
        } catch (_: java.io.IOException) {
            return Loaded.Unavailable
        }
        val plain = try {
            kek.open(blob, AAD)
        } catch (e: CredentialCipherException) {
            return when (e.failure) {
                CipherFailure.BadBlob, CipherFailure.KeyDead -> Loaded.Lost
                CipherFailure.Transient, CipherFailure.Suspect -> Loaded.Unavailable
            }
        }
        if (plain.size != ID_BYTES + MirrorCipher.KEY_BYTES) return Loaded.Lost
        val id = plain.copyOfRange(0, ID_BYTES).toHex()
        return Loaded.Present(MirrorDataKey(id, plain.copyOfRange(ID_BYTES, plain.size)))
    }

    /** Mint and wrap a NEW data key, replacing any previous one (atomic rename). */
    @Synchronized
    fun create(): MirrorDataKey {
        val id = ByteArray(ID_BYTES).also(random::nextBytes)
        val key = ByteArray(MirrorCipher.KEY_BYTES).also(random::nextBytes)
        val sealed = kek.seal(id + key, AAD)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("could not write the mirror key file")
        }
        return MirrorDataKey(id.toHex(), key)
    }

    /** Forget the data key (rotation): the next [create] mints a new one. The KEK stays. */
    @Synchronized
    fun deleteDataKey() {
        file.delete()
        File(file.path + ".tmp").delete()
    }

    /** Logout / revocation (§8.3): the data key AND the Keystore key that wraps it are destroyed. */
    @Synchronized
    fun destroy() {
        deleteDataKey()
        kek.destroyKey()
    }

    companion object {
        /** The Keystore alias of the key-encryption key (SYNC_DESIGN §8.1). */
        const val KEK_ALIAS = "tether.mirror.aesgcm.v1"
        const val KEY_FILE = "mirror.key"
        private const val ID_BYTES = 16
        private val AAD = "tether.mirror.datakey.v1".toByteArray(Charsets.UTF_8)

        fun keystoreKek(): KeystoreCredentialKeySource = KeystoreCredentialKeySource(KEK_ALIAS)

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
