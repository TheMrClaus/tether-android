package com.tether.app.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production trigger path ([PushController.startSync]), not a simulated
 * prefs emission: a credential appearing in the settings store, or the server
 * changing, must reach `/api/push/fcm-register` on the current server, once.
 * MockWebServer plays the Tether server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushControllerTriggerTest {

    private val serverA = MockWebServer()
    private val serverB = MockWebServer()
    private val settings = InMemorySettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Prefs : PushPrefs {
        override val pushEnabled = MutableStateFlow(true)
        override val pushScope = MutableStateFlow(PushScope.All)
        override val attachedSessions = MutableStateFlow(emptyList<String>())
        override val pinnedSessions = MutableStateFlow(emptyList<String>())
    }

    @Before
    fun setUp() {
        serverA.start()
        serverB.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        serverA.shutdown()
        serverB.shutdown()
    }

    private fun controller() = PushController(
        app = ApplicationProvider.getApplicationContext<Application>(),
        settings = settings,
        prefs = Prefs(),
        httpClient = OkHttpClient(),
        scope = scope,
        tokenProvider = FirebaseTokenProvider { "fake-fcm-token-not-a-credential" },
    ).also { it.startSync() }

    private fun MockWebServer.expectRegistration() {
        enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"reason":null}"""))
        enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true,"created":true}"""))
    }

    /** The fcm-config probe, then the POST; returns the POST body. */
    private fun MockWebServer.awaitRegistration(): String {
        val config = takeRequest(10, TimeUnit.SECONDS)
        assertEquals("/api/push/fcm-config", config?.path)
        val post = takeRequest(10, TimeUnit.SECONDS)
        assertEquals("POST", post?.method)
        assertEquals("/api/push/fcm-register", post?.path)
        return post!!.body.readUtf8()
    }

    @Test
    fun aCredentialAppearingRegistersExactlyOnceWithTheCurrentServer() = runBlocking {
        serverA.expectRegistration()
        controller()
        // Signed out: nothing may be sent.
        assertNull(serverA.takeRequest(500, TimeUnit.MILLISECONDS))

        settings.setServer(serverA.url("/").toString(), Credential.DeviceToken("fake-device-token-a"))

        val body = serverA.awaitRegistration()
        assertTrue(body, body.contains("\"syncHints\":false"))
        // Exactly one registration: no second probe or POST follows.
        assertNull(serverA.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(2, serverA.requestCount)
    }

    @Test
    fun aServerSwitchRegistersWithTheNewServer() = runBlocking {
        serverA.expectRegistration()
        serverB.expectRegistration()
        controller()
        settings.setServer(serverA.url("/").toString(), Credential.DeviceToken("fake-device-token-a"))
        serverA.awaitRegistration()

        settings.setServer(serverB.url("/").toString(), Credential.DeviceToken("fake-device-token-b"))

        val request = serverB.takeRequest(10, TimeUnit.SECONDS)
        assertEquals("Bearer fake-device-token-b", request?.getHeader("Authorization"))
        assertEquals("POST", serverB.takeRequest(10, TimeUnit.SECONDS)?.method)
        assertNull(serverB.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(2, serverA.requestCount)
    }

    @Test
    fun aCookieLoginRegistersNothing() = runBlocking {
        controller()
        settings.setServer(serverA.url("/").toString(), Credential.Cookie("fake-cookie"))
        assertNull(serverA.takeRequest(1, TimeUnit.SECONDS))
    }
}
