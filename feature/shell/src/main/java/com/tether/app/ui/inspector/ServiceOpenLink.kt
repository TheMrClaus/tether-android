package com.tether.app.ui.inspector

import com.tether.app.ui.text.SafeHref
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * T15.7 / ta-coik.2: the worktree services card's links (worktree-services-card.tsx:141-154).
 *
 * "Open" (`proxyAuthUrl`): what the server sends (server.mjs `worktreeScriptsSnapshot`, v134 /
 * tether#220) is NOT a link to the service: it is the console-relative
 * `/api/worktree/open?session=<id>&script=<name>` (both `encodeURIComponent`). That console route is
 * owner-grade (a paired device and an app passkey session included, ta-drm), mints a 60-second
 * single-use handoff for whoever asks and redirects to the service's own hostname. The app asks it
 * with its own sign-in ([com.tether.app.client.HttpServiceOpen]) and sends the browser where the
 * server redirects, as the web's browser is sent.
 *
 * "On this machine" (`proxyPath`): the console-origin path form, which the server sends only to a
 * viewer that reached the console over loopback on the daemon's machine ([resolveLocal]).
 *
 * Because the app sends its credential to the first and hands the second to a browser, each is
 * pinned to exactly its one route, for exactly this session and script. Every other value is no
 * link at all.
 */
object ServiceOpenLink {
    const val PATH = "/api/worktree/open"

    /** Far above any real value (a session id and a script name); bounds the work on a hostile one. */
    const val MAX_RAW = 2_048

    /** JS `encodeURIComponent`'s unreserved set: the only characters it leaves as they are. */
    private const val UNRESERVED_MARKS = "-_.!~*'()"
    private const val HEX_UPPER = "0123456789ABCDEF"

    /**
     * The absolute URL to open for [proxyAuthUrl], or null when it is not exactly the web's console
     * link for [sessionId] / [scriptName] on [pairedOrigin]. All of these must hold:
     *
     * - [proxyAuthUrl] is a relative reference whose path is exactly [PATH], immediately followed
     *   by `?` (so no scheme, no authority, no `//`, no backslash, no dot segment, no encoded slash
     *   or other escape in the path), and it is printable ASCII with no `#` (no fragment) and no
     *   second `?`;
     * - its query is exactly two `name=value` pairs, `session` and `script` (names written plainly,
     *   never escaped), each once, in either order;
     * - each value decodes STRICTLY: only `encodeURIComponent`'s unreserved characters and `%XX`
     *   escapes (a `+` is refused: form decoding would read it as a space, URI decoding as a plus),
     *   valid UTF-8, no NUL or other control character; and it is CANONICAL, i.e. exactly what
     *   `encodeURIComponent` of the decoded value gives (no over-encoded or lower-case escapes), so
     *   what opens is byte for byte what the server built;
     * - the decoded `session` equals [sessionId] (the session the inspector shows) and `script`
     *   equals [scriptName] (this row);
     * - [pairedOrigin] is the client's canonical origin (`com.tether.app.client.serverOrigin`,
     *   `scheme://host:port`), and the URL resolved against it passes [SafeHref] unchanged.
     */
    fun resolve(proxyAuthUrl: String?, sessionId: String, scriptName: String, pairedOrigin: String?): String? {
        if (proxyAuthUrl == null || pairedOrigin == null) return null
        if (sessionId.isEmpty() || scriptName.isEmpty()) return null
        if (proxyAuthUrl.length > MAX_RAW) return null
        if (proxyAuthUrl.any { it.code !in 0x21..0x7E || it == '#' || it == '\\' }) return null
        val prefix = "$PATH?"
        if (!proxyAuthUrl.startsWith(prefix)) return null
        val query = proxyAuthUrl.substring(prefix.length)
        if ('?' in query) return null
        val pairs = query.split('&')
        if (pairs.size != 2) return null
        val values = HashMap<String, String>(2)
        for (pair in pairs) {
            val eq = pair.indexOf('=')
            if (eq <= 0) return null
            val name = pair.substring(0, eq)
            if (name != "session" && name != "script") return null
            if (values.containsKey(name)) return null
            values[name] = decodeComponent(pair.substring(eq + 1)) ?: return null
        }
        if (values["session"] != sessionId || values["script"] != scriptName) return null
        if (!pairedOrigin.startsWith("http://") && !pairedOrigin.startsWith("https://")) return null
        // The canonical origin has no path; anything after the authority would change the target.
        val authority = pairedOrigin.substringAfter("://")
        if (authority.isEmpty() || authority.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '\\' }) return null
        val resolved = pairedOrigin + proxyAuthUrl
        val target = SafeHref.target(resolved) ?: return null
        if (target.scheme == SafeHref.Scheme.Mailto || target.display != resolved) return null
        return resolved
    }

    /** server.mjs / lib/service-capability.mjs: the path form's per-launch capability segment value. */
    private val CAPABILITY = Regex("^[0-9a-z]{1,12}\\.[A-Za-z0-9_-]{43}$")

    /**
     * ta-coik.2: the absolute "On this machine" URL for [proxyPath], or null unless it is exactly the
     * server's path form for [sessionId] / [scriptName] (lib/worktree-scripts.mjs
     * `/services/<sid>/<script>/`, with lib/service-origin.mjs's optional `~<capability>/` after
     * `/services/`), each part exactly `encodeURIComponent` of its value, resolved on [pairedOrigin]
     * and passing [SafeHref] unchanged. Like the web's link it carries no app credential: the
     * browser's own console sign-in authenticates it.
     */
    fun resolveLocal(proxyPath: String?, sessionId: String, scriptName: String, pairedOrigin: String?): String? {
        if (proxyPath == null || pairedOrigin == null) return null
        if (sessionId.isEmpty() || scriptName.isEmpty() || proxyPath.length > MAX_RAW) return null
        val prefix = "/services/"
        if (!proxyPath.startsWith(prefix)) return null
        var rest = proxyPath.substring(prefix.length)
        if (rest.startsWith("~")) {
            val slash = rest.indexOf('/')
            if (slash < 0 || !CAPABILITY.matches(rest.substring(1, slash))) return null
            rest = rest.substring(slash + 1)
        }
        val sid = encodeComponent(sessionId) ?: return null
        val script = encodeComponent(scriptName) ?: return null
        if (rest != "$sid/$script/") return null
        if (!pairedOrigin.startsWith("http://") && !pairedOrigin.startsWith("https://")) return null
        val authority = pairedOrigin.substringAfter("://")
        if (authority.isEmpty() || authority.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '\\' }) return null
        val resolved = pairedOrigin + proxyPath
        val target = SafeHref.target(resolved) ?: return null
        if (target.scheme == SafeHref.Scheme.Mailto || target.display != resolved) return null
        return resolved
    }

    /** JS `encodeURIComponent(value)`: UTF-8, upper-case hex. Null for a lone surrogate (JS throws). */
    fun encodeComponent(value: String): String? {
        val out = StringBuilder(value.length + 8)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return null
            }
            val cp = value.codePointAt(i)
            if (cp < 0x80 && unreserved(cp.toChar())) {
                out.append(cp.toChar())
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                    val v = b.toInt() and 0xFF
                    out.append('%').append(HEX_UPPER[v shr 4]).append(HEX_UPPER[v and 0xF])
                }
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** The strict, canonical decode of one query value (see [resolve]), or null. */
    internal fun decodeComponent(raw: String): String? {
        if (raw.isEmpty()) return null
        val bytes = ByteArray(raw.length)
        var n = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '%' -> {
                    if (i + 2 >= raw.length) return null // a cut escape: `%`, `%4`
                    val hi = hexValue(raw[i + 1])
                    val lo = hexValue(raw[i + 2])
                    if (hi < 0 || lo < 0) return null
                    bytes[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                }
                unreserved(c) -> {
                    bytes[n++] = c.code.toByte()
                    i += 1
                }
                else -> return null // `+`, `=`, `&`, `/`, `:` and every other reserved or unsafe character
            }
        }
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, n))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (decoded.any { it.code < 0x20 || it.code in 0x7F..0x9F }) return null
        // Canonical: exactly what the server's encodeURIComponent wrote.
        if (encodeComponent(decoded) != raw) return null
        return decoded
    }

    private fun unreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in UNRESERVED_MARKS

    /** Upper-case hex only (encodeURIComponent writes upper case; the canonical check would refuse lower case anyway). */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
