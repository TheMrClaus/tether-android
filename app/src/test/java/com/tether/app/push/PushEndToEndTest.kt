package com.tether.app.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.RemoteMessage
import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The push behaviour test (PLAN §4; emulators are deferred by the owner, so it
 * runs on Robolectric). MockWebServer is the Tether server, answering by route.
 * The chain is real: PushController → PushRegistrar → AndroidFirebaseInitializer
 * (FirebaseApp from fcm-config) → fcm-register → TetherFcmService →
 * NotificationManager → logout. Only the FCM token calls are faked, and every
 * identifier is obviously fake.
 *
 * fcm-config here already carries the `client` block that the server S-task adds
 * (v130 does not yet send it; FirebaseSetupTest covers that case).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushEndToEndTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val tether = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val settings = InMemorySettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tokenCalls = AtomicInteger()
    private val deleteCalls = AtomicInteger()
    private val tokens = object : FirebaseTokenProvider {
        override suspend fun token(): String {
            tokenCalls.incrementAndGet()
            return "fake-fcm-token-not-a-credential"
        }

        override suspend fun delete() {
            deleteCalls.incrementAndGet()
        }
    }

    private class Prefs : PushPrefs {
        override val pushEnabled = MutableStateFlow(true)
        override val pushScope = MutableStateFlow(PushScope.All)
        override val attachedSessions = MutableStateFlow(emptyList<String>())
        override val pinnedSessions = MutableStateFlow(emptyList<String>())
    }

    @Before
    fun setUp() {
        FirebaseApp.clearInstancesForTest()
        context.getSharedPreferences(FirebaseClientConfigStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        PushChannels.ensure(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ForegroundState.isForeground = false
        ForegroundState.activeTag = null
        tether.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when ("${request.method} ${request.path}") {
                    "GET /api/push/fcm-config" -> MockResponse().setBody(
                        """{"configured":true,"reason":null,"client":{"projectId":"fake-project-01",""" +
                            """"appId":"1:123456789012:android:0123456789abcdef",""" +
                            """"apiKey":"fake-api-key-not-a-real-key-0000","senderId":"123456789012"}}""",
                    )
                    "POST /api/push/fcm-register" -> MockResponse().setResponseCode(201).setBody("""{"ok":true,"created":true}""")
                    "DELETE /api/push/fcm-register" -> MockResponse().setBody("""{"ok":true,"removed":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        tether.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        tether.shutdown()
        manager.cancelAll()
        FirebaseApp.clearInstancesForTest()
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("expected $count requests, got ${requests.map { "${it.method} ${it.path}" }}", requests.size >= count)
    }

    private fun dataMessage(vararg data: Pair<String, String>) =
        RemoteMessage(Bundle().apply { data.forEach { (k, v) -> putString(k, v) } })

    @Test
    fun signInRegisterReceiveAndLogOut() = runBlocking {
        val controller = PushController(
            app = context as Application,
            settings = settings,
            prefs = Prefs(),
            httpClient = OkHttpClient(),
            scope = scope,
            tokenProvider = tokens,
            firebase = AndroidFirebaseInitializer(context),
        )
        controller.startSync()
        val baseUrl = tether.url("/").toString()
        val credential = Credential.DeviceToken("fake-device-token-not-a-credential")

        // 1. Sign-in (pairing stores the device credential).
        settings.setServer(baseUrl, credential)

        // 2. fcm-config → FirebaseApp from its client block → token → fcm-register.
        awaitRequests(2)
        assertEquals("GET /api/push/fcm-config", "${requests[0].method} ${requests[0].path}")
        assertEquals("Bearer fake-device-token-not-a-credential", requests[0].getHeader("Authorization"))
        assertEquals("fake-project-01", FirebaseApp.getInstance().options.projectId)
        assertEquals("http://${tether.hostName}:${tether.port}", FirebaseClientConfigStore(context).load()?.origin)
        assertEquals(1, tokenCalls.get())
        val register = requests[1]
        assertEquals("POST /api/push/fcm-register", "${register.method} ${register.path}")
        val body = register.body.readUtf8()
        assertTrue(body, body.contains("\"fcmToken\":\"fake-fcm-token-not-a-credential\""))
        assertTrue(body, body.contains("\"syncHints\":false"))
        assertTrue(body, body.contains("\"scope\":\"all\""))

        // 3. A data message per kind posts on its channel.
        val service = Robolectric.buildService(TetherFcmService::class.java).get()
        val kinds = listOf(
            "approval" to PushChannels.APPROVAL,
            "question" to PushChannels.QUESTION,
            "turn_end" to PushChannels.TURN_DONE,
            "resume_choice" to PushChannels.RATE_LIMIT,
        )
        for ((kind, _) in kinds) {
            service.onMessageReceived(
                dataMessage("kind" to kind, "tag" to "tether-$kind-fake", "url" to "/", "title" to "Tether needs you", "body" to "A session needs you."),
            )
        }
        val posted: List<Notification> = shadowOf(manager).allNotifications
        assertEquals(kinds.map { it.second }.toSet(), posted.map { it.channelId }.toSet())
        assertEquals(4, posted.size)

        // 4. A sync hint posts nothing.
        service.onMessageReceived(dataMessage("kind" to "sync", "v" to "1"))
        assertEquals(4, shadowOf(manager).allNotifications.size)

        // 5. Logout, in RealTetherClient's order: forget the credential, then the
        // onLogout hook DELETEs with the one that was in force and kills the token.
        settings.clearCredential()
        controller.unregisterAfterLogout(baseUrl, credential)
        awaitRequests(3)
        val delete = requests[2]
        assertEquals("DELETE /api/push/fcm-register", "${delete.method} ${delete.path}")
        assertEquals("Bearer fake-device-token-not-a-credential", delete.getHeader("Authorization"))
        assertEquals(1, deleteCalls.get())
        // The accepted Firebase project is forgotten, so a re-pair can accept another.
        assertEquals(null, FirebaseClientConfigStore(context).load())

        // Signed out: nothing else reaches the server.
        Thread.sleep(500)
        assertEquals(3, requests.size)
    }
}
