package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10.4 on the real client: [TetherClient.deviceSecurity] carries the one credential in force (a
 * device token as a bearer; a session cookie with the console's own Origin, which tether #213's
 * write guard needs), says which kind it was, goes to the paired server only, never follows a
 * redirect, and sends nothing once signed out. Device tokens are no longer refused locally (the
 * owner's 2026-10-02 decision): the server decides.
 */
class DeviceSecurityClientTest {
    private val h = ConnectionHarness()
    private val other = MockWebServer()

    @After fun tearDown() {
        h.close()
        runCatching { other.shutdown() }
    }

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(20_000) { flow.first(predicate) } }

    private fun loadedButIdle(cookie: String? = null, token: String? = null) {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        h.settings = InMemorySettings(initialBaseUrl = base, initialCookie = cookie, initialDeviceToken = token)
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.server.enqueue(MockResponse().setResponseCode(401))
        h.client.start()
        await(h.client.signedOutReason) { it == SignedOutReason.GatewayRefused }
        take()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val origin get() = "http://${h.server.hostName}:${h.server.port}"

    @Test fun aPairedDeviceSendsItsBearerTokenAndIsToldItIsADeviceToken() = runBlocking<Unit> {
        loadedButIdle(token = "tthr_device")
        h.server.enqueue(json(DeviceSecurityFixtures.DEVICES_JSON))
        assertEquals(SecurityResult.Ok(DeviceSecurityFixtures.DEVICES, origin, AppSignIn.DeviceToken), h.client.deviceSecurity.devices(origin))
        h.server.enqueue(json(DeviceSecurityFixtures.OWNER_REFUSAL_887, 403))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), h.client.deviceSecurity.pair(origin))
        listOf(take(), take()).forEach { req ->
            assertEquals("Bearer tthr_device", req.getHeader("Authorization"))
            assertNull(req.getHeader("Cookie"))
        }
    }

    @Test fun aSessionCookieWriteCarriesTheConsoleOriginAndAJsonBody() = runBlocking<Unit> {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(json(DeviceSecurityFixtures.PAIR_JSON, 201))
        val minted = h.client.deviceSecurity.pair(origin)
        assertTrue(minted is SecurityResult.Ok && minted.signIn == AppSignIn.SessionCookie)
        val req = take()
        assertEquals("POST", req.method)
        assertEquals("tether_session=cookie", req.getHeader("Cookie"))
        assertEquals(origin, req.getHeader("Origin"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun aRedirectToAnotherOriginNeverReceivesTheCredential() = runBlocking<Unit> {
        other.start()
        loadedButIdle(token = "tthr_device")
        h.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/api/devices/pair")))
        assertEquals(SecurityResult.Blocked(307, origin), h.client.deviceSecurity.pair(origin))
        assertEquals(0, other.requestCount)
    }

    /**
     * r2 (security F2): handing back the handle of the credential in force signs the client out at
     * once (compare-and-clear: the stored credential is dropped); a handle on any other credential
     * changes nothing.
     */
    @Test fun aRejectedHandleSignsOutOnlyWhileItsCredentialIsInForce() = runBlocking<Unit> {
        loadedButIdle(token = "tthr_device")
        h.client.deviceSecurity.credentialRejected(SignInHandle(Credential.DeviceToken("tthr_device")))
        kotlinx.coroutines.delay(300)
        assertEquals("a different credential object: nothing happens", "tthr_device", h.settings.session().credential.let { (it as? Credential.DeviceToken)?.value })
        h.server.enqueue(json(DeviceSecurityFixtures.DEVICES_JSON))
        val listed = h.client.deviceSecurity.devices(origin) as SecurityResult.Ok
        take()
        val handle = listed.handle!!
        h.client.deviceSecurity.credentialRejected(handle)
        await(h.client.signedOutReason) { it == SignedOutReason.DeviceUnpaired }
        withTimeout(10_000) { while (h.settings.session().credential != null) kotlinx.coroutines.delay(20) }
        assertEquals(SecurityResult.SignedOut(), h.client.deviceSecurity.devices(origin))
    }

    @Test fun anotherServersOriginOrASignOutSendsNothing() = runBlocking<Unit> {
        loadedButIdle(token = "tthr_device")
        val before = h.server.requestCount
        assertEquals(SecurityResult.NotSent(origin), h.client.deviceSecurity.revokeDevice("https://elsewhere.example.test:443", "a1"))
        h.client.logout()
        val after = h.server.requestCount
        assertEquals(before, after)
        assertEquals(SecurityResult.SignedOut(), h.client.deviceSecurity.devices(origin))
        assertEquals(SecurityResult.SignedOut(), h.client.deviceSecurity.revokeDevice(origin, "a1"))
        assertEquals(after, h.server.requestCount)
    }
}
