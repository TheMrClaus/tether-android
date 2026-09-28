package com.tether.app.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.AesGcmCredentialCipher
import com.tether.app.client.CredentialCipher
import com.tether.app.client.SoftwareKeySource
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * A mirror over Robolectric's real SQLite in the app's `databases/` dir, a software KEK
 * standing in for the Keystore (it survives [restart], as the Keystore survives process death),
 * and a manual clock.
 */
class MirrorFixture(
    val reducerVersion: String = "test-reducer",
    val rotateAfterWrites: Long = 1L shl 28,
    val batchWindowMs: Long = 100,
    val maxBlobPlaintextBytes: Int = 8 * 1024 * 1024,
    val maxStoredBlobBytes: Int = 1536 * 1024,
    val maxSessions: Int = 500,
    val maxOriginBytes: Long = 200L * 1024 * 1024,
    /** Extra log observer (it may throw, to inject a failure into the writer). */
    val onLog: (String) -> Unit = {},
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val kekKeys = SoftwareKeySource()
    val keyFile = File(context.noBackupFilesDir, MirrorKeyStore.KEY_FILE)
    /**
     * Test seams (ta-hra), null = pass through: each runs on the calling thread right before the
     * KEK seals / opens / destroys, or the factory opens / deletes a DB, so a test can hold the
     * writer inside a "Keystore call" or pick the instant of a process death.
     */
    @Volatile var beforeKekSeal: (() -> Unit)? = null
    @Volatile var beforeKekOpen: (() -> Unit)? = null
    @Volatile var beforeKekDestroy: (() -> Unit)? = null
    @Volatile var beforeDbOpen: ((String) -> Unit)? = null
    @Volatile var beforeDbDelete: ((String) -> Unit)? = null
    private val androidFactory = AndroidMirrorDbFactory(context)
    val factory: MirrorDbFactory = object : MirrorDbFactory {
        override fun open(name: String): MirrorDatabase {
            beforeDbOpen?.invoke(name)
            return androidFactory.open(name)
        }

        override fun existing(): List<String> = androidFactory.existing()

        override fun delete(name: String) {
            beforeDbDelete?.invoke(name)
            androidFactory.delete(name)
        }
    }
    val now = AtomicLong(1_000_000)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    var mirror: JournalMirror = newMirror()
        private set

    fun keyStore() = MirrorKeyStore(keyFile, HookedKek(AesGcmCredentialCipher(kekKeys)))

    private inner class HookedKek(private val inner: CredentialCipher) : CredentialCipher {
        override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
            beforeKekSeal?.invoke()
            return inner.seal(plaintext, aad)
        }

        override fun open(blob: ByteArray, aad: ByteArray): ByteArray {
            beforeKekOpen?.invoke()
            return inner.open(blob, aad)
        }

        override fun destroyKey() {
            beforeKekDestroy?.invoke()
            inner.destroyKey()
        }
    }

    private fun newMirror(version: String = reducerVersion) = JournalMirror(
        dbFactory = factory,
        keyStore = keyStore(),
        reducerVersion = version,
        scope = scope,
        clock = { now.get() },
        batchWindowMs = batchWindowMs,
        rotateAfterWrites = rotateAfterWrites,
        log = {
            logs.add(it)
            onLog(it)
        },
        maxBlobPlaintextBytes = maxBlobPlaintextBytes,
        maxStoredBlobBytes = maxStoredBlobBytes,
        maxSessions = maxSessions,
        maxOriginBytes = maxOriginBytes,
    )

    /** Process death (nothing uncommitted survives), then a new process over the same files. */
    fun restart(version: String = reducerVersion): JournalMirror {
        runBlocking { mirror.abandon() }
        mirror = newMirror(version)
        return mirror
    }

    fun dbFile(origin: String): File = context.getDatabasePath(JournalMirror.dbName(JournalMirror.originKeyOf(origin)))

    /** Every byte of the DB files (main + WAL), for at-rest checks. */
    fun dbBytes(origin: String): ByteArray {
        val main = dbFile(origin)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm"))
            .filter { it.isFile }.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
    }

    fun close() {
        beforeKekSeal = null
        beforeKekOpen = null
        beforeKekDestroy = null
        beforeDbOpen = null
        beforeDbDelete = null
        runBlocking { mirror.abandon() }
        scope.cancel()
        for (name in factory.existing()) factory.delete(name)
        keyFile.delete()
    }

    companion object {
        fun obj(json: String): JsObj = JsCodec.parse(json) as JsObj
    }
}
