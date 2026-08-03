package com.tether.app.push

import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PushRegistrar] round-trips against a [MockWebServer]: config probe → POST
 * register → PATCH update → DELETE unregister. The Firebase token call is
 * stubbed via [FirebaseTokenProvider] so no Play Services initialisation is
 * needed. The bearer-token auth header and the JSON body shapes are asserted
 * against the server's `/api/push/fcm-register` contract.
 */
class PushRegistrarTest {

    private val server = MockWebServer()
    private val token = "tthr_test_token_value_abcdefghijklmnopqrstuvwxyz"
    private val settings = InMemorySettings()

    @Before
    fun setUp() {
        server.start()
        // InMemorySettings.setServer is suspend but only mutates StateFlows —
        // runBlocking here is safe and confined to the test thread.
        runBlocking {
            settings.setServer(server.url("/").toString(), Credential.DeviceToken(token))
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun registrar(token: String? = "fcm-token-abc-123"): PushRegistrar =
        PushRegistrar(
            settings = settings,
            httpClient = OkHttpClient(),
            tokenProvider = FirebaseTokenProvider { token },
        )

    @Test
    fun syncPostsRegisterWithTokenScopeAndSets() = runBlocking {
        // 1. config probe → configured: true
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"reason":null}"""))
        // 2. POST register → 201
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true,"created":true}"""))

        val result = registrar().sync(PushScope.Attached, setOf("s1", "s2"), setOf("p1"))

        assertEquals(PushRegistrarResult.Success, result)

        val configReq = server.takeRequest()
        assertEquals("/api/push/fcm-config", configReq.path)
        assertEquals("Bearer $token", configReq.getHeader("Authorization"))

        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("/api/push/fcm-register", postReq.path)
        val body = postReq.body.readUtf8()
        assertTrue(body.contains("\"fcmToken\":\"fcm-token-abc-123\""))
        assertTrue(body.contains("\"scope\":\"attached\""))
        assertTrue(body.contains("\"attachedSessions\":[\"s1\",\"s2\"]"))
        assertTrue(body.contains("\"pinnedSessions\":[\"p1\"]"))
    }

    @Test
    fun syncReturnsServerUnconfiguredWhenConfigReportsFalse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":false,"reason":"not configured"}"""))
        val result = registrar().sync(PushScope.All, emptySet(), emptySet())
        assertEquals(PushRegistrarResult.ServerUnconfigured, result)
    }

    @Test
    fun syncReturnsErrorWhenFirebaseTokenIsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"reason":null}"""))
        val result = registrar(token = null).sync(PushScope.All, emptySet(), emptySet())
        assertTrue(result is PushRegistrarResult.Error)
    }

    @Test
    fun updatePatchesScopeAndSets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val result = registrar().update(PushScope.Pinned, emptySet(), setOf("p1"))
        assertEquals(PushRegistrarResult.Success, result)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/push/fcm-register", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"scope\":\"pinned\""))
        assertTrue(body.contains("\"pinnedSessions\":[\"p1\"]"))
        // PATCH must not re-send the fcm token.
        assertTrue(!body.contains("fcmToken"))
    }

    @Test
    fun updateReturnsErrorOn404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"No FCM registration for this device."}"""))
        val result = registrar().update(PushScope.All, emptySet(), emptySet())
        assertTrue(result is PushRegistrarResult.Error)
    }

    @Test
    fun unregisterDeletesTheRow() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"removed":true}"""))
        val result = registrar().unregister()
        assertEquals(PushRegistrarResult.Success, result)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/push/fcm-register", req.path)
    }

    @Test
    fun syncRejectsCookieCredential() = runBlocking {
        val cookieSettings = InMemorySettings()
        cookieSettings.setServer(server.url("/").toString(), Credential.Cookie("cookie-value"))
        val r = PushRegistrar(
            settings = cookieSettings,
            httpClient = OkHttpClient(),
            tokenProvider = FirebaseTokenProvider { "tok" },
        )
        val result = r.sync(PushScope.All, emptySet(), emptySet())
        assertTrue(result is PushRegistrarResult.Error)
    }
}