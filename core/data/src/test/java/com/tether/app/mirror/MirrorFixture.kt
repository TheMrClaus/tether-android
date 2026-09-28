package com.tether.app.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.AesGcmCredentialCipher
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
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val kekKeys = SoftwareKeySource()
    val keyFile = File(context.noBackupFilesDir, MirrorKeyStore.KEY_FILE)
    val factory = AndroidMirrorDbFactory(context)
    val now = AtomicLong(1_000_000)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    var mirror: JournalMirror = newMirror()
        private set

    fun keyStore() = MirrorKeyStore(keyFile, AesGcmCredentialCipher(kekKeys))

    private fun newMirror(version: String = reducerVersion) = JournalMirror(
        dbFactory = factory,
        keyStore = keyStore(),
        reducerVersion = version,
        scope = scope,
        clock = { now.get() },
        batchWindowMs = batchWindowMs,
        rotateAfterWrites = rotateAfterWrites,
        log = { logs.add(it) },
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
        runBlocking { mirror.abandon() }
        scope.cancel()
        for (name in factory.existing()) factory.delete(name)
        keyFile.delete()
    }

    companion object {
        fun obj(json: String): JsObj = JsCodec.parse(json) as JsObj
    }
}
