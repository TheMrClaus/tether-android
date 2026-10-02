package com.tether.app.client

import com.tether.app.client.DeviceSecurityFixtures.CODE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T10.4 request shapes for Settings → Devices (tether 887c222 server.mjs :7349-7541): only the
 * fixed routes, each by its own method, with exactly its body; only to the paired server and only
 * when it is the server the screen names (a write drawn for another origin sends nothing); a
 * redirect never followed; Tether's own answers told apart from a sign-in gateway's; JSON only; the
 * body bounded; the owner-grade 403 recognised by its opening (both wordings); the pairing code
 * never printed.
 */
class DeviceSecurityHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var files: FilesAuthority = FilesAuthority.SignedOut
    private var signIn: AppSignIn? = AppSignIn.DeviceToken
    private lateinit var source: HttpDeviceSecurity

    private val json = "application/json; charset=utf-8"

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        files = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        source = HttpDeviceSecurity(noRedirects, authority = { SecurityAuthority(files, signIn) })
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")
    private fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", json).setBody(body)
    private val origin get() = "http://${server.hostName}:${server.port}"
    private val otherOrigin get() = "http://${elsewhere.hostName}:${elsewhere.port}"

    private fun RecordedRequest.text() = body.readUtf8()

    // ---- each route: method, path, headers, body ----------------------------------------------

    @Test fun everyRouteIsItsFixedPathWithItsOwnMethodAndBody() = runBlocking<Unit> {
        data class Case(val method: String, val path: String, val body: String, val run: suspend () -> SecurityResult<*>, val reply: MockResponse)
        val cases = listOf(
            Case("GET", "/api/devices", "", { source.devices(origin) }, ok(DeviceSecurityFixtures.DEVICES_JSON)),
            Case("POST", "/api/devices/pair", "{}", { source.pair(origin) }, ok(DeviceSecurityFixtures.PAIR_JSON, 201)),
            Case("DELETE", "/api/devices/a1b2c3d4e5f60718", "", { source.revokeDevice(origin, "a1b2c3d4e5f60718") }, ok("""{"ok":true,"disconnected":1}""")),
            Case("GET", "/api/auth/passkeys", "", { source.passkeys(origin) }, ok(DeviceSecurityFixtures.PASSKEYS_JSON)),
            Case("PATCH", "/api/auth/passkeys/cred-AbC_123", """{"label":"Work laptop"}""", { source.renamePasskey(origin, "cred-AbC_123", "Work laptop") }, ok("""{"passkey":{"id":"cred-AbC_123"}}""")),
            Case("DELETE", "/api/auth/passkeys/cred-AbC_123", "", { source.removePasskey(origin, "cred-AbC_123") }, ok("""{"ok":true}""")),
            Case("PUT", "/api/auth/passkeys/policy", """{"passwordLoginEnabled":false}""", { source.setPasswordLogin(origin, false) }, ok("""{"passwordLoginEnabled":false,"policySource":"stored"}""")),
            Case("GET", "/api/auth/sessions", "", { source.sessions(origin) }, ok(DeviceSecurityFixtures.SESSIONS_JSON)),
            Case("DELETE", "/api/auth/sessions/5e55a1d0000000000000000000000001", "", { source.revokeSession(origin, "5e55a1d0000000000000000000000001") }, ok("""{"ok":true,"disconnected":0}""")),
            Case("DELETE", "/api/auth/sessions", "", { source.revokeOtherSessions(origin) }, ok("""{"revoked":3,"disconnected":2}""")),
        )
        for (case in cases) {
            server.enqueue(case.reply)
            val result = case.run()
            assertTrue("${case.method} ${case.path}: $result", result is SecurityResult.Ok<*>)
            assertEquals(origin, result.origin)
            assertEquals(AppSignIn.DeviceToken, (result as SecurityResult.Ok<*>).signIn)
            val req = take()
            assertEquals(case.method, req.method)
            assertEquals(case.path, req.path)
            assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
            assertEquals("application/json", req.getHeader("Accept"))
            assertEquals("no-store", req.getHeader("Cache-Control"))
            assertEquals(case.body, req.text())
            if (case.body.isNotEmpty()) assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test fun theAnswersAreReadAsTheWebReadsThem() = runBlocking<Unit> {
        server.enqueue(ok(DeviceSecurityFixtures.DEVICES_JSON))
        assertEquals(SecurityResult.Ok(DeviceSecurityFixtures.DEVICES, origin, AppSignIn.DeviceToken), source.devices(origin))
        server.enqueue(ok(DeviceSecurityFixtures.PASSKEYS_JSON))
        assertEquals(SecurityResult.Ok(DeviceSecurityFixtures.PASSKEYS, origin, AppSignIn.DeviceToken), source.passkeys(origin))
        server.enqueue(ok(DeviceSecurityFixtures.SESSIONS_JSON))
        assertEquals(SecurityResult.Ok(DeviceSecurityFixtures.SESSIONS, origin, AppSignIn.DeviceToken), source.sessions(origin))
        server.enqueue(ok("""{"ok":true,"disconnected":3}"""))
        assertEquals(SecurityResult.Ok(DeviceRevoked(3), origin, AppSignIn.DeviceToken), source.revokeDevice(origin, "a1b2c3d4e5f60718"))
        server.enqueue(ok("""{"revoked":0,"disconnected":0}"""))
        assertEquals(SecurityResult.Ok(SessionsRevoked(0), origin, AppSignIn.DeviceToken), source.revokeOtherSessions(origin))
        signIn = AppSignIn.SessionCookie
        server.enqueue(ok(DeviceSecurityFixtures.PAIR_JSON, 201))
        val minted = source.pair(origin) as SecurityResult.Ok
        assertEquals(AppSignIn.SessionCookie, minted.signIn)
        assertTrue(minted.value.code.matches(CODE))
        assertEquals(1759400300000L, minted.value.expiresAt)
        repeat(6) { take() }
    }

    // ---- the pairing code is a secret ---------------------------------------------------------

    @Test fun thePairingCodeIsNeverPrinted() = runBlocking<Unit> {
        server.enqueue(ok(DeviceSecurityFixtures.PAIR_JSON, 201))
        val minted = source.pair(origin)
        take()
        assertTrue(minted is SecurityResult.Ok)
        // Control: the code is in there.
        assertTrue((minted as SecurityResult.Ok).value.code.reveal() == CODE)
        for (printed in listOf(minted.toString(), minted.value.toString(), minted.value.code.toString())) {
            assertFalse("printed: $printed", printed.contains(CODE))
        }
        // Not data classes: no generated copy / componentN can carry it out.
        for (type in listOf(PairingCode::class.java, FreshPairingCode::class.java)) {
            assertFalse("${type.simpleName} has a generated accessor", type.declaredMethods.any { it.name == "copy" || it.name.startsWith("component") })
        }
    }

    @Test fun aMintReplyWithoutAUsableCodeIsUnavailable() = runBlocking<Unit> {
        for (body in listOf("""{"expiresAt":1}""", """{"code":"","expiresAt":1}""", """{"code":12345678,"expiresAt":1}""", """{"code":"ABCD EFGH","expiresAt":1}""",
            """{"code":"ABC","expiresAt":1}""", """{"code":"ABCDEFGH"}""", """{"code":"ABCDEFGH","expiresAt":"soon"}""", """{"code":"ABCD‮EFGH","expiresAt":1}""")) {
            server.enqueue(ok(body, 201))
            assertEquals(body, SecurityResult.Unavailable(201, origin), source.pair(origin))
            take()
        }
    }

    // ---- bound to the drawing origin ----------------------------------------------------------

    @Test fun aCallDrawnForAnotherServerSendsNothing() = runBlocking<Unit> {
        val wrong = otherOrigin
        val results = listOf(
            source.devices(wrong), source.pair(wrong), source.revokeDevice(wrong, "a1b2c3d4e5f60718"),
            source.passkeys(wrong), source.renamePasskey(wrong, "cred", "x"), source.removePasskey(wrong, "cred"), source.setPasswordLogin(wrong, true),
            source.sessions(wrong), source.revokeSession(wrong, "s1"), source.revokeOtherSessions(wrong),
        )
        results.forEach { assertEquals(SecurityResult.NotSent(origin), it) }
        assertEquals(0, server.requestCount)
        assertEquals(0, elsewhere.requestCount)
        // A different spelling of the same origin is the same server.
        server.enqueue(ok(DeviceSecurityFixtures.DEVICES_JSON))
        assertTrue(source.devices("http://${server.hostName}:${server.port}") is SecurityResult.Ok)
        take()
    }

    /** An origin switch while a request is in flight: the answer is tagged with the server it came from, so the screen drops it. */
    @Test fun anAnswerThatLandsAfterAServerSwitchIsTaggedWithItsOwnServer() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(10, TimeUnit.SECONDS)
                return ok(DeviceSecurityFixtures.DEVICES_JSON)
            }
        }
        val pending = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) { source.devices(origin) }
        withTimeout(10_000) { while (server.requestCount == 0) kotlinx.coroutines.delay(10) }
        // Signed in to another server now.
        files = FilesAuthority.Paired(elsewhere.url("/")) { it.header("Authorization", "Bearer tthr_other") }
        release.countDown()
        val late = withTimeout(10_000) { pending.await() }
        assertEquals(origin, late.origin)
        // And anything drawn for the old server now sends nothing (nowhere).
        assertEquals(SecurityResult.NotSent(otherOrigin), source.revokeDevice(origin, "a1b2c3d4e5f60718"))
        assertEquals(0, elsewhere.requestCount)
        assertEquals(1, server.requestCount)
    }

    @Test fun anIdOutsideAPlainTokenShapeSendsNothing() = runBlocking<Unit> {
        for (id in listOf("", ".", "..", "../pair", "a/b", "a%2Fb", "a?x=1", "a#f", "a b", "a.b", "pairings/../x", "x".repeat(1401), "é",
            // r2 (verifier F2): an id spelled like a sibling route of /api/devices or /api/auth.
            "pair", "pairings", "claim", "policy", "register", "options", "verify", "login", "logout", "passkey", "passkeys", "session", "sessions", "Pairings", "POLICY")) {
            assertEquals(id, SecurityResult.NotSent(origin), source.revokeDevice(origin, id))
            assertEquals(id, SecurityResult.NotSent(origin), source.removePasskey(origin, id))
            assertEquals(id, SecurityResult.NotSent(origin), source.renamePasskey(origin, id, "x"))
            assertEquals(id, SecurityResult.NotSent(origin), source.revokeSession(origin, id))
        }
        assertEquals(0, server.requestCount)
    }

    /**
     * r2 (verifier F1, F2): an id longer than a path may carry, or spelled like a sibling route, is
     * listed (cut for keeping) but NOT actionable; the decision is made on the id as sent, so the cut
     * copy of a too-long id never passes for a valid one.
     */
    @Test fun anIdTooLongOrNamingASiblingRouteIsListedButNotActionable() = runBlocking<Unit> {
        val long = "x".repeat(1401)
        server.enqueue(ok("""{"devices":[{"id":"$long","label":"Long"},{"id":"pairings","label":"Sibling"},{"id":"Claim","label":"Case"},{"id":"${"y".repeat(1400)}","label":"Edge"},{"id":"a1","label":"Fine"}],"pairings":[]}"""))
        val devices = (source.devices(origin) as SecurityResult.Ok).value.devices.associateBy { it.label }
        take()
        assertEquals(5, devices.size)
        assertEquals("kept cut", DeviceSecurityJson.MAX_ID, devices.getValue("Long").id.length)
        assertFalse("decided on the id as sent", devices.getValue("Long").actionable)
        assertTrue("the cut copy alone would pass", DeviceSecurityJson.isPathId(devices.getValue("Long").id))
        assertFalse(devices.getValue("Sibling").actionable)
        assertFalse(devices.getValue("Case").actionable)
        assertTrue("1400 is still a path id", devices.getValue("Edge").actionable)
        assertTrue(devices.getValue("Fine").actionable)
        server.enqueue(ok("""{"passkeys":[{"id":"policy","label":"P"},{"id":"register","label":"R"},{"id":"$long","label":"L"},{"id":"cred-1","label":"C"}],"passwordLoginEnabled":true}"""))
        val passkeys = (source.passkeys(origin) as SecurityResult.Ok).value.passkeys.associateBy { it.label }
        take()
        assertEquals(listOf(false, false, false, true), listOf("P", "R", "L", "C").map { passkeys.getValue(it).actionable })
        server.enqueue(ok("""{"sessions":[{"id":"sessions","method":"password"},{"id":"$long","method":"password"},{"id":"5e55","method":"password"}]}"""))
        val sessions = (source.sessions(origin) as SecurityResult.Ok).value
        take()
        assertEquals(listOf(false, false, true), sessions.map { it.actionable })
    }

    /** tether #240 (unmerged): the caller's own device carries `current: true`; an older server sends no such field. */
    @Test fun theCurrentFlagIsReadWhenPresent() = runBlocking<Unit> {
        server.enqueue(ok("""{"devices":[{"id":"a1","label":"Mine","current":true},{"id":"b2","label":"Other"}],"pairings":[]}"""))
        assertEquals(listOf(true, false), (source.devices(origin) as SecurityResult.Ok).value.devices.map { it.current })
        take()
        server.enqueue(ok("""{"devices":[{"id":"a1","current":"yes"},{"id":"b2","current":1}],"pairings":[]}"""))
        assertEquals("only a JSON true counts", listOf(false, false), (source.devices(origin) as SecurityResult.Ok).value.devices.map { it.current })
        take()
        server.enqueue(ok(DeviceSecurityFixtures.DEVICES_JSON))
        assertTrue((source.devices(origin) as SecurityResult.Ok).value.devices.none { it.current })
        take()
    }

    /** r2 (security F2): each answer carries a handle on the credential it went out with (outside equality), and the screen can hand it back. */
    @Test fun answersCarryTheCredentialsHandleAndItCanBeHandedBack() = runBlocking<Unit> {
        val handle = SignInHandle(Any())
        val rejected = mutableListOf<SignInHandle>()
        val withHandle = HttpDeviceSecurity(noRedirects, authority = { SecurityAuthority(files, signIn, handle) }, onRejected = { rejected += it })
        server.enqueue(ok("""{"ok":true,"disconnected":1}"""))
        val revoked = withHandle.revokeDevice(origin, "a1") as SecurityResult.Ok
        assertTrue(revoked.handle === handle)
        assertEquals(SecurityResult.Ok(DeviceRevoked(1), origin, AppSignIn.DeviceToken), revoked)
        server.enqueue(ok("""{"error":"Authentication required."}""", 401))
        val refused = withHandle.devices(origin) as SecurityResult.SignedOut
        assertTrue(refused.handle === handle)
        withHandle.credentialRejected(handle)
        assertEquals(listOf(handle), rejected)
        assertEquals("SignInHandle", handle.toString())
        repeat(2) { take() }
    }

    @Test fun signedOutOrLocalNetworkBlockedSendsNothing() = runBlocking<Unit> {
        files = FilesAuthority.SignedOut
        assertEquals(SecurityResult.SignedOut(), source.devices(origin))
        assertEquals(SecurityResult.SignedOut(), source.revokeDevice(origin, "a1b2c3d4e5f60718"))
        files = FilesAuthority.LocalNetworkBlocked
        assertEquals(SecurityResult.LocalNetworkBlocked, source.pair(origin))
        assertEquals(0, server.requestCount)
    }

    @Test fun aPairedOriginWithAPathOrQueryStillAsksOnlyTheFixedRoute() = runBlocking<Unit> {
        files = FilesAuthority.Paired(server.url("/prefix/?x=1#f")) { it.header("Authorization", "Bearer tthr_test") }
        server.enqueue(ok(DeviceSecurityFixtures.PAIR_JSON, 201))
        source.pair(origin)
        assertEquals("/api/devices/pair", take().path)
    }

    // ---- redirects, gateways, types, sizes -----------------------------------------------------

    @Test fun aRedirectIsBlockedAndNeverFollowedForReadsOrWrites() = runBlocking<Unit> {
        for (code in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/api/devices/pair")))
            assertEquals(SecurityResult.Blocked(code, origin), source.pair(origin))
            take()
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/api/devices/a1")))
            assertEquals(SecurityResult.Blocked(code, origin), source.revokeDevice(origin, "a1"))
            take()
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/login")))
            assertEquals(SecurityResult.Blocked(code, origin), source.sessions(origin))
            take()
        }
        assertEquals("one request per call: nothing followed", 15, server.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun aSignInGatewayIsBlockedButTethersOwnAnswersAreNot() = runBlocking<Unit> {
        val cases = listOf(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html").setBody("<html>login</html>") to SecurityResult.Blocked(401, origin),
            MockResponse().setResponseCode(403).setBody("Forbidden") to SecurityResult.Blocked(403, origin),
            MockResponse().setResponseCode(403).setHeader("Content-Type", json).setHeader("WWW-Authenticate", "Bearer").setBody(DeviceSecurityFixtures.OWNER_REFUSAL_887) to SecurityResult.Blocked(403, origin),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>sign in</html>") to SecurityResult.Blocked(200, origin),
            ok("""{"error":"Authentication required."}""", 401) to SecurityResult.SignedOut(origin),
            ok(DeviceSecurityFixtures.OWNER_REFUSAL_887, 403) to SecurityResult.OwnerSignInNeeded(origin),
            ok(DeviceSecurityFixtures.OWNER_REFUSAL_236, 403) to SecurityResult.OwnerSignInNeeded(origin),
            // Another 403 of Tether's own (tether #213's refused cross-origin write): its words, not "owner sign-in".
            ok("""{"error":"This request must come from the Tether console itself.","code":"cross_origin_refused"}""", 403) to
                SecurityResult.Refused(403, "This request must come from the Tether console itself.", origin),
            ok("""{"error":"No such device."}""", 404) to SecurityResult.Refused(404, "No such device.", origin),
            ok("""{"error":"Every device was revoked, but the Android app passkey sessions could not be."}""", 500) to
                SecurityResult.Refused(500, "Every device was revoked, but the Android app passkey sessions could not be.", origin),
            // A proxy's JSON error: its words are never taken as Tether's.
            ok("""{"error":"Upstream down: sign in at https://sso.example/login"}""", 502) to SecurityResult.Unavailable(502, origin),
            ok("""{"error":"x"}""", 503) to SecurityResult.Unavailable(503, origin),
            ok("""{"error":42}""", 404) to SecurityResult.Unavailable(404, origin),
            ok("{}", 409) to SecurityResult.Unavailable(409, origin),
            MockResponse().setResponseCode(409).setHeader("Content-Type", "text/plain").setBody("""{"error":"x"}""") to SecurityResult.Unavailable(409, origin),
            MockResponse().setResponseCode(204) to SecurityResult.Unavailable(204, origin),
        )
        for ((index, case) in cases.withIndex()) {
            server.enqueue(case.first)
            assertEquals("case $index", case.second, source.revokeDevice(origin, "a1b2c3d4e5f60718"))
            take()
        }
    }

    @Test fun aTwoHundredThatIsNotTethersAnswerIsUnavailable() = runBlocking<Unit> {
        val cases = listOf(
            MockResponse().setHeader("Content-Type", "text/plain").setBody(DeviceSecurityFixtures.DEVICES_JSON),
            MockResponse().setBody(DeviceSecurityFixtures.DEVICES_JSON).removeHeader("Content-Type"),
            MockResponse().setHeader("Content-Type", "application/problem+json").setBody(DeviceSecurityFixtures.DEVICES_JSON),
            ok("""{"error":"login required"}"""),
            ok("{}"),
            ok("not json"),
            ok("[]"),
            ok(""),
            ok("[".repeat(200_000)),
        )
        for ((index, response) in cases.withIndex()) {
            server.enqueue(response)
            assertEquals("case $index", SecurityResult.Unavailable(200, origin), source.devices(origin))
            take()
        }
        for (body in listOf("""{"ok":false}""", """{"disconnected":1}""")) {
            server.enqueue(ok(body))
            assertEquals(body, SecurityResult.Unavailable(200, origin), source.revokeDevice(origin, "a1"))
            take()
        }
        server.enqueue(ok("""{"policySource":"stored"}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), source.setPasswordLogin(origin, true))
        take()
    }

    @Test fun aHugeBodyIsDroppedWhetherDeclaredOrStreamed() = runBlocking<Unit> {
        val small = HttpDeviceSecurity(noRedirects, authority = { SecurityAuthority(files, signIn) }, maxBytes = 1024)
        val padded = """{"devices":[],"pad":"${"x".repeat(4096)}"}"""
        server.enqueue(ok(padded))
        assertEquals(SecurityResult.Unavailable(200, origin), small.devices(origin))
        take()
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody(padded, 128))
        assertEquals(SecurityResult.Unavailable(200, origin), small.devices(origin))
        take()
        // A refusal over the cap is not read either.
        server.enqueue(ok("""{"error":"${"y".repeat(4096)}"}""", 409))
        assertEquals(SecurityResult.Unavailable(409, origin), small.pair(origin))
        take()
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody("""{"devices":[],"pad":"""" + "x".repeat(140_000) + "\"}", 8192))
        assertEquals("the default cap", SecurityResult.Unavailable(200, origin), source.devices(origin))
        take()
    }

    @Test fun serverTextIsBoundedAndRowsAreCapped() = runBlocking<Unit> {
        val rows = (0 until 300).joinToString(",") { """{"id":"d$it","label":"${if (it == 0) "L".repeat(500) else "L"}"}""" }
        server.enqueue(ok("""{"devices":[$rows,{"id":"d1","label":"again"}],"pairings":"no"}"""))
        val list = (source.devices(origin) as SecurityResult.Ok).value
        assertEquals(DeviceSecurityJson.MAX_ROWS, list.devices.size)
        assertTrue(list.devices.all { it.label.length <= DeviceSecurityJson.MAX_TEXT })
        assertEquals("a second row with the same id is dropped", 1, list.devices.count { it.id == "d1" })
        assertTrue(list.pairings.isEmpty())
        take()
        server.enqueue(ok("""{"error":"${"e".repeat(5000)}"}""", 409))
        val refused = source.pair(origin) as SecurityResult.Refused
        assertTrue(refused.message.length <= DeviceSecurityJson.MAX_ERROR)
        take()
    }

    @Test fun anUnreachableOrHangingServerIsUnavailable() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(SecurityResult.Unavailable(null, origin), source.devices(origin))
        val quick = HttpDeviceSecurity(noRedirects, authority = { SecurityAuthority(files, signIn) }, callTimeoutMs = 300)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(SecurityResult.Unavailable(null, origin), withTimeout(10_000) { quick.revokeDevice(origin, "a1") })
    }

    @Test fun cancellingTheCallerCancelsTheRequest() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = async(start = CoroutineStart.UNDISPATCHED) { source.devices(origin) }
        take()
        call.cancel()
        withTimeout(5_000) { call.join() }
        assertTrue(call.isCancelled)
    }

    @Test fun aClientThatFollowsRedirectsIsRefused() {
        val refused = runCatching { HttpDeviceSecurity(OkHttpClient(), authority = { SecurityAuthority(files, signIn) }) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertNull(runCatching { HttpDeviceSecurity(noRedirects, authority = { SecurityAuthority(files, signIn) }) }.exceptionOrNull())
    }
}
