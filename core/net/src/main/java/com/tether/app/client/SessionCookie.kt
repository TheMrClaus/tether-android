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
        val cookie = Cookie.parse(url, header) ?: continue
        if (cookie.name != Credential.Cookie.HOST_NAME && cookie.name != Credential.Cookie.LEGACY_NAME) continue
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
