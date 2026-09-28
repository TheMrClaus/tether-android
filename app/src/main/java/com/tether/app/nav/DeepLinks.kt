package com.tether.app.nav

import com.tether.app.client.bracketedHost
import com.tether.app.client.serverOrigin
import com.tether.app.push.SessionIds
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a link asks the app to go (T4.4). Navigation only: a destination selects what is on
 * screen and never sends, approves, answers, kills, archives, pins, renames or changes a setting.
 */
sealed interface Destination {
    /** Open the app on whatever it shows now: a notification tap, or the web's `/`, `/usage`, … */
    data object Home : Destination

    /** One session, opened like a pick in the sidebar: the web's `/?session=<id>`. */
    data class Session(val id: String) : Destination
}

/** Why a link was refused. None of them echoes the link back to the user or to a log. */
enum class LinkRejection {
    /** Not a well-formed link, or one written in a way a parser could read two ways. */
    Malformed,

    /** Neither `tether:` nor the server's own `http(s):` (for example `javascript:`, `intent:`). */
    UnsupportedScheme,

    /** An http(s) link to any origin but the paired server's. It never switches servers. */
    OtherOrigin,

    /** An http(s) link while no server is known, so no origin can match it. */
    NoServer,

    /** A well-formed link to a place the app does not route (another path, another `tether://` host). */
    UnknownRoute,

    /** The session id fails [SessionIds.isValid] (charset, length). */
    InvalidSessionId,
}

sealed interface ParsedLink {
    /**
     * [origin] is the server the link names: the paired origin for an http(s) link, null for a
     * `tether://` link (which names no server, so it means "the server this app is paired with").
     */
    data class Open(val destination: Destination, val origin: String? = null) : ParsedLink

    data class Rejected(val reason: LinkRejection) : ParsedLink
}

/**
 * The one parser for every link the app honours (T4.4):
 *
 * - `tether://session/<id>`: the app's own scheme (manifest intent filter, BROWSABLE).
 * - The web's URL shapes, only on the paired server's origin: `/?session=<id>` (the notification
 *   deep link, `lib/push-notifications.mjs` `sessionDeepLink`, read by `components/dashboard.tsx`
 *   `pendingSessionId`), and the web's other pages (`/`, `/usage`, `/login`, `/setup`, `/web`),
 *   which open the app where it is.
 *
 * It is an allowlist, like the web's `public/sw.js` `safeTarget`: printable ASCII only (so an IDN
 * lookalike or a bidi control never gets as far as a host compare), no backslash, no userinfo, no
 * percent-encoding or `@` in the authority, no dot segments, and an origin compared after OkHttp's
 * canonicalisation ([serverOrigin]: case, default port, punycode) AND cross-checked against the raw
 * authority, so two URL parsers can never disagree about which host a link names. Only the id is
 * kept: the url itself is dropped once parsed.
 */
object DeepLinks {
    const val SCHEME = "tether"
    const val SESSION_HOST = "session"

    /** The whole link. The web target (path onward) has the web's own cap, [MAX_WEB_TARGET]. */
    const val MAX_LINK = 1_024

    /** public/sw.js `safeTarget`: a notification target over 512 characters is refused. */
    const val MAX_WEB_TARGET = 512

    /** The web's pages other than the dashboard (`app/<route>/page.tsx` at the parity base). */
    private val WEB_PAGES = setOf("/usage", "/login", "/setup", "/web")

    private val SCHEME_PATTERN = Regex("^[a-z][a-z0-9+.-]*$")
    private val PORT_PATTERN = Regex("^[0-9]{1,5}$")

    /** The `tether://` link for [id], or null when [id] is not a valid session id. */
    fun sessionLink(id: String): String? = if (SessionIds.isValid(id)) "$SCHEME://$SESSION_HOST/$id" else null

    /**
     * Parse [link] against the paired server [pairedBaseUrl] (the stored base URL, or null when
     * none is known). An http(s) link is honoured only when its origin equals the paired origin.
     */
    fun parse(link: String?, pairedBaseUrl: String?): ParsedLink {
        if (link.isNullOrEmpty() || link.length > MAX_LINK) return rejected(LinkRejection.Malformed)
        if (!link.isPrintableAscii() || '\\' in link) return rejected(LinkRejection.Malformed)
        val colon = link.indexOf(':')
        if (colon <= 0) return rejected(LinkRejection.Malformed)
        val scheme = link.substring(0, colon).asciiLowercase()
        if (!SCHEME_PATTERN.matches(scheme)) return rejected(LinkRejection.Malformed)
        val rest = link.substring(colon + 1)
        return when (scheme) {
            SCHEME -> parseTether(rest)
            "https", "http" -> parseWeb(link, rest, pairedBaseUrl)
            else -> rejected(LinkRejection.UnsupportedScheme)
        }
    }

    /**
     * A root-relative web target (`/…`) on the paired origin: the dashboard with or without
     * `?session=`, or another web page. The fragment is ignored, as `safeTarget` drops it, so a
     * `?session=` inside the fragment is never read.
     */
    fun parseWebTarget(target: String): ParsedLink {
        if (target.length > MAX_WEB_TARGET) return rejected(LinkRejection.Malformed)
        if (!target.isPrintableAscii() || '\\' in target) return rejected(LinkRejection.Malformed)
        if (!target.startsWith("/") || target.startsWith("//")) return rejected(LinkRejection.Malformed)
        val withoutFragment = target.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        if (path.split('/').any { it == "." || it == ".." } || '%' in path) return rejected(LinkRejection.Malformed)
        return when (path) {
            "/" -> dashboard(query)
            in WEB_PAGES -> ParsedLink.Open(Destination.Home)
            else -> rejected(LinkRejection.UnknownRoute)
        }
    }

    /** `tether://session/<id>`: exactly one path segment; no userinfo, port, query or fragment. */
    private fun parseTether(rest: String): ParsedLink {
        if (!rest.startsWith("//")) return rejected(LinkRejection.Malformed)
        val afterSlashes = rest.substring(2)
        if ('?' in afterSlashes || '#' in afterSlashes) return rejected(LinkRejection.Malformed)
        val host = afterSlashes.substringBefore('/')
        if ('@' in host || ':' in host || '%' in host) return rejected(LinkRejection.Malformed)
        if (host.asciiLowercase() != SESSION_HOST) return rejected(LinkRejection.UnknownRoute)
        val path = afterSlashes.substring(host.length)
        if (!path.startsWith("/")) return rejected(LinkRejection.InvalidSessionId)
        val segment = path.substring(1)
        if (segment.isEmpty() || '/' in segment) return rejected(LinkRejection.InvalidSessionId)
        val id = percentDecode(segment, plusIsSpace = false) ?: return rejected(LinkRejection.InvalidSessionId)
        return if (SessionIds.isValid(id)) ParsedLink.Open(Destination.Session(id)) else rejected(LinkRejection.InvalidSessionId)
    }

    private fun parseWeb(link: String, rest: String, pairedBaseUrl: String?): ParsedLink {
        if (!rest.startsWith("//")) return rejected(LinkRejection.Malformed)
        val afterSlashes = rest.substring(2)
        val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) afterSlashes else afterSlashes.substring(0, authorityEnd)
        if (authority.isEmpty() || '@' in authority || '%' in authority) return rejected(LinkRejection.Malformed)
        val rawHost = hostOf(authority) ?: return rejected(LinkRejection.Malformed)

        val pairedOrigin = serverOrigin(pairedBaseUrl) ?: return rejected(LinkRejection.NoServer)
        val url = link.toHttpUrlOrNull() ?: return rejected(LinkRejection.Malformed)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return rejected(LinkRejection.Malformed)
        // Both parsers must name the same host, or the link is ambiguous and refused.
        if (bracketedHost(url.host) != rawHost.asciiLowercase()) return rejected(LinkRejection.Malformed)
        val origin = serverOrigin(link) ?: return rejected(LinkRejection.Malformed)
        if (origin != pairedOrigin) return rejected(LinkRejection.OtherOrigin)

        val target = if (authorityEnd < 0) "/" else afterSlashes.substring(authorityEnd)
        val rooted = if (target.startsWith("/")) target else "/$target"
        return when (val parsed = parseWebTarget(rooted)) {
            is ParsedLink.Open -> parsed.copy(origin = origin)
            is ParsedLink.Rejected -> parsed
        }
    }

    /** The host of `host[:port]` or `[v6][:port]`, or null when the port part is not a port. */
    private fun hostOf(authority: String): String? {
        val (host, port) = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val tail = authority.substring(close + 1)
            if (tail.isNotEmpty() && !tail.startsWith(":")) return null
            authority.substring(0, close + 1) to tail.removePrefix(":").takeIf { tail.isNotEmpty() }
        } else {
            authority.substringBefore(':') to authority.substringAfter(':', missingDelimiterValue = "").takeIf { ':' in authority }
        }
        if (host.isEmpty() || host == "[]") return null
        if (port != null && !PORT_PATTERN.matches(port)) return null
        return host
    }

    /**
     * `new URLSearchParams(search).get("session")` (dashboard.tsx): the first `session` pair,
     * form-decoded. No `session`, or an empty one, is the dashboard itself.
     */
    private fun dashboard(query: String): ParsedLink {
        if (query.isEmpty()) return ParsedLink.Open(Destination.Home)
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = percentDecode(pair.substringBefore('='), plusIsSpace = true) ?: return rejected(LinkRejection.Malformed)
            if (key != "session") continue
            val raw = pair.substringAfter('=', missingDelimiterValue = "")
            val id = percentDecode(raw, plusIsSpace = true) ?: return rejected(LinkRejection.InvalidSessionId)
            if (id.isEmpty()) return ParsedLink.Open(Destination.Home)
            return if (SessionIds.isValid(id)) ParsedLink.Open(Destination.Session(id)) else rejected(LinkRejection.InvalidSessionId)
        }
        return ParsedLink.Open(Destination.Home)
    }

    /**
     * Strict percent-decoding: every `%` must start two hex digits and the bytes must be valid
     * UTF-8, or the whole value is refused (null). Decoded once only, so `%252F` stays `%2F`,
     * which the id charset then refuses.
     */
    private fun percentDecode(value: String, plusIsSpace: Boolean): String? {
        if ('%' !in value && !(plusIsSpace && '+' in value)) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' -> {
                    if (i + 2 >= value.length) return null
                    val hi = Character.digit(value[i + 1], 16)
                    val lo = Character.digit(value[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                    continue
                }
                c == '+' && plusIsSpace -> bytes.write(' '.code)
                else -> bytes.write(c.code)
            }
            i++
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun rejected(reason: LinkRejection) = ParsedLink.Rejected(reason)

    private fun String.isPrintableAscii(): Boolean = all { it.code in 0x21..0x7e }

    /** ASCII-only lower-casing: the JVM's would fold Unicode (Turkish dotless i and friends). */
    private fun String.asciiLowercase(): String = buildString(length) {
        for (c in this@asciiLowercase) append(if (c in 'A'..'Z') c + 32 else c)
    }
}
