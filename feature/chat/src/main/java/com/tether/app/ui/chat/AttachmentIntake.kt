package com.tether.app.ui.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import com.tether.app.client.AttachmentFrame
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.helpers.AttachmentDraft
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * T7.4: staging files and pictures for a send (tether components/chat-view.tsx addFiles :2531-2566,
 * pasteImageFromClipboard :2596-2622, lib/attachment-draft.ts). Everything a picker, the Photo
 * Picker or the clipboard hands over is UNTRUSTED content from another app:
 * - its size is read in bounded chunks and the read aborts at the per-file cap, whatever the
 *   provider claimed (a missing or lying size never makes the app read everything first);
 * - its type is decided from its first bytes for the formats the server sends to the model
 *   natively, never from the provider's claim alone;
 * - its display name is cleaned before it goes on the wire and is drawn through the code rule;
 *   it is never used as a path.
 */

/** One item a row handed over (a picked document or picture, a clipboard picture). */
interface AttachmentSource {
    /** The provider's display name (untrusted; null: none). */
    val displayName: String?

    /** The provider's claimed size in bytes (untrusted; null: unknown). */
    val reportedSize: Long?

    /** The provider's claimed MIME type (untrusted; null: none). */
    val declaredType: String?

    /** The bytes, or null when the provider cannot open them. */
    fun open(): InputStream?
}

/** A document, a Photo Picker picture or a clipboard picture, read through the [resolver]. */
class ContentUriSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val nameOverride: String? = null,
    private val typeOverride: String? = null,
) : AttachmentSource {
    private val columns: Pair<String?, Long?> by lazy {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                }
            }
        } catch (_: RuntimeException) {
            // A provider that cannot answer: no name, unknown size (the read still bounds it).
        }
        name to size
    }

    override val displayName: String? get() = nameOverride ?: columns.first
    override val reportedSize: Long? get() = columns.second?.takeIf { it >= 0 }
    override val declaredType: String? get() = typeOverride ?: runCatching { resolver.getType(uri) }.getOrNull()
    override fun open(): InputStream? = resolver.openInputStream(uri)
}

/** The web's flash copy for staging (chat-view.tsx), plus the native frame bound's. */
object AttachmentCopy {
    val COUNT: String = "You can attach up to ${AttachmentDraft.MAX_ATTACHMENTS} files at once."
    fun tooLarge(name: String): String = "\"$name\" is too large (max ${size(AttachmentDraft.MAX_ATTACHMENT_BYTES.toLong())})."
    val TOTAL: String = "Attachments exceed the total size limit (${size(AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES.toLong())})."
    fun unreadable(name: String): String = "Could not read \"$name\"."

    /** T7.4 (native, logged divergence): the encoded message would not fit the link's frame bound. */
    val FRAME: String = "Attachments exceed what one message can carry (${size(AttachmentFrame.STAGING_BUDGET_BYTES)} once encoded). Send the rest in another message."

    const val NO_CLIPBOARD_IMAGE = "No image found on the clipboard."
    const val CLIPBOARD_UNREADABLE = "Couldn't read the clipboard — grant clipboard permission and try again."

    fun size(bytes: Long): String = AttachmentDraft.humanSize(bytes.toDouble())
}

/** Wire names for untrusted display names. */
object AttachmentNames {
    /** The web's fallback (`file.name || "attachment"`). */
    const val FALLBACK = "attachment"

    /** A name longer than this (UTF-8 bytes) is shortened; the server allows 512 (protocol-validate). */
    const val MAX_BYTES = 255

    /**
     * The name that goes on the wire (and so into every device's transcript): the last path
     * segment only (either separator); no C0/C1 controls, DEL, FORMAT characters (every bidi
     * control, the zero-widths, tags), line or paragraph separators or lone surrogates; trimmed;
     * at most [MAX_BYTES] UTF-8 bytes, keeping a short extension; [FALLBACK] when nothing is left
     * or only dots are. The server reduces it to a safe basename again before any use on disk
     * (engines/attachments.mjs safeBaseName); the app never uses it as a path at all.
     */
    fun wire(displayName: String?): String {
        val raw = displayName ?: return FALLBACK
        val segment = raw.substring(max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1)
        val out = StringBuilder(minOf(segment.length, 1024))
        var i = 0
        while (i < segment.length && out.length < 4096) {
            val cp = segment.codePointAt(i)
            val n = Character.charCount(cp)
            val drop = Character.isISOControl(cp) ||
                Character.getType(cp) == Character.FORMAT.toInt() ||
                Character.getType(cp) == Character.SURROGATE.toInt() ||
                cp == 0x2028 || cp == 0x2029
            if (!drop) out.appendCodePoint(cp)
            i += n
        }
        var name = out.toString().trim()
        if (name.isEmpty() || name.all { it == '.' }) return FALLBACK
        if (AttachmentFrame.utf8Length(name) > MAX_BYTES) name = shorten(name)
        return name.ifEmpty { FALLBACK }
    }

    private fun shorten(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val budget = MAX_BYTES - AttachmentFrame.utf8Length(ext).toInt()
        val stem = StringBuilder()
        var used = 0
        var i = 0
        val body = name.substring(0, name.length - ext.length)
        while (i < body.length) {
            val cp = body.codePointAt(i)
            val bytes = AttachmentFrame.utf8Length(String(Character.toChars(cp))).toInt()
            if (used + bytes > budget) break
            stem.appendCodePoint(cp)
            used += bytes
            i += Character.charCount(cp)
        }
        return stem.toString().trimEnd() + ext
    }
}

/** Types: the formats the server sends to the model natively are decided from their bytes. */
object AttachmentTypes {
    const val OCTET = "application/octet-stream"

    /** engines/attachments.mjs INLINE_IMAGE_TYPES + INLINE_DOCUMENT_TYPES. */
    val NATIVE: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf")

    private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$")

    /** The native format [head] starts with (PNG, JPEG, GIF, WebP, PDF), or null. */
    fun sniff(head: ByteArray): String? {
        if (MediaMagic.matches(head, "image/png")) return "image/png"
        if (MediaMagic.matches(head, "image/jpeg")) return "image/jpeg"
        if (MediaMagic.matches(head, "image/gif")) return "image/gif"
        if (MediaMagic.matches(head, "image/webp")) return "image/webp"
        if (head.size >= 5 && String(head, 0, 5, Charsets.US_ASCII) == "%PDF-") return "application/pdf"
        return null
    }

    /**
     * The type to send (the web sends `file.type || "application/octet-stream"`, the browser's
     * type from the extension). Here: the sniffed native format when the bytes are one; otherwise
     * the provider's claim when it is a well-formed type that is NOT one of the native formats (a
     * claim of PNG, JPEG, GIF, WebP or PDF the bytes do not back would reach the model as a broken
     * image or document, so it goes as a plain file instead); otherwise [OCTET].
     */
    fun resolve(bytes: ByteArray, declared: String?): String {
        sniff(bytes.copyOf(minOf(bytes.size, 16)))?.let { return it }
        val claim = declared?.substringBefore(';')?.trim()?.lowercase() ?: return OCTET
        if (!MIME.matches(claim) || claim in NATIVE) return OCTET
        return claim
    }
}

/** A bounded read's outcome. */
sealed interface BoundedRead {
    class Bytes(val bytes: ByteArray) : BoundedRead
    data object TooLarge : BoundedRead
    data object Failed : BoundedRead
    data object Cancelled : BoundedRead
}

/**
 * Read [source] in chunks, aborting as soon as more than [max] bytes arrive (the provider's size
 * claim only sizes the first buffer; it is never trusted to bound the read), or as soon as [active]
 * says the caller went away (a cancelled pick stops reading at the next chunk).
 */
fun readBounded(
    source: AttachmentSource,
    max: Long = AttachmentDraft.MAX_ATTACHMENT_BYTES.toLong(),
    chunk: Int = 64 * 1024,
    active: () -> Boolean = { true },
): BoundedRead {
    val claimed = source.reportedSize
    if (claimed != null && claimed > max) return BoundedRead.TooLarge
    return try {
        val input = source.open() ?: return BoundedRead.Failed
        input.use { stream ->
            val out = ByteArrayOutputStream(((claimed ?: chunk.toLong()).coerceIn(1, max.coerceAtMost(Int.MAX_VALUE.toLong()))).toInt())
            val buffer = ByteArray(chunk)
            var total = 0L
            while (true) {
                if (!active()) return BoundedRead.Cancelled
                val n = stream.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) return BoundedRead.TooLarge
                out.write(buffer, 0, n)
            }
            if (total == 0L) BoundedRead.Failed else BoundedRead.Bytes(out.toByteArray())
        }
    } catch (_: OutOfMemoryError) {
        BoundedRead.TooLarge
    } catch (_: Exception) {
        BoundedRead.Failed
    }
}

/**
 * lib/attachment-draft.ts prepareAttachmentData (issue #135): a large JPEG, PNG or WebP is
 * downscaled to a long edge of [LONG_EDGE] px and re-encoded as JPEG at quality [QUALITY] before it
 * is base64-encoded. Anything else, a picture already within the edge, a re-encode that is not
 * smaller, and any decode or encode failure pass the original bytes through unchanged, exactly as
 * there. Native bounds the web does not need: the decode is sampled down first (never the full
 * picture in memory), a header past [MediaLimits.MAX_IMAGE_PIXELS] is passed through undecoded,
 * and the EXIF orientation is applied (the browser's createImageBitmap does, and the re-encode
 * drops the EXIF tag).
 */
object ImageShrink {
    const val LONG_EDGE = 1568
    const val QUALITY = 80
    val TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

    class Prepared(val bytes: ByteArray, val mediaType: String)

    fun prepare(bytes: ByteArray, mediaType: String): Prepared {
        val passthrough = Prepared(bytes, mediaType)
        if (mediaType !in TYPES) return passthrough
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return passthrough
            if (rawW.toLong() * rawH > MediaLimits.MAX_IMAGE_PIXELS) return passthrough
            val rotation = if (mediaType == "image/jpeg") exifRotation(bytes) else 0
            val quarter = rotation == 90 || rotation == 270
            val width = if (quarter) rawH else rawW
            val height = if (quarter) rawW else rawH
            val longEdge = max(width, height)
            if (longEdge <= LONG_EDGE) return passthrough
            val scale = LONG_EDGE.toDouble() / longEdge
            val targetW = max(1, (width * scale).roundToInt())
            val targetH = max(1, (height * scale).roundToInt())
            // Sample down by powers of two while the picture stays at least the target size.
            var sample = 1
            while (max(rawW, rawH) / (sample * 2) >= LONG_EDGE) sample *= 2
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return passthrough
            val matrix = Matrix()
            val rotW = if (quarter) targetH else targetW
            val rotH = if (quarter) targetW else targetH
            matrix.postScale(rotW.toFloat() / decoded.width, rotH.toFloat() / decoded.height)
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            scaled = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return passthrough
            val jpeg = out.toByteArray()
            if (jpeg.isEmpty() || jpeg.size >= bytes.size) passthrough else Prepared(jpeg, "image/jpeg")
        } catch (_: OutOfMemoryError) {
            passthrough
        } catch (_: RuntimeException) {
            passthrough
        } finally {
            if (scaled !== decoded) scaled?.recycle()
            decoded?.recycle()
        }
    }

    /** The clockwise rotation a JPEG's EXIF orientation asks for (0 when absent or unreadable). */
    private fun exifRotation(bytes: ByteArray): Int = try {
        val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}

/**
 * chat-view.tsx addFiles (:2531-2566), with the native reading rules above: per source, in order,
 * the count cap (flash, stop), the per-file cap (flash, skip), the total cap (flash, stop), then the
 * read and the shrink ("Could not read", skip), then the native frame bound (flash, stop). A flash
 * replaces the one before (the web's flash()), so the composer shows the LAST of [flashes].
 */
object AttachmentIntake {
    class Result(val added: List<StagedAttachment>, val flashes: List<String>)

    fun intake(
        sources: List<AttachmentSource>,
        existing: List<StagedAttachment>,
        newId: () -> Long,
        prepare: (ByteArray, String) -> ImageShrink.Prepared = ImageShrink::prepare,
        active: () -> Boolean = { true },
    ): Result {
        val added = ArrayList<StagedAttachment>()
        val flashes = ArrayList<String>()
        var total = existing.sumOf { it.sizeBytes }
        var wire = AttachmentFrame.wireBytes(existing.map { it.attachment })
        for (source in sources) {
            if (!active()) return Result(emptyList(), emptyList())
            if (existing.size + added.size >= AttachmentDraft.MAX_ATTACHMENTS) {
                flashes += AttachmentCopy.COUNT
                break
            }
            val name = AttachmentNames.wire(source.displayName)
            val claimed = source.reportedSize
            if (claimed != null && claimed > AttachmentDraft.MAX_ATTACHMENT_BYTES) {
                flashes += AttachmentCopy.tooLarge(name)
                continue
            }
            if (claimed != null && total + claimed > AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES) {
                flashes += AttachmentCopy.TOTAL
                break
            }
            val bytes = when (val read = readBounded(source, active = active)) {
                is BoundedRead.Bytes -> read.bytes
                BoundedRead.Cancelled -> return Result(emptyList(), emptyList())
                BoundedRead.TooLarge -> {
                    flashes += AttachmentCopy.tooLarge(name)
                    continue
                }
                BoundedRead.Failed -> {
                    flashes += AttachmentCopy.unreadable(name)
                    continue
                }
            }
            // The size the provider did not (or did not truthfully) report: the web's file.size check.
            if (claimed == null || claimed != bytes.size.toLong()) {
                if (total + bytes.size > AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES) {
                    flashes += AttachmentCopy.TOTAL
                    break
                }
            }
            val attachment: Attachment
            val size: Long
            try {
                val prepared = prepare(bytes, AttachmentTypes.resolve(bytes, source.declaredType))
                attachment = Attachment(
                    name = name,
                    mediaType = prepared.mediaType.ifEmpty { AttachmentTypes.OCTET },
                    data = java.util.Base64.getEncoder().encodeToString(prepared.bytes),
                )
                size = prepared.bytes.size.toLong()
            } catch (_: OutOfMemoryError) {
                flashes += AttachmentCopy.unreadable(name)
                continue
            } catch (_: RuntimeException) {
                flashes += AttachmentCopy.unreadable(name)
                continue
            }
            val adds = AttachmentFrame.wireBytes(attachment)
            if (wire + adds > AttachmentFrame.STAGING_BUDGET_BYTES) {
                flashes += AttachmentCopy.FRAME
                break
            }
            added += StagedAttachment(newId(), attachment, size)
            total += size
            wire += adds
        }
        return Result(added, flashes)
    }
}

/**
 * pasteImageFromClipboard (chat-view.tsx:2596-2622): every image the clipboard offers is staged
 * like a picked file, named `pasted-image.<subtype>` (the subtype before any `+`, "png" when none).
 * Text on the clipboard is not an attachment (the web's row pastes images only).
 */
object ClipboardImages {
    /** The clip's image items, as sources; null when the clipboard could not be read. */
    fun sources(clip: android.content.ClipData?, resolver: ContentResolver): List<AttachmentSource> {
        if (clip == null) return emptyList()
        val found = ArrayList<AttachmentSource>()
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i)?.uri ?: continue
            val type = runCatching { resolver.getType(uri) }.getOrNull()?.lowercase()
                ?: (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it).lowercase() }.firstOrNull { it.startsWith("image/") }
            if (type == null || !type.startsWith("image/")) continue
            found += ContentUriSource(resolver, uri, nameOverride = pastedName(type), typeOverride = type)
        }
        return found
    }

    fun pastedName(imageType: String): String {
        val ext = imageType.substringAfter('/', "").substringBefore('+').ifEmpty { "png" }
        return "pasted-image.$ext"
    }
}
