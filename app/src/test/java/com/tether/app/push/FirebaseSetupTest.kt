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
        FirebaseClientConfigStore(context).clear()
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

    private val originA = "https://a.tether.invalid:443"
    private val originB = "https://b.tether.invalid:443"

    /** Records the project that was the default app each time a token delete ran. */
    private val deletedFor = mutableListOf<String?>()

    private fun initializer(override: FirebaseConfig? = null) = AndroidFirebaseInitializer(
        context,
        override = override,
        deleteCurrentToken = {
            deletedFor += FirebaseApp.getApps(context).firstOrNull()?.options?.projectId
        },
    )

    private fun runningProject(): String? = FirebaseApp.getApps(context).firstOrNull()?.options?.projectId

    @Test
    fun theSavedConfigIsRestoredAtTheNextStart() = runBlocking {
        assertEquals(FirebaseSetup.Ready, initializer().ensure(parse(fakeClient), originA))
        FirebaseApp.clearInstancesForTest() // a new process

        assertTrue(initializer().restore())

        assertEquals("fake-project-01", runningProject())
    }

    @Test
    fun nothingSavedAndNothingRunningRestoresNothing() {
        assertEquals(false, initializer().restore())
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }

    @Test
    fun theSameServerNamingAnotherProjectIsNotSwitchedSilently() = runBlocking {
        val init = initializer()
        assertEquals(FirebaseSetup.Ready, init.ensure(parse(fakeClient), originA))

        assertEquals(FirebaseSetup.ProjectChanged, init.ensure(parse(otherClient), originA))

        assertEquals("the accepted project stays up", "fake-project-01", runningProject())
        assertEquals("fake-project-01", FirebaseClientConfigStore(context).load()?.config?.projectId)
        assertEquals(emptyList<String?>(), deletedFor)
    }

    @Test
    fun theProjectChangeIsSurfacedByTheRegistrarAndNothingIsRegistered() = runBlocking {
        initializer().ensure(parse(otherClient), "http://${server.hostName}:${server.port}")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"client":$fakeClient}"""))
        val tokenCalls = AtomicInteger()
        val registrar = PushRegistrar(settings, OkHttpClient(), FirebaseTokenProvider { tokenCalls.incrementAndGet(); "fake" }, initializer())
        assertEquals(PushRegistrarResult.ProjectChanged, registrar.sync(PushScope.All, emptySet(), emptySet(), syncHints = false))
        assertEquals(0, tokenCalls.get())
        server.takeRequest(5, TimeUnit.SECONDS)
        assertNull("no fcm-register", server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun afterLogoutARePairAcceptsTheNewProject() = runBlocking {
        val init = initializer()
        init.ensure(parse(fakeClient), originA)
        init.forget()
        assertEquals(FirebaseSetup.Ready, init.ensure(parse(otherClient), originA))
        assertEquals("fake-project-02", runningProject())
        assertEquals(listOf<String?>("fake-project-01"), deletedFor)
    }

    @Test
    fun theSameServerWithoutAClientBlockReusesItsOwnConfig() = runBlocking {
        val init = initializer()
        init.ensure(parse(fakeClient), originA)
        FirebaseApp.clearInstancesForTest()
        assertEquals(FirebaseSetup.Ready, init.ensure(null, originA))
        assertEquals("fake-project-01", runningProject())
    }

    @Test
    fun anotherServerWithoutAClientBlockNeverReusesTheOldConfig() = runBlocking {
        val init = initializer()
        init.ensure(parse(fakeClient), originA)

        assertEquals(FirebaseSetup.Unavailable, init.ensure(null, originB))

        assertEquals("the old project's token was deleted first", listOf<String?>("fake-project-01"), deletedFor)
        assertTrue("the default app is gone", FirebaseApp.getApps(context).isEmpty())
        assertNull("the binding is cleared", FirebaseClientConfigStore(context).load())
        assertEquals(false, initializer().restore())
    }

    @Test
    fun anotherServersProjectReplacesTheDefaultAppAfterDeletingTheOldToken() = runBlocking {
        val init = initializer()
        assertEquals(FirebaseSetup.Ready, init.ensure(parse(fakeClient), originA))
        assertEquals(FirebaseSetup.Ready, init.ensure(parse(otherClient), originB))
        assertEquals("deleted while the old project was still the default", listOf<String?>("fake-project-01"), deletedFor)
        assertEquals("fake-project-02", runningProject())
        assertEquals(BoundFirebaseConfig(originB, parse(otherClient)!!), FirebaseClientConfigStore(context).load())
    }

    @Test
    fun theSameProjectNeedsNoTokenDelete() = runBlocking {
        val init = initializer()
        init.ensure(parse(fakeClient), originA)
        init.ensure(parse(fakeClient), originA)
        assertEquals(emptyList<String?>(), deletedFor)
    }

    @Test
    fun anExplicitOverrideWinsAndIsNotSaved() = runBlocking {
        val override = FirebaseConfig {
            FirebaseOptions.Builder()
                .setProjectId("fake-dev-project")
                .setApplicationId("1:999:android:abc")
                .setApiKey("fake-api-key-not-a-real-key-9999")
                .build()
        }
        assertEquals(FirebaseSetup.Ready, initializer(override).ensure(parse(fakeClient), originA))
        assertEquals("fake-dev-project", runningProject())
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
