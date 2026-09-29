package com.tether.app.client

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-41x: tether#216 accepts a write made with an ambient credential (the session cookie) only
 * from the console's own origin: an `Origin` whose host(+port) equals `X-Forwarded-Host`, else
 * `Host`. Every request that carries the cookie sends the same `Origin` the `/ws` upgrade sends,
 * and every cookie POST declares `application/json` (the JSON routes answer 415 otherwise). A
 * paired device's bearer token is not ambient: its requests are unchanged (no Origin).
 */
class CookieOriginTest {
    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private val listing = """{"current":"/w","parent":null,"breadcrumbs":[{"name":"w","path":"/w"}],"entries":[]}"""
    private val mutation = """{"ok":true,"parent":"/w"}"""
    private val png = "/api/tool-media/${"a".repeat(64)}.png"

    /** Signed in over a live socket; returns the Origin the `/ws` upgrade carried. */
    private fun connected(http: OkHttpClient = OkHttpClient(), baseUrl: String? = null, cookie: String? = "cookie", token: String? = null): String {
        h.server.start()
        val base = baseUrl ?: h.server.url("/").toString().trimEnd('/')
        h.settings = InMemorySettings(initialBaseUrl = base, initialCookie = cookie, initialDeviceToken = token)
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = http,
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.enqueueConnect()
        h.client.start()
        h.handshake(h.nextSocket())
        val probe = take()
        assertEquals("/api/auth/session", probe.path)
        val upgrade = take()
        assertEquals("/ws", upgrade.path)
        val origin = upgrade.getHeader("Origin")
        assertNotNull("the /ws upgrade carries an Origin", origin)
        // The auth probe (a GET) goes out with the cookie too, so it carries the same Origin.
        assertEquals(if (cookie != null) origin else null, probe.getHeader("Origin"))
        return origin!!
    }

    /** Every HTTP call the client makes with the credential in force, each answered 200. */
    private fun everyCredentialedCall(): List<RecordedRequest> = runBlocking {
        val out = mutableListOf<RecordedRequest>()
        fun ok(body: String = mutation) = h.server.enqueue(MockResponse().setBody(body))
        ok(listing); h.client.files.list("/w"); out += take()
        ok(); h.client.files.mkdir("/w", "d"); out += take()
        ok(); h.client.files.touch("/w", "f"); out += take()
        ok(); h.client.files.rename("/w/f", "g"); out += take()
        ok(); h.client.files.move("/w/g", "/w/d"); out += take()
        ok(); h.client.files.copy("/w/d/g", "/w"); out += take()
        ok(); h.client.files.delete("/w/g"); out += take()
        ok()
        h.client.files.upload("/w", "u.txt", object : UploadSource {
            override val length: Long = 3
            override fun open() = "abc".byteInputStream()
        })
        out += take()
        h.server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("12345678"))
        h.client.toolMedia.fetch(png, 1024, ByteArrayOutputStream()); out += take()
        ok(STATS); h.client.fetchStats(); out += take()
        out
    }

    /** The owner-grade sessions API (cookie only) and the logout revoke, last. */
    private fun sessionsThenLogout(): List<RecordedRequest> = runBlocking {
        val out = mutableListOf<RecordedRequest>()
        h.server.enqueue(MockResponse().setBody("""{"sessions":[]}""")); h.client.listSignInSessions(); out += take()
        h.server.enqueue(MockResponse().setBody("""{"ok":true}""")); h.client.revokeSignInSession("other"); out += take()
        h.server.enqueue(MockResponse().setBody("""{"revoked":1}""")); h.client.revokeOtherSignInSessions(); out += take()
        h.server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertEquals(LogoutResult.Revoked, h.client.logout())
        out += generateSequence { h.server.takeRequest(20, TimeUnit.SECONDS) }.first { it.path == "/api/auth/logout" }
        out
    }

    /** The server's rule (lib/origin-guard.mjs consoleOriginMatches): the Origin's host(+port) is the Host. */
    private fun assertConsoleOrigin(request: RecordedRequest, wsOrigin: String) {
        val what = "${request.method} ${request.path}"
        assertEquals("$what: the Origin the /ws upgrade sends", wsOrigin, request.getHeader("Origin"))
        assertEquals("$what: Origin host(+port) == Host", request.getHeader("Host"), request.getHeader("Origin")!!.toHttpUrl().let { u ->
            bracketedHost(u.host) + if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
        })
    }

    private fun assertJsonPost(request: RecordedRequest) {
        if (request.method != "POST") return
        val type = request.getHeader("Content-Type")
        assertEquals("${request.path}: a cookie POST is declared JSON (got $type)", "application/json", type?.substringBefore(';')?.trim()?.lowercase())
    }

    @Test
    fun everyCookieRequestCarriesTheSocketsOriginAndEveryCookiePostIsJson() {
        val wsOrigin = connected()
        assertEquals("http://${h.server.hostName}:${h.server.port}", wsOrigin)
        val requests = everyCredentialedCall() + sessionsThenLogout()
        // Every route, by method, so a route that is dropped from the list is noticed.
        assertEquals(
            listOf(
                "GET /api/files/list", "POST /api/files/mkdir", "POST /api/files/touch", "POST /api/files/rename",
                "POST /api/files/move", "POST /api/files/copy", "DELETE /api/files", "PUT /api/files/upload",
                "GET $png", "GET /api/stats", "GET /api/auth/sessions", "DELETE /api/auth/sessions/other",
                "DELETE /api/auth/sessions", "POST /api/auth/logout",
            ),
            requests.map { "${it.method} ${it.path!!.substringBefore('?')}" },
        )
        for (request in requests) {
            assertEquals("${request.path}: the cookie", "tether_session=cookie", request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            assertConsoleOrigin(request, wsOrigin)
            assertJsonPost(request)
        }
        // The logout body is empty and still declared JSON (no CORS-simple POST, ever).
        assertEquals(0L, requests.last().bodySize)
    }

    @Test
    fun aPairedDevicesBearerRequestsAreUnchanged() {
        val wsOrigin = connected(cookie = null, token = "tthr_device")
        // The upgrade still carries the Origin the server insists on for /ws.
        assertEquals("http://${h.server.hostName}:${h.server.port}", wsOrigin)
        for (request in everyCredentialedCall()) {
            assertEquals("${request.path}: the bearer", "Bearer tthr_device", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertNull("${request.method} ${request.path}: a bearer request is not changed", request.getHeader("Origin"))
        }
        // Owner-grade calls and the revoke are never sent with a device token.
        val before = h.server.requestCount
        assertEquals(SignInSessionsResult.OwnerGradeRequired, runBlocking { h.client.revokeOtherSignInSessions() })
        assertEquals(LogoutResult.LocalOnly, runBlocking { h.client.logout() })
        assertEquals(before, h.server.requestCount)
    }

    /**
     * A reverse proxy in front of the server (a `TETHER_PUBLIC_ORIGIN`-style public URL): the app
     * is configured with the public name on the scheme's default port, typed in mixed case. The
     * proxy passes `Host` (or `X-Forwarded-Host`) through, so the Origin must be that name with no
     * port, lowercase, exactly as `/ws` sends it. A fake DNS and socket factory route the public
     * name to the mock server.
     */
    @Test
    fun behindAReverseProxyTheOriginIsThePublicNameWithoutTheDefaultPortAsOnTheSocket() {
        val routed = routedTo(h.server)
        val wsOrigin = connected(http = routed, baseUrl = "http://Tether.Proxy.Test")
        assertEquals("http://tether.proxy.test", wsOrigin)
        val requests = everyCredentialedCall() + sessionsThenLogout()
        assertEquals(14, requests.size)
        for (request in requests) {
            assertEquals("tether.proxy.test", request.getHeader("Host"))
            assertConsoleOrigin(request, wsOrigin)
            assertJsonPost(request)
        }
    }

    @Test
    fun theOriginIsTheServersSchemeHostAndNonDefaultPort() {
        fun of(url: String) = consoleOrigin(url.toHttpUrl())
        assertEquals("https://tether.example.test", of("https://tether.example.test/"))
        assertEquals("https://tether.example.test", of("https://tether.example.test:443/some/path"))
        assertEquals("http://tether.example.test", of("http://tether.example.test:80"))
        assertEquals("https://tether.example.test:8443", of("https://Tether.Example.Test:8443"))
        assertEquals("http://tether.example.test:443", of("http://tether.example.test:443"))
        assertEquals("http://[::1]:4290", of("http://[::1]:4290"))
        assertEquals("https://[::1]", of("https://[::1]:443"))
        assertEquals("https://xn--bcher-kva.example", of("https://bücher.example"))
    }

    private companion object {
        const val STATS = """{"ok":true,"uptimeMs":61000,"pid":9,"protocolVersion":128,"headless":{"mode":"claude","persistent":false},
            "sessions":{"total":2,"byMode":{"headless":2}},"headlessRuntime":{"warm":1,"activeTurns":0,"maxConcurrentTurns":0},
            "memory":{"rss":1048576,"heapUsed":524288},"clients":1}"""
    }

    /** An OkHttp client that sends every connection for any host and port to [server]. */
    private fun routedTo(server: okhttp3.mockwebserver.MockWebServer): OkHttpClient {
        val loopback = InetAddress.getByName("127.0.0.1")
        return OkHttpClient.Builder()
            .dns(Dns { listOf(loopback) })
            .socketFactory(object : SocketFactory() {
                private fun routed() = object : Socket() {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) =
                        super.connect(InetSocketAddress(loopback, server.port), timeout)
                }
                override fun createSocket(): Socket = routed()
                override fun createSocket(host: String?, port: Int): Socket = routed().apply { connect(null, 0) }
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
                override fun createSocket(host: InetAddress?, port: Int): Socket = routed().apply { connect(null, 0) }
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
            })
            .build()
    }
}
