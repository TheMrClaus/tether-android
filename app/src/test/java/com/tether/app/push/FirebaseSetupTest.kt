package com.tether.app.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import com.tether.app.protocol.TetherJson
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F2: in production FirebaseApp is initialised from the paired server's
 * fcm-config `client` block (there is no google-services plugin and no process
 * environment). The initializer and FirebaseApp are real here; only the token
 * call is faked. All identifiers are obviously fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirebaseSetupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val settings = InMemorySettings()

    private val fakeClient = """{"projectId":"fake-project-01","appId":"1:123456789012:android:0123456789abcdef",""" +
        """"apiKey":"fake-api-key-not-a-real-key-0000","senderId":"123456789012"}"""
    private val otherClient = """{"projectId":"fake-project-02","appId":"1:210987654321:android:fedcba9876543210",""" +
        """"apiKey":"fake-api-key-not-a-real-key-1111","senderId":"210987654321"}"""

    @Before
    fun setUp() {
        FirebaseApp.clearInstancesForTest()
        context.getSharedPreferences(FirebaseClientConfigStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        server.start()
        runBlocking { settings.setServer(server.url("/").toString(), Credential.DeviceToken("fake-device-token")) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        FirebaseApp.clearInstancesForTest()
    }

    private fun parse(json: String) = FirebaseClientConfig.parse(TetherJson.parseToJsonElement(json).jsonObject)

    @Test
    fun fcmConfigClientBuildsTheFirebaseAppAndThenTheTokenIsFetched() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"reason":null,"client":$fakeClient}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))
        val tokenCalls = AtomicInteger()
        val registrar = PushRegistrar(
            settings = settings,
            httpClient = OkHttpClient(),
            tokenProvider = FirebaseTokenProvider {
                // The token is requested only once FirebaseApp exists.
                assertTrue(FirebaseApp.getApps(context).isNotEmpty())
                tokenCalls.incrementAndGet()
                "fake-fcm-token-not-a-credential"
            },
            firebase = AndroidFirebaseInitializer(context),
        )

        val result = registrar.sync(PushScope.All, emptySet(), emptySet(), syncHints = false)

        assertEquals(PushRegistrarResult.Success, result)
        val options = FirebaseApp.getInstance().options
        assertEquals("fake-project-01", options.projectId)
        assertEquals("1:123456789012:android:0123456789abcdef", options.applicationId)
        assertEquals("fake-api-key-not-a-real-key-0000", options.apiKey)
        assertEquals("123456789012", options.gcmSenderId)
        assertEquals(1, tokenCalls.get())
        assertEquals("/api/push/fcm-config", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)?.method)
    }

    @Test
    fun todaysServerWithoutAClientBlockRegistersNothing() = runBlocking {
        // v130 fcm-config is {configured, reason} only: nothing to initialise from.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"reason":null}"""))
        val tokenCalls = AtomicInteger()
        val registrar = PushRegistrar(
            settings,
            OkHttpClient(),
            FirebaseTokenProvider { tokenCalls.incrementAndGet(); "fake" },
            AndroidFirebaseInitializer(context),
        )
        val result = registrar.sync(PushScope.All, emptySet(), emptySet(), syncHints = false)
        assertEquals(PushRegistrarResult.Error("Firebase client config unavailable."), result)
        assertEquals(0, tokenCalls.get())
        assertTrue(FirebaseApp.getApps(context).isEmpty())
        server.takeRequest(5, TimeUnit.SECONDS)
        assertNull("no fcm-register without a token", server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun theSavedConfigIsRestoredAtTheNextStart() {
        assertTrue(AndroidFirebaseInitializer(context).ensure(parse(fakeClient)))
        FirebaseApp.clearInstancesForTest() // a new process

        assertTrue(AndroidFirebaseInitializer(context).restore())

        assertEquals("fake-project-01", FirebaseApp.getInstance().options.projectId)
    }

    @Test
    fun nothingSavedAndNothingRunningRestoresNothing() {
        assertEquals(false, AndroidFirebaseInitializer(context).restore())
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }

    @Test
    fun anotherServersProjectReplacesTheDefaultApp() {
        val initializer = AndroidFirebaseInitializer(context)
        assertTrue(initializer.ensure(parse(fakeClient)))
        assertTrue(initializer.ensure(parse(otherClient)))
        assertEquals("fake-project-02", FirebaseApp.getInstance().options.projectId)
        assertEquals("fake-project-02", FirebaseClientConfigStore(context).load()?.projectId)
    }

    @Test
    fun anExplicitOverrideWinsAndIsNotSaved() {
        val override = FirebaseConfig {
            FirebaseOptions.Builder()
                .setProjectId("fake-dev-project")
                .setApplicationId("1:999:android:abc")
                .setApiKey("fake-api-key-not-a-real-key-9999")
                .build()
        }
        assertTrue(AndroidFirebaseInitializer(context, override = override).ensure(parse(fakeClient)))
        assertEquals("fake-dev-project", FirebaseApp.getInstance().options.projectId)
        assertNull(FirebaseClientConfigStore(context).load())
    }

    @Test
    fun theClientBlockIsValidated() {
        assertNotNull(parse(fakeClient))
        val bad = listOf(
            // sender id is not the project number inside the app id
            fakeClient.replace("\"senderId\":\"123456789012\"", "\"senderId\":\"999\""),
            // not an Android app id
            fakeClient.replace("1:123456789012:android:", "1:123456789012:ios:"),
            fakeClient.replace("\"projectId\":\"fake-project-01\"", "\"projectId\":\"Bad Project\""),
            fakeClient.replace("\"apiKey\":\"fake-api-key-not-a-real-key-0000\"", "\"apiKey\":\"short\""),
            fakeClient.replace("\"apiKey\":\"fake-api-key-not-a-real-key-0000\"", "\"apiKey\":7"),
            fakeClient.replace(",\"senderId\":\"123456789012\"", ""),
            "{}",
        )
        for (json in bad) assertNull(json, parse(json))
        assertNull(FirebaseClientConfig.parse(null))
    }
}
