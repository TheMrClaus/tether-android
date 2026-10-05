package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10.5 request and answer shapes against tether 90fbb9f (server.mjs :7251-7305 sign-in, :7469-7507
 * registration; lib/origin-guard.mjs; lib/console-cookie.mjs):
 *  - registration: the two owner-grade fixed routes with the paired credential, `{}` then
 *    `{challengeId, response, label}` with the authenticator's answer as it came;
 *  - sign-in: /healthz, then the two public routes with NO credential and NO Origin (a native
 *    caller: the server then issues the legacy `tether_session` name), JSON bodies (a cookie-less
 *    POST needs neither, but the verify route's body must be JSON), the cookie adopted, and the
 *    socket that follows carrying it with the console Origin a cookie upgrade needs;
 *  - the anti-relay guard: options for another rpId never reach the authenticator, and nothing more
 *    is sent; a dismissed prompt sends nothing more; nothing is retried or followed.
 */
class PasskeyWireTest {
    private val h = ConnectionHarness()
    private val elsewhere = MockWebServer()
    private val json = "application/json; charset=utf-8"

    @After fun tearDown() {
        h.close()
        runCatching { elsewhere.shutdown() }
    }

    private fun take(server: MockWebServer = h.server): RecordedRequest = server.takeRequest(10, TimeUnit.SECONDS) ?: error("no request reached the server")
    private fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", json).setBody(body)
    private fun obj(text: String) = TetherJson.parseToJsonElement(text) as JsonObject
    private fun health() = MockResponse().setBody(HEALTH_143)

    // =========================== registration (Settings → Devices) ===========================

    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    private fun security(): Pair<HttpDeviceSecurity, String> {
        h.server.start()
        val files = FilesAuthority.Paired(h.server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        return HttpDeviceSecurity(noRedirects, authority = { SecurityAuthority(files, AppSignIn.DeviceToken) }) to "http://${h.server.hostName}:${h.server.port}"
    }

    @Test fun registrationIsTheTwoFixedRoutesWithThePairedCredentialAndTheAnswerAsItCame() = runBlocking<Unit> {
        val (source, origin) = security()
        h.server.enqueue(ok(PasskeyFixtures.registerOptionsJson(h.server.hostName)))
        val options = source.passkeyRegistrationOptions(origin) as SecurityResult.Ok
        val first = take()
        assertEquals("POST", first.method)
        assertEquals("/api/auth/passkeys/register/options", first.path)
        assertEquals("Bearer tthr_test", first.getHeader("Authorization"))
        assertTrue(first.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("{}", first.body.readUtf8())
        assertEquals(PasskeyFixtures.CHALLENGE_ID, options.value.challengeId)
        assertEquals(h.server.hostName, options.value.rpId)

        h.server.enqueue(ok(PasskeyFixtures.PASSKEY_JSON))
        val answer = PasskeyRules.response(PasskeyCeremony.Done(PasskeyFixtures.REGISTRATION_RESPONSE))!!
        val added = source.registerPasskey(origin, options.value.challengeId, answer, "Pixel") as SecurityResult.Ok
        val second = take()
        assertEquals("POST", second.method)
        assertEquals("/api/auth/passkeys/register/verify", second.path)
        assertEquals("Bearer tthr_test", second.getHeader("Authorization"))
        assertTrue(second.getHeader("Content-Type")!!.startsWith("application/json"))
        // use-sign-in-security.ts: JSON.stringify({ challengeId, response, label }).
        val body = obj(second.body.readUtf8())
        assertEquals(setOf("challengeId", "response", "label"), body.keys)
        assertEquals(PasskeyFixtures.CHALLENGE_ID, (body["challengeId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(obj(PasskeyFixtures.REGISTRATION_RESPONSE), body["response"])
        assertEquals("Pixel", (body["label"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(Passkey("dGV0aGVyLXRlc3QtY3JlZGVudGlhbC0wMDAx", "Pixel", 1759400000000, 0, backedUp = true), added.value)
        assertEquals(2, h.server.requestCount)
    }

    @Test fun registrationAnswersAreToldApart() = runBlocking<Unit> {
        val (source, origin) = security()
        val answer = PasskeyRules.response(PasskeyCeremony.Done(PasskeyFixtures.REGISTRATION_RESPONSE))!!
        // The server's own refusals, shown as it words them.
        h.server.enqueue(ok("""{"error":"Passkeys need HTTPS (or localhost). Open the console over a secure address to register one."}""", 400))
        assertEquals(SecurityResult.Refused(400, "Passkeys need HTTPS (or localhost). Open the console over a secure address to register one.", origin), source.passkeyRegistrationOptions(origin))
        h.server.enqueue(ok("""{"error":"This console already has 16 passkeys. Remove one first."}""", 409))
        assertEquals(SecurityResult.Refused(409, "This console already has 16 passkeys. Remove one first.", origin), source.passkeyRegistrationOptions(origin))
        h.server.enqueue(ok(DeviceSecurityFixtures.OWNER_REFUSAL_236, 403))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), source.passkeyRegistrationOptions(origin))
        h.server.enqueue(ok("""{"error":"That registration expired. Try again."}""", 400))
        assertEquals(SecurityResult.Refused(400, "That registration expired. Try again.", origin), source.registerPasskey(origin, PasskeyFixtures.CHALLENGE_ID, answer, "x"))
        h.server.enqueue(ok("""{"error":"That passkey is already registered."}""", 409))
        assertEquals(SecurityResult.Refused(409, "That passkey is already registered.", origin), source.registerPasskey(origin, PasskeyFixtures.CHALLENGE_ID, answer, "x"))
        // A 200 without the route's shape is no answer.
        h.server.enqueue(ok("""{"challengeId":"${PasskeyFixtures.CHALLENGE_ID}"}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), source.passkeyRegistrationOptions(origin))
        h.server.enqueue(ok("""{"ok":true}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), source.registerPasskey(origin, PasskeyFixtures.CHALLENGE_ID, answer, "x"))
        // A gateway's redirect is never followed.
        h.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/login")))
        assertEquals(SecurityResult.Blocked(302, origin), source.passkeyRegistrationOptions(origin))
        assertEquals(0, elsewhere.requestCount)
        // The label is cut where the server cuts it.
        h.server.enqueue(ok(PasskeyFixtures.PASSKEY_JSON))
        source.registerPasskey(origin, PasskeyFixtures.CHALLENGE_ID, answer, "L".repeat(200))
        repeat(8) { take() }
        val cut = obj(take().body.readUtf8())
        assertEquals("L".repeat(64), (cut["label"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun registrationDrawnForAnotherServerSendsNothing() = runBlocking<Unit> {
        val (source, _) = security()
        val answer = PasskeyRules.response(PasskeyCeremony.Done(PasskeyFixtures.REGISTRATION_RESPONSE))!!
        val wrong = "https://other-console.example.test"
        assertTrue(source.passkeyRegistrationOptions(wrong) is SecurityResult.NotSent)
        assertTrue(source.registerPasskey(wrong, PasskeyFixtures.CHALLENGE_ID, answer, "x") is SecurityResult.NotSent)
        assertEquals(0, h.server.requestCount)
    }

    // =========================== sign-in (the login screen) ===========================

    private val base get() = h.server.url("/").toString().trimEnd('/')
    private val host get() = h.server.hostName

    /**
     * r2 (security F1): a passkey sign-in goes only to an https server, so these run over TLS: the
     * console serves a test certificate the client alone trusts.
     */
    private fun signedOutClient(): RealTetherClient {
        val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName(h.server.hostName).build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        h.server.useHttps(serverTls.sslSocketFactory(), false)
        h.server.start()
        h.settings = InMemorySettings()
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        return h.client
    }

    @Test fun anHttpServerIsNeverAskedAndNoPromptOpens() {
        h.server.start()
        val client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = h.scope, scheduler = h.scheduler)
        val passkeys = RecordingPasskeys()
        val http = h.server.url("/").toString().trimEnd('/')
        assertTrue(http.startsWith("http://"))
        assertEquals(LoginResult.PasskeyFailed(PasskeyLoginCopy.NEEDS_HTTPS), runBlocking { client.passkeyLogin(http, passkeys) })
        assertEquals("nothing sent, not even /healthz", 0, h.server.requestCount)
        assertTrue("no prompt", passkeys.authenticated.isEmpty())
        client.stop()
    }

    @Test fun signInIsThreeUncredentialedCallsThenTheCookieRidesTheSocketWithTheConsoleOrigin() {
        val client = signedOutClient()
        val passkeys = RecordingPasskeys()
        h.server.enqueue(health())
        h.server.enqueue(ok(PasskeyFixtures.loginOptionsJson(host)))
        h.server.enqueue(ok("""{"ok":true}""").addHeader("Set-Cookie", "tether_session=0123456789abcdef0123456789abcdef.c2VjcmV0; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000"))
        h.enqueueConnect()

        assertEquals(LoginResult.Success, runBlocking { client.passkeyLogin(base, passkeys) })

        assertEquals("/healthz", take().path)
        val options = take()
        assertEquals("POST", options.method)
        assertEquals("/api/auth/passkey/login/options", options.path)
        assertEquals(json, options.getHeader("Content-Type"))
        assertEquals("{}", options.body.readUtf8())
        val verify = take()
        assertEquals("POST", verify.method)
        assertEquals("/api/auth/passkey/login/verify", verify.path)
        assertEquals(json, verify.getHeader("Content-Type"))
        for (r in listOf(options, verify)) {
            assertNull("a sign-in carries no earlier credential", r.getHeader("Cookie"))
            assertNull(r.getHeader("Authorization"))
            // No Origin: the server then treats this as a native caller and issues the legacy name.
            assertNull(r.getHeader("Origin"))
        }
        // use-login-flow.ts: JSON.stringify({ challengeId, response }).
        val body = obj(verify.body.readUtf8())
        assertEquals(setOf("challengeId", "response"), body.keys)
        assertEquals(PasskeyFixtures.CHALLENGE_ID, (body["challengeId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(obj(PasskeyFixtures.AUTHENTICATION_RESPONSE), body["response"])
        // The authenticator was handed the server's options exactly.
        assertEquals(listOf(obj(PasskeyFixtures.loginOptionsJson(host))["options"]), passkeys.authenticated.map(::obj))

        // The session is a cookie, adopted like a password sign-in's: the probe and the socket carry it.
        val credential = runBlocking { h.settings.credential.first() }
        assertEquals(Credential.Cookie("0123456789abcdef0123456789abcdef.c2VjcmV0", "tether_session"), credential)
        val probe = take()
        assertEquals("/api/auth/session", probe.path)
        assertEquals("tether_session=0123456789abcdef0123456789abcdef.c2VjcmV0", probe.getHeader("Cookie"))
        val upgrade = take()
        assertEquals("websocket", upgrade.getHeader("Upgrade")?.lowercase())
        assertEquals("tether_session=0123456789abcdef0123456789abcdef.c2VjcmV0", upgrade.getHeader("Cookie"))
        // A cookie upgrade must carry the console's Origin (server.mjs upgrade: only a device skips it).
        assertEquals("https://$host:${h.server.port}", upgrade.getHeader("Origin"))
        h.handshake(h.nextSocket())
    }

    @Test fun optionsForAnotherRelyingPartyNeverReachTheAuthenticator() {
        val client = signedOutClient()
        val passkeys = RecordingPasskeys()
        h.server.enqueue(health())
        // A server asking for ANOTHER console's passkey (a relay): refused before any prompt.
        h.server.enqueue(ok(PasskeyFixtures.loginOptionsJson("other-console.example.test")))
        assertEquals(LoginResult.PasskeyFailed(PasskeyLoginCopy.WRONG_RP), runBlocking { client.passkeyLogin(base, passkeys) })
        assertTrue("no prompt", passkeys.authenticated.isEmpty())
        take()
        take()
        assertEquals("nothing after the options", 2, h.server.requestCount)
        assertNull(runBlocking { h.settings.credential.first() })
    }

    @Test fun whatThePhoneAnsweredDecidesWhetherAnythingMoreIsSent() {
        val cases = listOf(
            PasskeyCeremony.Dismissed to LoginResult.PasskeyDismissed,
            PasskeyCeremony.NoCredential to LoginResult.PasskeyFailed(PasskeyLoginCopy.NO_CREDENTIAL),
            PasskeyCeremony.Unsupported to LoginResult.PasskeyFailed(PasskeyLoginCopy.UNSUPPORTED),
            PasskeyCeremony.Failed to LoginResult.PasskeyFailed(PasskeyLoginCopy.FAILED),
            PasskeyCeremony.Done("""{"id":"x","type":"public-key"}""") to LoginResult.PasskeyFailed(PasskeyLoginCopy.UNREADABLE_ANSWER),
        )
        val client = signedOutClient()
        var expected = 0
        for ((answer, result) in cases) {
            h.server.enqueue(health())
            h.server.enqueue(ok(PasskeyFixtures.loginOptionsJson(host)))
            assertEquals(answer.toString(), result, runBlocking { client.passkeyLogin(base, RecordingPasskeys(answer)) })
            expected += 2
            take()
            take()
            assertEquals("no verify after $answer", expected, h.server.requestCount)
        }
        assertNull(runBlocking { h.settings.credential.first() })
    }

    @Test fun refusalsAreTethersOwnWordsOrAGatewaysAndNothingIsFollowedOrKept() {
        val client = signedOutClient()
        fun attempt(vararg replies: MockResponse): LoginResult {
            h.server.enqueue(health())
            replies.forEach(h.server::enqueue)
            return runBlocking { client.passkeyLogin(base, RecordingPasskeys()) }
        }
        assertEquals(LoginResult.PasskeyFailed("No passkey is registered on this console."), attempt(ok("""{"error":"No passkey is registered on this console."}""", 400)))
        assertEquals(LoginResult.RateLimited("Too many attempts. Try again in a few minutes."), attempt(ok("""{"error":"Too many attempts. Try again in a few minutes."}""", 429)))
        assertTrue(attempt(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=x").setBody("<html>")) is LoginResult.GatewayRefused)
        assertTrue(attempt(MockResponse().setResponseCode(403).setBody("<html>SSO</html>")) is LoginResult.GatewayRefused)
        val redirected = attempt(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/sso")))
        assertTrue(redirected is LoginResult.Unreachable)
        assertEquals(0, elsewhere.requestCount)
        assertEquals(LoginResult.PasskeyFailed(PasskeyLoginCopy.UNREADABLE), attempt(ok("""{"challengeId":"${PasskeyFixtures.CHALLENGE_ID}"}""")))
        assertEquals(LoginResult.PasskeyFailed(PasskeyLoginCopy.UNREADABLE), attempt(ok("""{"pad":"${"x".repeat(70 * 1024)}"}""")))
        // The verify route's own 401: the web's generic sentence, nothing kept.
        assertEquals(
            LoginResult.PasskeyFailed("That passkey could not be verified."),
            attempt(ok(PasskeyFixtures.loginOptionsJson(host)), ok("""{"error":"That passkey could not be verified."}""", 401)),
        )
        // A 200 without a cookie is not a sign-in.
        assertEquals(LoginResult.Unreachable("The server did not return a session cookie."), attempt(ok(PasskeyFixtures.loginOptionsJson(host)), ok("""{"ok":true}""")))
        assertNull(runBlocking { h.settings.credential.first() })
    }

    @Test fun aServerOutsideTheNativeWindowOrAPhoneWithoutPasskeysSendsNothingMore() {
        val client = signedOutClient()
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"protocolVersion":143,"nativeProtocolFloor":999}"""))
        assertTrue(runBlocking { client.passkeyLogin(base, RecordingPasskeys()) } is LoginResult.VersionMismatch)
        take()
        assertEquals(1, h.server.requestCount)
        val none = RecordingPasskeys(available = false)
        assertEquals(LoginResult.PasskeyFailed(PasskeyLoginCopy.UNSUPPORTED), runBlocking { client.passkeyLogin(base, none) })
        assertEquals(1, h.server.requestCount)
    }

    @Test fun theAnswerIsNeverPrintedNorKept() {
        val client = signedOutClient()
        val out = java.io.ByteArrayOutputStream()
        val (stdout, stderr) = System.out to System.err
        System.setOut(java.io.PrintStream(out, true))
        System.setErr(java.io.PrintStream(out, true))
        val results = try {
            listOf(
                ok("""{"error":"That passkey could not be verified."}""", 401),
                ok("""{"ok":true}"""),
            ).map { verify ->
                h.server.enqueue(health())
                h.server.enqueue(ok(PasskeyFixtures.loginOptionsJson(host)))
                h.server.enqueue(verify)
                runBlocking { client.passkeyLogin(base, RecordingPasskeys()) }.also { repeat(3) { take() } }
            }
        } finally {
            System.setOut(stdout)
            System.setErr(stderr)
        }
        // Control: the answer did go out, once per attempt, to the server that asked.
        assertEquals(2, results.size)
        for (printed in results.map { it.toString() } + out.toString()) assertFalse(printed, printed.contains(PasskeyFixtures.SENTINEL))
        assertNull(runBlocking { h.settings.credential.first() })
    }
}
