package com.tether.app.crash

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/** What the handler needs from the store: one write. */
fun interface CrashRecordStoreWriter {
    fun write(record: CrashRecord)
}

/**
 * ta-otgf: the one crash record, in one private file (the app passes `File(noBackupFilesDir,
 * [FILE_NAME])`: never backed up, never transferred). Pure JVM: plain file I/O, no coroutines, no
 * DataStore, nothing that needs the process to be healthy.
 *
 * A write goes to a unique temp file in the same directory and is renamed over the record (the
 * [com.tether.app.mirror.MirrorKeyStore] pattern), under a process-wide lock, so two threads
 * crashing at once leave one whole record and a reader never sees a torn one. [read] answers null
 * for a missing, truncated or foreign file.
 */
class CrashRecordStore(private val file: File) : CrashRecordStoreWriter {
    override fun write(record: CrashRecord) {
        val bytes = encode(record)
        synchronized(LOCK) {
            val dir = file.absoluteFile.parentFile ?: throw IOException("no directory for the crash record")
            dir.mkdirs()
            val tmp = File.createTempFile("last-crash", ".tmp", dir)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) throw IOException("could not write the crash record")
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    /** The record on disk, or null when there is none (or it is not one of ours, whole). */
    fun read(): CrashRecord? = try {
        val bytes = synchronized(LOCK) { file.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.readBytes() }
        bytes?.let(::decode)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Delete the record, and only the record. */
    fun clear() {
        synchronized(LOCK) { file.delete() }
    }

    companion object {
        /** The record's file name inside noBackupFilesDir (shared by the installer and the dialog's reader). */
        const val FILE_NAME = "last-crash.bin"

        private const val MAGIC = 0x54435231 // "TCR1"
        private const val END = 0x454E4421 // "END!"
        private const val MAX_FILE_BYTES = 512L * 1024
        private const val MAX_STRING_BYTES = 256 * 1024

        /** Every store in the process writes under this one lock. */
        private val LOCK = Any()

        internal fun encode(r: CrashRecord): ByteArray {
            val buffer = ByteArrayOutputStream(r.stack.length + 512)
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(r.timeMs)
                out.writeText(r.versionName)
                out.writeLong(r.versionCode)
                out.writeText(r.androidRelease)
                out.writeInt(r.androidSdk)
                out.writeText(r.thread)
                out.writeText(r.exceptionClass)
                out.writeBoolean(r.message != null)
                out.writeText(r.message ?: "")
                out.writeText(r.stack)
                out.writeInt(END)
            }
            return buffer.toByteArray()
        }

        internal fun decode(bytes: ByteArray): CrashRecord? = try {
            DataInputStream(bytes.inputStream()).use { input ->
                if (input.readInt() != MAGIC) return null
                val timeMs = input.readLong()
                val versionName = input.readText()
                val versionCode = input.readLong()
                val androidRelease = input.readText()
                val androidSdk = input.readInt()
                val thread = input.readText()
                val exceptionClass = input.readText()
                val hasMessage = input.readBoolean()
                val message = input.readText().takeIf { hasMessage }
                val stack = input.readText()
                if (input.readInt() != END || input.available() != 0) return null
                CrashRecord(timeMs, versionName, versionCode, androidRelease, androidSdk, thread, exceptionClass, message, stack)
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        }

        private fun DataOutputStream.writeText(text: String) {
            val raw = text.toByteArray(Charsets.UTF_8)
            writeInt(raw.size)
            write(raw)
        }

        private fun DataInputStream.readText(): String {
            val size = readInt()
            if (size < 0 || size > MAX_STRING_BYTES || size > available()) throw IOException("bad length")
            val raw = ByteArray(size)
            readFully(raw)
            return String(raw, Charsets.UTF_8)
        }
    }
}
