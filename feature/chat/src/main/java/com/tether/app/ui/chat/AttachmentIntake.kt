package com.tether.app.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.tether.app.client.AttachmentFrame
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.helpers.AttachmentDraft
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
 *   it is never used as a path;
 * - (r2, M1) only a `content://` URI of ANOTHER app's provider is ever read ([AttachmentUriPolicy]):
 *   never a `file://` URI (opened inside this process, with its access to the app's own files) and
 *   never one of the app's own providers;
 * - (r2 L3, r3 F1) every call into the provider (its query, its type, its open, its reads) runs on
 *   a provider-call thread ([ProviderCalls]) that the caller waits for with deadlines and with the
 *   pick's cancellation: a provider that stalls anywhere is walked away from, and never holds the
 *   stager.
 */

/** One item a row handed over (a picked document or picture, a clipboard picture). */
interface AttachmentSource {
    /** The provider's display name (untrusted; null: none). */
    val displayName: String?

    /** The provider's claimed size in bytes (untrusted; null: unknown). */
    val reportedSize: Long?

    /** The provider's claimed MIME type (untrusted; null: none). */
    val declaredType: String?

    /**
     * A pasted picture (the clipboard row: images only). It is offered only when its claimed type
     * is an image, and kept only when its BYTES are a picture ([AttachmentTypes.sniff]), whatever
     * the clip claimed, and is named for what they are.
     */
    val imagesOnly: Boolean get() = false

    /**
     * F1 (r3): the provider's claims (name, size, type), asked once. The intake calls this on a
     * provider-call thread ([ProviderCalls]), never its own: a provider that stalls answering is
     * walked away from, and [signal] is cancelled then (a query that honours it stops).
     */
    fun describe(signal: CancellationSignal): SourceInfo = SourceInfo(displayName, reportedSize, declaredType)

    /** The bytes, or null when the provider cannot open them. */
    fun open(): InputStream?

    /** [open], ended early when [signal] is cancelled (a provider that honours it stops opening). */
    fun open(signal: CancellationSignal): InputStream? = open()
}

/** What a provider claims about one source ([AttachmentSource.describe]); all of it untrusted. */
class SourceInfo(val displayName: String?, val reportedSize: Long?, val declaredType: String?)

/**
 * M1 (T7.4 r2): the only URIs an attachment is ever read from. Everything a picker result or the
 * clipboard hands over names content chosen by ANOTHER app, which may be hostile:
 * - the scheme must be `content`: a `file://` URI would be opened inside this process, with its
 *   access to the app's own private files (the drafts store holds every paired server's drafts);
 * - its authority must resolve to an installed provider (one that does not is refused, fail
 *   closed), and that provider must not be one of this app's own: a URI naming one of them would
 *   have the app attach its own data. A user prefix (`10@media`) is looked up without it, so an
 *   own provider under another user's prefix is refused too.
 * Known limit (fails closed, r3): the lookup is made in THIS profile, so a cross-profile pick
 * (`content://10@authority`) whose provider is installed only in the other profile does not
 * resolve here and is refused ("Could not read").
 * [providerPackage] answers the package that owns an authority (null: none resolves).
 */
class AttachmentUriPolicy(private val ownPackage: String, private val providerPackage: (authority: String) -> String?) {
    fun allows(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority?.substringAfterLast('@')
        if (authority.isNullOrEmpty()) return false
        val owner = try {
            providerPackage(authority)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        return owner != ownPackage
    }

    companion object {
        /** The installed providers as the package manager resolves them, against this app's package. */
        fun of(context: Context): AttachmentUriPolicy {
            val pm = context.packageManager
            return AttachmentUriPolicy(context.packageName) { authority -> pm.resolveContentProvider(authority, 0)?.packageName }
        }
    }
}

/**
 * A document, a Photo Picker picture or a clipboard picture, read through the [resolver]. Nothing
 * about a URI the [policy] refuses is asked of anyone (no name, size or type query, no open): it
 * reads as unopenable ("Could not read"). A pasted picture ([imagesOnly]) is typed by its provider,
 * else by the clip's [clipType], and named `pasted-image.<subtype>` (no query).
 */
class ContentUriSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val policy: AttachmentUriPolicy,
    private val nameOverride: String? = null,
    private val typeOverride: String? = null,
    override val imagesOnly: Boolean = false,
    private val clipType: String? = null,
) : AttachmentSource {
    private val allowed: Boolean by lazy { policy.allows(uri) }

    @Volatile private var described: SourceInfo? = null

    override fun describe(signal: CancellationSignal): SourceInfo {
        described?.let { return it }
        if (!allowed) return SourceInfo(nameOverride, null, typeOverride).also { described = it }
        var name: String? = null
        var size: Long? = null
        if (!imagesOnly && nameOverride == null) {
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { name = cursor.getString(it) }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                    }
                }
            } catch (_: RuntimeException) {
                // A provider that cannot answer (or a cancelled query): no name, unknown size (the read still bounds it).
            }
        }
        val type = (typeOverride ?: runCatching { resolver.getType(uri) }.getOrNull() ?: clipType)?.lowercase()
        if (imagesOnly) name = ClipboardImages.pastedName(type?.takeIf { it.startsWith("image/") } ?: "image/png")
        return SourceInfo(nameOverride ?: name, size?.takeIf { it >= 0 }, type).also { described = it }
    }

    // Direct reads of the claims ask the provider on the caller's thread: the intake never does.
    override val displayName: String? get() = describe(CancellationSignal()).displayName
    override val reportedSize: Long? get() = describe(CancellationSignal()).reportedSize
    override val declaredType: String? get() = describe(CancellationSignal()).declaredType

    override fun open(): InputStream? = open(CancellationSignal())

    // ContentResolver.openInputStream is this for a content URI, without the signal.
    override fun open(signal: CancellationSignal): InputStream? =
        if (allowed) resolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream() else null
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

    /** ta-coik.3: the "Take photo" row found no camera app to take the picture. */
    const val CAMERA_UNAVAILABLE = "No camera app is available to take a photo."

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

    /** protocol-validate.mjs LIMITS.ATTACHMENT_MEDIA_TYPE_BYTES: a longer type fails the whole send. */
    const val MAX_MEDIA_TYPE_CHARS = 128

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
     * the provider's claim when it is a well-formed type of at most [MAX_MEDIA_TYPE_CHARS]
     * characters (the server's bound; ASCII by the pattern) that is NOT one of the native formats
     * (a claim of PNG, JPEG, GIF, WebP or PDF the bytes do not back would reach the model as a
     * broken image or document, so it goes as a plain file instead); otherwise [OCTET].
     */
    fun resolve(bytes: ByteArray, declared: String?): String {
        sniff(bytes.copyOf(minOf(bytes.size, 16)))?.let { return it }
        val claim = declared?.substringBefore(';')?.trim()?.lowercase() ?: return OCTET
        if (claim.length > MAX_MEDIA_TYPE_CHARS || !MIME.matches(claim) || claim in NATIVE) return OCTET
        return claim
    }
}

/** A bounded read's outcome. */
sealed interface BoundedRead {
    class Bytes(val bytes: ByteArray) : BoundedRead
    data object TooLarge : BoundedRead
    data object Failed : BoundedRead
    data object Cancelled : BoundedRead

    /** L3: the provider stalled (no bytes for [ReadLimits.idleMs]) or took past [ReadLimits.totalMs]. */
    data object TimedOut : BoundedRead
}

/**
 * L3 (T7.4 r2): how long one provider call may take. A read that delivers nothing for [idleMs], or
 * any call not done after [totalMs], is walked away from (a picked cloud file may be slow; a
 * provider that never answers must not hold the stager). [pollMs] is how often the waiting caller
 * looks at its deadlines and at the pick's cancellation.
 */
class ReadLimits(val idleMs: Long, val totalMs: Long, val pollMs: Long = 100) {
    companion object {
        val DEFAULT = ReadLimits(idleMs = 30_000, totalMs = 120_000)
    }
}

/**
 * F1 (T7.4 r3): every call into another app's provider (its query and type lookup, its open, its
 * reads) runs here, on at most [MAX_THREADS] daemon threads. The caller waits for it itself, looking
 * every [ReadLimits.pollMs] at its deadlines and at the pick's cancellation; past them it walks away:
 * `abandon` cancels the call's signal and closes what the call opened (which unblocks a read), the
 * call's late result is discarded, and the caller (the stager, and its lock) goes on at once. A
 * provider that never returns keeps its thread (a Binder call cannot be interrupted), never the
 * stager; with every thread stuck, a new call is refused at once ("Could not read").
 */
internal object ProviderCalls {
    const val MAX_THREADS = 8

    private val pool = ThreadPoolExecutor(0, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "attachment-provider-call").apply { isDaemon = true }
    }

    sealed interface Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>
        data object Failed : Outcome<Nothing>
        data object TimedOut : Outcome<Nothing>
        data object Cancelled : Outcome<Nothing>
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    /**
     * Run [call] on a provider-call thread and wait for it: [progress] (moved by the call as it makes
     * headway) starts the idle deadline, the call's start the total one. [abandon] runs once, on the
     * caller's thread, if it walks away.
     */
    fun <T> await(
        limits: ReadLimits,
        active: () -> Boolean,
        progress: AtomicLong = AtomicLong(System.nanoTime()),
        abandon: () -> Unit = {},
        call: () -> T,
    ): Outcome<T> {
        val started = System.nanoTime()
        val future = try {
            pool.submit(Callable(call))
        } catch (_: RejectedExecutionException) {
            return Outcome.Failed
        }
        while (true) {
            try {
                return Outcome.Done(future.get(limits.pollMs, TimeUnit.MILLISECONDS))
            } catch (_: TimeoutException) {
                // Still running: look at the deadlines below.
            } catch (_: ExecutionException) {
                return Outcome.Failed
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                abandon()
                future.cancel(true)
                return Outcome.Cancelled
            }
            val why: Outcome<T> = when {
                !active() -> Outcome.Cancelled
                ms(progress.get()) > limits.idleMs || ms(started) > limits.totalMs -> Outcome.TimedOut
                else -> continue
            }
            abandon()
            future.cancel(true)
            return why
        }
    }
}

/**
 * Read [source] in chunks, aborting as soon as more than [max] bytes arrive (the provider's size
 * claim only sizes the first buffer; it is never trusted to bound the read), or as soon as [active]
 * says the caller went away. The open and the reads run on a provider-call thread ([ProviderCalls]);
 * this thread waits for them with [limits] and [active]. When it walks away (a stall in the open or
 * a read, the total passed, the pick cancelled) it cancels the open's signal and closes the stream
 * if one was opened (Android signals the threads blocked on a descriptor it closes, so a stuck read
 * returns); an open that ignores the signal keeps its provider-call thread until the provider
 * returns, and then its stream is closed and its bytes are discarded. The stream is closed exactly
 * once, by whichever side gets to it first.
 */
fun readBounded(
    source: AttachmentSource,
    max: Long = AttachmentDraft.MAX_ATTACHMENT_BYTES.toLong(),
    chunk: Int = 64 * 1024,
    active: () -> Boolean = { true },
    limits: ReadLimits = ReadLimits.DEFAULT,
): BoundedRead {
    val signal = CancellationSignal()
    val stream = AtomicReference<InputStream?>(null)
    // The caller walked away: whatever the call still does is discarded.
    val ended = AtomicBoolean(false)
    // The single owner of the close (security re-check): the side that wins it closes, once.
    val closed = AtomicBoolean(false)
    val progress = AtomicLong(System.nanoTime())
    fun closeOnce() {
        val s = stream.get() ?: return
        if (closed.compareAndSet(false, true)) runCatching { s.close() }
    }
    val outcome = ProviderCalls.await(limits, active, progress, abandon = {
        ended.set(true)
        runCatching { signal.cancel() }
        closeOnce()
    }) {
        try {
            val claimed = source.reportedSize
            if (claimed != null && claimed > max) return@await BoundedRead.TooLarge
            val input = source.open(signal) ?: return@await BoundedRead.Failed
            stream.set(input)
            // Walked away while it was opening: the caller had nothing to close yet.
            if (ended.get()) {
                closeOnce()
                return@await BoundedRead.Cancelled
            }
            try {
                val out = ByteArrayOutputStream(((claimed ?: chunk.toLong()).coerceIn(1, max.coerceAtMost(Int.MAX_VALUE.toLong()))).toInt())
                val buffer = ByteArray(chunk)
                var total = 0L
                while (true) {
                    if (!active() || ended.get()) return@await BoundedRead.Cancelled
                    val n = input.read(buffer)
                    if (ended.get()) return@await BoundedRead.Cancelled
                    if (n < 0) break
                    progress.set(System.nanoTime())
                    total += n
                    if (total > max) return@await BoundedRead.TooLarge
                    out.write(buffer, 0, n)
                }
                if (total == 0L) BoundedRead.Failed else BoundedRead.Bytes(out.toByteArray())
            } finally {
                closeOnce()
            }
        } catch (_: OutOfMemoryError) {
            BoundedRead.TooLarge
        } catch (_: Exception) {
            // Including the IOException of a read whose stream the caller closed, and the open's
            // OperationCanceledException (both discarded then: the caller already answered).
            if (ended.get()) BoundedRead.Cancelled else BoundedRead.Failed
        }
    }
    return when (outcome) {
        is ProviderCalls.Outcome.Done -> outcome.value
        ProviderCalls.Outcome.Failed -> BoundedRead.Failed
        ProviderCalls.Outcome.TimedOut -> BoundedRead.TimedOut
        ProviderCalls.Outcome.Cancelled -> BoundedRead.Cancelled
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
        limits: ReadLimits = ReadLimits.DEFAULT,
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
            // F1 (r3): the provider's claims are asked on a provider-call thread, under the limits.
            val signal = CancellationSignal()
            val info = when (val d = ProviderCalls.await(limits, active, abandon = { runCatching { signal.cancel() } }) { source.describe(signal) }) {
                is ProviderCalls.Outcome.Done -> d.value
                ProviderCalls.Outcome.Cancelled -> return Result(emptyList(), emptyList())
                ProviderCalls.Outcome.Failed, ProviderCalls.Outcome.TimedOut -> {
                    flashes += AttachmentCopy.unreadable(AttachmentNames.FALLBACK)
                    continue
                }
            }
            var name = AttachmentNames.wire(info.displayName)
            val claimed = info.reportedSize
            // A pasted item that does not even claim to be an image is not read at all.
            if (source.imagesOnly && info.declaredType?.lowercase()?.startsWith("image/") != true) {
                flashes += AttachmentCopy.NO_CLIPBOARD_IMAGE
                continue
            }
            if (claimed != null && claimed > AttachmentDraft.MAX_ATTACHMENT_BYTES) {
                flashes += AttachmentCopy.tooLarge(name)
                continue
            }
            if (claimed != null && total + claimed > AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES) {
                flashes += AttachmentCopy.TOTAL
                break
            }
            val bytes = when (val read = readBounded(source, active = active, limits = limits)) {
                is BoundedRead.Bytes -> read.bytes
                BoundedRead.Cancelled -> return Result(emptyList(), emptyList())
                BoundedRead.TooLarge -> {
                    flashes += AttachmentCopy.tooLarge(name)
                    continue
                }
                BoundedRead.Failed, BoundedRead.TimedOut -> {
                    flashes += AttachmentCopy.unreadable(name)
                    continue
                }
            }
            // M1: a pasted picture is one only if its bytes say so (not the clip's claim).
            var sniffedPicture: String? = null
            if (source.imagesOnly) {
                sniffedPicture = AttachmentTypes.sniff(bytes.copyOf(minOf(bytes.size, 16)))?.takeIf { it in MediaLimits.IMAGE_TYPES }
                if (sniffedPicture == null) {
                    flashes += AttachmentCopy.NO_CLIPBOARD_IMAGE
                    continue
                }
                name = AttachmentNames.wire(ClipboardImages.pastedName(sniffedPicture))
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
                val prepared = prepare(bytes, sniffedPicture ?: AttachmentTypes.resolve(bytes, info.declaredType))
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
 *
 * M1 (T7.4 r2): the clip is another app's. Only an item the [AttachmentUriPolicy] allows is
 * considered at all (a `file://` URI, one of this app's own providers or an authority that does not
 * resolve is dropped before anything is asked of it); the claimed type (the provider's, else the
 * clip's) only pre-filters; the item is kept only if its bytes are a picture
 * ([AttachmentSource.imagesOnly]), and named for them. r3 (F1): nothing is asked of the provider
 * here (its type is asked by the intake, on a provider-call thread under the limits); the package
 * manager is, so call it off the main thread.
 */
object ClipboardImages {
    /** The clip's image items, as sources. */
    fun sources(clip: android.content.ClipData?, resolver: ContentResolver, policy: AttachmentUriPolicy): List<AttachmentSource> {
        if (clip == null) return emptyList()
        val found = ArrayList<AttachmentSource>()
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i)?.uri ?: continue
            if (!policy.allows(uri)) continue
            // F1 (r3): the provider's own type is asked later, by the intake, under its limits.
            val clipType = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it).lowercase() }.firstOrNull { it.startsWith("image/") }
            found += ContentUriSource(resolver, uri, policy, imagesOnly = true, clipType = clipType)
        }
        return found
    }

    fun pastedName(imageType: String): String {
        val ext = imageType.substringAfter('/', "").substringBefore('+').ifEmpty { "png" }
        return "pasted-image.$ext"
    }
}
