package com.tether.app.mirror

import com.tether.app.client.CipherFailure
import com.tether.app.client.CredentialCipher
import com.tether.app.client.CredentialCipherException
import com.tether.app.client.DataStoreSettings
import com.tether.app.client.KeystoreCredentialKeySource
import java.io.File
import java.security.SecureRandom

/** The mirror's data key: [bytes] seal every blob; [id] names it in the DB's `meta` (key check). */
class MirrorDataKey(val id: String, val bytes: ByteArray) {
    /** Zero the key bytes once a [MirrorCipher] holds them (best effort: the JVM may have copied them). */
    fun wipe() = bytes.fill(0)

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

    /**
     * Guards the key FILE only (a read, or a write + rename), never a Keystore call ([kek]),
     * so nothing that takes it can stall behind the Keystore (ta-hra R3). [destroy] does not
     * take it at all.
     */
    private val fileLock = Any()

    /** Writer thread. The Keystore unwrap runs outside [fileLock]. */
    fun load(): Loaded {
        val result = loadNow()
        when (result) {
            is Loaded.Present, Loaded.Absent -> clearSuspect()
            else -> Unit
        }
        return result
    }

    private fun loadNow(): Loaded {
        val blob = synchronized(fileLock) {
            if (!file.isFile) return Loaded.Absent
            try {
                file.readBytes()
            } catch (_: java.io.IOException) {
                return Loaded.Unavailable
            }
        }
        val plain = try {
            kek.open(blob, AAD)
        } catch (e: CredentialCipherException) {
            return when (e.failure) {
                CipherFailure.BadBlob, CipherFailure.KeyDead -> Loaded.Lost
                CipherFailure.Transient -> Loaded.Unavailable
                CipherFailure.Suspect -> if (noteSuspect()) Loaded.Lost else Loaded.Unavailable
            }
        }
        try {
            if (plain.size != ID_BYTES + MirrorCipher.KEY_BYTES) return Loaded.Lost
            val id = plain.copyOfRange(0, ID_BYTES).toHex()
            return Loaded.Present(MirrorDataKey(id, plain.copyOfRange(ID_BYTES, plain.size)))
        } finally {
            plain.fill(0)
        }
    }

    // This process already counted its Suspect failure (once per launch, like the credential store).
    private var suspectCounted = false

    /**
     * ta-705 (5): a Keystore key that answers Suspect (unexplained) on launch after launch must
     * not leave the mirror unavailable forever. Same rule as the credential store
     * (`DataStoreSettings.noteSuspectFailureLocked`, [SUSPECT_ROTATE_AFTER] consecutive launches):
     * count at most once per process, persist across launches, and at the threshold report the
     * key [Loaded.Lost] so the mirror deletes its DB and mints a new key. A successful load
     * ([clearSuspect]) or a fresh key resets the count. True = rotate now.
     */
    private fun noteSuspect(): Boolean {
        if (suspectCounted) return false
        suspectCounted = true
        val n = readCounter(suspectFile) + 1
        if (n >= SUSPECT_ROTATE_AFTER) {
            suspectFile.delete()
            return true
        }
        writeSmall(suspectFile, n.toString())
        return false
    }

    private fun clearSuspect() {
        if (suspectFile.exists()) suspectFile.delete()
    }

    private val suspectFile: File get() = File(file.path + SUSPECT_SUFFIX)
    private val writesFile: File get() = File(file.path + WRITES_SUFFIX)

    /**
     * ta-705 (1): blobs sealed under data key [id], persisted WITH the key (a sidecar next to the
     * key file, so the key file itself is unchanged and an old install's file stays readable),
     * not per DB file: deleting the DB (an origin switch, a corrupt file) does not reset the
     * 2^28 rotation budget while the same key stays in use. 0 for an unknown or other key.
     */
    fun writesFor(id: String): Long {
        val parts = try {
            synchronized(fileLock) { writesFile.takeIf { it.isFile }?.readText() }
        } catch (_: java.io.IOException) {
            null
        }?.split(':') ?: return 0L
        if (parts.size != 2 || parts[0] != id) return 0L
        return parts[1].toLongOrNull()?.takeIf { it > 0 } ?: 0L
    }

    /** Persist the blobs-sealed counter of key [id] (never lowers a higher stored value for it). */
    fun recordWrites(id: String, count: Long) {
        try {
            synchronized(fileLock) {
                if (writesFor(id) >= count) return
                writeSmall(writesFile, "$id:$count")
            }
        } catch (_: java.io.IOException) {
            // A counter that cannot be saved falls back to the DB's own meta value.
        }
    }

    private fun readCounter(f: File): Int = try {
        synchronized(fileLock) { f.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() } ?: 0
    } catch (_: java.io.IOException) {
        0
    }

    private fun writeSmall(f: File, text: String) {
        try {
            synchronized(fileLock) {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        } catch (_: java.io.IOException) {
            // Best effort: a count that is not saved only delays a rotation.
        }
    }

    /**
     * Writer thread. Mint and wrap a NEW data key, replacing any previous one (atomic rename).
     * The Keystore wrap runs outside [fileLock]. A [destroy] racing this call can be undone by
     * it (the file lands after the delete): the caller re-checks for a wipe and destroys the
     * key if one landed ([JournalMirror]'s wipe epoch, ta-hra R1).
     */
    fun create(): MirrorDataKey {
        val id = ByteArray(ID_BYTES).also(random::nextBytes)
        val key = ByteArray(MirrorCipher.KEY_BYTES).also(random::nextBytes)
        val plain = id + key
        val sealed = try {
            kek.seal(plain, AAD)
        } finally {
            plain.fill(0)
        }
        synchronized(fileLock) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("could not write the mirror key file")
            }
        }
        return MirrorDataKey(id.toHex(), key)
    }

    /**
     * Forget the data key (rotation): the next [create] mints a new one. The KEK stays. No lock:
     * an unlink is atomic, and it must never wait for a writer that is inside the Keystore.
     */
    fun deleteDataKey() {
        file.delete()
        File(file.path + ".tmp").delete()
        writesFile.delete()
        File(writesFile.path + ".tmp").delete()
        suspectFile.delete()
        File(suspectFile.path + ".tmp").delete()
    }

    /**
     * Logout / revocation (§8.3), from any thread, without waiting for anything (ta-hra R3):
     * the wrapped data key file goes FIRST, so every blob is unreadable from that instant even
     * if the process dies inside the Keystore call that follows; then the Keystore key that
     * wrapped it is destroyed, best effort (it wraps nothing on disk any more).
     */
    fun destroy() {
        try {
            deleteDataKey()
        } finally {
            // Best effort, and attempted even if the delete threw (I-3).
            runCatching { kek.destroyKey() }
        }
    }

    companion object {
        /** The Keystore alias of the key-encryption key (SYNC_DESIGN §8.1). */
        const val KEK_ALIAS = "tether.mirror.aesgcm.v1"
        const val KEY_FILE = "mirror.key"
        private const val WRITES_SUFFIX = ".writes"
        private const val SUSPECT_SUFFIX = ".suspect"

        /** Consecutive launches with a Suspect Keystore answer before the key is rotated (the credential store's own number). */
        const val SUSPECT_ROTATE_AFTER = DataStoreSettings.SUSPECT_ROTATE_AFTER
        private const val ID_BYTES = 16
        private val AAD = "tether.mirror.datakey.v1".toByteArray(Charsets.UTF_8)

        fun keystoreKek(): KeystoreCredentialKeySource = KeystoreCredentialKeySource(KEK_ALIAS)

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
