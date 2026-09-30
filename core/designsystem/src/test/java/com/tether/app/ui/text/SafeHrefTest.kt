package com.tether.app.ui.text

import com.tether.app.ui.text.SafeHref.Refusal
import com.tether.app.ui.text.SafeHref.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.Bidi
import kotlin.random.Random

/**
 * ta-fz3: [SafeHref], hostile href by hostile href. Every control, invisible and look-alike
 * character in this file is written as an escape: the source must not carry what it tests.
 */
class SafeHrefTest {
    private fun s(cp: Int): String = String(Character.toChars(cp))

    private fun allowed(href: String): SafeHref.Target {
        val v = SafeHref.check(href)
        if (v !is Verdict.Allowed) fail("expected allowed: ${esc(href)} got $v")
        return (v as Verdict.Allowed).target
    }

    private fun refused(href: String, reason: Refusal) {
        assertEquals(esc(href), Verdict.Refused(reason), SafeHref.check(href))
        assertFalse(esc(href), SafeHref.isSafe(href))
        assertNull(esc(href), SafeHref.target(href))
    }

    /** A readable name for a test failure: every non-printable-ASCII char as \\uXXXX. */
    private fun esc(x: String): String = buildString {
        for (c in x) if (c in ' '..'~') append(c) else append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
    }

    // ---- scheme allowlist (kept) --------------------------------------------------------------

    @Test fun theSchemeAllowlistIsKept() {
        for (href in listOf("javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,x", "intent://x#Intent;end", "file:///etc/hosts", "tether://session/s1", "ftp://example.com", "/docs", "//evil.example/x", "https:evil.example", "https:/evil.example")) {
            refused(href, Refusal.Scheme)
        }
        // ASCII case only, like JS `/i` without `u`: long s, dotless i and dotted capital I never fold.
        for (href in listOf("http\u017F://example.com", "HTTP\u017F://example.com", "ma\u0131lto:a@b.test", "MA\u0130LTO:a@b.test", "\u212Attp://example.com")) {
            refused(href, Refusal.Scheme)
        }
        // A leading control or space is not the scheme either (browsers strip them; we refuse).
        refused(" https://example.com", Refusal.Scheme)
        refused("\u202Ehttps://example.com", Refusal.Scheme)
        assertEquals("https://example.com/x", allowed("HTTPS://Example.COM/x").display)
        assertEquals("http://example.com", allowed("hTtP://example.com").display)
        assertEquals(listOf("a@b.test"), allowed("MAILTO:a@b.test").recipients)
    }

    // ---- full-string code point check ---------------------------------------------------------

    /** Every bidi control, invisible, control and separator the brief names, in the host and in the path. */
    private val hostile: List<Int> = buildList {
        addAll(0x202A..0x202E) // LRE RLE PDF LRO RLO
        addAll(0x2066..0x2069) // LRI RLI FSI PDI
        addAll(listOf(0x200E, 0x200F, 0x061C)) // LRM RLM ALM
        addAll(listOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x034F, 0x180E, 0x2062, 0x2063, 0x2064)) // ZWSP ZWNJ ZWJ WJ BOM SHY CGJ MVS invisible operators
        addAll(listOf(0x2028, 0x2029)) // line / paragraph separator
        addAll(listOf(0x3164, 0xFFA0, 0x115F, 0x1160, 0x2800)) // Hangul fillers, braille blank
        addAll(listOf(0xE0001, 0xE0041, 0xE007F)) // tag characters
        addAll(listOf(0x00A0, 0x2000, 0x2009, 0x200A, 0x202F, 0x205F, 0x3000, 0x1680)) // space separators
        addAll(0x00..0x20) // C0 (TAB, LF, CR included) and SPACE
        add(0x7F) // DEL
        addAll(0x80..0x9F) // C1
        addAll(listOf(0xFFFC, 0xFFFD, 0xE000, 0xF8FF, 0xF0000, 0xFDD0, 0xFFFE, 0x1FFFF, 0x0378)) // replacement, private use, noncharacters, unassigned
    }

    private val hostileSet: Set<Int> by lazy { hostile.toSet() }

    @Test fun everyBidiInvisibleAndControlCodePointIsRefusedInTheHostAndThePath() {
        for (cp in hostile) {
            val c = s(cp)
            refused("https://exa${c}mple.com/docs", Refusal.CodePoint)
            refused("https://example.com/do${c}cs", Refusal.CodePoint)
            refused("https://example.com/docs?q=${c}", Refusal.CodePoint)
            refused("https://example.com/docs#${c}", Refusal.CodePoint)
            refused("https://example.com/docs$c", Refusal.CodePoint)
            refused("mailto:ops$c@example.com", Refusal.CodePoint)
            refused("mailto:ops@example.com?subject=$c", Refusal.CodePoint)
        }
    }

    @Test fun aLoneSurrogateIsRefused() {
        refused("https://example.com/\uD800x", Refusal.CodePoint)
        refused("https://example.com/\uDC00", Refusal.CodePoint)
        refused("https://example.com/\uDB40", Refusal.CodePoint)
    }

    @Test fun everyCodePointTheCodeRuleTokenisesIsRefused() {
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (SafeText.codeEscapes(cp)) assertTrue("U+" + Integer.toHexString(cp), SafeHref.refusedCodePoint(cp, 'a'.code))
            when (Character.getType(cp)) {
                Character.FORMAT.toInt(), Character.CONTROL.toInt(), Character.SPACE_SEPARATOR.toInt(),
                Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(), Character.PRIVATE_USE.toInt(),
                Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt(), Character.ENCLOSING_MARK.toInt(),
                -> assertTrue("U+" + Integer.toHexString(cp), SafeHref.refusedCodePoint(cp, 'a'.code))
            }
            cp++
        }
    }

    @Test fun delimiterLookAlikesAreRefused() {
        val lookalikes = listOf(
            0xFF0F, 0x2215, 0x2044, 0x29F8, 0x2571, // slashes: fullwidth, division, fraction, big solidus, box diagonal
            0xFF3C, 0x2216, 0xFE68, // backslashes
            0xFF1A, 0x2236, 0xA789, 0x02D0, 0x0589, 0xFE55, // colons
            0x3002, 0xFF0E, 0xFF61, 0x2024, 0xFE52, 0x06D4, // full stops (three are IDNA label separators)
            0xFF20, 0xFE6B, // at signs
            0xFF05, 0x066A, 0xFF1F, 0xFF03, // percent, question mark, number sign
            0x1D41A, 0x1D5BA, 0x24D0, 0xFB01, 0x2474, // mathematical a, circled a, fi ligature, parenthesized 1
        )
        for (cp in lookalikes) {
            refused("https://google.com${s(cp)}evil.example/", Refusal.CodePoint)
            refused("https://evil.example/google.com${s(cp)}login", Refusal.CodePoint)
        }
    }

    @Test fun aCombiningMarkOnAnAsciiBaseIsRefusedButNotOnItsOwnLetter() {
        refused("https://example.com/\u0338/x", Refusal.CodePoint) // a slash overlaid on a slash
        refused("https://example.com/a\u0338", Refusal.CodePoint)
        refused("https://exa\u0301mple.com/", Refusal.CodePoint) // decomposed accent on ASCII
        refused("https://example.com/\u20DD", Refusal.CodePoint) // enclosing circle
        // A mark on a non-ASCII letter is real writing (Hindi vowel sign, Hebrew point).
        assertTrue(SafeHref.isSafe("https://example.com/\u0915\u093F"))
        assertTrue(SafeHref.isSafe("https://example.com/\u05E9\u05B8"))
    }

    @Test fun aBackslashIsRefusedAnywhere() {
        refused("https://evil.example\\@good.example/", Refusal.Backslash)
        refused("https://good.example/\\evil", Refusal.Backslash)
        refused("https://good.example\\evil.example", Refusal.Backslash)
    }

    // ---- authority: user-info, ports, hosts ---------------------------------------------------

    @Test fun userInfoIsRefusedBecauseItCanDisguiseTheHost() {
        refused("https://good.com@evil.com", Refusal.UserInfo)
        refused("https://good.com@evil.com/login", Refusal.UserInfo)
        refused("https://user:pass@evil.com/", Refusal.UserInfo)
        refused("https://@evil.com/", Refusal.UserInfo)
        refused("https://good.com:443@evil.com/", Refusal.UserInfo)
        refused("HTTPS://GOOD.COM@EVIL.COM", Refusal.UserInfo)
        // Percent-escaped `@` in the host: never decoded, the host refuses it.
        refused("https://good.com%40evil.com/", Refusal.Host)
        // An `@` after the authority is ordinary path/query/fragment text: the host stays good.com.
        assertEquals("good.com", allowed("https://good.com/@evil.com").host)
        assertEquals("good.com", allowed("https://good.com?@evil.com").host)
        assertEquals("good.com", allowed("https://good.com#@evil.com").host)
    }

    @Test fun portsAreShownAndMalformedOnesRefused() {
        val t = allowed("https://example.com:8443/x")
        assertEquals("example.com", t.host)
        assertEquals(8443, t.port)
        assertEquals("https://example.com:8443/x", t.display)
        assertEquals(443, allowed("https://example.com:443/").port)
        assertEquals(80, allowed("http://example.com:80").port)
        assertNull(allowed("https://example.com/").port)
        for (bad in listOf("https://example.com:/", "https://example.com:0/", "https://example.com:65536/", "https://example.com:08443/", "https://example.com:44a/", "https://example.com:-1/", "https://example.com:123456/")) {
            refused(bad, Refusal.Port)
        }
    }

    @Test fun anEmptyAuthorityIsRefused() {
        refused("https://", Refusal.Authority)
        refused("https:///evil.example", Refusal.Authority)
        refused("https://?x", Refusal.Authority)
        refused("https://#x", Refusal.Authority)
    }

    @Test fun hostsMustBeCleanDnsNames() {
        for (bad in listOf("https://my_host.example/", "https://a..b/", "https://.example.com/", "https://-a.example/", "https://a-.example/", "https://ex%61mple.com/", "https://" + "a".repeat(64) + ".com/", "https://a*b.com/", "https://a!b.com/", "https://:8080/")) {
            refused(bad, Refusal.Host)
        }
        assertEquals("example.com.", allowed("https://example.com./").host)
        assertEquals("a-b.example", allowed("https://A-B.Example/").host)
    }

    @Test fun ipv4HostsMustBeCanonicalDottedQuads() {
        assertEquals("1.2.3.4", allowed("https://1.2.3.4/").host)
        assertEquals("127.0.0.1", allowed("http://127.0.0.1:8080/").host)
        // A browser reads these as addresses the text does not show.
        for (bad in listOf("https://0x7f.1/", "https://3627734350/", "https://0x7f000001/", "https://01.2.3.4/", "https://1.2.3.256/", "https://1.2.3/", "https://1.2.3.4.5/", "https://1.2.3.4./", "https://example.0x10/")) {
            refused(bad, Refusal.Host)
        }
        assertEquals("123.example.com", allowed("https://123.example.com/").host)
    }

    @Test fun ipv6LiteralsAreParsedAsEightGroups() {
        val t = allowed("https://[::1]:8080/x")
        assertEquals("[::1]", t.host)
        assertEquals(8080, t.port)
        assertEquals("https://[::1]:8080/x", t.display)
        assertEquals("[2001:db8::a]", allowed("https://[2001:DB8::A]/").host)
        for (good in listOf("::", "::1", "1::", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8", "fe80::1:2", "::ffff:1.2.3.4", "::1.2.3.4", "1:2:3:4:5:6:1.2.3.4", "1::1.2.3.4")) {
            assertTrue(good, SafeHref.ipv6Address(good))
            assertTrue(good, SafeHref.isSafe("https://[$good]/"))
        }
        // r2: `[:::::]` and friends passed the old character check.
        for (bad in listOf("https://[:::::]/", "https://[:::]/", "https://[1:::2]/", "https://[1::2::3]/", "https://[:1::2]/", "https://[1::2:]/", "https://[1:2:3:4:5:6:7:8:9]/", "https://[1:2:3:4:5:6:7]/", "https://[12345::]/", "https://[::1.2.3]/", "https://[::1.2.3.04]/", "https://[1.2.3.4]/", "https://[::1.2.3.4:5]/", "https://[1:2:3:4:5:6:7:1.2.3.4]/", "https://[::1%25eth0]/", "https://[evil.com]/", "https://[]/", "https://[::1/", "https://[::1]x/", "https://[1234]/")) {
            refused(bad, Refusal.Host)
        }
    }

    // ---- international names: punycode, fail closed ------------------------------------------

    @Test fun anInternationalHostIsShownAsPunycodeAndNeverOpensDirectly() {
        // Cyrillic a in a Latin name (mixed script).
        val href = "https://ex\u0430mple.com/docs"
        val t = allowed(href)
        assertEquals("xn--exmple-4nf.com", t.host)
        assertTrue(t.international)
        assertFalse(t.ascii)
        assertEquals("https://xn--exmple-4nf.com/docs", t.display)
        assertFalse("label == href is not enough for a non-ASCII href", SafeHref.opensDirectly(t, href))
        // Cyrillic a in paypal; Greek omicron in google; Hebrew; German umlaut, any case.
        assertTrue(allowed("https://p\u0430ypal.com/").host!!.startsWith("xn--"))
        assertTrue(allowed("https://g\u03BFogle.com/").host!!.startsWith("xn--"))
        assertEquals("xn--9dbne9b.com", allowed("https://\u05E9\u05DC\u05D5\u05DD.com/").host)
        assertEquals("xn--bcher-kva.de", allowed("https://b\u00FCcher.de/").host)
        assertEquals("xn--bcher-kva.de", allowed("https://B\u00DCCHER.DE/").host)
        // A mailto domain the same way.
        val m = allowed("mailto:ops@ex\u0430mple.com")
        assertEquals(listOf("ops@xn--exmple-4nf.com"), m.recipients)
        assertTrue(m.international)
        assertEquals("mailto:ops@xn--exmple-4nf.com", m.display)
    }

    @Test fun hostsWhoseConversionIsAmbiguousAreRefused() {
        // Deviation characters: IDNA2003 (java.net.IDN) and a browser's UTS #46 send them to different domains.
        refused("https://fa\u00DF.de/", Refusal.Host)
        refused("https://\u03C2.com/", Refusal.Host)
        refused("https://fa\u1E9E.de/", Refusal.Host) // capital sharp s maps to plain "ss"
        // Mapped to nothing by IDNA2003: "google" would be shown for a name written otherwise.
        refused("https://goo\u1806gle.com/", Refusal.Host)
        // A letter unassigned in the Unicode version IDNA2003 uses (3.2): the conversion refuses it (fail closed).
        refused("https://\u0221.example/", Refusal.Host)
    }

    // ---- percent-encoding: left encoded, never decoded ----------------------------------------

    @Test fun nonAsciiAfterTheHostIsShownAndOpensPercentEncoded() {
        // r2: look-alikes the list cannot all name (middle dots, katakana no, Arabic-Indic zero, ...),
        // Hebrew and CJK: the Link row is the UTF-8 escapes the browser sends anyway.
        val cases = mapOf(
            "https://example.com/\u05E9\u05DC\u05D5\u05DD/a" to "https://example.com/%D7%A9%D7%9C%D7%95%D7%9D/a",
            "https://example.com/\u6587\u6863?q=\u4E2D#\u6587" to "https://example.com/%E6%96%87%E6%A1%A3?q=%E4%B8%AD#%E6%96%87",
            "https://bank.example\u00B7evil.example/" to "", // in the host: not an escape, a punycode label
        )
        for ((href, shown) in cases) {
            val t = allowed(href)
            if (shown.isNotEmpty()) assertEquals(esc(href), shown, t.display)
            assertTrue(esc(href), t.display.all { it in '!'..'~' })
        }
        assertTrue(allowed("https://bank.example\u00B7evil.example/").host!!.startsWith("bank.xn--"))
        for (cp in listOf(0x00B7, 0x30FB, 0x2027, 0x0660, 0x06F0, 0xA78F, 0x30CE, 0x4E3F, 0x01C0, 0x05C0)) {
            val t = SafeHref.target("https://bank.example/login${s(cp)}evil.example") ?: continue // refused is fine too
            assertTrue(esc(t.href), t.display.startsWith("https://bank.example/login%"))
            assertTrue(esc(t.href), t.display.all { it in '!'..'~' })
        }
        // An escape and the character it stands for show and open the same bytes.
        assertEquals(allowed("https://example.com/%D7%A9").display, allowed("https://example.com/\u05E9").display)
    }

    @Test fun percentEscapesAreShownEncodedNeverDecoded() {
        val t = allowed("https://example.com/%E2%80%AE/gpj.exe?x=%E2%81%A6#%20")
        assertEquals("https://example.com/%E2%80%AE/gpj.exe?x=%E2%81%A6#%20", t.display)
        assertTrue(t.ascii)
        assertFalse(t.display.any { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' })
        // The code rule has nothing to token: what is shown is exactly the nine characters.
        assertEquals(t.display, SafeText.code(t.display))
        // Malformed escapes are literal text too.
        assertEquals("https://example.com/%ZZ%", allowed("https://example.com/%ZZ%").display)
    }

    // ---- mailto -------------------------------------------------------------------------------

    @Test fun mailtoOpensItsRecipientsOnlyAndDropsTheQuery() {
        val t = allowed("mailto:ops@example.test,dev@Example.ORG?subject=Hi&Body=see%20log")
        assertEquals(listOf("ops@example.test", "dev@example.org"), t.recipients)
        assertEquals("mailto:ops@example.test,dev@example.org", t.display)
        assertNull(t.host)
        // r2: whatever the query holds, raw or escaped, it is not opened (a mail app decodes it before it splits it).
        for (q in listOf("bcc=spy@evil.com", "subject=Deploy%26bcc%3Dspy@evil.test", "subject=x%26to%3Devil@x.com", "CC=spy@evil.com", "to=spy@evil.com", "subject=a%0Abcc:spy@evil.com")) {
            assertEquals(q, "mailto:a@good.com", allowed("mailto:a@good.com?$q").display)
        }
        refused("mailto:?subject=hello", Refusal.Recipient)
        refused("mailto:?to=spy@evil.com", Refusal.Recipient)
        refused("mailto:a#b@example.com", Refusal.Recipient) // a URI parser ends the address at `#`
        // r3: `/` reads like a path to another host, and a URI parser takes `//x` for an authority.
        refused("mailto://bank.example/support@evil.example", Refusal.Recipient)
        refused("mailto:/support@evil.example", Refusal.Recipient)
        refused("mailto:a/b@example.com", Refusal.Recipient)
        refused("MAILTO://bank.example/support@evil.example", Refusal.Recipient)
        refused("mailto:", Refusal.Recipient)
        refused("mailto:ceo%40good.com@evil.com", Refusal.Recipient)
        refused("mailto:a@b@c.com", Refusal.Recipient)
        refused("mailto:@example.com", Refusal.Recipient)
        refused("mailto:example.com", Refusal.Recipient)
        refused("mailto:a@example.com,", Refusal.Recipient)
        refused("mailto:a@1.2.3.4", Refusal.Recipient)
        refused("mailto:a@[1.2.3.4]", Refusal.Recipient)
        refused("mailto:a..b@example.com", Refusal.Recipient)
        refused("mailto:\"a\"@example.com", Refusal.Recipient)
        refused("mailto:a@exa_mple.com", Refusal.Recipient)
    }

    // ---- label == href ------------------------------------------------------------------------

    @Test fun onlyAnExactPrintableAsciiLabelOpensDirectly() {
        val href = "https://example.com/docs?q=1"
        val t = allowed(href)
        assertTrue(SafeHref.opensDirectly(t, href))
        for (label in listOf("docs", "https://example.com/docs", "HTTPS://example.com/docs?q=1", "https://example.com/docs?q=1 ", " https://example.com/docs?q=1", "https://example.com/docs?q=l", "")) {
            assertFalse(label, SafeHref.opensDirectly(t, label))
        }
        // Non-ASCII anywhere (a Hebrew path): the label's own bidi layout could reorder it, so ask.
        val rtl = "https://example.com/\u05E9\u05DC\u05D5\u05DD/a"
        assertFalse(SafeHref.opensDirectly(allowed(rtl), rtl))
        val mail = "mailto:ops@example.test"
        assertTrue(SafeHref.opensDirectly(allowed(mail), mail))
        assertFalse(SafeHref.opensDirectly(allowed(mail), "Email ops"))
        // ASCII case in the scheme and host is not a difference (they open lowercased).
        assertTrue(SafeHref.opensDirectly(allowed("HTTPS://Example.COM/Docs"), "HTTPS://Example.COM/Docs"))
    }

    @Test fun anythingThatCanMisleadWhereItWrapsOrIsCutAsks() {
        fun direct(href: String) = SafeHref.opensDirectly(allowed(href), href)
        // r2: an `@` anywhere in an http(s) href.
        assertFalse(direct("https://evil.example?@bank.example"))
        assertFalse(direct("https://evil.example/@bank.example"))
        assertFalse(direct("https://evil.example#@bank.example"))
        // A host over 40 characters or over 4 labels.
        assertTrue(direct("https://" + "a".repeat(36) + ".com/")) // 40
        assertFalse(direct("https://" + "a".repeat(37) + ".com/")) // 41
        assertTrue(direct("https://a.b.c.example/"))
        assertFalse(direct("https://bank.example.com.evil.example/"))
        assertFalse(direct("mailto:ops@bank.example.com.evil.example"))
        assertFalse(direct("mailto:ops@" + "a".repeat(37) + ".com"))
        // A mailto query is dropped, so the label no longer says what opens.
        assertFalse(direct("mailto:ops@example.test?subject=hi"))
    }

    // ---- display: idempotent, forced LTR ------------------------------------------------------

    @Test fun theDisplayIsItselfAllowedAndStable() {
        for (href in listOf("https://ex\u0430mple.com:8443/a/%E2%80%AE?q#f", "HTTP://[::1]/", "mailto:a@b\u00FCcher.de?subject=x", "https://example.com./x", "https://example.com/\u05E9\u05DC\u05D5\u05DD")) {
            val display = allowed(href).display
            assertEquals(display, allowed(display).display)
        }
    }

    @Test fun forcedLtrKeepsEveryCharacterInLogicalOrder() {
        // Hebrew path segments: without the override an LTR paragraph swaps the two segments visually.
        // (r2: a target's display is ASCII now; the override still holds for any text it is given.)
        val display = "https://example.com/\u05E9\u05DC\u05D5\u05DD/\u05E2\u05D5\u05DC\u05DD/?x=1"
        fun visualIsLogical(text: String): Boolean {
            val bidi = Bidi(text, Bidi.DIRECTION_LEFT_TO_RIGHT)
            return (0 until text.length).all { bidi.getLevelAt(it) % 2 == 0 }
        }
        assertFalse("negative control: implicit bidi reorders the raw display", visualIsLogical(display))
        val forced = SafeHref.forcedLtr(display)
        assertEquals('\u202D', forced.first())
        assertEquals('\u202C', forced.last())
        assertTrue(visualIsLogical(forced))
        // The same with the break opportunities the sheet inserts.
        assertTrue(visualIsLogical(SafeHref.forcedLtr(SafeText.breakAnywhere(SafeText.code(display)))))
        // Arabic letters and Arabic-Indic digits too.
        assertTrue(visualIsLogical(SafeHref.forcedLtr("https://example.com/\u0645\u0631\u062D\u0628\u0627/\u0661\u0662")))
    }

    // ---- length and fuzz ----------------------------------------------------------------------

    @Test fun overlongHrefsAreRefused() {
        refused("https://example.com/" + "a".repeat(SafeHref.MAX_HREF), Refusal.Length)
        assertTrue(SafeHref.isSafe("https://example.com/" + "a".repeat(SafeHref.MAX_HREF - 40)))
    }

    @Test fun boundedFuzzNeverAllowsAHostileCodePointAndNeverShowsOne() {
        val rnd = Random(0x7A_F2_3)
        val heads = listOf("https://example.com", "http://ex\u0430mple.com:8443", "https://[::1]", "HTTPS://Example.COM", "mailto:a@b.test", "mailto:a@b\u00FCcher.de", "https://1.2.3.4")
        val pieces = listOf("/", "?", "#", "@", ":", "8443", "%E2%80%AE", "%", ".", "-", "x", "\u05E9", "\u0645", "\u00FC", ",", "&", "subject=", "=", "evil.com", "a@b.test")
        var allowedCount = 0
        repeat(20_000) {
            val sb = StringBuilder(heads[rnd.nextInt(heads.size)])
            repeat(rnd.nextInt(7)) { sb.append(pieces[rnd.nextInt(pieces.size)]) }
            // A hostile code point at a random place (one case in three), the host included.
            if (rnd.nextInt(3) == 0) sb.insert(rnd.nextInt(sb.length + 1), s(hostile[rnd.nextInt(hostile.size)]))
            val href = sb.toString()
            val hasHostile = href.codePoints().anyMatch { it in hostileSet }
            val t = SafeHref.target(href)
            if (hasHostile) assertNull(esc(href), t)
            if (t != null) {
                allowedCount++
                assertFalse(esc(href), t.display.codePoints().anyMatch { SafeText.codeEscapes(it) || it <= 0x20 })
                assertEquals(esc(href), t.display, SafeText.code(t.display))
                assertTrue(esc(href), t.display.all { it in '!'..'~' })
                if (t.scheme == SafeHref.Scheme.Mailto) assertFalse(esc(href), '?' in t.display)
                if (t.scheme != SafeHref.Scheme.Mailto) {
                    assertFalse(esc(href), '@' in t.display.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#'))
                }
            }
        }
        assertTrue("the fuzz reached allowed hrefs: $allowedCount", allowedCount > 100)
    }
}
