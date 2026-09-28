package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source

/**
 * Where a /api/files call may go, decided per call from ONE consistent read of the client's
 * (server, credential) pair, so a sign-in to another server between two calls can never pair
 * this server's credential with that server's origin.
 */
sealed interface FilesAuthority {
    data object SignedOut : FilesAuthority
    data object LocalNetworkBlocked : FilesAuthority

    /**
     * The paired server's [origin] and the one way to attach its credential to a request.
     * [sign] is RealTetherClient's `authorize` (the only place the two credential kinds differ);
     * this class never sees the credential itself.
     */
    class Paired(val origin: HttpUrl, val sign: (Request.Builder) -> Request.Builder) : FilesAuthority
}

/**
 * [WorkspaceFiles] over OkHttp. [http] MUST NOT follow redirects (RealTetherClient's `authHttp`):
 * OkHttp drops `Authorization` on a cross-host redirect but keeps a hand-set `Cookie`, so a
 * followed 3xx could hand the credential to another origin. A 3xx is therefore just a failure.
 *
 * Every URL is built on [FilesAuthority.Paired.origin] with a fixed route path; only the query
 * carries caller data, percent-encoded exactly as the web's `encodeURIComponent`. Cancelling the
 * calling coroutine cancels the HTTP call (the web's AbortController).
 */
class HttpWorkspaceFiles(
    private val http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    // The server's caps; parameters only so tests can reach them without moving 512 MB.
    private val uploadCap: Long = WorkspaceFiles.MAX_UPLOAD_BYTES,
    private val listingCap: Long = WorkspaceFiles.MAX_LISTING_BYTES,
) : WorkspaceFiles {

    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "HttpWorkspaceFiles needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

    override suspend fun list(path: String): FilesResult<WorkspaceFileListing> {
        val fallback = FilesCopy.LIST_FALLBACK
        return call(fallback, { route("/api/files/list", "path" to path).header("Accept", "application/json") }) { response ->
            if (!response.isSuccessful) return@call failure(response, fallback)
            val text = readCapped(response, listingCap) ?: return@call FilesResult.Failed(fallback, response.code)
            parseListing(text)?.let { FilesResult.Ok(it) } ?: FilesResult.Failed(fallback, response.code)
        }
    }

    override suspend fun mkdir(parent: String, name: String) =
        postJson("/api/files/mkdir", FilesCopy.MKDIR_FALLBACK, "path" to parent, "name" to name)

    override suspend fun touch(parent: String, name: String) =
        postJson("/api/files/touch", FilesCopy.TOUCH_FALLBACK, "path" to parent, "name" to name)

    override suspend fun rename(path: String, name: String) =
        postJson("/api/files/rename", FilesCopy.RENAME_FALLBACK, "path" to path, "name" to name)

    override suspend fun move(path: String, destination: String) =
        postJson("/api/files/move", FilesCopy.ACTION_FALLBACK, "path" to path, "destination" to destination)

    override suspend fun copy(path: String, destination: String) =
        postJson("/api/files/copy", FilesCopy.ACTION_FALLBACK, "path" to path, "destination" to destination)

    override suspend fun delete(path: String): FilesResult<WorkspaceMutation> {
        val fallback = FilesCopy.DELETE_FALLBACK
        return call(fallback, { route("/api/files", "path" to path).header("Accept", "application/json").delete() }) { mutation(it, fallback) }
    }

    override suspend fun upload(parent: String, name: String, source: UploadSource, overwrite: Boolean): FilesResult<WorkspaceMutation> {
        val fallback = FilesCopy.uploadFallback(name)
        val declared = source.length
        // The server refuses a declared length past its cap with 413 before reading a byte;
        // refusing here saves opening (and the radio sending) any of it.
        if (declared != null && declared > uploadCap) {
            return FilesResult.Failed(FilesCopy.UPLOAD_TOO_LARGE, tooLarge = true)
        }
        val query = buildList {
            add("path" to parent)
            add("name" to name)
            if (overwrite) add("overwrite" to "1")
        }.toTypedArray()
        return try {
            call(fallback, { route("/api/files/upload", *query).header("Accept", "application/json").put(StreamingBody(source, uploadCap)) }) {
                mutation(it, fallback)
            }
        } catch (_: UploadTooLarge) {
            FilesResult.Failed(FilesCopy.UPLOAD_TOO_LARGE, tooLarge = true)
        }
    }

    override suspend fun head(path: String): FilesResult<FileHead> {
        val fallback = FilesCopy.FILE_FALLBACK
        return call(fallback, { route("/api/files", "path" to path).head() }) { response ->
            if (!response.isSuccessful) return@call FilesResult.Failed(fallback, response.code)
            FilesResult.Ok(FileHead(response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }, response.header("Content-Type")))
        }
    }

    override suspend fun readText(path: String, listedSize: Long): FilesResult<String> {
        val fallback = FilesCopy.TEXT_FALLBACK
        val cap = WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES
        return call(fallback, {
            route("/api/files", "path" to path).header("Accept", "text/plain").apply {
                // Metadata can race a growing file: bound the response too (web selectFile).
                if (listedSize > 0) header("Range", "bytes=0-${cap - 1}")
            }
        }) { response ->
            // The web reads no error body here: a failed preview always says the same thing.
            if (!response.isSuccessful) return@call FilesResult.Failed(fallback, response.code)
            FilesResult.Ok(readPrefix(response, cap).readUtf8())
        }
    }

    override suspend fun download(path: String, maxBytes: Long, sink: OutputStream): FilesResult<Long> {
        val fallback = FilesCopy.FILE_FALLBACK
        return call(fallback, { route("/api/files", "path" to path) }) { response ->
            if (!response.isSuccessful) return@call FilesResult.Failed(fallback, response.code)
            val declared = response.header("Content-Length")?.toLongOrNull()
            if (declared != null && declared > maxBytes) return@call FilesResult.Failed(FilesCopy.DOWNLOAD_TOO_LARGE, response.code, tooLarge = true)
            val source = response.body.source()
            val chunk = ByteArray(COPY_CHUNK)
            var total = 0L
            val input = source.inputStream()
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                total += read
                // Past the cap: stop reading (the call is cancelled with the response) and keep nothing.
                if (total > maxBytes) return@call FilesResult.Failed(FilesCopy.DOWNLOAD_TOO_LARGE, response.code, tooLarge = true)
                sink.write(chunk, 0, read)
            }
            FilesResult.Ok(total)
        }
    }

    // ------------------------------------------------------------------

    private suspend fun postJson(route: String, fallback: String, vararg fields: Pair<String, String>): FilesResult<WorkspaceMutation> {
        val body = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString().toRequestBody(JSON)
        return call(fallback, { route(route).header("Accept", "application/json").post(body) }) { mutation(it, fallback) }
    }

    /** web readJsonOrThrow: any 2xx succeeds (whatever the body), anything else carries `{error}`. */
    private fun mutation(response: Response, fallback: String): FilesResult<WorkspaceMutation> {
        if (!response.isSuccessful) return failure(response, fallback)
        val obj = readCapped(response, ERROR_BODY_CAP)?.let(::parseObject)
        return FilesResult.Ok(
            WorkspaceMutation(
                parent = obj?.string("parent"),
                path = obj?.string("path"),
                size = (obj?.get("size") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toLong(),
            ),
        )
    }

    private fun failure(response: Response, fallback: String): FilesResult.Failed {
        val message = readCapped(response, ERROR_BODY_CAP)?.let(::parseObject)?.string("error") ?: fallback
        return FilesResult.Failed(message, response.code, tooLarge = response.code == 413)
    }

    /** A request on the paired origin: [route] is a fixed path, [query] is encoded like encodeURIComponent. */
    private class RouteBuilder(private val origin: HttpUrl) {
        fun route(route: String, vararg query: Pair<String, String>): Request.Builder {
            val url = origin.newBuilder()
                .encodedPath(route)
                .encodedQuery(query.takeIf { it.isNotEmpty() }?.joinToString("&") { (k, v) -> "$k=${encodeUriComponent(v)}" })
                .fragment(null)
                .build()
            return Request.Builder().url(url)
        }
    }

    private suspend fun <T> call(
        fallback: String,
        build: RouteBuilder.() -> Request.Builder,
        handle: (Response) -> FilesResult<T>,
    ): FilesResult<T> {
        val paired = when (val a = authority()) {
            FilesAuthority.SignedOut -> return FilesResult.Failed(FilesCopy.NOT_SIGNED_IN)
            FilesAuthority.LocalNetworkBlocked -> return FilesResult.Failed(FilesCopy.LOCAL_NETWORK_BLOCKED)
            is FilesAuthority.Paired -> a
        }
        val unsigned = RouteBuilder(paired.origin).build()
        val request = paired.sign(unsigned).build()
        // Defence in depth: the credential only ever travels to the origin it belongs to. A request
        // that would leave it is refused before anything is sent.
        if (!sameOrigin(request.url, paired.origin)) return FilesResult.Failed(fallback)
        val call = http.newCall(request)
        return try {
            callCancellably(call) { response ->
                // A redirect is never followed (see the class doc) — and never trusted either.
                if (response.code in 300..399) FilesResult.Failed(fallback, response.code) else handle(response)
            }
        } catch (e: UploadTooLarge) {
            throw e
        } catch (_: UploadSourceFailed) {
            FilesResult.Failed(fallback)
        } catch (_: IOException) {
            FilesResult.Failed(FilesCopy.UNREACHABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // A sink or source that throws something else (a provider's SecurityException) fails
            // this call, never the app.
            FilesResult.Failed(fallback)
        }
    }

    private suspend fun <T> callCancellably(call: Call, block: (Response) -> T): T = coroutineScope {
        // Cancelling the caller cancels the socket too, so an abandoned preview/listing (a new
        // folder opened, the browser closed) stops reading at once instead of draining the body.
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } finally {
            guard.cancel()
        }
    }

    /** At most [cap] bytes of the body as text, or null when it is longer (refused, not truncated). */
    private fun readCapped(response: Response, cap: Long): String? {
        val source = response.body.source()
        val buffer = Buffer()
        while (buffer.size <= cap) {
            if (source.read(buffer, COPY_CHUNK.toLong()) == -1L) return buffer.readUtf8()
        }
        return null
    }

    /** The first [cap] bytes of the body (a truncated read, like a honoured Range). */
    private fun readPrefix(response: Response, cap: Long): Buffer {
        val source = response.body.source()
        val buffer = Buffer()
        while (buffer.size < cap) {
            if (source.read(buffer, minOf(COPY_CHUNK.toLong(), cap - buffer.size)) == -1L) break
        }
        return buffer
    }

    /** The upload body: streamed from [source] once, cut off past the server's cap. */
    private class StreamingBody(private val source: UploadSource, private val cap: Long) : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength(): Long = source.length ?: -1L

        // Never replayed on a retry: the stream is read once.
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            val stream = try {
                source.open()
            } catch (e: IOException) {
                // An unreadable document: this upload's failure, not the server's. (Anything else a
                // provider throws, a revoked grant's SecurityException, lands in call()'s catch.)
                throw UploadSourceFailed(e)
            }
            stream.source().use { input ->
                val buffer = Buffer()
                var total = 0L
                while (true) {
                    val read = input.read(buffer, COPY_CHUNK.toLong())
                    if (read == -1L) break
                    total += read
                    if (total > cap) throw UploadTooLarge()
                    sink.write(buffer, read)
                }
            }
        }
    }

    private class UploadTooLarge : IOException("upload passes the server's cap")

    /** The picked document could not be read: the upload's own failure, not the server's. */
    private class UploadSourceFailed(cause: Exception) : IOException("the upload source could not be opened", cause)

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val COPY_CHUNK = 64 * 1024
        private const val ERROR_BODY_CAP = 16L * 1024L

        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

        /** JavaScript `encodeURIComponent`: UTF-8, every byte outside [UNRESERVED] as %XX. */
        fun encodeUriComponent(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) {
                    append(c.toChar())
                } else {
                    append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        private fun parseObject(text: String): JsonObject? = try {
            TetherJson.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Tolerant: an entry without a string name/path is dropped, never guessed. */
        internal fun parseListing(text: String): WorkspaceFileListing? {
            val obj = parseObject(text) ?: return null
            val current = obj.string("current") ?: return null
            val crumbs = (obj["breadcrumbs"] as? JsonArray).orEmpty().mapNotNull { element ->
                val crumb = element as? JsonObject ?: return@mapNotNull null
                WorkspaceBreadcrumb(crumb.string("name") ?: return@mapNotNull null, crumb.string("path") ?: return@mapNotNull null)
            }
            val entries = (obj["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                fun num(key: String) = (entry[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                WorkspaceFileEntry(
                    name = entry.string("name") ?: return@mapNotNull null,
                    path = entry.string("path") ?: return@mapNotNull null,
                    size = num("size")?.toLong() ?: -1L,
                    mtime = num("mtime") ?: Double.NaN,
                    isDirectory = (entry["isDirectory"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            return WorkspaceFileListing(current, obj.string("parent"), crumbs, entries)
        }
    }
}
