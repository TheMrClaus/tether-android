package com.tether.app.client

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * ta-coik.58 (#242): the picture behind an `http(s)://` URL in a chat message's `![alt](url)`.
 * The web hands the URL to an `<img>` (fetched by the browser with no referrer and none of the
 * server's credentials); natively the bytes come over this source: a client of its own that never
 * sees the Tether credential, sends no cookie and no referrer, takes only `http` / `https`, and
 * bounds the body while it streams. Like the browser, it follows redirects: nothing it sends is
 * secret, so there is nothing for a redirect to leak. The caller sniffs the bytes (magic numbers)
 * before anything decodes them; the content type is not trusted.
 */
interface PublicImageSource {
    /** Stream [url] (an `http(s)` URL) into [sink], at most [maxBytes]. */
    suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult

    /** No source (previews and tests): every call fails without touching the network. */
    object Unavailable : PublicImageSource {
        override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult = ToolMediaResult.Failed()
    }
}

/** [PublicImageSource] over OkHttp. [http] must carry no credential (the default has none). */
class HttpPublicImages(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) : PublicImageSource {

    override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
        val target = try {
            Request.Builder().url(url).get().header("Accept", "image/*").build()
        } catch (_: IllegalArgumentException) {
            return ToolMediaResult.Refused
        }
        if (target.url.scheme != "http" && target.url.scheme != "https") return ToolMediaResult.Refused
        val call = http.newCall(target)
        return try {
            callCancellably(call) { response -> read(response, maxBytes, sink) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            ToolMediaResult.Failed()
        } catch (_: RuntimeException) {
            ToolMediaResult.Failed()
        } catch (_: OutOfMemoryError) {
            ToolMediaResult.TooLarge
        }
    }

    private fun read(response: Response, maxBytes: Long, sink: OutputStream): ToolMediaResult {
        if (!response.isSuccessful) return ToolMediaResult.Failed(response.code)
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > maxBytes) return ToolMediaResult.TooLarge
        val input = response.body.byteStream()
        val chunk = ByteArray(COPY_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            total += read
            if (total > maxBytes) return ToolMediaResult.TooLarge
            sink.write(chunk, 0, read)
        }
        return ToolMediaResult.Ok(total, response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty())
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
    }
}
