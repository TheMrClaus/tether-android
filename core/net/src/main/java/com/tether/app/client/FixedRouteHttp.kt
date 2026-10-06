package com.tether.app.client

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * T10.4 (the shape ta-0d9 proposed for HttpClaudeAccounts / HttpOverviewMetrics): ONE call on a FIXED
 * route of the paired server, for reads and writes alike.
 *
 * - [http] must never follow a redirect (OkHttp keeps a hand-set `Cookie` across a cross-host
 *   redirect, and a write must never be replayed elsewhere): refused at construction.
 * - The (server, credential) pair is read once, through the caller's [FilesAuthority]; the call is
 *   made only when that server is [expectedOrigin], the server the screen that asked was drawn
 *   from. Any other server: [Outcome.OtherOrigin], and nothing is sent.
 * - The path is the caller's fixed text, refused unless it is plain segments ([PATH]), set on that
 *   origin with no fragment and no query but the single parameter a caller may name (T9.2); building
 *   and signing are caught (nothing sent); the built request is checked again (method, origin = the
 *   drawing origin, path, exactly that query) before it goes.
 * - A body, when there is one, is JSON (`application/json`, the media type a cookie-authenticated
 *   POST needs, tether #213 lib/origin-guard.mjs rule 3).
 * - The answer: the sign-in gateway rule first ([HttpToolMedia.blockedBySignIn]: a redirect, a 401/403
 *   that is not Tether's JSON, a 200 HTML page); otherwise its status and, only when it declares
 *   `application/json`, its body as an object, read while streaming up to [maxBytes] and nested no
 *   deeper than [MAX_DEPTH]. Nothing past the cap is buffered.
 * - Cancelling the caller cancels the socket; a call not done after [callTimeoutMs] is abandoned.
 */
class FixedRouteHttp(
    private val http: OkHttpClient,
    private val maxBytes: Long,
    private val callTimeoutMs: Long,
) {
    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "FixedRouteHttp needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

    /** The methods a fixed route may use. */
    enum class Method { GET, POST, PUT, PATCH, DELETE }

    sealed interface Outcome {
        /** No credential: nothing was sent. */
        data object SignedOut : Outcome

        data object LocalNetworkBlocked : Outcome

        /** The client is signed in to [origin], not the server the caller drew from: nothing was sent. */
        data class OtherOrigin(val origin: String?) : Outcome

        /** The request could not be built as asked (never the case for a fixed route): nothing was sent. */
        data class NotBuilt(val origin: String) : Outcome

        /** A sign-in gateway answered instead of Tether. Nothing is followed, nothing read. */
        data class Blocked(val code: Int, val origin: String) : Outcome

        /** No answer (refused, reset, timed out). */
        data class Unreachable(val origin: String) : Outcome

        /**
         * Tether answered [code]. [json]: the body as an object when it declared `application/json`,
         * fit under the cap and parsed; else null ([jsonType] says whether it declared JSON at all).
         */
        data class Answered(val code: Int, val jsonType: Boolean, val json: JsonObject?, val origin: String) : Outcome
    }

    /**
     * [query] (T9.2): at most one parameter, its name one of the route's own fixed words ([QUERY_NAME])
     * and its value percent-encoded by OkHttp as the web's `encodeURIComponent` would (`/api/usage?since=`,
     * `/api/usage/accounts?force=`). The built request must carry exactly that query, nothing else.
     */
    suspend fun call(
        authority: FilesAuthority,
        expectedOrigin: String?,
        method: Method,
        path: String,
        body: JsonObject? = null,
        query: Pair<String, String>? = null,
    ): Outcome {
        val paired = when (authority) {
            FilesAuthority.SignedOut -> return Outcome.SignedOut
            FilesAuthority.LocalNetworkBlocked -> return Outcome.LocalNetworkBlocked
            is FilesAuthority.Paired -> authority
        }
        val origin = serverOrigin(paired.origin.toString()) ?: return Outcome.SignedOut
        // A null drawn origin means no server was recorded (the web's relative same-origin fetch): it goes to
        // the current paired server. Only a recorded DIFFERENT origin is refused, with nothing sent.
        val wanted = expectedOrigin ?: origin
        if (origin != wanted) return Outcome.OtherOrigin(origin)
        // r2 (security F4): the helper itself refuses a path that is not plain segments (no dot
        // segment, escape, query, fragment or empty segment), whatever its caller passes.
        if (!PATH.matches(path)) return Outcome.NotBuilt(origin)
        if (query != null && !QUERY_NAME.matches(query.first)) return Outcome.NotBuilt(origin)
        // r2 (security F4): building and signing cannot throw past the caller (nothing is sent then).
        val request = try {
            val target = paired.origin.newBuilder().encodedPath(path).query(null).fragment(null)
                .apply { if (query != null) addQueryParameter(query.first, query.second) }
                .build()
            val builder = paired.sign(
                Request.Builder().url(target).header("Accept", "application/json").header("Cache-Control", "no-store"),
            )
            val requestBody = body?.toString()?.toRequestBody(JSON_TYPE)
            when (method) {
                Method.GET -> builder.get()
                Method.POST -> builder.post(requestBody ?: EMPTY_OBJECT.toRequestBody(JSON_TYPE))
                Method.PUT -> builder.put(requestBody ?: EMPTY_OBJECT.toRequestBody(JSON_TYPE))
                Method.PATCH -> builder.patch(requestBody ?: EMPTY_OBJECT.toRequestBody(JSON_TYPE))
                Method.DELETE -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            }.build()
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            return Outcome.NotBuilt(origin)
        }
        // Nothing but the fixed route, by its own method, on the drawing origin ever carries the credential.
        if (
            request.method != method.name ||
            !sameOrigin(request.url, paired.origin) ||
            serverOrigin(request.url.toString()) != wanted ||
            request.url.encodedPath != path ||
            !queryIs(request.url, query)
        ) {
            return Outcome.NotBuilt(origin)
        }
        val call = http.newCall(request)
        call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            callCancellably(call) { response -> read(response, origin) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Outcome.Unreachable(origin)
        } catch (_: RuntimeException) {
            Outcome.Unreachable(origin)
        } catch (_: OutOfMemoryError) {
            Outcome.Unreachable(origin)
        }
    }

    private fun read(response: Response, origin: String): Outcome {
        val declaredType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (HttpToolMedia.blockedBySignIn(response.code, declaredType, response.header("WWW-Authenticate") != null)) {
            return Outcome.Blocked(response.code, origin)
        }
        val jsonType = declaredType == "application/json"
        val json = if (jsonType) readCapped(response)?.let(::parseObject) else null
        return Outcome.Answered(response.code, jsonType, json, origin)
    }

    /** The body as text, or null when it is (declared or streamed) over [maxBytes]. */
    private fun readCapped(response: Response): String? {
        val body = response.body
        if (body.contentLength() > maxBytes) return null
        val source = body.source()
        if (source.request(maxBytes + 1)) return null
        return source.buffer.readUtf8()
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

    companion object {
        /** The deepest container read; anything deeper is replaced by null before parsing. */
        const val MAX_DEPTH = 8

        /** A fixed route: `/` then plain segments of letters, digits, `_` and `-` (r2, security F4). */
        val PATH = Regex("^/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+$")

        private val JSON_TYPE = "application/json".toMediaType()
        private const val EMPTY_OBJECT = "{}"

        fun parseObject(text: String): JsonObject? = try {
            val bounded = com.tether.app.protocol.ServerMessage.flattenDeeperThan(text, MAX_DEPTH)
            com.tether.app.protocol.TetherJson.parseToJsonElement(bounded) as? JsonObject
        } catch (_: Exception) {
            null
        }

        /** A query parameter's name: a plain lower-case word (`since`, `force`). */
        val QUERY_NAME = Regex("^[a-z]{1,32}$")

        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

        /** No query when none was asked; else exactly the one parameter asked, with its exact value. */
        private fun queryIs(url: HttpUrl, query: Pair<String, String>?): Boolean = when (query) {
            null -> url.query == null
            else -> url.querySize == 1 && url.queryParameterName(0) == query.first && url.queryParameterValue(0) == query.second
        }
    }
}
