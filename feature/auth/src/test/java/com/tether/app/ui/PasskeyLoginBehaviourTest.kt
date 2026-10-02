package com.tether.app.ui

import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.tether.app.client.InMemorySettings
import com.tether.app.client.PasskeyAuthenticator
import com.tether.app.client.PasskeyCeremony
import com.tether.app.client.PasskeyLoginCopy
import com.tether.app.client.RealTetherClient
import com.tether.app.protocol.TetherJson
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.5: Sign in with a passkey, end to end on the JVM: the real [LoginScreen] (both surfaces) over
 * the real [RealTetherClient] against a MockWebServer console answering like server.mjs 90fbb9f, with a
 * Credential Manager that waits for the test's answer, on the phone and the expanded layout.
 *
 * The web's use-login-flow.ts: offered only when the probe says a passkey is registered and usable
 * and the phone can run one; "Waiting for your passkey…" while it runs; a dismissed prompt is the
 * notice "Passkey prompt dismissed." and back to ready; a refusal is the server's own sentence; success
 * is the success copy. Retro's Enter on an empty password line is the passkey. And the app's guard:
 * options for another rpId never open the prompt.
 */
abstract class PasskeyLoginBehaviourBase(private val surface: LoginSurface) {
    @get:Rule val rule = createComposeRule()

    /** A fake Credential Manager whose ceremony waits until [answer]. */
    private class WaitingPasskeys(override val available: Boolean = true) : PasskeyAuthenticator {
        val requests = CopyOnWriteArrayList<String>()
        private val replies = CopyOnWriteArrayList<CompletableDeferred<PasskeyCeremony>>()
        override suspend fun register(requestJson: String): PasskeyCeremony = error("the login screen never registers")
        override suspend fun authenticate(requestJson: String): PasskeyCeremony {
            val reply = CompletableDeferred<PasskeyCeremony>()
            requests += requestJson
            replies += reply
            return reply.await()
        }
        fun pending() = replies.any { !it.isCompleted }
        fun answer(c: PasskeyCeremony) = replies.first { !it.isCompleted }.complete(c)
    }

    private inner class Console : Dispatcher() {
        @Volatile var passkeyCount = 1
        @Volatile var passkeysUsable = true
        @Volatile var passwordLoginEnabled = true
        /** The rpId the options name; null = the request's own host (a well-configured console). */
        @Volatile var rpId: String? = null
        @Volatile var verifyRefuses = false
        val verifies = ConcurrentLinkedQueue<JsonObject>()
        val logins = ConcurrentLinkedQueue<String>()
        val probes = java.util.concurrent.atomic.AtomicInteger()
        val optionsCalls = java.util.concurrent.atomic.AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/healthz" -> MockResponse().setBody("""{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129}""")
            "/api/auth/session" -> if (request.getHeader("Cookie") != null) {
                MockResponse().setBody("""{"authenticated":true}""")
            } else {
                probes.incrementAndGet()
                MockResponse().setBody(
                    """{"authenticated":false,"usernameRequired":false,"passkeyCount":$passkeyCount,"passwordLoginEnabled":$passwordLoginEnabled,""" +
                        """"passkeysUsable":$passkeysUsable,"rpId":"${request.requestUrl!!.host}","sessionMethod":null}""",
                )
            }
            "/api/auth/passkey/login/options" -> MockResponse().also { optionsCalls.incrementAndGet() }.setHeader("Content-Type", "application/json").setBody(
                """{"challengeId":"0123456789abcdef0123456789abcdef","options":{"rpId":"${rpId ?: request.requestUrl!!.host}","challenge":"Y2hhbGxlbmdl","timeout":60000,"userVerification":"required"}}""",
            )
            "/api/auth/passkey/login/verify" -> {
                verifies += TetherJson.parseToJsonElement(request.body.readUtf8()) as JsonObject
                if (verifyRefuses) {
                    MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("""{"error":"That passkey could not be verified."}""")
                } else {
                    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}""")
                        .addHeader("Set-Cookie", "tether_session=0123456789abcdef0123456789abcdef.YXBwLXBhc3NrZXk; Path=/; HttpOnly; SameSite=Strict")
                }
            }
            "/api/auth/login" -> {
                logins += request.body.readUtf8()
                MockResponse().setResponseCode(401).setBody("""{"error":"Those credentials are not correct."}""")
            }
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
        }
    }

    private val server = MockWebServer()
    private val console = Console()

    // r2 (security F1): the console over TLS with a certificate only this client trusts.
    private val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName(server.hostName).build()
    private val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val tlsClient = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
    /** Off for the one test that types an http address. */
    private var tls = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passkeys = WaitingPasskeys()
    private val offMainWrites = ConcurrentLinkedQueue<String>()
    private var writeObserver: ObserverHandle? = null

    @Before fun setUp() {
        writeObserver = Snapshot.registerGlobalWriteObserver {
            if (Looper.myLooper() != Looper.getMainLooper()) offMainWrites += Thread.currentThread().name
        }
        server.dispatcher = console
    }

    @After fun tearDown() {
        writeObserver?.dispose()
        scope.cancel()
        server.shutdown()
        assertTrue("screen state written off the main thread: ${offMainWrites.distinct()}", offMainWrites.isEmpty())
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun launch(authenticator: PasskeyAuthenticator = passkeys) {
        if (tls) server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.start()
        val client = RealTetherClient(settings = InMemorySettings(), httpClient = tlsClient, scope = scope)
        rule.setContent {
            val skin = if (surface == LoginSurface.Studio) TetherSkin.Studio else TetherSkin.StudioDark
            TetherTheme(skin.mode) {
                LoginScreen(client = client, surface = surface, passkeys = authenticator)
            }
        }
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) = rule.waitUntil(timeout) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun field(description: String): SemanticsNodeInteraction =
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(description, substring = true)))

    private fun has(matcher: SemanticsMatcher) = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(text: String) = has(hasText(text, substring = true))
    private fun offered() = has(androidx.compose.ui.test.hasTestTag(LoginTags.Passkey))
    private fun keyEnabled() = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(LoginTags.Passkey), useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false

    /** The URL typed and the sign-in probe answered and drawn (Studio's "Connecting…", Retro's "checking…" gone). */
    private fun typeUrlAndWaitForTheProbe() {
        field("Server URL").performTextInput(base)
        waitFor { console.probes.get() > 0 && !shows("Connecting to your workspace…") && !shows("sign-in requirements · checking…") }
        rule.waitForIdle()
    }

    private fun tapPasskey() {
        waitFor { keyEnabled() }
        rule.onNodeWithTag(LoginTags.Passkey, useUnmergedTree = true).performScrollTo().performClick()
    }

    private val waitingCopy get() = if (surface == LoginSurface.Studio) "Waiting for your passkey…" else "authenticating with passkey"
    private val successCopy get() = if (surface == LoginSurface.Studio) "You’re in. Opening your workspace…" else "ACCESS GRANTED"

    @Test fun aConsoleWithNoPasskeyRegisteredOffersNone() {
        console.passkeyCount = 0
        launch()
        typeUrlAndWaitForTheProbe()
        assertFalse("none registered", offered())
    }

    @Test fun thePairingPathHasNoPasskey() {
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        rule.onNode(hasContentDescription("Pairing code", ignoreCase = true)).performClick()
        waitFor { !offered() }
        rule.onNode(hasContentDescription("Password", ignoreCase = true)).performClick()
        waitFor { offered() }
    }

    @Test fun aConsoleThatCannotUsePasskeysOffersNone() {
        console.passkeysUsable = false
        launch()
        typeUrlAndWaitForTheProbe()
        assertFalse(offered())
    }

    @Test fun aPhoneWithoutAPasskeyPromptOffersNone() {
        launch(WaitingPasskeys(available = false))
        typeUrlAndWaitForTheProbe()
        assertFalse(offered())
    }

    @Test fun signingInWithAPasskeyIsTheCeremonyThenTheSessionWithNoPassword() {
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        if (surface == LoginSurface.Studio) assertTrue(shows("or continue with your password"))
        else assertTrue(shows("⏎ on an empty line = passkey"))

        tapPasskey()
        waitFor { passkeys.pending() }
        waitFor { shows(waitingCopy) }
        assertFalse("one ceremony at a time", keyEnabled())
        assertEquals(1, passkeys.requests.size)
        assertTrue(passkeys.requests.single().contains("\"rpId\":\"${server.hostName}\""))

        passkeys.answer(PasskeyCeremony.Done(ANSWER))
        waitFor { shows(successCopy) }
        assertEquals(1, console.verifies.size)
        assertEquals(TetherJson.parseToJsonElement(ANSWER), console.verifies.single()["response"])
        assertTrue("no password went out", console.logins.isEmpty())
    }

    @Test fun aDismissedPromptIsTheWebsNoticeAndBackToReady() {
        launch()
        typeUrlAndWaitForTheProbe()
        tapPasskey()
        waitFor { passkeys.pending() }
        passkeys.answer(PasskeyCeremony.Dismissed)
        waitFor { shows(PASSKEY_DISMISSED_NOTICE) }
        waitFor { keyEnabled() }
        assertTrue("nothing more was sent", console.verifies.isEmpty())
    }

    @Test fun aRefusalIsTheServersOwnSentence() {
        console.verifyRefuses = true
        launch()
        typeUrlAndWaitForTheProbe()
        tapPasskey()
        waitFor { passkeys.pending() }
        passkeys.answer(PasskeyCeremony.Done(ANSWER))
        waitFor { shows("That passkey could not be verified.") }
        if (surface == LoginSurface.Retro) assertTrue(shows("ACCESS DENIED — That passkey could not be verified."))
        waitFor { keyEnabled() }
    }

    @Test fun optionsForAnotherRelyingPartyNeverOpenThePrompt() {
        console.rpId = "other-console.example.test"
        launch()
        typeUrlAndWaitForTheProbe()
        tapPasskey()
        waitFor { shows(PasskeyLoginCopy.WRONG_RP) }
        assertTrue("no prompt", passkeys.requests.isEmpty())
        assertTrue(console.verifies.isEmpty())
    }

    @Test fun withThePasswordOffThePasskeyIsStillOffered() {
        console.passwordLoginEnabled = false
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        assertFalse(shows("or continue with your password"))
        tapPasskey()
        waitFor { passkeys.pending() }
        passkeys.answer(PasskeyCeremony.Done(ANSWER))
        waitFor { shows(successCopy) }
    }

    /** r2 (security F1): an http address offers no passkey, says why, and Retro's Enter starts nothing. */
    @Test fun anHttpAddressOffersNoPasskeyAndSaysWhy() {
        tls = false
        launch()
        typeUrlAndWaitForTheProbe()
        assertTrue(base.startsWith("http://"))
        waitFor { has(androidx.compose.ui.test.hasTestTag(LoginTags.PasskeyNeedsHttps)) }
        assertFalse(offered())
        if (surface == LoginSurface.Retro) {
            assertFalse(shows("⏎ on an empty line = passkey"))
            field("Dashboard password").performImeAction()
            rule.waitForIdle()
        }
        assertTrue("no prompt", passkeys.requests.isEmpty())
        assertEquals("no passkey route was asked", 0, console.optionsCalls.get())
        assertTrue(console.verifies.isEmpty())
    }

    /** r2 (verifier P4): retro-login.tsx:213, the password line names the passkey shortcut for TalkBack. */
    @Test fun retrosPasswordLineNamesThePasskeyShortcut() {
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        val named = has(hasContentDescription(RETRO_PASSWORD_WITH_PASSKEY))
        assertEquals(surface == LoginSurface.Retro, named)
        assertTrue(has(hasContentDescription("Dashboard password", substring = true)))
    }

    @Test fun enterOnAnEmptyPasswordLineIsThePasskeyOnRetroOnly() {
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        field("Dashboard password").performImeAction()
        if (surface == LoginSurface.Retro) {
            waitFor { passkeys.pending() }
            passkeys.answer(PasskeyCeremony.Dismissed)
            waitFor { shows(PASSKEY_DISMISSED_NOTICE) }
        } else {
            waitFor { shows("Enter the password.") }
            assertTrue("Studio's Enter is the password's", passkeys.requests.isEmpty())
        }
        assertTrue(console.logins.isEmpty())
    }

    private companion object {
        const val ANSWER = """{"id":"dGV0aGVyLXRlc3Q","rawId":"dGV0aGVyLXRlc3Q","type":"public-key","clientExtensionResults":{},"response":{"clientDataJSON":"eyJ9","authenticatorData":"AAAA","signature":"U0lH","userHandle":"dQ"}}"""
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PasskeyLoginPhoneBehaviourTest(surface: LoginSurface) : PasskeyLoginBehaviourBase(surface) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun surfaces(): List<Array<Any>> = LoginSurface.entries.map { arrayOf(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class PasskeyLoginExpandedBehaviourTest(surface: LoginSurface) : PasskeyLoginBehaviourBase(surface) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun surfaces(): List<Array<Any>> = LoginSurface.entries.map { arrayOf(it) }
    }
}
