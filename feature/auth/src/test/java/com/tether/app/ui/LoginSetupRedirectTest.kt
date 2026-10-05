package com.tether.app.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.tether.app.client.InMemorySettings
import com.tether.app.client.RealTetherClient
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.6 (ta-jwbs): the sign-in screen sends a server whose /healthz says `setupRequired: true` to the
 * setup wizard (the web's /login redirects to /setup), by the address probe and by a sign-in attempt, and
 * leaves a normal server's sign-in alone. The real [LoginScreen] over the real [RealTetherClient] against a
 * MockWebServer shaped from lib/setup-server.mjs (its /api answers 503, its /healthz `setupRequired`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LoginSetupRedirectTest {
    @get:Rule val rule = createComposeRule()

    private val paths = ConcurrentLinkedQueue<String>()
    private val setupMode = java.util.concurrent.atomic.AtomicBoolean(true)
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.path}"
                return when (request.path) {
                    "/healthz" -> if (setupMode.get()) {
                        MockResponse().setBody("""{"ok":true,"setupRequired":true,"runtime":"native"}""")
                    } else {
                        MockResponse().setBody("""{"ok":true,"protocolVersion":143,"nativeProtocolFloor":129,"pairing":true}""")
                    }
                    "/api/auth/session" -> if (setupMode.get()) {
                        MockResponse().setResponseCode(503).setBody("""{"error":"Setup required.","setupRequired":true}""")
                    } else {
                        MockResponse().setBody("""{"authenticated":false,"usernameRequired":false,"passkeyCount":0,"passwordLoginEnabled":true,"passkeysUsable":false}""")
                    }
                    "/api/auth/login" -> MockResponse().setResponseCode(401).setBody("""{"error":"Invalid credentials."}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opened = CopyOnWriteArrayList<String>()
    private val base get() = server.url("/").toString().trimEnd('/')

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun launch(autoOpenSetup: Boolean = true, initial: String? = null) {
        val client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope)
        rule.setContent {
            TetherTheme(TetherSkin.Studio.mode) {
                LoginScreen(client = client, onSetupRequired = { opened += it }, autoOpenSetup = autoOpenSetup, initialBaseUrl = initial)
            }
        }
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) = rule.waitUntil(timeout) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun field(description: String): SemanticsNodeInteraction =
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(description)))

    @Test fun theAddressProbeOpensTheWizardForASetupServer() {
        launch()
        field("Server URL").performTextInput(base)
        waitFor { opened.isNotEmpty() }
        assertEquals(listOf(base), opened.toList())
        // Nothing was signed in with: only the probes.
        assertTrue(paths.none { it.startsWith("POST") })
    }

    @Test fun anAddressTheWizardWasJustLeftIsNotOpenedAgainByTheProbeButASignInAttemptIs() {
        launch(autoOpenSetup = false, initial = base)
        waitFor { paths.contains("GET /api/auth/session") }
        rule.waitForIdle()
        Thread.sleep(300)
        assertTrue("the probe re-opened the wizard it was just sent from: $opened", opened.isEmpty())

        field("Dashboard password").performTextInput("a password")
        field("Dashboard password").performImeAction()
        waitFor { opened.isNotEmpty() }
        assertEquals(listOf(base), opened.toList())
        // The sign-in stopped at /healthz: the password was never posted anywhere.
        assertTrue(paths.none { it == "POST /api/auth/login" })
    }

    @Test fun aNormalServerIsNotSentToTheWizard() {
        setupMode.set(false)
        launch()
        field("Server URL").performTextInput(base)
        waitFor { paths.contains("GET /api/auth/session") }
        rule.waitForIdle()
        Thread.sleep(300)
        assertTrue(opened.isEmpty())
        // A normal server's probe read answers, so nothing asked /healthz for the wizard.
        assertTrue(paths.none { it == "GET /healthz" })
    }
}
