package com.tether.app.client

import okhttp3.Cookie
import okhttp3.HttpUrl

/** What a login response's `Set-Cookie` headers say about the session cookie; see [sessionCookieFrom]. */
sealed interface SessionCookieResult {
    data class Found(val cookie: Credential.Cookie) : SessionCookieResult

    /** No live session cookie under either name. */
    data object Missing : SessionCookieResult

    /** One session cookie name was set to two different things: which one counts depends on order. */
    data object Ambiguous : SessionCookieResult
}

/**
 * The session cookie a login response issued (ta-96z, tether#224 lib/console-cookie.mjs).
 *
 * - Each header is parsed by OkHttp's RFC 6265 [Cookie.parse]: the name is matched EXACTLY
 *   (`tether_session_x` or `x__Host-tether_session` are other cookies), the value runs to the
 *   first `;`, and a header OkHttp refuses (no `=`, control characters, a Domain that is not
 *   this host) is not a cookie at all.
 * - [Credential.Cookie.HOST_NAME] only counts with the `__Host-` prefix's own guarantees
 *   (RFC 6265bis §4.1.3.2), which OkHttp does not check: an https login URL, `Secure`,
 *   `Path=/`, and no `Domain` (host-only). A `__Host-` cookie without them is skipped like a
 *   lookalike: a plain-HTTP server (or anything on its path) cannot hand the app a cookie
 *   under the name that promises it came from a secure, host-only origin.
 * - A header whose parse throws is skipped too: a `Domain` attribute makes OkHttp consult its
 *   public-suffix list, which throws [IllegalStateException] when that list cannot be loaded,
 *   and login() only expects [java.io.IOException]. One bad header never crashes a sign-in.
 * - A deletion (an empty value, or already expired: `Max-Age=0`) issues nothing.
 * - [Credential.Cookie.HOST_NAME] wins over [Credential.Cookie.LEGACY_NAME], as on the server.
 * - A name that appears more than once must say the same thing every time. Two different
 *   values (or a value and a deletion) is [SessionCookieResult.Ambiguous], never resolved
 *   first- or last-wins, and fails the login closed: the server reads a duplicated name as
 *   ambiguous too, so picking either copy could only store a credential it will not honour.
 */
fun sessionCookieFrom(url: HttpUrl, setCookieHeaders: List<String>): SessionCookieResult {
    val now = System.currentTimeMillis()
    val byName = HashMap<String, MutableSet<String?>>()
    for (header in setCookieHeaders) {
        val cookie = runCatching { Cookie.parse(url, header) }.getOrNull() ?: continue
        if (cookie.name != Credential.Cookie.HOST_NAME && cookie.name != Credential.Cookie.LEGACY_NAME) continue
        if (cookie.name == Credential.Cookie.HOST_NAME && !keepsHostPrefixRules(url, cookie)) continue
        // null = a deletion of this name.
        val issued = cookie.value.takeIf { it.isNotEmpty() && cookie.expiresAt > now }
        byName.getOrPut(cookie.name) { HashSet() }.add(issued)
    }
    if (byName.values.any { it.size > 1 }) return SessionCookieResult.Ambiguous
    for (name in listOf(Credential.Cookie.HOST_NAME, Credential.Cookie.LEGACY_NAME)) {
        val value = byName[name]?.single() ?: continue
        return SessionCookieResult.Found(Credential.Cookie(value, name))
    }
    return SessionCookieResult.Missing
}

/** The `__Host-` prefix rules: sent over https, `Secure`, host-only (no `Domain`), `Path=/`. */
private fun keepsHostPrefixRules(url: HttpUrl, cookie: Cookie): Boolean =
    url.isHttps && cookie.secure && cookie.hostOnly && cookie.path == "/"
