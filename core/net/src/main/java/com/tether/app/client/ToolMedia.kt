package com.tether.app.client

import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What fetching one piece of tool media came to. */
sealed interface ToolMediaResult {
    /** [bytes] were written to the sink; [mediaType] is the server's (allow-listed) content type. */
    data class Ok(val bytes: Long, val mediaType: String) : ToolMediaResult

    /** Bigger than the caller's cap (declared or streamed): nothing usable was kept. */
    data object TooLarge : ToolMediaResult

    /** Not a `/api/tool-media/<sha256>.<ext>` path: refused before any request (never sent anywhere). */
    data object Refused : ToolMediaResult

    data object SignedOut : ToolMediaResult
    data object LocalNetworkBlocked : ToolMediaResult

    /**
     * T6.8: something in front of Tether answered instead of it — a sign-in gateway (SSO or a proxy)
     * that does not exempt this route for paired devices. Decided from the status line and headers
     * only (see [HttpToolMedia.blockedBySignIn]); the body is never kept and a redirect's target is
     * never read, shown or followed.
     */
    data class Blocked(val code: Int) : ToolMediaResult

    /** Unreachable, a non-2xx that is not [Blocked], or a content type off the allow-list. */
    data class Failed(val code: Int? = null) : ToolMediaResult
}

/**
 * ta-coik.68: a sink that wants the response's declared `Content-Length` before the first byte
 * ([HttpToolMedia] calls it once, after its own size check, when the header is there): a player
 * reading the body as it arrives needs the clip's length to seek.
 */
interface DeclaredLengthSink {
    fun declaredLength(bytes: Long)
}

/**
 * v94 / v112 / v122: the materialized tool-result, attachment and spawned-run pictures the server
 * serves at `GET /api/tool-media/<sha256>.<ext>` (server.mjs:7184, lib/tool-media-store.mjs), under
 * the same authentication as every other route. The web uses the journaled `url` as an `<img src>`
 * on its own origin; natively the bytes are fetched here with the paired credential.
 */
interface ToolMediaSource {
    /** Stream [url] (a server-produced `/api/tool-media/…` path) into [sink], at most [maxBytes]. */
    suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult

    object Unavailable : ToolMediaSource {
        override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult = ToolMediaResult.SignedOut
    }

    companion object {
        /** lib/tool-media-store.mjs `FILENAME_PATTERN`, under the route prefix: nothing else is ever requested. */
        val PATH_PATTERN = Regex("^/api/tool-media/[0-9a-f]{64}\\.(png|jpg|jpeg|gif|webp|mp4)\\z")

        /** lib/tool-media-store.mjs `CONTENT_TYPE_BY_EXT`. */
        val CONTENT_TYPE_BY_EXT: Map<String, String> = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "mp4" to "video/mp4",
        )

        /** lib/tool-media-store.mjs `MAX_MEDIA_BYTES`: the server never stores more. */
        const val MAX_MEDIA_BYTES: Long = 100L * 1024L * 1024L

        /** The extension of a valid path, or null when [url] is not one. */
        fun extensionOf(url: String): String? = PATH_PATTERN.find(url)?.groupValues?.get(1)
    }
}

/**
 * [ToolMediaSource] over OkHttp, T11.1's pattern: [http] MUST NOT follow redirects (OkHttp keeps a
 * hand-set `Cookie` across a cross-host redirect), the URL is built on the paired origin from a
 * path that passed [ToolMediaSource.PATH_PATTERN] (no host, query or fragment from the journal is
 * ever used), the request is re-checked to be on that origin before it is sent, and the body is
 * bounded while it streams. Cancelling the caller cancels the socket.
 *
 * ta-coik.68: no read timeout. The web plays a clip with a plain `<video src controls>` (chat-tool-render.tsx
 * :129, :346): a slow or stalled body just waits, and the browser has no timer that gives up. [http] is the
 * paired client with its default 10 s socket read timeout, which would end a slow clip as "unavailable";
 * [client] is derived from it with `newBuilder()` (so no-redirect, the credential's interceptors and every other
 * setting stay) and only its read timeout is 0. A dead connection still ends the call (a socket error is an
 * error), and cancelling the caller still cancels the socket.
 */
class HttpToolMedia(
    http: OkHttpClient,
    private val authority: () -> FilesAuthority,
) : ToolMediaSource {

    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "HttpToolMedia needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

    /** [http] with the read timeout lifted (see the class comment); everything else is the paired client's. */
    private val client: OkHttpClient = http.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
        val ext = ToolMediaSource.extensionOf(url) ?: return ToolMediaResult.Refused
        val paired = when (val a = authority()) {
            FilesAuthority.SignedOut -> return ToolMediaResult.SignedOut
            FilesAuthority.LocalNetworkBlocked -> return ToolMediaResult.LocalNetworkBlocked
            is FilesAuthority.Paired -> a
        }
        val target = paired.origin.newBuilder().encodedPath(url).query(null).fragment(null).build()
        val request = paired.sign(Request.Builder().url(target).header("Accept", ToolMediaSource.CONTENT_TYPE_BY_EXT.getValue(ext))).get().build()
        if (!sameOrigin(request.url, paired.origin) || request.url.encodedPath != url) return ToolMediaResult.Refused
        val call = client.newCall(request)
        return try {
            callCancellably(call) { response -> read(response, ext, maxBytes, sink) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            ToolMediaResult.Failed()
        } catch (_: RuntimeException) {
            ToolMediaResult.Failed()
        } catch (_: OutOfMemoryError) {
            // Security review M1: a sink or a response buffer that runs the heap out is "too large".
            ToolMediaResult.TooLarge
        }
    }

    private fun read(response: Response, ext: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
        // A redirect is never followed, and never trusted either.
        val declaredType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (blockedBySignIn(response.code, declaredType, response.header("WWW-Authenticate") != null)) {
            return ToolMediaResult.Blocked(response.code)
        }
        if (!response.isSuccessful) return ToolMediaResult.Failed(response.code)
        val expected = ToolMediaSource.CONTENT_TYPE_BY_EXT.getValue(ext)
        if (declaredType != expected) return ToolMediaResult.Failed(response.code)
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > maxBytes) return ToolMediaResult.TooLarge
        if (declared != null) (sink as? DeclaredLengthSink)?.declaredLength(declared)
        val input = response.body.byteStream()
        val chunk = ByteArray(COPY_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            total += read
            // Past the cap: stop reading (closing the response cancels the rest) and keep nothing.
            if (total > maxBytes) return ToolMediaResult.TooLarge
            sink.write(chunk, 0, read)
        }
        return ToolMediaResult.Ok(total, expected)
    }

    private suspend fun <T> callCancellably(call: Call, block: (Response) -> T): T = coroutineScope {
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

    internal companion object {
        private const val COPY_CHUNK = 64 * 1024

        /**
         * T6.8: is this answer a sign-in page rather than Tether? Tether's tool-media route never
         * redirects and answers its own 401/403 as JSON without `WWW-Authenticate` (server.mjs's
         * `/api/` gate, `streamToolMedia`). So: any 3xx; a 401/403 that is not JSON or carries
         * `WWW-Authenticate` (ta-s4r's rule for the sign-in probe); or a 200 HTML page.
         */
        fun blockedBySignIn(code: Int, contentType: String?, wwwAuthenticate: Boolean): Boolean = when (code) {
            in 300..399 -> true
            401, 403 -> wwwAuthenticate || contentType != "application/json"
            200 -> contentType == "text/html" || contentType == "application/xhtml+xml"
            else -> false
        }

        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
