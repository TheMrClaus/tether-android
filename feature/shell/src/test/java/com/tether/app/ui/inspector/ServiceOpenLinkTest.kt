package com.tether.app.ui.inspector

import com.tether.app.client.serverOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T15.7: the "Open" link's pin. The server sends the console-relative
 * `/api/worktree/open?session=<id>&script=<name>` (server.mjs, both encodeURIComponent); the app
 * opens exactly that, for exactly this session and script, on the paired origin, or nothing.
 * Every control character in this file is written as an escape.
 */
class ServiceOpenLinkTest {
    private val origin = serverOrigin("https://Tether.Example.test/")!!
    private val session = "s1"
    private val script = "web"
    private val good = "/api/worktree/open?session=s1&script=web"

    private fun resolve(raw: String?, sessionId: String = session, scriptName: String = script, paired: String? = origin) =
        ServiceOpenLink.resolve(raw, sessionId, scriptName, paired)

    @Test fun theWebsOwnLinkResolvesAgainstTheCanonicalPairedOrigin() {
        assertEquals("https://tether.example.test:443", origin)
        assertEquals("https://tether.example.test:443/api/worktree/open?session=s1&script=web", resolve(good))
        // Either order: two parameters, each once.
        assertEquals("https://tether.example.test:443/api/worktree/open?script=web&session=s1", resolve("/api/worktree/open?script=web&session=s1"))
        // A plain-http LAN or loopback server keeps its own scheme and port.
        assertEquals(
            "http://192.168.1.20:4173/api/worktree/open?session=s1&script=web",
            resolve(good, paired = serverOrigin("http://192.168.1.20:4173")),
        )
    }

    @Test fun encodedNamesRoundTripExactlyAsTheServerWroteThem() {
        // encodeURIComponent of a session id and a script name with spaces, non-ASCII and reserved characters.
        val sid = "a b/c?d"
        val name = "d\u00E9v:app"
        val raw = "/api/worktree/open?session=${ServiceOpenLink.encodeComponent(sid)}&script=${ServiceOpenLink.encodeComponent(name)}"
        assertEquals("/api/worktree/open?session=a%20b%2Fc%3Fd&script=d%C3%A9v%3Aapp", raw)
        assertEquals(origin + raw, resolve(raw, sessionId = sid, scriptName = name))
        // encodeURIComponent's unreserved marks stay literal.
        assertEquals("a-_.!~*'()", ServiceOpenLink.encodeComponent("a-_.!~*'()"))
        assertNull("a lone surrogate (JS throws)", ServiceOpenLink.encodeComponent("a\uD800"))
    }

    @Test fun nullAbsentOrNoOriginIsNoLink() {
        assertNull(resolve(null))
        assertNull(resolve(""))
        assertNull("no paired server", resolve(good, paired = null))
        assertNull("not an http(s) origin", resolve(good, paired = "ftp://tether.example.test:21"))
        assertNull("an origin with a path", resolve(good, paired = "https://tether.example.test:443/base"))
        assertNull("an origin with userinfo", resolve(good, paired = "https://u@tether.example.test:443"))
        assertNull("no current session", resolve(good, sessionId = ""))
        assertNull("no script name", resolve(good, scriptName = ""))
    }

    @Test fun wrongPathIsNoLink() {
        for (raw in listOf(
            "/api/worktree/opener?session=s1&script=web",
            "/api/worktree/open/?session=s1&script=web",
            "/API/worktree/open?session=s1&script=web",
            "/api/worktree/open",
            "/api/worktree/open&session=s1&script=web",
            "api/worktree/open?session=s1&script=web",
            "/api/auth/logout?session=s1&script=web",
            "/api/worktree/open;x?session=s1&script=web",
        )) assertNull(raw, resolve(raw))
    }

    @Test fun dotSegmentsAndEncodedPathTricksAreNoLink() {
        for (raw in listOf(
            "/api/worktree/./open?session=s1&script=web",
            "/api/worktree/../worktree/open?session=s1&script=web",
            "/api/x/../worktree/open?session=s1&script=web",
            "/api/worktree%2Fopen?session=s1&script=web",
            "/api/worktree%2fopen?session=s1&script=web",
            "/api/worktree/%6Fpen?session=s1&script=web",
            "/api/worktree/open%3Fsession=s1&script=web",
            "/%2E%2E/api/worktree/open?session=s1&script=web",
        )) assertNull(raw, resolve(raw))
    }

    @Test fun schemesAuthoritiesAndBackslashesAreNoLink() {
        for (raw in listOf(
            "javascript:alert(1)//api/worktree/open?session=s1&script=web",
            "javascript:alert(1)",
            "file:///api/worktree/open?session=s1&script=web",
            "intent://api/worktree/open?session=s1&script=web#Intent;scheme=https;end",
            "https://evil.example/api/worktree/open?session=s1&script=web",
            // Even the console's own absolute URL: the server sends a relative one, nothing else is accepted.
            "https://tether.example.test/api/worktree/open?session=s1&script=web",
            "//evil.example/api/worktree/open?session=s1&script=web",
            "///evil.example/api/worktree/open?session=s1&script=web",
            "/\\evil.example/api/worktree/open?session=s1&script=web",
            "\\api\\worktree\\open?session=s1&script=web",
            "/api\\worktree/open?session=s1&script=web",
            "/api/worktree/open?session=s1&script=web\\",
            "//u:p@evil.example/api/worktree/open?session=s1&script=web",
            "/api/worktree/open?session=s1&script=web@evil.example",
        )) assertNull(raw, resolve(raw))
    }

    @Test fun aFragmentOrASecondQueryIsNoLink() {
        assertNull(resolve("$good#x"))
        assertNull(resolve("$good#"))
        assertNull(resolve("/api/worktree/open?session=s1&script=web?x=1"))
        assertNull(resolve("/api/worktree/open??session=s1&script=web"))
    }

    @Test fun theQueryHasExactlySessionAndScriptOnce() {
        for (raw in listOf(
            "/api/worktree/open?session=s1",
            "/api/worktree/open?script=web",
            "/api/worktree/open?",
            "/api/worktree/open?session=s1&script=web&next=https://evil.example",
            "/api/worktree/open?session=s1&session=s1&script=web",
            "/api/worktree/open?session=s1&script=web&script=web",
            "/api/worktree/open?session=s1&session=s2",
            "/api/worktree/open?session=s1&&script=web",
            "/api/worktree/open?&session=s1&script=web",
            "/api/worktree/open?session=s1&script=web&",
            "/api/worktree/open?session=s1&script",
            "/api/worktree/open?session=s1&script=",
            "/api/worktree/open?session=&script=web",
            "/api/worktree/open?=s1&script=web",
            "/api/worktree/open?Session=s1&script=web",
            // A parameter NAME is never escaped.
            "/api/worktree/open?%73ession=s1&script=web",
            "/api/worktree/open?session%3Ds1&script=web",
            "/api/worktree/open?session=s1;script=web",
        )) assertNull(raw, resolve(raw))
    }

    @Test fun wrongSessionOrScriptIsNoLink() {
        assertNull("another session", resolve("/api/worktree/open?session=s2&script=web"))
        assertNull("another script", resolve("/api/worktree/open?session=s1&script=api"))
        assertNull("case matters", resolve("/api/worktree/open?session=S1&script=web"))
        assertNull("prefix only", resolve("/api/worktree/open?session=s1x&script=web"))
        assertNull("the row's name, not the snapshot's", resolve(good, scriptName = "web2"))
        assertNull("the shown session, not the link's", resolve(good, sessionId = "s2"))
    }

    @Test fun strictDecodingRefusesMalformedAmbiguousAndControlValues() {
        for (raw in listOf(
            // malformed escapes
            "/api/worktree/open?session=s%&script=web",
            "/api/worktree/open?session=s%3&script=web",
            "/api/worktree/open?session=s%G1&script=web",
            "/api/worktree/open?session=s1&script=we%",
            // + vs space: never read either way
            "/api/worktree/open?session=s1&script=my+app",
            // NUL, LF, CR, TAB, DEL, C1 decoded
            "/api/worktree/open?session=s1%00&script=web",
            "/api/worktree/open?session=s1&script=web%0A",
            "/api/worktree/open?session=s1&script=web%0D%0ALocation:x",
            "/api/worktree/open?session=s1&script=we%09b",
            "/api/worktree/open?session=s1&script=web%7F",
            "/api/worktree/open?session=s1&script=web%C2%85",
            // invalid UTF-8: an overlong slash, a lone continuation byte, a cut sequence, an encoded surrogate
            "/api/worktree/open?session=s1&script=%C0%AF",
            "/api/worktree/open?session=s1&script=%80",
            "/api/worktree/open?session=s1&script=%E2%80",
            "/api/worktree/open?session=s1&script=%ED%A0%80",
        )) assertNull(raw, resolve(raw, scriptName = "web"))
        assertNull("decodes to the name, but with a NUL", resolve("/api/worktree/open?session=s1&script=web%00", scriptName = "web\u0000"))
    }

    @Test fun nonCanonicalEncodingIsNoLinkEvenWhenItDecodesToTheRightName() {
        for (raw in listOf(
            "/api/worktree/open?session=%73%31&script=web", // over-encoded "s1"
            "/api/worktree/open?session=s1&script=w%65b",
            "/api/worktree/open?session=s1&script=d%c3%a9v", // lower-case hex
        )) assertNull(raw, resolve(raw, scriptName = if ("d%c3" in raw) "d\u00E9v" else "web"))
        // Literal reserved or unsafe characters that encodeURIComponent would have escaped.
        assertNull(resolve("/api/worktree/open?session=s1&script=a/b", scriptName = "a/b"))
        assertNull(resolve("/api/worktree/open?session=s1&script=a:b", scriptName = "a:b"))
        assertNull(resolve("/api/worktree/open?session=s1&script=a=b", scriptName = "a=b"))
    }

    @Test fun hostileCharactersAnywhereAreNoLink() {
        for (raw in listOf(
            "/api/worktree/open?session=s1&script=web\u202E",
            "/api/worktree/open?session=s1&script=w\u200Beb",
            "/api/worktree/open?session=s1&script=web\u0000",
            "/api/worktree/open?session=s1&script=web\n",
            "/api/worktree/open?session=s1&script=web ",
            " /api/worktree/open?session=s1&script=web",
            "\u202E/api/worktree/open?session=s1&script=web",
            "/api/worktree\uFF0Fopen?session=s1&script=web",
            "/api/worktree/open?session=s1&script=w\u00E9b",
        )) assertNull(raw.map { if (it.code in 0x21..0x7E) it.toString() else "\\u%04X".format(it.code) }.joinToString(""), resolve(raw, scriptName = raw.substringAfter("script=")))
    }

    @Test fun aHugeLinkIsNoLink() {
        val name = "a".repeat(ServiceOpenLink.MAX_RAW)
        assertNull(resolve("/api/worktree/open?session=s1&script=$name", scriptName = name))
        // Just under the bound still resolves.
        val fits = "a".repeat(ServiceOpenLink.MAX_RAW - good.length + 3)
        val raw = "/api/worktree/open?session=s1&script=$fits"
        assertEquals(ServiceOpenLink.MAX_RAW, raw.length)
        assertEquals(origin + raw, resolve(raw, scriptName = fits))
    }

    @Test fun aHostileOriginNeverYieldsALink() {
        // RLO / userinfo in what claims to be the paired origin: SafeHref refuses the resolved URL.
        assertNull(resolve(good, paired = "https://tether.example\u202E.test:443"))
        assertNull(resolve(good, paired = "https://good.example@evil.example:443"))
        assertNull(resolve(good, paired = "https://:443"))
    }
}
