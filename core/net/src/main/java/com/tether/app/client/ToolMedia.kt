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

    /** Unreachable, a non-2xx (incl. any 3xx: never followed), or a content type off the allow-list. */
    data class Failed(val code: Int? = null) : ToolMediaResult
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
 */
class HttpToolMedia(
    private val http: OkHttpClient,
    private val authority: () -> FilesAuthority,
) : ToolMediaSource {

    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "HttpToolMedia needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

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
        val call = http.newCall(request)
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
        if (!response.isSuccessful) return ToolMediaResult.Failed(response.code)
        val expected = ToolMediaSource.CONTENT_TYPE_BY_EXT.getValue(ext)
        val declaredType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (declaredType != expected) return ToolMediaResult.Failed(response.code)
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > maxBytes) return ToolMediaResult.TooLarge
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

    private companion object {
        const val COPY_CHUNK = 64 * 1024

        fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
