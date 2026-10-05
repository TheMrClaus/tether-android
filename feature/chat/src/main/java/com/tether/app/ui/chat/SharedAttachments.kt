package com.tether.app.ui.chat

import android.os.CancellationSignal
import com.tether.app.protocol.helpers.AttachmentDraft
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/*
 * T11.2: files another app shares into Tether (the system share sheet). The sender's URI grant lasts
 * only as long as the activity that received it, so each shared item is read at once, under the
 * intake's own rules (AttachmentUriPolicy, the provider-call deadlines, the per-file cap), into the
 * app's cache. What was read is handed to the SAME intake as a pick ([AttachmentIntake]) once the user
 * has chosen where it goes: the count, size, total and frame caps, the type sniffing, the picture
 * shrink and the flashes are the composer's, applied then against what that composer already holds.
 */

/**
 * One shared item as read when it arrived: its claimed name and type, the bytes in [file] (null: it
 * could not be read, which the intake reports as "Could not read"), and its size: the bytes read, or
 * a claim past the per-file cap when the read stopped there (the intake then reports it too large).
 */
class SharedFileSource(
    override val displayName: String?,
    override val reportedSize: Long?,
    override val declaredType: String?,
    val file: File?,
) : AttachmentSource {
    override fun open(): InputStream? = file?.takeIf { it.isFile }?.let(::FileInputStream)
}

object SharedAttachments {
    /**
     * Reads [sources] into files under [dir], one after another. Only the first
     * [AttachmentDraft.MAX_ATTACHMENTS] are read: the intake never reads past its count cap, so the
     * rest are kept unread (it flashes the count copy at the first of them). Null when [active]
     * turned false (the receiving activity went away): nothing is kept.
     */
    fun buffer(
        sources: List<AttachmentSource>,
        dir: File,
        active: () -> Boolean = { true },
        limits: ReadLimits = ReadLimits.DEFAULT,
    ): List<SharedFileSource>? {
        dir.mkdirs()
        val out = ArrayList<SharedFileSource>(sources.size)
        for ((index, source) in sources.withIndex()) {
            if (!active()) return null
            if (index >= AttachmentDraft.MAX_ATTACHMENTS) {
                out += SharedFileSource(null, null, null, null)
                continue
            }
            val signal = CancellationSignal()
            val info = when (val d = ProviderCalls.await(limits, active, abandon = { runCatching { signal.cancel() } }) { source.describe(signal) }) {
                is ProviderCalls.Outcome.Done -> d.value
                ProviderCalls.Outcome.Cancelled -> return null
                ProviderCalls.Outcome.Failed, ProviderCalls.Outcome.TimedOut -> {
                    out += SharedFileSource(null, null, null, null)
                    continue
                }
            }
            val described = object : AttachmentSource {
                override val displayName: String? get() = info.displayName
                override val reportedSize: Long? get() = info.reportedSize
                override val declaredType: String? get() = info.declaredType
                override fun open(): InputStream? = source.open()
                override fun open(signal: CancellationSignal): InputStream? = source.open(signal)
            }
            out += when (val read = readBounded(described, active = active, limits = limits)) {
                is BoundedRead.Bytes -> {
                    val file = File(dir, "item-$index")
                    try {
                        file.writeBytes(read.bytes)
                        SharedFileSource(info.displayName, read.bytes.size.toLong(), info.declaredType, file)
                    } catch (_: java.io.IOException) {
                        file.delete()
                        SharedFileSource(info.displayName, null, info.declaredType, null)
                    }
                }
                BoundedRead.TooLarge -> SharedFileSource(info.displayName, AttachmentDraft.MAX_ATTACHMENT_BYTES.toLong() + 1, info.declaredType, null)
                BoundedRead.Cancelled -> return null
                BoundedRead.Failed, BoundedRead.TimedOut -> SharedFileSource(info.displayName, null, info.declaredType, null)
            }
        }
        return out
    }
}
