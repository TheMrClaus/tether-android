package com.tether.app.client

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10.6 (ta-jwbs): the sign-in paths branch on `/healthz`'s `setupRequired` FIRST, before the native window
 * and the pairing check (lib/setup-server.mjs :810-816 answers `{ok, setupRequired:true, runtime}`: no
 * protocolVersion, no pairing, no WebSocket hub). A normal server is unaffected. No real server: a
 * MockWebServer shaped from the handlers.
 */
class SetupDetectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paths = ConcurrentLinkedQueue<String>()

    @Volatile private var healthz = SETUP_HEALTH

    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.path}"
                return when (request.path) {
                    "/healthz" -> MockResponse().setBody(healthz)
                    // setup mode: the normal API does not exist.
                    "/api/auth/session" -> MockResponse().setResponseCode(503).setBody("""{"error":"Setup required.","setupRequired":true}""")
                    "/api/auth/login" -> MockResponse().setResponseCode(401).setBody("""{"error":"Invalid credentials."}""")
                    "/api/devices/claim" -> MockResponse().setResponseCode(401).setBody("""{"error":"Invalid code."}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    private val client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope)
    private val base get() = web.url("/").toString().trimEnd('/')

    @After fun tearDown() {
        scope.cancel()
        web.shutdown()
    }

    @Test fun aPasswordSignInToASetupServerIsSetupRequiredAndNoLoginIsSent() = runBlocking {
        val result = client.login(base, "pw", "op")
        assertEquals(LoginResult.SetupRequired, result)
        assertEquals(listOf("GET /healthz"), paths.toList())
    }

    @Test fun pairingWithASetupServerIsSetupRequiredNotPredatesPairing() = runBlocking {
        val result = client.pair(base, "ABCD-EFGH", "Pixel")
        assertEquals(PairResult.SetupRequired, result)
        assertEquals(listOf("GET /healthz"), paths.toList())
    }

    @Test fun theProbeSaysWhetherTheServerNeedsSetupWithoutAnythingElse() = runBlocking {
        assertTrue(client.setupRequired(base))
        assertEquals(listOf("GET /healthz"), paths.toList())
        healthz = NORMAL_HEALTH
        assertFalse(client.setupRequired(base))
        healthz = "not json"
        assertFalse(client.setupRequired(base))
        assertFalse(client.setupRequired("   "))
    }

    @Test fun aServerThatIsDownIsNotSetupRequired() = runBlocking {
        web.shutdown()
        assertFalse(client.setupRequired(base))
    }

    @Test fun aNormalServerIsUnaffected() = runBlocking {
        healthz = NORMAL_HEALTH
        // The password path goes on to /api/auth/login (the mock refuses it: a wrong password).
        val login = client.login(base, "pw", "op")
        assertTrue(login is LoginResult.BadPassword)
        assertEquals(listOf("GET /healthz", "POST /api/auth/login"), paths.toList())
        // Pairing goes on to the claim.
        paths.clear()
        val pair = client.pair(base, "ABCD-EFGH", "Pixel")
        assertTrue(pair is PairResult.Rejected)
        assertEquals(listOf("GET /healthz", "POST /api/devices/claim"), paths.toList())
    }

    @Test fun aServerOutsideTheNativeWindowIsStillAVersionMismatch() = runBlocking {
        healthz = """{"ok":true,"protocolVersion":100,"nativeProtocolFloor":null}"""
        assertTrue(client.login(base, "pw", "op") is LoginResult.VersionMismatch)
    }

    @Test fun aServerThatPredatesPairingIsStillNotSupported() = runBlocking {
        healthz = """{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129}"""
        assertTrue(client.pair(base, "ABCD-EFGH", "Pixel") is PairResult.NotSupported)
    }

    @Test fun theWizardsApiIsForTheTypedServerAndAnInvalidAddressHasNone() {
        assertNotNull(client.setupApi(base))
        assertNull(client.setupApi("  "))
    }

    private companion object {
        const val SETUP_HEALTH = """{"ok":true,"setupRequired":true,"runtime":"native"}"""
        const val NORMAL_HEALTH = """{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129,"pairing":true}"""
    }
}
