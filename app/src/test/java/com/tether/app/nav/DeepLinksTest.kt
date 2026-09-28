package com.tether.app.nav

import com.tether.app.nav.LinkRejection.InvalidSessionId
import com.tether.app.nav.LinkRejection.Malformed
import com.tether.app.nav.LinkRejection.NoServer
import com.tether.app.nav.LinkRejection.OtherOrigin
import com.tether.app.nav.LinkRejection.UnknownRoute
import com.tether.app.nav.LinkRejection.UnsupportedScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.4: every link shape the app honours (the web's, at the parity base) and the hostile inputs
 * it must refuse. The parser is pure, so these run on the plain JVM.
 */
class DeepLinksTest {

    private val paired = "https://tether.example.com"
    private val pairedOrigin = "https://tether.example.com:443"

    private fun parse(link: String?, server: String? = paired) = DeepLinks.parse(link, server)
    private fun session(id: String, origin: String? = null) = ParsedLink.Open(Destination.Session(id), origin)
    private fun home(origin: String? = pairedOrigin) = ParsedLink.Open(Destination.Home, origin)
    private fun rejected(reason: LinkRejection) = ParsedLink.Rejected(reason)

    private fun assertAll(expected: ParsedLink, links: List<String?>, server: String? = paired) {
        for (link in links) assertEquals("link=$link", expected, parse(link, server))
    }

    // ---- the app's own scheme -----------------------------------------------------------

    @Test
    fun tetherSessionLinks() {
        assertEquals(session("sess-1"), parse("tether://session/sess-1"))
        assertEquals(session("0b8f7a8e-6c3d-4e2a-9f1b-2d3c4e5f6a7b"), parse("tether://session/0b8f7a8e-6c3d-4e2a-9f1b-2d3c4e5f6a7b"))
        // encodeURIComponent writes ':' as %3A.
        assertEquals(session("claude:1b2c"), parse("tether://session/claude%3A1b2c"))
        assertEquals(session("claude:1b2c"), parse("tether://session/claude:1b2c"))
        // Scheme and host are case-insensitive; the id is not.
        assertEquals(session("AbC"), parse("TETHER://Session/AbC"))
        assertEquals(session("a".repeat(128)), parse("tether://session/" + "a".repeat(128)))
    }

    @Test
    fun aTetherLinkNeedsNoServer() {
        assertEquals(session("sess-1"), parse("tether://session/sess-1", server = null))
    }

    @Test
    fun sessionLinkRoundTrips() {
        assertEquals("tether://session/sess-1", DeepLinks.sessionLink("sess-1"))
        assertEquals(session("claude:1b2c"), parse(DeepLinks.sessionLink("claude:1b2c")))
        assertNull(DeepLinks.sessionLink("a/b"))
        assertNull(DeepLinks.sessionLink(""))
    }

    @Test
    fun tetherLinksOtherThanASessionAreRefused() {
        assertAll(rejected(UnknownRoute), listOf("tether://kill/sess-1", "tether://approve/sess-1", "tether://settings", "tether:///sess-1", "tether://open"))
        assertAll(
            rejected(InvalidSessionId),
            listOf("tether://session", "tether://session/", "tether://session/a/b", "tether://session//a", "tether://session/a/"),
        )
        // Userinfo, a port, a query (no link may carry an action) and a fragment are refused.
        assertAll(
            rejected(Malformed),
            listOf(
                "tether://x@session/a",
                "tether://session:80/a",
                "tether://session%2Fa",
                "tether://session/a?approve=1",
                "tether://session/a?send=hello",
                "tether://session/a#frag",
                "tether:session/a",
                "tether:/session/a",
            ),
        )
    }

    // ---- the web's URL shapes, paired origin only -----------------------------------------

    @Test
    fun theWebsSessionDeepLink() {
        // lib/push-notifications.mjs sessionDeepLink: `/?session=${encodeURIComponent(id)}`.
        assertEquals(session("sess-1", pairedOrigin), parse("https://tether.example.com/?session=sess-1"))
        assertEquals(session("claude:1b2c", pairedOrigin), parse("https://tether.example.com/?session=claude%3A1b2c"))
        // URLSearchParams.get: the first `session`, keys decoded too, other params ignored.
        assertEquals(session("abc", pairedOrigin), parse("https://tether.example.com/?x=1&session=abc&y=2"))
        assertEquals(session("a", pairedOrigin), parse("https://tether.example.com/?session=a&session=b"))
        assertEquals(session("abc", pairedOrigin), parse("https://tether.example.com/?sess%69on=abc"))
        // No path is the root.
        assertEquals(session("abc", pairedOrigin), parse("https://tether.example.com?session=abc"))
        // The fragment is dropped (sw.js safeTarget), the query before it still counts.
        assertEquals(session("abc", pairedOrigin), parse("https://tether.example.com/?session=abc#frag"))
    }

    @Test
    fun theOriginIsCanonicalisedBeforeTheCompare() {
        for (link in listOf(
            "https://TETHER.Example.COM/?session=abc",
            "https://tether.example.com:443/?session=abc",
            "HTTPS://tether.example.com/?session=abc",
        )) assertEquals(link, session("abc", pairedOrigin), parse(link))
        // The stored base URL may carry a path or a trailing slash; the origin is what counts.
        assertEquals(session("abc", pairedOrigin), parse("https://tether.example.com/?session=abc", server = "https://tether.example.com/"))
    }

    @Test
    fun plainHttpIpv6AndIdnServers() {
        assertEquals(
            session("abc", "http://192.168.1.20:4173"),
            parse("http://192.168.1.20:4173/?session=abc", server = "http://192.168.1.20:4173"),
        )
        assertEquals(
            session("abc", "http://[fd00::5]:3000"),
            parse("http://[fd00::5]:3000/?session=abc", server = "http://[fd00::5]:3000"),
        )
        // An IDN server is stored and compared in punycode; the Unicode spelling of a link is refused.
        assertEquals(
            session("abc", "https://xn--bcher-kva.example:443"),
            parse("https://xn--bcher-kva.example/?session=abc", server = "https://bücher.example"),
        )
        assertEquals(rejected(Malformed), parse("https://bücher.example/?session=abc", server = "https://bücher.example"))
    }

    @Test
    fun theWebsOtherPagesOpenTheApp() {
        assertAll(
            home(),
            listOf(
                "https://tether.example.com",
                "https://tether.example.com/",
                "https://tether.example.com/?session=",
                "https://tether.example.com/?other=1",
                "https://tether.example.com/usage",
                "https://tether.example.com/login",
                "https://tether.example.com/setup",
                "https://tether.example.com/web",
                // Only the dashboard reads `session`.
                "https://tether.example.com/usage?session=abc",
                // A `?session=` inside the fragment is never read.
                "https://tether.example.com/#?session=abc",
                "https://tether.example.com/#/?session=abc",
            ),
        )
    }

    @Test
    fun unknownPathsOnThePairedServerAreNotRouted() {
        assertAll(
            rejected(UnknownRoute),
            listOf(
                "https://tether.example.com/api/auth/logout",
                "https://tether.example.com/api/devices/pair",
                "https://tether.example.com/settings?session=abc",
                "https://tether.example.com/usage/",
                "https://tether.example.com/ws",
            ),
        )
    }

    // ---- hostile inputs ------------------------------------------------------------------

    @Test
    fun otherHostsNeverMatch() {
        assertAll(
            rejected(OtherOrigin),
            listOf(
                "https://evil.example/?session=abc",
                "https://tether.example.com.evil.example/?session=abc",
                "https://evil.example/tether.example.com/?session=abc",
                "https://a.tether.example.com/?session=abc",
                "https://example.com/?session=abc",
                "https://tether.example.com:8443/?session=abc",
                "http://tether.example.com/?session=abc",
                // A punycode lookalike ("tеther" with a Cyrillic е) is simply another host.
                "https://xn--tther-zwe.example.com/?session=abc",
            ),
        )
    }

    @Test
    fun userinfoAndAmbiguousAuthoritiesAreRefused() {
        assertAll(
            rejected(Malformed),
            listOf(
                "https://user@tether.example.com/?session=abc",
                "https://user:pw@tether.example.com/?session=abc",
                "https://tether.example.com@evil.example/?session=abc",
                "https://evil.example@tether.example.com/?session=abc",
                "https://tether.example.com%40evil.example/?session=abc",
                "https://%74ether.example.com/?session=abc",
                "https://evil.example\\@tether.example.com/?session=abc",
                "https:\\\\tether.example.com/?session=abc",
                "https:/tether.example.com/?session=abc",
                "https:tether.example.com/?session=abc",
                "https://",
                "https:///?session=abc",
                "https://tether.example.com:/?session=abc",
                "https://tether.example.com:99999/?session=abc",
                "https://tether.example.com:44x3/?session=abc",
                "https://[fd00::5/?session=abc",
            ),
        )
    }

    @Test
    fun hostSpellingsTheTwoParsersCouldReadDifferentlyAreRefused() {
        // OkHttp canonicalises these hosts; the raw authority keeps the spelling. Any disagreement
        // is refused, even when the canonical origin would match the paired one.
        for ((server, link) in listOf(
            paired to "https://tether.example.com./?session=abc",
            "http://[fd00::5]:3000" to "http://[FD00:0:0::5]:3000/?session=abc",
            "http://[fd00::5]:3000" to "http://[fd00:0000::5]:3000/?session=abc",
            "http://127.0.0.1:4173" to "http://2130706433:4173/?session=abc",
            "http://127.0.0.1:4173" to "http://0x7f.0.0.1:4173/?session=abc",
            "http://127.0.0.1:4173" to "http://127.1:4173/?session=abc",
        )) assertTrue(link, parse(link, server) is ParsedLink.Rejected)
    }

    @Test
    fun idnLookalikesNeverReachTheHostCompare() {
        // Cyrillic 'е' and 'а', a zero-width joiner, a fullwidth dot: non-ASCII is refused outright.
        assertAll(
            rejected(Malformed),
            listOf(
                "https://tеther.example.com/?session=abc",
                "https://tether.exаmple.com/?session=abc",
                "https://teth‍er.example.com/?session=abc",
                "https://tether.example．com/?session=abc",
                "tether://session/аbc",
            ),
        )
    }

    @Test
    fun encodedSlashesAndDotSegmentsAreRefused() {
        assertAll(
            rejected(InvalidSessionId),
            listOf(
                "tether://session/abc%2F..%2Fx",
                "tether://session/..%2F..%2Fetc",
                "tether://session/a%252Fb",
                "tether://session/%2E%2E",
                "https://tether.example.com/?session=..%2F..%2Fetc",
                "https://tether.example.com/?session=a%252Fb",
                "https://tether.example.com/?session=a+b",
                "https://tether.example.com/?session=%ZZ",
                "https://tether.example.com/?session=%E2%80%AEabc",
                "https://tether.example.com/?session=%0Aabc",
                "https://tether.example.com/?session=%C0%AF",
            ),
        )
        assertAll(
            rejected(Malformed),
            listOf(
                "https://tether.example.com/%2F?session=abc",
                "https://tether.example.com/usage/..?session=abc",
                "https://tether.example.com/./?session=abc",
                "https://tether.example.com/x/../?session=abc",
                "https://tether.example.com//?session=abc",
            ),
        )
    }

    @Test
    fun badIdsAreRefused() {
        assertAll(
            rejected(InvalidSessionId),
            listOf(
                "tether://session/" + "a".repeat(129),
                "tether://session/-leading-dash",
                "tether://session/.hidden",
                "tether://session/a%20b",
                "tether://session/%00",
                "tether://session/a%",
                "tether://session/a%4",
                "https://tether.example.com/?session=" + "a".repeat(129),
                "https://tether.example.com/?session=%3Cscript%3E",
            ),
        )
    }

    @Test
    fun overlongLinksAreRefused() {
        assertEquals(rejected(Malformed), parse("tether://session/" + "a".repeat(DeepLinks.MAX_LINK)))
        // The web target keeps the web's own 512-character cap.
        assertEquals(rejected(Malformed), parse("https://tether.example.com/?session=s1" + "&x".repeat(300)))
    }

    @Test
    fun whitespaceAndControlCharactersAreRefused() {
        assertAll(
            rejected(Malformed),
            listOf(
                "",
                null,
                " tether://session/a",
                "tether://session/a ",
                "tether://session/a\n",
                "tether://session/a\u0000",
                "tether://session/‮abc",
                "https://tether.example.com/\t?session=abc",
                "https://tether.example.com/?session=abc\r\n",
            ),
        )
    }

    @Test
    fun otherSchemesAreRefused() {
        assertAll(
            rejected(UnsupportedScheme),
            listOf(
                "javascript:alert(1)",
                "JavaScript:alert(document.cookie)",
                "javascript://tether.example.com/%0Aalert(1)",
                "intent://session/abc#Intent;scheme=tether;package=com.tether.app;end",
                "intent:#Intent;action=android.intent.action.VIEW;end",
                "content://com.tether.app.provider/x",
                "file:///data/data/com.tether.app/files/settings",
                "data:text/html,<script>alert(1)</script>",
                "mailto:someone@example.com",
                "ftp://tether.example.com/?session=abc",
                "ws://tether.example.com/ws",
                "wss://tether.example.com/ws",
            ),
        )
        assertAll(rejected(Malformed), listOf("/?session=abc", "//evil.example/?session=abc", "session/abc", ":abc", "1http://tether.example.com/"))
    }

    @Test
    fun anHttpLinkNeedsAKnownServer() {
        assertEquals(rejected(NoServer), parse("https://tether.example.com/?session=abc", server = null))
        assertEquals(rejected(NoServer), parse("https://tether.example.com/?session=abc", server = ""))
        assertEquals(rejected(NoServer), parse("https://tether.example.com/?session=abc", server = "not a url"))
    }

    @Test
    fun theWebTargetParserMatchesTheServiceWorkerRules() {
        assertEquals(ParsedLink.Open(Destination.Session("sess-1")), DeepLinks.parseWebTarget("/?session=sess-1"))
        assertEquals(ParsedLink.Open(Destination.Home), DeepLinks.parseWebTarget("/"))
        assertEquals(rejected(Malformed), DeepLinks.parseWebTarget("//evil.example/?session=a"))
        assertEquals(rejected(Malformed), DeepLinks.parseWebTarget("https://evil.example/?session=a"))
        assertEquals(rejected(Malformed), DeepLinks.parseWebTarget("?session=a"))
    }
}
