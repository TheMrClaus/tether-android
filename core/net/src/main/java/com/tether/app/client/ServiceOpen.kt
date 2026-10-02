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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * ta-coik.2: the worktree services card's "Open" with the APP's sign-in, the way the web's works with
 * the browser's (worktree-services-card.tsx:143-147, server.mjs `/api/worktree/open`).
 *
 * On the web the link is the console-side `/api/worktree/open?session&script`: the browser navigates
 * to it with its console sign-in, the server (owner-grade; since ta-drm a paired device and an app
 * passkey session are owner-grade too, and a device principal is the handoff's parent) mints a
 * 60-second, single-use handoff bound to the service's own hostname and answers 303 to
 * `<proxyUrl>/?tether-auth=<handoff>`; the service host spends it for a cookie on its own origin.
 *
 * The app makes that same request itself, with its own credential and never following the redirect
 * (so the credential stays on the console), and hands the 303's target to the browser: exactly the
 * URL the web's browser is sent to. The credential never leaves the app; the handoff leaves it the
 * same way it leaves the console on the web.
 */
interface ServiceOpenSource {
    sealed interface Outcome {
        /** Open [url] (the service's own hostname, carrying its handoff) in the browser. */
        class Open(val url: String) : Outcome {
            override fun toString(): String = "Open(url=<redacted>)"
        }

        /** Nothing to open; [message] says why (the server's own text when it gave one). */
        data class Refused(val message: String) : Outcome
    }

    /**
     * Ask the paired server for [link] (the console's pinned `/api/worktree/open` URL, absolute on
     * the paired origin) and return where to send the browser: only a 303-style answer whose target
     * is the service host [serviceHost] with exactly one `tether-auth` handoff.
     */
    suspend fun open(link: String, serviceHost: String): Outcome

    object Unavailable : ServiceOpenSource {
        override suspend fun open(link: String, serviceHost: String): Outcome = Outcome.Refused(SIGNED_OUT)
    }

    companion object {
        const val PATH = "/api/worktree/open"
        const val HANDSHAKE_PARAM = "tether-auth"
        const val CALL_TIMEOUT_MS = 15_000L
        const val MAX_BODY_BYTES = 16_384L
        const val MAX_ERROR = 300

        const val SIGNED_OUT = "Sign in to Tether to open this service."
        const val LOCAL_NETWORK = "Tether can't reach this server: local network access is off for the app."
        const val UNREACHABLE = "The console did not answer. Try again."
        const val GATEWAY = "A sign-in page answered instead of Tether, so the service could not be opened."
        const val NOT_OPENED = "The console could not open this service."

        /** server.mjs: the handoff is `randomBytes(32).toString("base64url")`; a bound well above it. */
        private val TOKEN = Regex("^[A-Za-z0-9_-]{16,512}$")

        /**
         * The browser target for a redirect [location], or null unless it is exactly what server.mjs
         * writes: `http(s)://<serviceHost>[:port]/?tether-auth=<handoff>` (no user info, no other
         * path, query or fragment).
         */
        fun handoffTarget(location: String?, serviceHost: String): String? {
            if (location == null || location.length > 2_048) return null
            if (location.any { it.code !in 0x21..0x7E }) return null
            val url = location.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            if (!url.host.equals(serviceHost, ignoreCase = true)) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
            if (url.encodedPath != "/" || url.querySize != 1 || url.queryParameterName(0) != HANDSHAKE_PARAM) return null
            val token = url.queryParameterValue(0) ?: return null
            if (!TOKEN.matches(token)) return null
            // What opens is byte for byte what the server wrote (no parser-normalised variant).
            return url.toString().takeIf { it == location }
        }
    }
}

/**
 * [ServiceOpenSource] over OkHttp. [http] MUST NOT follow redirects (RealTetherClient's `authHttp`):
 * the 303 is read, never followed, so the credential never reaches the service host.
 */
class HttpServiceOpen(
    private val http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    private val callTimeoutMs: Long = ServiceOpenSource.CALL_TIMEOUT_MS,
) : ServiceOpenSource {
    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "HttpServiceOpen needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

    override suspend fun open(link: String, serviceHost: String): ServiceOpenSource.Outcome {
        val paired = when (val a = authority()) {
            FilesAuthority.SignedOut -> return refused(ServiceOpenSource.SIGNED_OUT)
            FilesAuthority.LocalNetworkBlocked -> return refused(ServiceOpenSource.LOCAL_NETWORK)
            is FilesAuthority.Paired -> a
        }
        val asked = link.toHttpUrlOrNull() ?: return refused(ServiceOpenSource.NOT_OPENED)
        // Only the paired server's own worktree-open route, with its session and script, carries the credential.
        if (!sameOrigin(asked, paired.origin) || asked.encodedPath != ServiceOpenSource.PATH || asked.fragment != null) {
            return refused(ServiceOpenSource.NOT_OPENED)
        }
        val names = (0 until asked.querySize).map(asked::queryParameterName)
        if (names.sorted() != listOf("script", "session")) return refused(ServiceOpenSource.NOT_OPENED)
        val request = try {
            val target = paired.origin.newBuilder().encodedPath(ServiceOpenSource.PATH).encodedQuery(asked.encodedQuery).fragment(null).build()
            paired.sign(Request.Builder().url(target).header("Cache-Control", "no-store")).get().build()
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            return refused(ServiceOpenSource.NOT_OPENED)
        }
        if (request.method != "GET" || !sameOrigin(request.url, paired.origin) || request.url.encodedPath != ServiceOpenSource.PATH) {
            return refused(ServiceOpenSource.NOT_OPENED)
        }
        val call = http.newCall(request)
        call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            callCancellably(call) { response -> read(response, serviceHost) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            refused(ServiceOpenSource.UNREACHABLE)
        } catch (_: RuntimeException) {
            refused(ServiceOpenSource.UNREACHABLE)
        }
    }

    private fun read(response: Response, serviceHost: String): ServiceOpenSource.Outcome {
        if (response.code in 300..399) {
            // Tether's answer goes to the service host; any other redirect is a sign-in gateway's.
            val target = ServiceOpenSource.handoffTarget(response.header("Location"), serviceHost)
            return if (target != null) ServiceOpenSource.Outcome.Open(target) else refused(ServiceOpenSource.GATEWAY)
        }
        val declaredType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (HttpToolMedia.blockedBySignIn(response.code, declaredType, response.header("WWW-Authenticate") != null)) {
            return refused(ServiceOpenSource.GATEWAY)
        }
        // The server's own sentence (404 no proxied address, 409 origin not configured, 403 owner sign-in).
        val error = if (declaredType == "application/json") readCapped(response)?.let(FixedRouteHttp::parseObject)?.get("error") else null
        val text = (error as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { TextCut.cut(it, ServiceOpenSource.MAX_ERROR) }?.takeIf { it.isNotBlank() }
        return refused(text ?: ServiceOpenSource.NOT_OPENED)
    }

    private fun readCapped(response: Response): String? {
        val body = response.body
        if (body.contentLength() > ServiceOpenSource.MAX_BODY_BYTES) return null
        val source = body.source()
        if (source.request(ServiceOpenSource.MAX_BODY_BYTES + 1)) return null
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

    private fun refused(message: String) = ServiceOpenSource.Outcome.Refused(message)

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
}
