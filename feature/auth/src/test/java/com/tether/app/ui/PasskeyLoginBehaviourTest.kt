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
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.InMemorySettings
import com.tether.app.client.PasskeyAuthenticator
import com.tether.app.client.PasskeyAutofillOffer
import com.tether.app.client.PasskeyCeremony
import com.tether.app.client.PasskeyLoginCopy
import com.tether.app.client.RealTetherClient
import com.tether.app.protocol.TetherJson
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /**
     * A fake Credential Manager whose ceremony waits until [answer]. ta-coik.1: with [autofill], it can
     * also make the real autofill offer (the framework request and receiver), recorded in [offers].
     */
    private class WaitingPasskeys(override val available: Boolean = true, private val autofill: Boolean = false) : PasskeyAuthenticator {
        val requests = CopyOnWriteArrayList<String>()
        val offers = CopyOnWriteArrayList<PasskeyAutofillOffer>()
        override val autofillAvailable: Boolean get() = autofill
        override fun autofillOffer(requestJson: String, onAnswer: (PasskeyCeremony) -> Unit): PasskeyAutofillOffer? =
            if (autofill) PasskeyAutofillOffer.of(requestJson, onAnswer).also { offers += it } else null
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
        /** ta-coik.1 r2: [optionsCalls] as each sign-in probe arrived (what had been asked before it). */
        val optionsAtProbe = ConcurrentLinkedQueue<Int>()
        /** ta-coik.1 r2: when set, a sign-in probe is held here until the test releases it. */
        @Volatile var holdProbe: CountDownLatch? = null
        /** ta-coik.1 r2: the Nth options call (1-based) is held at gate N-1, when there is one. */
        @Volatile var optionsGates: List<CountDownLatch> = emptyList()
        /** ta-coik.1 r2: when set, a password sign-in is held here (after it is recorded) until released. */
        @Volatile var holdLogin: CountDownLatch? = null

        fun releaseAll() {
            holdProbe?.countDown()
            optionsGates.forEach { it.countDown() }
            holdLogin?.countDown()
        }

        private fun hold(gate: CountDownLatch?) {
            gate?.await(15, TimeUnit.SECONDS)
        }

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/healthz" -> MockResponse().setBody("""{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129}""")
            "/api/auth/session" -> if (request.getHeader("Cookie") != null) {
                MockResponse().setBody("""{"authenticated":true}""")
            } else {
                optionsAtProbe += optionsCalls.get()
                probes.incrementAndGet()
                hold(holdProbe)
                MockResponse().setBody(
                    """{"authenticated":false,"usernameRequired":false,"passkeyCount":$passkeyCount,"passwordLoginEnabled":$passwordLoginEnabled,""" +
                        """"passkeysUsable":$passkeysUsable,"rpId":"${request.requestUrl!!.host}","sessionMethod":null}""",
                )
            }
            "/api/auth/passkey/login/options" -> MockResponse().also { hold(optionsGates.getOrNull(optionsCalls.incrementAndGet() - 1)) }.setHeader("Content-Type", "application/json").setBody(
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
                hold(holdLogin)
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
        console.releaseAll()
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

    private fun choosePairing() {
        rule.onNode(hasContentDescription("Pairing code", ignoreCase = true) and androidx.compose.ui.test.hasClickAction()).performScrollTo().performClick()
        waitFor { has(hasSetTextAction() and hasAnyAncestor(hasContentDescription("Pairing code", substring = true))) }
    }

    /**
     * ta-coik.1: the web offers the passkey wherever you sign in (one screen: studio-login.tsx:41/99,
     * retro-login.tsx:164), so the app offers it on its Pairing path too, not only beside the password.
     */
    @Test fun thePasskeyIsOfferedOnThePairingPathToo() {
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        choosePairing()
        waitFor { offered() }
        if (surface == LoginSurface.Studio) assertTrue(shows(PASSKEY_OR_PAIRING))
        else assertTrue(shows("⏎ on an empty line = passkey"))
        tapPasskey()
        waitFor { passkeys.pending() }
        waitFor { shows(waitingCopy) }
        passkeys.answer(PasskeyCeremony.Done(ANSWER))
        waitFor { shows(successCopy) }
        assertEquals(1, console.verifies.size)
        assertTrue("no password went out", console.logins.isEmpty())
    }

    @Test fun switchingPathsKeepsThePasskeyOffered() {
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        choosePairing()
        waitFor { offered() }
        rule.onNode(hasContentDescription("Password", ignoreCase = true) and androidx.compose.ui.test.hasClickAction()).performScrollTo().performClick()
        waitFor { has(hasContentDescription("Dashboard password", substring = true)) }
        assertTrue(offered())
    }

    /** ta-coik.1: Retro's "⏎ on an empty line = passkey" holds on the pairing-code line too; Studio's Enter there is the pairing. */
    @Test fun enterOnAnEmptyCodeLineIsThePasskeyOnRetroOnly() {
        launch()
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        choosePairing()
        val line = if (surface == LoginSurface.Retro) RETRO_CODE_WITH_PASSKEY else "Pairing code"
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(line))).performImeAction()
        if (surface == LoginSurface.Retro) {
            waitFor { passkeys.pending() }
            passkeys.answer(PasskeyCeremony.Dismissed)
            waitFor { shows(PASSKEY_DISMISSED_NOTICE) }
        } else {
            waitFor { shows("Enter the pairing code from your browser.") }
            assertTrue("Studio's Enter is the pairing's", passkeys.requests.isEmpty())
        }
    }

    // ---- ta-coik.1: the conditional (autofill) offer, use-login-flow.ts:224-246 --------------------

    private val credentialRequest = androidx.compose.ui.semantics.SemanticsPropertiesAndroid.CredentialRequest

    /** The nodes carrying a pending credential request (useUnmergedTree: the editable node itself). */
    private fun armedNodes() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(credentialRequest), useUnmergedTree = true).fetchSemanticsNodes()

    private fun armedOffer(): androidx.compose.ui.semantics.CredentialRequestData = armedNodes().single().config[credentialRequest]

    private fun pick(offer: androidx.compose.ui.semantics.CredentialRequestData, answer: String = ANSWER) =
        offer.callback.onResult(
            android.credentials.GetCredentialResponse(
                android.credentials.Credential(
                    "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL",
                    android.os.Bundle().apply { putString("androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON", answer) },
                ),
            ),
        )

    @Test fun onceAPasskeyIsReadyThePasswordFieldOffersItAmongItsSuggestions() {
        val autofill = WaitingPasskeys(autofill = true)
        launch(autofill)
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        waitFor { armedNodes().isNotEmpty() }
        assertEquals("one challenge, asked as soon as a passkey is ready", 1, console.optionsCalls.get())
        // On the password field itself (the web's autocomplete="current-password webauthn"), and only there.
        val node = armedNodes().single()
        assertTrue(node.config.contains(SemanticsProperties.EditableText))
        assertTrue(has(hasSetTextAction() and hasAnyAncestor(hasContentDescription("Dashboard password", substring = true)) and SemanticsMatcher.keyIsDefined(credentialRequest)))
        // The prompt's own request, for this server's rpId, as the framework class.
        val option = armedOffer().request.credentialOptions.single()
        assertEquals("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL", option.type)
        assertTrue(option.credentialRetrievalData.getString("androidx.credentials.BUNDLE_KEY_REQUEST_JSON")!!.contains("\"rpId\":\"${server.hostName}\""))
        assertTrue("no prompt opened", autofill.requests.isEmpty())
        assertTrue("nothing verified yet", console.verifies.isEmpty())
        assertFalse("no attempt shown", shows(waitingCopy))
    }

    @Test fun pickingThePasskeyFromTheSuggestionsSignsIn() {
        val autofill = WaitingPasskeys(autofill = true)
        launch(autofill)
        typeUrlAndWaitForTheProbe()
        waitFor { armedNodes().isNotEmpty() }
        // The framework answers off the main thread; the screen's state is still only written on it.
        pick(armedOffer())
        waitFor { shows(successCopy) }
        assertEquals(1, console.verifies.size)
        assertEquals(TetherJson.parseToJsonElement(ANSWER), console.verifies.single()["response"])
        assertEquals("0123456789abcdef0123456789abcdef", (console.verifies.single()["challengeId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue("no modal prompt", autofill.requests.isEmpty())
        assertTrue("no password went out", console.logins.isEmpty())
    }

    @Test fun aPickedPasskeyTheServerRefusesIsTheServersSentence() {
        console.verifyRefuses = true
        launch(WaitingPasskeys(autofill = true))
        typeUrlAndWaitForTheProbe()
        waitFor { armedNodes().isNotEmpty() }
        pick(armedOffer())
        waitFor { shows("That passkey could not be verified.") }
        waitFor { keyEnabled() }
        assertTrue("used up, not re-armed", armedNodes().isEmpty())
        assertEquals(1, console.optionsCalls.get())
    }

    /** use-login-flow.ts:204-215: an offer that is closed or fails before it became an attempt says nothing. */
    @Test fun anUnusedOfferStaysQuietAndIsNotReArmed() {
        launch(WaitingPasskeys(autofill = true))
        typeUrlAndWaitForTheProbe()
        waitFor { armedNodes().isNotEmpty() }
        armedOffer().callback.onError(android.credentials.GetCredentialException(android.credentials.GetCredentialException.TYPE_USER_CANCELED))
        waitFor { armedNodes().isEmpty() }
        rule.waitForIdle()
        assertFalse("no notice", shows(PASSKEY_DISMISSED_NOTICE))
        assertTrue(console.verifies.isEmpty())
        assertEquals("once per page", 1, console.optionsCalls.get())
        assertTrue("the key remains", keyEnabled())
    }

    /** The web supersedes a pending conditional ceremony with the modal one; so does the app. */
    @Test fun theKeySupersedesTheOffer() {
        val autofill = WaitingPasskeys(autofill = true)
        launch(autofill)
        typeUrlAndWaitForTheProbe()
        waitFor { armedNodes().isNotEmpty() }
        val stale = armedOffer()
        tapPasskey()
        waitFor { autofill.pending() }
        assertTrue(armedNodes().isEmpty())
        pick(stale)
        rule.waitForIdle()
        assertTrue("the superseded offer sends nothing", console.verifies.isEmpty())
        autofill.answer(PasskeyCeremony.Done(ANSWER))
        waitFor { shows(successCopy) }
        assertEquals(1, console.verifies.size)
    }

    /** An offer armed for one address answers nothing once the address has changed. */
    @Test fun anOfferForAnAddressNoLongerTypedSendsNothing() {
        launch(WaitingPasskeys(autofill = true))
        typeUrlAndWaitForTheProbe()
        waitFor { armedNodes().isNotEmpty() }
        val stale = armedOffer()
        field("Server URL").performTextInput("/")
        // Cleared at once; the new address may arm its own offer later, never this one again.
        waitFor { armedNodes().isEmpty() || armedOffer() !== stale }
        pick(stale)
        rule.waitForIdle()
        assertTrue(console.verifies.isEmpty())
        assertFalse(shows(waitingCopy))
    }

    /**
     * ta-coik.1 r2 (security F2): an edited address asks for no challenge on the last address's reading.
     * Before the fix the old reading was still held in the frame after the edit, and the offer asked the
     * edited text for a challenge at once (a /healthz and an options POST per keystroke, no debounce).
     * Now nothing is asked until the edited address's own probe has answered; then the offer re-arms once
     * for it, and a pick of that offer signs in.
     */
    @Test fun anEditedAddressAsksForNoChallengeUntilItsOwnProbeHasAnswered() {
        launch(WaitingPasskeys(autofill = true))
        typeUrlAndWaitForTheProbe()
        waitFor { armedNodes().isNotEmpty() }
        val stale = armedOffer()
        assertEquals(1, console.optionsCalls.get())
        val gate = CountDownLatch(1)
        console.holdProbe = gate
        field("Server URL").performTextInput("/")
        // The edited address's own probe reaches the console (after the debounce) and is held there.
        waitFor { console.probes.get() == 2 }
        assertEquals("nothing asked between the edit and the edited address's probe", listOf(0, 1), console.optionsAtProbe.toList())
        rule.waitForIdle()
        assertEquals("still nothing while that probe is unanswered", 1, console.optionsCalls.get())
        assertTrue("the old offer is withdrawn", armedNodes().isEmpty())
        assertFalse("no key on another address's reading", offered())
        gate.countDown()
        // The reading lands: the offer re-arms, once, for the new address.
        waitFor { armedNodes().isNotEmpty() }
        assertTrue(armedOffer() !== stale)
        assertEquals(2, console.optionsCalls.get())
        pick(armedOffer())
        waitFor { shows(successCopy) }
        assertEquals(1, console.verifies.size)
    }

    /**
     * ta-coik.1 r2 (security F2): an arming run cancelled while its options call is still at the console
     * finishes only when that call returns, which can be after a newer run claimed the same address. It
     * must not release the newer run's claim, or a later re-read of the address arms it a second time.
     */
    @Test fun aCancelledArmingNeverReleasesANewerArmingsClaim() {
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        console.optionsGates = listOf(first, second)
        launch(WaitingPasskeys(autofill = true))
        typeUrlAndWaitForTheProbe()
        // Run 1 asks for its challenge and is held at the console.
        waitFor { console.optionsCalls.get() == 1 }
        // The address is cleared and typed again: run 1 is cancelled (its blocking call still pending) ...
        field("Server URL").performTextReplacement("")
        rule.waitForIdle()
        field("Server URL").performTextReplacement(base)
        // ... and once the address's reading is back, run 2 claims it and is held too.
        waitFor { console.optionsCalls.get() == 2 }
        // Run 1's call returns now, so its cancelled run finishes after run 2's claim.
        first.countDown()
        val settle = System.currentTimeMillis() + 750
        waitFor { System.currentTimeMillis() > settle }
        second.countDown()
        waitFor { armedNodes().isNotEmpty() }
        val armed = armedOffer()
        // A re-read of the same address (a trailing space: the same address once trimmed) ...
        val probesBefore = console.probes.get()
        field("Server URL").performTextInput(" ")
        waitFor { console.probes.get() == probesBefore + 1 }
        waitFor { armedNodes().isNotEmpty() }
        // A wrongful re-arm would go out on IO after this frame (a /healthz, then the options call): give
        // it time to reach the console before saying nothing was asked.
        val quiet = System.currentTimeMillis() + 1_500
        waitFor { System.currentTimeMillis() > quiet }
        rule.waitForIdle()
        // ... arms nothing again: once per address.
        assertEquals("once per address", 2, console.optionsCalls.get())
        assertTrue("the same offer", armedOffer() === armed)
    }

    /**
     * ta-coik.1 r2 (verifier finding 2): use-login-flow.ts's conditional branch checks no phase. A pick
     * that lands while a password attempt is at the console goes ahead (verifying-passkey, then verify),
     * and the password fetch is neither aborted nor awaited. Before the fix the app dropped the pick.
     */
    @Test fun aPickWhileAPasswordAttemptIsInFlightGoesAheadAsOnTheWeb() {
        launch(WaitingPasskeys(autofill = true))
        typeUrlAndWaitForTheProbe()
        waitFor { armedNodes().isNotEmpty() }
        val offer = armedOffer()
        val gate = CountDownLatch(1)
        console.holdLogin = gate
        field("Dashboard password").performTextInput("not-the-passkey")
        field("Dashboard password").performImeAction()
        // The password attempt is at the console, unanswered: the screen is busy with it.
        waitFor { console.logins.size == 1 }
        assertFalse(keyEnabled())
        pick(offer)
        waitFor { console.verifies.size == 1 }
        assertEquals(TetherJson.parseToJsonElement(ANSWER), console.verifies.single()["response"])
        waitFor { shows(successCopy) }
        assertEquals("the password attempt was left to settle on its own", 1, console.logins.size)
        gate.countDown()
    }

    @Test fun aPhoneThatCannotOfferPasskeysInAutofillAsksForNoChallenge() {
        launch() // the default fake: no autofill (Android 14, the app's minimum)
        typeUrlAndWaitForTheProbe()
        waitFor { offered() }
        rule.waitForIdle()
        assertTrue(armedNodes().isEmpty())
        assertEquals(0, console.optionsCalls.get())
    }

    @Test fun anOfferForAnotherRelyingPartyIsNeverArmedAndSaysNothing() {
        console.rpId = "other-console.example.test"
        launch(WaitingPasskeys(autofill = true))
        typeUrlAndWaitForTheProbe()
        waitFor { console.optionsCalls.get() == 1 }
        rule.waitForIdle()
        assertTrue(armedNodes().isEmpty())
        assertFalse("quiet, as the web's unused offer", shows(PasskeyLoginCopy.WRONG_RP))
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
        // ta-coik.1: with an autofill-capable phone, so nothing is armed for http either.
        val autofill = WaitingPasskeys(autofill = true)
        launch(autofill)
        typeUrlAndWaitForTheProbe()
        assertTrue(base.startsWith("http://"))
        waitFor { has(androidx.compose.ui.test.hasTestTag(LoginTags.PasskeyNeedsHttps)) }
        assertFalse(offered())
        if (surface == LoginSurface.Retro) {
            assertFalse(shows("⏎ on an empty line = passkey"))
            field("Dashboard password").performImeAction()
            rule.waitForIdle()
        }
        assertTrue("no prompt", autofill.requests.isEmpty())
        assertTrue("no offer", autofill.offers.isEmpty() && armedNodes().isEmpty())
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
