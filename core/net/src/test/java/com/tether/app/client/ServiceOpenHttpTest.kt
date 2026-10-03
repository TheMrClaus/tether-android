package com.tether.app.client

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-coik.2: "Open" asks `/api/worktree/open` with the app's own credential (server.mjs:7510-7541:
 * owner-grade, a device principal parents the handoff) and returns the 303's service-host target,
 * never following it; every refusal says why and opens nothing.
 */
class ServiceOpenHttpTest {
    private val console = MockWebServer()
    private val service = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde"

    @Before fun setUp() {
        console.start()
        service.start()
    }

    @After fun tearDown() {
        console.shutdown()
        service.shutdown()
    }

    private val paired get() = FilesAuthority.Paired(console.url("/")) { it.header("Authorization", "Bearer tthr_test") }
    private val source get() = HttpServiceOpen(noRedirects, authority = { paired })
    private val link get() = "http://${console.hostName}:${console.port}/api/worktree/open?session=s%201&script=web"

    /** The service origin the snapshot named (its proxyUrl); the redirect must go there. */
    private val host get() = "http://${service.hostName}:${service.port}"
    private val target get() = "http://${service.hostName}:${service.port}/?tether-auth=$token"

    private fun redirect(location: String) = MockResponse().setResponseCode(303).setHeader("Location", location).setHeader("Cache-Control", "no-store")

    @Test fun theRouteIsAskedWithTheAppCredentialAndItsTargetIsReturnedNotFollowed() = runBlocking<Unit> {
        console.enqueue(redirect(target))
        val outcome = source.open(link, host)
        assertTrue(outcome.toString(), outcome is ServiceOpenSource.Outcome.Open)
        assertEquals(target, (outcome as ServiceOpenSource.Outcome.Open).url)
        val asked = console.takeRequest()
        assertEquals("GET", asked.method)
        assertEquals("/api/worktree/open?session=s%201&script=web", asked.path)
        assertEquals("Bearer tthr_test", asked.getHeader("Authorization"))
        assertEquals("never followed: the credential never reaches the service host", 0, service.requestCount)
        assertFalse("the outcome never prints the handoff", token in outcome.toString())
    }

    @Test fun aRedirectAnywhereButTheServiceHandoffIsAGatewayAndOpensNothing() = runBlocking<Unit> {
        for (location in listOf(
            "https://login.example.test/authorize?rd=x",
            "http://${service.hostName}:${service.port}/login",
            "http://${service.hostName}:${service.port}/?tether-auth=$token&next=x",
            "http://${service.hostName}:${service.port}/?tether-auth=short",
            "http://${service.hostName}:${service.port}/?tether-auth=$token#f",
            "http://u:p@${service.hostName}:${service.port}/?tether-auth=$token",
            "ftp://${service.hostName}/?tether-auth=$token",
            "/?tether-auth=$token",
        )) {
            console.enqueue(redirect(location))
            assertEquals(location, ServiceOpenSource.Outcome.Refused(ServiceOpenSource.GATEWAY), source.open(link, host))
        }
        // The right handoff for ANOTHER host than the snapshot named.
        console.enqueue(redirect(target))
        assertEquals(ServiceOpenSource.Outcome.Refused(ServiceOpenSource.GATEWAY), source.open(link, "http://other.example.test:${service.port}"))
        // ...or with no proxyUrl at all.
        console.enqueue(redirect(target))
        assertEquals(ServiceOpenSource.Outcome.Refused(ServiceOpenSource.GATEWAY), source.open(link, null))
        assertEquals(0, service.requestCount)
    }

    @Test fun theConsolesOwnRefusalIsShownAsItSaysIt() = runBlocking<Unit> {
        for ((code, error) in listOf(
            403 to "This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app).",
            404 to "That service has no proxied address.",
            409 to "Own address not configured.",
        )) {
            console.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody("""{"error":"$error"}"""))
            assertEquals(ServiceOpenSource.Outcome.Refused(error), source.open(link, host))
        }
        // A sign-in gateway's HTML 401 is not Tether's answer.
        console.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html").setBody("<html>"))
        assertEquals(ServiceOpenSource.Outcome.Refused(ServiceOpenSource.GATEWAY), source.open(link, host))
        // JSON without an error string: the generic sentence.
        console.enqueue(MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json").setBody("""{"error":7}"""))
        assertEquals(ServiceOpenSource.Outcome.Refused(ServiceOpenSource.NOT_OPENED), source.open(link, host))
    }

    @Test fun anythingButThePairedServersWorktreeOpenRouteSendsNothing() = runBlocking<Unit> {
        val base = "http://${console.hostName}:${console.port}"
        for (asked in listOf(
            "http://${service.hostName}:${service.port}/api/worktree/open?session=s1&script=web",
            "$base/api/worktree/close?session=s1&script=web",
            "$base/api/worktree/open?session=s1",
            "$base/api/worktree/open?session=s1&script=web&x=1",
            "$base/api/worktree/open?session=s1&script=web#f",
            "not a url",
        )) {
            assertEquals(asked, ServiceOpenSource.Outcome.Refused(ServiceOpenSource.NOT_OPENED), source.open(asked, host))
        }
        assertEquals(0, console.requestCount)
        assertEquals(0, service.requestCount)
        // Signed out / local network off: nothing sent either.
        assertEquals(ServiceOpenSource.Outcome.Refused(ServiceOpenSource.SIGNED_OUT), HttpServiceOpen(noRedirects, { FilesAuthority.SignedOut }).open(link, host))
        assertEquals(ServiceOpenSource.Outcome.Refused(ServiceOpenSource.LOCAL_NETWORK), HttpServiceOpen(noRedirects, { FilesAuthority.LocalNetworkBlocked }).open(link, host))
        assertEquals(0, console.requestCount)
    }

    @Test fun noAnswerIsUnreachable() = runBlocking<Unit> {
        val dead = "http://${console.hostName}:${console.port}"
        console.shutdown()
        assertEquals(ServiceOpenSource.Outcome.Refused(ServiceOpenSource.UNREACHABLE), source.open("$dead/api/worktree/open?session=s1&script=web", host))
    }

    @Test fun aClientThatFollowsRedirectsIsRefused() {
        val threw = runCatching { HttpServiceOpen(OkHttpClient(), { FilesAuthority.SignedOut }) }.exceptionOrNull()
        assertTrue(threw is IllegalArgumentException)
    }

    @Test fun handoffTargetIsExactlyWhatTheServerWrites() {
        assertEquals("https://web--feat.svc.example.test/?tether-auth=$token", ServiceOpenSource.handoffTarget("https://web--feat.svc.example.test/?tether-auth=$token", "https://web--feat.svc.example.test"))
        assertEquals("https://web.svc.example.test:8443/?tether-auth=$token", ServiceOpenSource.handoffTarget("https://web.svc.example.test:8443/?tether-auth=$token", "https://web.svc.example.test:8443"))
        // A parser-normalised variant (upper-case host, default port) is not byte for byte the server's.
        assertNull(ServiceOpenSource.handoffTarget("https://WEB.svc.example.test/?tether-auth=$token", "https://web.svc.example.test"))
        assertNull(ServiceOpenSource.handoffTarget("https://web.svc.example.test:443/?tether-auth=$token", "https://web.svc.example.test"))
        assertNull(ServiceOpenSource.handoffTarget("https://web.svc.example.test/?tether-auth=$token\n", "https://web.svc.example.test"))
        assertNull(ServiceOpenSource.handoffTarget(null, "https://web.svc.example.test"))
    }

    /**
     * ta-t5rl: the handoff goes only to the origin the snapshot's proxyUrl names (server.mjs writes
     * `${proxyUrl}/?tether-auth=...`; a browser following that 303 stays on exactly that origin):
     * scheme, host and effective port, compared normalised.
     */
    @Test fun theRedirectIsPinnedToTheSnapshotsWholeOrigin() {
        val h = "web.svc.example.test"
        // The exact origin.
        assertEquals("https://$h/?tether-auth=$token", ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", "https://$h"))
        assertEquals("http://$h:4173/?tether-auth=$token", ServiceOpenSource.handoffTarget("http://$h:4173/?tether-auth=$token", "http://$h:4173"))
        // The same origin, its default port written explicitly in the snapshot (or its host in upper case).
        assertEquals("https://$h/?tether-auth=$token", ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", "https://$h:443"))
        assertEquals("http://$h/?tether-auth=$token", ServiceOpenSource.handoffTarget("http://$h/?tether-auth=$token", "http://$h:80"))
        assertEquals("https://$h/?tether-auth=$token", ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", "https://WEB.svc.example.test"))
        // Another port on the same host.
        assertNull(ServiceOpenSource.handoffTarget("https://$h:9999/?tether-auth=$token", "https://$h"))
        assertNull(ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", "https://$h:8443"))
        assertNull(ServiceOpenSource.handoffTarget("http://$h:4174/?tether-auth=$token", "http://$h:4173"))
        // An http downgrade (default or same port), or an upgrade the snapshot did not name.
        assertNull(ServiceOpenSource.handoffTarget("http://$h/?tether-auth=$token", "https://$h"))
        assertNull(ServiceOpenSource.handoffTarget("http://$h:443/?tether-auth=$token", "https://$h"))
        assertNull(ServiceOpenSource.handoffTarget("http://$h:8443/?tether-auth=$token", "https://$h:8443"))
        assertNull(ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", "http://$h"))
        // Another host; no or no usable proxyUrl.
        assertNull(ServiceOpenSource.handoffTarget("https://other.example.test/?tether-auth=$token", "https://$h"))
        assertNull(ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", null))
        assertNull(ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", h))
        assertNull(ServiceOpenSource.handoffTarget("https://$h/?tether-auth=$token", ""))
    }

    @Test fun overHttpAnotherPortOrADowngradeOpensNothing() = runBlocking<Unit> {
        val serviceUrl = "https://${service.hostName}:${service.port}"
        for (location in listOf(
            "https://${service.hostName}:9999/?tether-auth=$token",
            "http://${service.hostName}:${service.port}/?tether-auth=$token",
        )) {
            console.enqueue(redirect(location))
            assertEquals(location, ServiceOpenSource.Outcome.Refused(ServiceOpenSource.GATEWAY), source.open(link, serviceUrl))
        }
        val exact = "$serviceUrl/?tether-auth=$token"
        console.enqueue(redirect(exact))
        assertEquals(exact, (source.open(link, serviceUrl) as ServiceOpenSource.Outcome.Open).url)
        assertEquals(0, service.requestCount)
    }
}
