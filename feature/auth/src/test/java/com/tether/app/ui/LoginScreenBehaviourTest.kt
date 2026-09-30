package com.tether.app.ui

import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.tether.app.client.InMemorySettings
import com.tether.app.client.LocalNetworkAccess
import com.tether.app.client.RealTetherClient
import com.tether.app.protocol.TetherJson
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.theme.mode
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-s4r end to end on the JVM: the real [LoginScreen] (every surface) over the real
 * [RealTetherClient] against a MockWebServer "Tether" that answers like server.mjs:
 * `/api/auth/session` advertises `usernameRequired`, and `/api/auth/login` refuses a
 * password-only attempt with the same 401 as a wrong password when a username is set
 * (`credentialsMatch = passwordMatches && (!username || usernameMatches)`, no trimming).
 *
 * The fixture is the owner's console: TETHER_USERNAME set, passkeys registered, password
 * sign-in on. Passkeys change nothing on the password path at 0e6e862 (the only policy there
 * is the 403 when password sign-in is off; use-login-flow.ts posts `{username, password}` with
 * no challenge, cookie or extra header first).
 *
 * Paths covered: a submit that beats the probe or follows a failed one (never sends
 * `username: ""` blind), the refusal wording, a 401 from something in front of Tether, the
 * password and username exactly as typed, a submit in the same frame as the last keystroke,
 * and the autofill hints on the fields.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LoginScreenBehaviourTest(private val surface: LoginSurface) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun surfaces(): List<Array<Any>> = LoginSurface.entries.map { arrayOf(it) }

        const val REFUSED = "Those credentials are not correct."
    }

    // ta-vmg: the v2 rule runs the screen's coroutines on a StandardTestDispatcher, so they resume
    // on the main thread as they do on a device (AndroidUiDispatcher). The v1 rule's Unconfined
    // dispatcher resumed them on the OkHttp/IO thread that answered the probe or the login, which
    // then wrote the screen's state and applied the snapshot there: an intermittent
    // CalledFromWrongThreadException. tearDown fails any test that writes screen state off main.
    @get:Rule val rule = createComposeRule()

    /** A Tether console. [username] "" = TETHER_USERNAME unset (legacy password-only). */
    private inner class Console(var username: String, var password: String) : Dispatcher() {
        /** Registered passkeys (the owner has one): only ever reported by the probe. */
        var passkeyCount = 1

        /**
         * A gateway in front of Tether answering the login POST itself, the way Tether's README
         * sets SSO up: /healthz, /api/auth/session, /ws and /api/devices/claim exempt, and
         * /api/auth/login left gated. "basic" = a basic-auth challenge; "sso" = a bare 401 page.
         */
        @Volatile var gateway: String? = null

        val logins = ConcurrentLinkedQueue<JsonObject>()
        val probes = AtomicInteger()

        /** Closed = every probe answers at once; set to hold probes until counted down. */
        @Volatile var probeGate = CountDownLatch(0)

        /** How many of the next probes fail like a proxy error page; -1 = all of them. */
        val failProbes = AtomicInteger(0)

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/healthz" -> MockResponse().setBody("""{"ok":true,"protocolVersion":132,"nativeProtocolFloor":129}""")
            // The client's own post-sign-in check carries the session; only the login
            // screen's uncredentialed sign-in probe is counted (and can be held or failed).
            "/api/auth/session" -> if (request.getHeader("Cookie") == "tether_session=s3ss10n") {
                MockResponse().setBody("""{"authenticated":true,"usernameRequired":${username.isNotEmpty()}}""")
            } else {
                probes.incrementAndGet()
                probeGate.await(10, TimeUnit.SECONDS)
                val fail = failProbes.get().let { it < 0 || (it > 0 && failProbes.decrementAndGet() >= 0) }
                if (fail) {
                    MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>")
                } else {
                    MockResponse().setBody(
                        """{"authenticated":false,"usernameRequired":${username.isNotEmpty()},"passkeyCount":$passkeyCount,""" +
                            """"passwordLoginEnabled":true,"passkeysUsable":true,"rpId":"${request.requestUrl!!.host}","sessionMethod":null}""",
                    )
                }
            }
            "/api/auth/login" -> {
                val body = TetherJson.parseToJsonElement(request.body.readUtf8()) as JsonObject
                logins += body
                loginResponse(body)
            }
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
        }

        private fun loginResponse(body: JsonObject): MockResponse = when (gateway) {
            "basic" -> MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"lab\"")
                .setHeader("Server", "nginx/1.27.1")
                .setHeader("Content-Type", "text/html").setBody("<html><body>401 Authorization Required</body></html>")
            "sso" -> MockResponse().setResponseCode(401).setHeader("Content-Type", "text/plain").setBody("401 Unauthorized")
            else -> {
                val presentedUsername = body["username"]?.jsonPrimitive?.content.orEmpty()
                val presentedPassword = body["password"]?.jsonPrimitive?.content.orEmpty()
                val credentialsMatch = presentedPassword == password && (username.isEmpty() || presentedUsername == username)
                if (credentialsMatch) {
                    MockResponse().setBody("""{"ok":true}""").addHeader("set-cookie", "tether_session=s3ss10n; Path=/; HttpOnly")
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"error":"$REFUSED"}""")
                }
            }
        }
    }

    private val server = MockWebServer()
    private val console = Console(username = "operator", password = "correct horse")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restricted = AtomicBoolean(false)
    private val pendingRetry = AtomicReference<(() -> Unit)?>(null)

    /** Every screen-state write made off the main thread (ta-vmg): on a device there are none. */
    private val offMainWrites = ConcurrentLinkedQueue<String>()
    private var writeObserver: ObserverHandle? = null

    @Before fun setUp() {
        writeObserver = Snapshot.registerGlobalWriteObserver {
            if (Looper.myLooper() != Looper.getMainLooper()) offMainWrites += Thread.currentThread().name
        }
        server.dispatcher = console
        server.start()
    }

    @After fun tearDown() {
        writeObserver?.dispose()
        console.probeGate.countDown()
        scope.cancel()
        server.shutdown()
        assertTrue("screen state written off the main thread: ${offMainWrites.distinct()}", offMainWrites.isEmpty())
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun launch(http: OkHttpClient = OkHttpClient()) {
        val client = RealTetherClient(
            settings = InMemorySettings(),
            httpClient = http,
            scope = scope,
            localNetworkAccess = LocalNetworkAccess { restricted.get() },
        )
        rule.setContent {
            val skin = if (surface == LoginSurface.Studio) TetherSkin.Studio else TetherSkin.StudioDark
            TetherTheme(skin.mode) {
                LoginScreen(
                    client = client,
                    surface = surface,
                    onLocalNetworkBlocked = { retry -> pendingRetry.set(retry) },
                )
            }
        }
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) = rule.waitUntil(timeout) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun field(description: String): SemanticsNodeInteraction =
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(description)))

    private fun has(matcher: SemanticsMatcher): Boolean =
        rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun shows(text: String): Boolean = has(hasText(text, substring = true))

    private val usernameShown get() = has(hasContentDescription("Operator username", substring = true))

    private fun typeUrl(url: String = base) = field("Server URL").performTextInput(url)

    private fun submitPassword(password: String) {
        field("Dashboard password").performTextInput(password)
        field("Dashboard password").performImeAction()
    }

    private fun lastLogin(): Pair<String, String> = console.logins.last().let {
        it["username"]!!.jsonPrimitive.content to it["password"]!!.jsonPrimitive.content
    }

    /** What each surface says while a submit waits for the sign-in probe. */
    private val checkingCopy get() = if (surface == LoginSurface.Studio) "Checking sign-in…" else "checking sign-in"

    private val successCopy get() = when (surface) {
        LoginSurface.Studio -> "You’re in. Opening your workspace…"
        LoginSurface.Retro -> "ACCESS GRANTED"
    }

    @Test fun aSubmitThatBeatsTheProbeWaitsForItAndNeverSendsWithoutTheUsername() {
        console.probeGate = CountDownLatch(1)
        launch()
        typeUrl()
        submitPassword(console.password)
        waitFor { shows(checkingCopy) }
        // Held probe: nothing has gone to /api/auth/login, and the username line is not offered yet.
        assertTrue(console.logins.isEmpty())
        assertTrue(!usernameShown)

        console.probeGate.countDown()
        waitFor { shows("Enter your username.") }
        assertTrue("a password-only attempt went out: ${console.logins}", console.logins.isEmpty())
        assertTrue(usernameShown)

        field("Operator username").performTextInput("operator")
        // Retro clears nothing on a local validation error; the password line still holds it.
        field("Dashboard password").performImeAction()
        waitFor { shows(successCopy) }
        assertEquals(1, console.logins.size)
        assertEquals("operator" to "correct horse", lastLogin())
    }

    @Test fun aFailedProbeShowsTheUsernameAsOptionalAndSigningInWithItWorks() {
        console.failProbes.set(-1)
        launch()
        typeUrl()
        waitFor { usernameShown }
        assertTrue(has(hasContentDescription("Operator username, optional")))
        val hint = if (surface == LoginSurface.Retro) "login only if this console has a username" else USERNAME_OPTIONAL_HINT
        assertTrue(shows(hint))
        val probesBefore = console.probes.get()

        field("Operator username, optional").performTextInput("operator")
        submitPassword(console.password)
        waitFor { shows(successCopy) }
        // The submit asked once more before sending (the failed probe was not trusted as final).
        assertEquals(probesBefore + 1, console.probes.get())
        assertEquals("operator" to "correct horse", lastLogin())
    }

    @Test fun aRefusalWithNoUsernameSaysTheUsernameMayBeMissing() {
        console.failProbes.set(-1)
        launch()
        typeUrl()
        waitFor { usernameShown }
        submitPassword(console.password)
        waitFor { shows(REFUSED) }
        assertTrue(shows("$REFUSED $USERNAME_MISSING_HINT"))
        assertEquals("" to "correct horse", lastLogin())
    }

    @Test fun aConsoleWithoutAUsernameShowsNoFieldAndSignsInWithThePasswordAlone() {
        console.username = ""
        launch()
        typeUrl()
        waitFor { console.probes.get() >= 1 && !shows("checking…") && !shows("Connecting to your workspace…") }
        rule.waitForIdle()
        assertTrue(!usernameShown)

        submitPassword(console.password)
        waitFor { shows(successCopy) }
        // Known requirements: no second probe, straight to the login.
        assertEquals(1, console.probes.get())
        assertEquals("" to "correct horse", lastLogin())
    }

    @Test fun aWrongPasswordOnAConsoleKnownToHaveNoUsernameIsNotBlamedOnTheUsername() {
        console.username = ""
        launch()
        typeUrl()
        waitFor { console.probes.get() >= 1 && !shows("checking…") && !shows("Connecting to your workspace…") }
        submitPassword("wrong")
        waitFor { shows(REFUSED) }
        assertTrue(!shows(USERNAME_MISSING_HINT))
    }

    @Test fun thePasswordIsSentExactlyAsTypedLikeTheWeb() {
        // server.mjs compares the presented password as-is and the web's forms never trim it.
        console.username = ""
        console.password = "  spaced out  "
        launch()
        typeUrl()
        submitPassword("  spaced out  ")
        waitFor { shows(successCopy) }
        assertEquals("  spaced out  ", lastLogin().second)
    }

    /**
     * The path the owner can hit on Android 17 with a LAN console: the probe is refused
     * before local-network access is granted, the login is blocked, the host runs the
     * permission flow and retries. The retry must re-ask the probe, not send `username: ""`.
     */
    @Test fun theRetryAfterLocalNetworkAccessIsGrantedAsksTheProbeAgain() {
        restricted.set(true)
        // A single-label name is a LAN host (LocalNetworkHosts); resolve it to the MockWebServer.
        val http = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName(server.hostName))
        }).build()
        launch(http)
        typeUrl("http://tether:${server.port}")
        submitPassword(console.password)
        waitFor { pendingRetry.get() != null }
        assertEquals(0, console.probes.get())
        assertTrue(console.logins.isEmpty())

        restricted.set(false)
        rule.runOnIdle { pendingRetry.get()!!.invoke() }
        waitFor { shows("Enter your username.") }
        assertTrue("the retry sent a password-only login: ${console.logins}", console.logins.isEmpty())
        assertTrue(console.probes.get() >= 1)

        field("Operator username").performTextInput("operator")
        field("Dashboard password").performImeAction()
        waitFor { shows(successCopy) }
        assertEquals("operator" to "correct horse", lastLogin())
    }

    @Test fun thePasswordTypedAndSentInTheSameFrameIsSentWhole() {
        launch()
        typeUrl()
        waitFor { usernameShown }
        field("Operator username").performTextInput("operator")
        rule.waitForIdle()
        // No frame between the last keystroke and Go: the surface still holds the composition
        // that saw an empty password. The submit must read the live value, not that copy.
        rule.mainClock.autoAdvance = false
        field("Dashboard password").performTextInput(console.password)
        field("Dashboard password").performImeAction()
        rule.mainClock.autoAdvance = true
        waitFor { shows(successCopy) }
        assertEquals("operator" to "correct horse", lastLogin())
    }

    @Test fun theUsernameIsSentTrimmedButOtherwiseExactlyAsTyped() {
        // retro-login.tsx:105 trims; nothing anywhere changes case or inner characters.
        console.username = "Op.Erator_1"
        launch()
        typeUrl()
        waitFor { usernameShown }
        field("Operator username").performTextInput("  Op.Erator_1 ")
        submitPassword(console.password)
        waitFor { shows(successCopy) }
        assertEquals("Op.Erator_1" to "correct horse", lastLogin())
    }

    /**
     * The owner's report: the username line shows (the probe is exempt and reached Tether),
     * "checking password", then "That password is not correct." - the old fallback for a 401
     * without Tether's `{error}`. That 401 came from the gateway, which the password never
     * gets past from the app (it holds no SSO session), so the screen must say so.
     */
    @Test fun aGatewayGuardingTheLoginIsNamedNotCalledAWrongPassword() {
        console.gateway = "sso"
        launch()
        typeUrl()
        waitFor { usernameShown }
        field("Operator username").performTextInput("operator")
        submitPassword(console.password)
        waitFor { shows("before Tether checked the password (HTTP 401)") }
        assertTrue(shows("Pair this device with a code from the browser instead."))
        assertTrue(!shows("That password is not correct."))
        assertTrue(!shows(REFUSED))
        assertTrue(!shows(USERNAME_MISSING_HINT))
        // The password did reach the gateway once; nothing retried it behind the user's back.
        assertEquals(1, console.logins.size)
    }

    @Test fun aBasicAuthProxyIsNamedWithItsServerAndScheme() {
        console.gateway = "basic"
        launch()
        typeUrl()
        waitFor { usernameShown }
        field("Operator username").performTextInput("operator")
        submitPassword(console.password)
        waitFor { shows("(HTTP 401, from nginx/1.27.1, asking for Basic authentication)") }
        assertTrue(!shows("That password is not correct."))
    }

    @Test fun aRefusalFromTetherShowsItsOwnWordsAndOnlyRetroClearsThePassword() {
        launch()
        typeUrl()
        waitFor { usernameShown }
        field("Operator username").performTextInput("operator")
        submitPassword("wrong horse")
        waitFor { shows(REFUSED) }
        assertTrue(!shows(USERNAME_MISSING_HINT))
        val left = field("Dashboard password").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        if (surface == LoginSurface.Retro) assertEquals("", left) else assertEquals("wrong horse".length, left.length)

        // The retry after a refusal goes out with what is on screen now.
        field("Dashboard password").performTextClearance()
        submitPassword(console.password)
        waitFor { shows(successCopy) }
        assertEquals("operator" to "correct horse", lastLogin())
    }

    @Test fun theSignInFieldsTellAutofillWhichIsTheUsernameAndWhichThePassword() {
        launch()
        typeUrl()
        waitFor { usernameShown }
        field("Operator username").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username))
        field("Dashboard password").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password))
        // The server URL is not a credential: no hint an autofill service could fill a username into.
        assertEquals(null, field("Server URL").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentType))
    }

    @Test fun aProbeThatFailsOnceIsAskedAgainBeforeTheSubmitGivesUp() {
        // The clock is held so the screen's own debounced probe never starts: the only probes
        // are the submit's, and the first of them fails (a proxy hiccup, a network change).
        rule.mainClock.autoAdvance = false
        console.failProbes.set(1)
        launch()
        typeUrl()
        submitPassword(console.password)
        val deadline = System.currentTimeMillis() + 10_000
        while (!shows("Enter your username.") && System.currentTimeMillis() < deadline) {
            rule.mainClock.advanceTimeBy(50)
            Thread.sleep(10)
        }
        rule.mainClock.autoAdvance = true
        assertTrue(shows("Enter your username."))
        assertTrue("sent before the retried probe answered: ${console.logins}", console.logins.isEmpty())
        assertTrue(console.probes.get() >= 2)
    }
}
