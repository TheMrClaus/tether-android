package com.tether.app.ui.text

import java.net.IDN
import java.text.Normalizer

/**
 * ta-fz3: which server- or agent-supplied link targets may become tappable, and the ASCII form
 * one opens in. Pure Kotlin (java.net.IDN, java.text.Normalizer), no Compose. ta-coik.8: a chat
 * link opens at once on a tap, like the web's (no confirm sheet).
 *
 * The web's markdown allowlist (`SAFE_HREF = /^(https?:\/\/|mailto:)/i`) looks at the first
 * characters only; this is a full-string check behind the same scheme allowlist. A href that
 * fails it is never a link: the label stays inert text (the prose rule still draws it).
 *
 * REFUSED ([check] gives [Verdict.Refused]; nothing is normalised away):
 * - A scheme other than `http://`, `https://`, `mailto:`, compared in ASCII case only (JS `/i`
 *   without `u`: `httpſ:` and `maılto:` are refused, as before).
 * - Any code point [refusedCodePoint] names: every C0 control (TAB, LF and CR too: a URL parser
 *   drops them silently), SPACE, DEL, C1; everything the CODE rule draws as a token
 *   ([SafeText.codeEscapes]: all twelve bidi controls, every FORMAT character including the soft
 *   hyphen, the zero-width characters, the line and paragraph separators, the default-ignorables,
 *   lone surrogates, the braille blank); every space separator; private-use, unassigned and
 *   noncharacter code points; U+FFFC/U+FFFD; an enclosing mark; a combining mark on an ASCII base
 *   (`/` + U+0338 draws a different slash); every compatibility character, i.e. one NFKC changes
 *   (fullwidth `／ ＠ ． ：`, small forms, one-dot leader, mathematical letters); and the
 *   NFKC-stable look-alikes of the URL delimiters `/ \ : . @ % ?` in [DELIMITER_LOOKALIKES]
 *   (division slash, fraction slash, ratio, ideographic full stop, ...).
 * - A backslash anywhere (browsers read it as `/` in http(s), Android's Uri does not, so the
 *   host would be ambiguous).
 * - http(s): an empty authority (`https:///x`); USER-INFO (`https://good.example@evil.example`,
 *   and every other `@` before the path: it can disguise the host, and chat links never need it);
 *   a port that is empty, has a leading zero, or is outside 1..65535; a host that fails
 *   `IDN.toASCII(host, USE_STD3_ASCII_RULES)` (so `%`, `_` and every other non-LDH ASCII in a
 *   host), whose non-ASCII label does not become an `xn--` label (IDNA2003 maps some characters to
 *   nothing or to plain ASCII: `goo` U+1806 `gle` becomes `google`), which holds a deviation
 *   character (`ß`, `ς`: IDNA2003 and the browsers' UTS #46 send them to different domains), with
 *   an empty label, over 253 characters, or which a browser reads as an IPv4 address but is not a
 *   canonical dotted quad (`0x7f.1`, `3627734350`); an IPv6 literal that is not eight 16-bit hex
 *   groups (one `::` at most, an optional dotted-quad tail; no zone id).
 * - mailto: no recipient; a recipient that is not exactly `local@domain` with an ASCII local part
 *   of RFC 5322 atext and dots (`%` refused: an escaped `@` or `,` would split differently in the
 *   mail app; `#` refused: a URI parser ends the address there; `/` refused, r3: `mailto://bank.
 *   example/support@evil.example` reads like the bank); a domain that fails the host rule or is an
 *   address.
 *
 * ALLOWED hrefs become a [Target]:
 * - [Target.host] is the host in ASCII: an internationalised name as punycode (`xn--`), letters
 *   lowercased. [Target.port] is the port when the href names one, default or not.
 * - [Target.display] is what an http(s) link opens, always printable
 *   ASCII: the scheme lowercased, the host (or each mailto domain) in ASCII, every non-ASCII
 *   character after the host PERCENT-ENCODED as UTF-8 (r2: what the browser sends anyway, so no
 *   look-alike outside the host can draw as a delimiter), every other character exactly as
 *   written. Percent-escapes already in the href are LEFT ENCODED, never decoded: `%E2%80%AE` in a
 *   path stays those nine characters, so no decoding can surface a hidden character.
 * - mailto: [Target.display] is the recipients only (`mailto:a@x,b@y`), each domain in ASCII. A
 *   chat link opens it with the href's query (subject, body) as written, like the web
 *   (ta-coik.8, `openChatLink` in feature/chat).
 */
object SafeHref {
    enum class Scheme(val prefix: String) { Http("http://"), Https("https://"), Mailto("mailto:") }

    /** Why a href is not a link (for tests and logs; never shown raw to the operator). */
    enum class Refusal { Scheme, Length, CodePoint, Backslash, Authority, UserInfo, Port, Host, Recipient }

    /** A href that passed [check]. */
    class Target internal constructor(
        /** The href exactly as the source wrote it. */
        val href: String,
        val scheme: Scheme,
        /** http(s): the host in ASCII (punycode for an international name, lowercase; IPv6 in brackets). Null for mailto. */
        val host: String?,
        /** http(s): the port the href names, or null. */
        val port: Int?,
        /** mailto: each recipient, its domain in ASCII. */
        val recipients: List<String>,
        /** A host or mailto domain was an internationalised name (it is shown as punycode). */
        val international: Boolean,
        /** The ASCII target (see the class doc). */
        val display: String,
        /** Every character of [href] is printable ASCII (U+0021..U+007E). */
        val ascii: Boolean,
    )

    sealed interface Verdict {
        data class Allowed(val target: Target) : Verdict
        data class Refused(val reason: Refusal) : Verdict
    }

    /** Longer hrefs are refused (bounds the host conversion; far above any real chat link). */
    const val MAX_HREF: Int = 8192

    /**
     * NFKC-stable characters that draw like a URL delimiter (`/ \ : . @ % ?`). NFKC catches the
     * fullwidth and small forms; these survive it.
     */
    val DELIMITER_LOOKALIKES: Set<Int> = setOf(
        // slash
        0x2044, 0x2215, 0x2571, 0x29F8, 0x27CB, 0x1735, 0x2CC6,
        // backslash
        0x2216, 0x29F5, 0x29F9, 0x27CD, 0x2572,
        // colon
        0x2236, 0xA789, 0x02D0, 0x02F8, 0x0589, 0x05C3, 0x0703, 0x0704, 0x16EC, 0x1803, 0x1809, 0x205A, 0xA4FD,
        // full stop
        0x3002, 0x06D4, 0x0701, 0x0702, 0x2E31, 0x2E33, 0xA60E, 0xA4F8, 0x2219, 0x22C5, 0x10A50,
        // at, percent, question mark
        0x066A, 0x2052, 0x2047, 0x2048, 0x2049,
    )

    private val SCHEMES = Scheme.entries

    /** Is [href] an allowed link target? */
    fun isSafe(href: String): Boolean = check(href) is Verdict.Allowed

    /** The [Target] of an allowed [href], or null. */
    fun target(href: String): Target? = (check(href) as? Verdict.Allowed)?.target

    fun check(href: String): Verdict {
        if (href.length > MAX_HREF) return refused(Refusal.Length)
        val scheme = SCHEMES.firstOrNull { href.startsWithAsciiFold(it.prefix) } ?: return refused(Refusal.Scheme)
        var i = 0
        var base = -1
        var ascii = true
        while (i < href.length) {
            val cp = href.codePointAt(i)
            if (refusedCodePoint(cp, base)) return refused(Refusal.CodePoint)
            if (cp == '\\'.code) return refused(Refusal.Backslash)
            if (cp !in 0x21..0x7E) ascii = false
            if (!isMark(cp)) base = cp
            i += Character.charCount(cp)
        }
        val rest = href.substring(scheme.prefix.length)
        return if (scheme == Scheme.Mailto) mailto(href, rest, ascii) else web(href, scheme, rest, ascii)
    }

    /**
     * Is [cp] refused anywhere in a href? [previousBase] is the nearest earlier code point that is
     * not a combining mark (-1 at the start): a combining mark is refused on an ASCII base.
     */
    fun refusedCodePoint(cp: Int, previousBase: Int = -1): Boolean {
        if (cp <= 0x20 || cp == 0x7F) return true
        if (cp < 0x7F) return false
        if (cp <= 0x9F) return true
        if (SafeText.codeEscapes(cp)) return true
        if (cp in DELIMITER_LOOKALIKES) return true
        if (cp == 0xFFFC || cp == 0xFFFD) return true
        if (cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE) return true
        when (Character.getType(cp)) {
            Character.SPACE_SEPARATOR.toInt(), Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(),
            Character.ENCLOSING_MARK.toInt(), Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt(),
            -> return true
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() ->
                if (previousBase < 0x80) return true
        }
        val one = String(Character.toChars(cp))
        return Normalizer.normalize(one, Normalizer.Form.NFKC) != one
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    // ---- http(s) ------------------------------------------------------------------------------

    private fun web(href: String, scheme: Scheme, rest: String, ascii: Boolean): Verdict {
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
        if (authority.isEmpty()) return refused(Refusal.Authority)
        if ('@' in authority) return refused(Refusal.UserInfo)
        val hostPart: String
        val portPart: String?
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return refused(Refusal.Host)
            hostPart = authority.substring(0, close + 1)
            val after = authority.substring(close + 1)
            portPart = when {
                after.isEmpty() -> null
                after.startsWith(':') -> after.substring(1)
                else -> return refused(Refusal.Host)
            }
        } else {
            val colon = authority.lastIndexOf(':')
            hostPart = if (colon < 0) authority else authority.substring(0, colon)
            portPart = if (colon < 0) null else authority.substring(colon + 1)
        }
        val port = portPart?.let { parsePort(it) ?: return refused(Refusal.Port) }
        val host: AsciiHost = if (hostPart.startsWith('[')) {
            ipv6(hostPart) ?: return refused(Refusal.Host)
        } else {
            asciiHost(hostPart) ?: return refused(Refusal.Host)
        }
        val display = buildString {
            append(scheme.prefix)
            append(host.ascii)
            if (portPart != null) append(':').append(portPart)
            appendEncoded(rest, end, rest.length)
        }
        return Verdict.Allowed(Target(href, scheme, host.ascii, port, emptyList(), host.international, display, ascii))
    }

    /** 1..65535, digits only, no leading zero (so what is shown is the number the browser uses). */
    private fun parsePort(s: String): Int? {
        if (s.isEmpty() || s.length > 5 || s.any { it !in '0'..'9' } || s[0] == '0') return null
        return s.toInt().takeIf { it in 1..65535 }
    }

    private class AsciiHost(val ascii: String, val international: Boolean)

    private fun ipv6(bracketed: String): AsciiHost? {
        val inner = bracketed.substring(1, bracketed.length - 1)
        if (!ipv6Address(inner)) return null
        return AsciiHost("[" + inner.asciiLowercase() + "]", international = false)
    }

    /** r2: RFC 4291 text form: eight 1-4 digit hex groups, one `::` at most, an optional canonical dotted-quad tail (two groups). */
    internal fun ipv6Address(s: String): Boolean {
        if (s.isEmpty() || s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
        var body = s
        var tailGroups = 0
        val lastColon = s.lastIndexOf(':')
        if (lastColon < 0) return false
        val tail = s.substring(lastColon + 1)
        if ('.' in tail) {
            val octets = tail.split('.')
            if (octets.size != 4 || octets.any { !canonicalOctet(it) }) return false
            body = s.substring(0, lastColon + 1)
            tailGroups = 2
            // `…:1.2.3.4` leaves `…:`; only `::1.2.3.4` may end in the double colon.
            if (body.endsWith(':') && !body.endsWith("::")) body = body.dropLast(1)
        }
        fun group(g: String) = g.length in 1..4 && g.all { it != '.' }
        val dbl = body.indexOf("::")
        if (dbl >= 0) {
            if (body.indexOf("::", dbl + 1) >= 0) return false
            val head = body.substring(0, dbl)
            val rest = body.substring(dbl + 2)
            val hg = if (head.isEmpty()) emptyList() else head.split(':')
            val rg = if (rest.isEmpty()) emptyList() else rest.split(':')
            if (!hg.all(::group) || !rg.all(::group)) return false
            return hg.size + rg.size + tailGroups <= 7
        }
        val groups = body.split(':')
        return groups.all(::group) && groups.size + tailGroups == 8
    }

    /** A DNS host (or a canonical IPv4 address) in ASCII, or null (see the class doc). */
    private fun asciiHost(host: String): AsciiHost? {
        if (host.isEmpty() || host.length > 253 + 1) return null
        if (host.any { it == '\u00DF' || it == '\u03C2' }) return null // sharp s, final sigma: IDNA2003 and UTS #46 disagree
        val converted = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        } catch (_: RuntimeException) { // IllegalArgumentException: not a legal name
            return null
        }.asciiLowercase()
        if (converted.isEmpty() || converted.any { it.code > 0x7E }) return null
        val trailingDot = converted.endsWith('.')
        val body = if (trailingDot) converted.dropLast(1) else converted
        if (body.isEmpty() || body.length > 253) return null
        val labels = body.split('.')
        val sourceLabels = (if (host.endsWith('.')) host.dropLast(1) else host).split('.')
        if (sourceLabels.size != labels.size) return null
        var international = false
        for ((n, label) in labels.withIndex()) {
            if (label.isEmpty() || label.length > 63) return null
            if (label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-') }) return null
            if (label.startsWith('-') || label.endsWith('-')) return null
            val source = sourceLabels[n]
            if (source.any { it.code > 0x7F }) {
                // A non-ASCII label must stay visibly international: IDNA2003 maps some characters
                // to nothing or to ASCII, and the browser's mapping may differ.
                if (!label.startsWith("xn--")) return null
                international = true
            } else if (source.asciiLowercase() != label) {
                return null
            }
        }
        if (numericLabel(labels.last())) {
            if (trailingDot || labels.size != 4 || labels.any { !canonicalOctet(it) }) return null
        }
        return AsciiHost(converted, international)
    }

    /** A last label a browser's URL parser reads as a number (decimal, or `0x` hex): the host is then an IPv4 address. */
    private fun numericLabel(label: String): Boolean =
        label.all { it in '0'..'9' } ||
            (label.length >= 2 && label[0] == '0' && label[1] == 'x' && label.drop(2).all { it in '0'..'9' || it in 'a'..'f' })

    private fun canonicalOctet(s: String): Boolean =
        s.isNotEmpty() && s.length <= 3 && s.all { it in '0'..'9' } && (s == "0" || s[0] != '0') && s.toInt() <= 255

    // ---- mailto -------------------------------------------------------------------------------

    /**
     * RFC 5322 atext punctuation and dot, without `#` (a URI parser ends the address there) and
     * (r3) without `/`: `mailto://bank.example/support@evil.example` reads like a bank address, and
     * a URI parser takes `//bank.example` for an authority.
     */
    private const val ATEXT_EXTRA = "!$&'*+-=^_`{|}~."

    private fun mailto(href: String, rest: String, ascii: Boolean): Verdict {
        // r2: the query is dropped (see the class doc); only the address line is read and opens.
        val to = rest.substringBefore('?')
        // r3: `mailto:/…` and `mailto://…` are never an address line (a URI path or authority).
        if (to.isEmpty() || to.startsWith('/')) return refused(Refusal.Recipient)
        val recipients = ArrayList<String>()
        var international = false
        for (address in to.split(',')) {
            val at = address.indexOf('@')
            if (at <= 0 || at != address.lastIndexOf('@')) return refused(Refusal.Recipient)
            val local = address.substring(0, at)
            if (local.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ATEXT_EXTRA) }) return refused(Refusal.Recipient)
            if (local.startsWith('.') || local.endsWith('.') || ".." in local) return refused(Refusal.Recipient)
            val domain = address.substring(at + 1)
            if (domain.startsWith('[')) return refused(Refusal.Recipient)
            val host = asciiHost(domain) ?: return refused(Refusal.Recipient)
            if (numericLabel(host.ascii.trimEnd('.').substringAfterLast('.'))) return refused(Refusal.Recipient)
            international = international || host.international
            recipients.add("$local@${host.ascii}")
        }
        val display = Scheme.Mailto.prefix + recipients.joinToString(",")
        return Verdict.Allowed(Target(href, Scheme.Mailto, null, null, recipients, international, display, ascii))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun refused(reason: Refusal): Verdict = Verdict.Refused(reason)

    private const val HEX_UPPER = "0123456789ABCDEF"

    /** r2: [s] from [from] to [to], every code point above U+007E percent-encoded as UTF-8, the rest as is. */
    private fun StringBuilder.appendEncoded(s: String, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            if (cp <= 0x7E) {
                append(cp.toChar())
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                    val v = b.toInt() and 0xFF
                    append('%').append(HEX_UPPER[v shr 4]).append(HEX_UPPER[v and 0xF])
                }
            }
            i += len
        }
    }

    /** JS `/^prefix/i` without `u`: only A-Z fold (never Unicode case folding). */
    private fun String.startsWithAsciiFold(lowercasePrefix: String): Boolean {
        if (length < lowercasePrefix.length) return false
        for (n in lowercasePrefix.indices) {
            val c = this[n]
            val folded = if (c in 'A'..'Z') c + ('a' - 'A') else c
            if (folded != lowercasePrefix[n]) return false
        }
        return true
    }

    private fun String.asciiLowercase(): String {
        if (none { it in 'A'..'Z' }) return this
        val chars = toCharArray()
        for (n in chars.indices) if (chars[n] in 'A'..'Z') chars[n] = chars[n] + ('a' - 'A')
        return String(chars)
    }
}
