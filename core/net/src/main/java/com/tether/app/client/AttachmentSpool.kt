package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.helpers.AttachmentDraft
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** One spooled attachment: what the persisted pending record keeps INSTEAD of the bytes. */
data class SpoolRef(val name: String, val mediaType: String, val file: String, val bytes: Long)

/**
 * ta-delivery A1: app-private, backup-excluded storage for the bytes of a pending message's
 * attachments, so a message waiting to be delivered survives process death (it used to be dropped:
 * `toPersistable` leaves every record that carries attachments out).
 *
 * Layout: `<root>/<recordKey>/<index>.bin`, the DECODED bytes (so a later upload step can stream a
 * file as it is). The record in the persisted slot carries [SpoolRef]s; a file is written
 * (temp + fsync + atomic rename) BEFORE the slot that names it, so a crash leaves either an
 * unreferenced file (swept) or a complete one.
 *
 * Caps follow what the server takes today (AttachmentDraft: 10 files, 9 MB each, 18 MB a message);
 * [maxTotalBytes] bounds the whole spool (default four full messages). A record that does not fit,
 * or that cannot be written, is simply not spooled: it stays in memory and is redelivered for the
 * life of the process, exactly as before. The spool NEVER refuses a send.
 *
 * The spool owns no policy about which records are alive: callers [delete] on ack/discard and
 * [sweep] with the set of keys any record still references.
 */
class AttachmentSpool(
    private val root: File,
    private val maxFileBytes: Long = AttachmentDraft.MAX_ATTACHMENT_BYTES.toLong(),
    private val maxRecordFiles: Int = AttachmentDraft.MAX_ATTACHMENTS,
    private val maxRecordBytes: Long = AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES.toLong(),
    private val maxTotalBytes: Long = 4 * AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES.toLong(),
) {
    // key -> refs already on disk: a persist runs after every store change; the bytes are written once.
    private val known = HashMap<String, List<SpoolRef>>()

    /**
     * The decoded bytes [put] would write for [key], or null when it would not spool them (over a cap, not
     * canonical base64, a bad key). The ONE rule behind [put] and [wouldHold].
     */
    private fun acceptable(key: String, attachments: List<Attachment>): List<ByteArray>? {
        if (!validKey(key)) return null
        if (attachments.isEmpty() || attachments.size > maxRecordFiles) return null
        val raws = ArrayList<ByteArray>(attachments.size)
        var total = 0L
        for (attachment in attachments) {
            if (attachment.data.length.toLong() * 3 / 4 > maxFileBytes + 3) return null
            val raw = try {
                Base64.getDecoder().decode(attachment.data)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (raw.size > maxFileBytes) return null
            // Only a lossless round trip is spooled: what is sent after a restart is the same string.
            if (Base64.getEncoder().encodeToString(raw) != attachment.data) return null
            total += raw.size
            raws += raw
        }
        if (total > maxRecordBytes || usedBytesExcept(key) + total > maxTotalBytes) return null
        return raws
    }

    /** Dry run of [put]: would the spool accept [key]'s bytes now? Writes nothing (ta-delivery §4.1d A.1). */
    @Synchronized
    fun wouldHold(key: String, attachments: List<Attachment>): Boolean =
        validKey(key) && (known.containsKey(key) || acceptable(key, attachments) != null)

    /** Writes [attachments] for [key] (once); null = not spooled (over a cap, not canonical base64, or an I/O error). */
    @Synchronized
    fun put(key: String, attachments: List<Attachment>): List<SpoolRef>? {
        if (!validKey(key)) return null
        known[key]?.let { return it }
        val raws = acceptable(key, attachments) ?: return null
        val dir = File(root, key)
        return try {
            dir.deleteRecursively()
            if (!dir.mkdirs() && !dir.isDirectory) throw IOException("spool dir")
            val refs = attachments.mapIndexed { index, attachment ->
                val name = "$index.bin"
                val tmp = File(dir, "$name.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(raws[index])
                    out.fd.sync()
                }
                Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
                SpoolRef(attachment.name, attachment.mediaType, name, raws[index].size.toLong())
            }
            known[key] = refs
            refs
        } catch (_: IOException) {
            dir.deleteRecursively()
            null
        }
    }

    /** The attachments [refs] name for [key], or null when any file is missing or its size is not the recorded one. */
    @Synchronized
    fun load(key: String, refs: List<SpoolRef>): List<Attachment>? {
        if (!validKey(key) || refs.isEmpty()) return null
        val encoder = Base64.getEncoder()
        val out = ArrayList<Attachment>(refs.size)
        for (ref in refs) {
            if (!FILE_NAME.matches(ref.file)) return null
            val file = File(File(root, key), ref.file)
            if (!file.isFile || file.length() != ref.bytes) return null
            val raw = try {
                file.readBytes()
            } catch (_: IOException) {
                return null
            }
            out += Attachment(ref.name, ref.mediaType, encoder.encodeToString(raw))
        }
        known[key] = refs
        return out
    }

    /** Removes [key]'s files (the record was delivered, discarded or expired). */
    @Synchronized
    fun delete(key: String) {
        known.remove(key)
        if (validKey(key)) File(root, key).deleteRecursively()
    }

    /** True while the spool holds anything (a cheap test before a [sweep]). */
    @Synchronized
    fun isEmpty(): Boolean = root.list().isNullOrEmpty()

    /**
     * Removes every record directory whose key is not in [referenced], stray files, and leftover
     * temp files. Returns how many entries were removed.
     */
    @Synchronized
    fun sweep(referenced: Set<String>): Int {
        var removed = 0
        for (entry in root.listFiles().orEmpty()) {
            if (!entry.isDirectory || entry.name !in referenced) {
                entry.deleteRecursively()
                known.remove(entry.name)
                removed++
                continue
            }
            for (file in entry.listFiles().orEmpty()) {
                if (file.name.endsWith(".tmp") && file.delete()) removed++
            }
        }
        known.keys.retainAll(referenced)
        return removed
    }

    private fun usedBytesExcept(key: String): Long = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name != key }
        .sumOf { dir -> dir.listFiles().orEmpty().sumOf { it.length() } }

    private fun validKey(key: String) = KEY.matches(key)

    private companion object {
        val KEY = Regex("[A-Za-z0-9-]{1,64}")
        val FILE_NAME = Regex("[0-9]{1,3}\\.bin")
    }
}
