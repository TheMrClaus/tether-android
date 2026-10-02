package com.tether.app.client

import android.os.Bundle
import com.tether.app.protocol.TetherJson
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ta-coik.1: the browser's rpId rule (WebAuthn §5.1.3/§5.1.4 step 8, HTML "is a registrable domain
 * suffix of or is equal to"): the server's host, or a parent of it that is not a public suffix, by the
 * Public Suffix List OkHttp ships, compared as canonical ASCII. Robolectric: OkHttp reads that list from
 * its Android asset through the application context (androidx-startup in the app; by hand here).
 */
@RunWith(RobolectricTestRunner::class)
class PasskeyRelyingPartyTest {
    @Before fun psl() = OkHttp.initialize(RuntimeEnvironment.getApplication())

    private fun rp(rpId: String, server: String): String? = PasskeyRules.relyingParty(rpId, server.toHttpUrl())

    /** Positive control: the list really is loaded, so each refusal below is the list's, not a failure to read it. */
    @Test fun thePublicSuffixListIsReadable() {
        assertEquals("example.co.uk", "https://console.example.co.uk".toHttpUrl().topPrivateDomain())
        assertEquals("owner.github.io", "https://a.owner.github.io".toHttpUrl().topPrivateDomain())
        assertNull("co.uk is a public suffix", "https://co.uk".toHttpUrl().topPrivateDomain())
        assertNull("github.io is one too (the list's private section)", "https://github.io".toHttpUrl().topPrivateDomain())
    }

    @Test fun theExactHostIsItsOwnRelyingParty() {
        assertEquals("console.example.test", rp("console.example.test", "https://console.example.test"))
        assertEquals("the port is not part of an rpId", "console.example.test", rp("console.example.test", "https://console.example.test:8443"))
        assertEquals("console.example.co.uk", rp("console.example.co.uk", "https://console.example.co.uk"))
        assertEquals("a single label (an intranet name) equals itself", "tether", rp("tether", "https://tether"))
        // HTML: equal is allowed before any public-suffix check (a site that is itself on the list).
        assertEquals("owner.github.io", rp("owner.github.io", "https://owner.github.io"))
        assertEquals("localhost", rp("localhost", "https://localhost:4290"))
    }

    @Test fun aRegistrableParentIsAllowedAsABrowserAllowsIt() {
        assertEquals("example.test", rp("example.test", "https://console.example.test"))
        assertEquals("example.co.uk", rp("example.co.uk", "https://console.example.co.uk"))
        assertEquals("example.co.uk", rp("example.co.uk", "https://a.b.console.example.co.uk"))
        assertEquals("b.console.example.co.uk", rp("b.console.example.co.uk", "https://a.b.console.example.co.uk"))
        assertEquals("owner.github.io", rp("owner.github.io", "https://tether.owner.github.io"))
        assertEquals("example.com", rp("example.com", "https://tether.example.com:9443"))
    }

    @Test fun aPublicSuffixIsNeverARelyingParty() {
        for ((rpId, server) in listOf(
            "co.uk" to "https://console.example.co.uk",
            "uk" to "https://console.example.co.uk",
            "github.io" to "https://owner.github.io",
            "github.io" to "https://tether.owner.github.io",
            "com" to "https://tether.example.com",
            "test" to "https://console.example.test", // an unlisted TLD: the list's default rule "*"
            "\u516C\u53F8.cn" to "https://example.\u516C\u53F8.cn", // \u516C\u53F8.cn, an IDN public suffix
            "xn--55qx5d.cn" to "https://example.xn--55qx5d.cn",
        )) {
            assertNull("refused: '$rpId' for $server", rp(rpId, server))
        }
    }

    @Test fun anythingNotTheHostOrAParentOfItIsRefused() {
        val server = "https://console.example.test"
        for (other in listOf(
            "other-console.example.test", // another Tether console vouching for the same app
            "evil.console.example.test", // a child
            "console.example.test.evil", // a suffix trick
            "xconsole.example.test",
            "ample.test", // a string suffix that is not a label suffix
            "console.example.tes",
            "example.org",
            "",
            " console.example.test",
            "console.example.test/",
            "console.example.test:443",
            "user@console.example.test",
            "console.example.test#x",
            "console.example.test?x",
            "console\\example.test",
            "console.exa^mple.test",
            "console.exa|mple.test",
            "console.exa<mple.test",
            "[console.example.test]",
        )) {
            assertNull("refused: '$other'", rp(other, server))
        }
    }

    @Test fun anIdnIsComparedAsPunycode() {
        val idn = "https://b\u00FCcher.example".toHttpUrl()
        assertEquals("xn--bcher-kva.example", idn.host)
        assertEquals("xn--bcher-kva.example", PasskeyRules.relyingParty("xn--bcher-kva.example", idn))
        assertEquals("its Unicode spelling parses to the same host", "xn--bcher-kva.example", PasskeyRules.relyingParty("b\u00FCcher.example", idn))
        assertEquals("XN-- in capitals is the same label", "xn--bcher-kva.example", PasskeyRules.relyingParty("XN--BCHER-KVA.EXAMPLE", idn))
        assertEquals("an IDN parent", "xn--bcher-kva.de", rp("b\u00FCcher.de", "https://konsole.b\u00FCcher.de"))
        assertNull("a different IDN", PasskeyRules.relyingParty("b\u00F6cher.example", idn))
    }

    /**
     * r2 (security F2), kept closed by checking and using one spelling: a lookalike either parses to the
     * very host a browser would make of it (UTS #46: the long ſ is s, the Kelvin sign is k) or to another
     * host (the dotless ı stays ı, a different punycode label); never to a match that is then signed as
     * something else. Unicode case folding would have accepted all three.
     */
    @Test fun aLookalikeIsJudgedAsTheHostItParsesTo() {
        val server = "https://kiosk.example.test".toHttpUrl()
        for (lookalike in listOf("k\u0131osk.example.test", "kio\u017Fk.example.test", "\u212Aiosk.example.test")) {
            assertTrue("control: Unicode folding would accept '$lookalike'", lookalike.equals("kiosk.example.test", ignoreCase = true))
        }
        assertNull("the dotless ı is another host", PasskeyRules.relyingParty("k\u0131osk.example.test", server))
        assertEquals("kiosk.example.test", PasskeyRules.relyingParty("kio\u017Fk.example.test", server))
        assertEquals("kiosk.example.test", PasskeyRules.relyingParty("\u212Aiosk.example.test", server))
        // And what Credential Manager is given is that ASCII host, not the lookalike.
        val challenge = PasskeyRules.challenge(TetherJson.parseToJsonElement(PasskeyFixtures.loginOptionsJson("kio\u017Fk.example.test")) as JsonObject, PasskeyPurpose.Login)!!
        val handed = TetherJson.parseToJsonElement(PasskeyRules.ceremonyOptions(challenge, server)!!) as JsonObject
        assertEquals(JsonPrimitive("kiosk.example.test"), handed["rpId"])
    }

    @Test fun caseIsFoldedAsAHostIsAndHandedOverInLowerCase() {
        assertEquals("console.example.test", rp("Console.Example.TEST", "https://console.example.test"))
        assertEquals("example.test", rp("EXAMPLE.test", "https://console.example.test"))
        assertNull(rp("CO.UK", "https://console.example.co.uk"))
    }

    /** The URL Standard keeps a trailing dot: "example.test." is another host than "example.test". */
    @Test fun aTrailingDotIsPartOfTheHost() {
        assertNull(rp("console.example.test.", "https://console.example.test"))
        assertNull(rp("example.test.", "https://console.example.test"))
        assertNull(rp("console.example.test..", "https://console.example.test"))
        val dotted = "https://console.example.co.uk.".toHttpUrl()
        assertEquals("control: OkHttp keeps the dot", "console.example.co.uk.", dotted.host)
        assertEquals("console.example.co.uk.", PasskeyRules.relyingParty("console.example.co.uk.", dotted))
        assertEquals("example.co.uk.", PasskeyRules.relyingParty("example.co.uk.", dotted))
        assertNull("its public suffix, with the dot", PasskeyRules.relyingParty("co.uk.", dotted))
        assertNull("without the dot it is not a suffix of that host", PasskeyRules.relyingParty("example.co.uk", dotted))
    }

    /** WebAuthn: an IP address is not a valid domain, as an origin or as an rpId (nor could assetlinks be checked). */
    @Test fun anIpAddressIsNeverARelyingParty() {
        assertNull(rp("192.168.1.5", "https://192.168.1.5"))
        assertNull(rp("::1", "https://[::1]"))
        assertNull(rp("[::1]", "https://[::1]"))
        assertNull(rp("1.5", "https://192.168.1.5"))
        assertNull("ends in a number", rp("example.0x1f", "https://console.example.0x1f"))
        assertNull("ends in a number", rp("example.123", "https://console.example.123"))
        assertEquals("control: a digit-led label that is not last is fine", "1password.example.test", rp("1password.example.test", "https://1password.example.test"))
    }

    /** A browser requires a secure context; so does the app, for the exact host and a parent alike. */
    @Test fun onlyAnHttpsServerHasARelyingParty() {
        assertNull(rp("console.example.test", "http://console.example.test"))
        assertNull(rp("example.test", "http://console.example.test"))
        assertNull(rp("localhost", "http://localhost:4290"))
    }

    /** Fail closed: with the list unreadable a parent is refused; the exact host needs no list and still works. */
    @Test fun anUnreadableListRefusesEveryParent() {
        val server = "https://console.example.co.uk".toHttpUrl()
        assertEquals("console.example.co.uk", PasskeyRules.relyingParty("console.example.co.uk", server) { null })
        assertNull(PasskeyRules.relyingParty("example.co.uk", server) { null })
        assertEquals("control: the same with the list", "example.co.uk", PasskeyRules.relyingParty("example.co.uk", server))
    }

    @Test fun credentialManagerIsGivenTheCanonicalRpIdInTheFieldEachPurposeReads() {
        val server = "https://console.example.test".toHttpUrl()
        val login = PasskeyRules.challenge(obj(PasskeyFixtures.loginOptionsJson("Example.TEST")), PasskeyPurpose.Login)!!
        val handedLogin = obj(PasskeyRules.ceremonyOptions(login, server)!!)
        assertEquals(JsonPrimitive("example.test"), handedLogin["rpId"])
        assertEquals("nothing else changes", JsonObject(login.options - "rpId"), JsonObject(handedLogin - "rpId"))

        val reg = PasskeyRules.challenge(obj(PasskeyFixtures.registerOptionsJson("EXAMPLE.test")), PasskeyPurpose.Register)!!
        val handedReg = obj(PasskeyRules.ceremonyOptions(reg, server)!!)
        assertEquals(JsonPrimitive("example.test"), (handedReg["rp"] as JsonObject)["id"])
        assertEquals(JsonPrimitive("Tether"), (handedReg["rp"] as JsonObject)["name"])
        assertEquals(JsonObject(reg.options - "rp"), JsonObject(handedReg - "rp"))

        // Already canonical (what a Tether server sends): handed over exactly as it came.
        val exact = PasskeyRules.challenge(obj(PasskeyFixtures.loginOptionsJson("console.example.test")), PasskeyPurpose.Login)!!
        assertEquals(exact.optionsJson(), PasskeyRules.ceremonyOptions(exact, server))
        // Refused: nothing to hand over.
        assertNull(PasskeyRules.ceremonyOptions(PasskeyRules.challenge(obj(PasskeyFixtures.loginOptionsJson("test")), PasskeyPurpose.Login)!!, server))
        assertNull(PasskeyRules.ceremonyOptions(exact, "http://console.example.test"))
    }

    private fun obj(text: String) = TetherJson.parseToJsonElement(text) as JsonObject
}

/** androidx's bundle keys (internal there): the request and answer JSON as Credential Manager carries them. */
private const val REQUEST_JSON_KEY = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
private const val RESPONSE_JSON_KEY = "androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON"

/** ta-coik.1: the autofill offer is the prompt's own request, as the framework class, answered once. */
@RunWith(RobolectricTestRunner::class)
class PasskeyAutofillOfferTest {
    private val request = PasskeyFixtures.loginOptionsJson("console.example.test").let {
        (TetherJson.parseToJsonElement(it) as JsonObject)["options"].toString()
    }

    @Test fun theOfferCarriesThePromptsOwnPublicKeyRequest() {
        val offer = PasskeyAutofillOffer.of(request) {}
        val option = offer.request.credentialOptions.single()
        assertEquals(androidx.credentials.PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, option.type)
        assertEquals(request, option.credentialRetrievalData.getString(REQUEST_JSON_KEY))
        assertEquals(request, option.candidateQueryData.getString(REQUEST_JSON_KEY))
        assertTrue("prints nothing of the request", !offer.toString().contains("Y2hhbGxlbmdl"))
    }

    @Test fun aPickIsTheAnswerAndOnlyTheFirstOutcomeCounts() {
        val answers = mutableListOf<PasskeyCeremony>()
        val offer = PasskeyAutofillOffer.of(request) { answers += it }
        offer.receiver.onResult(response(PasskeyFixtures.AUTHENTICATION_RESPONSE))
        offer.receiver.onResult(response("""{"id":"second"}"""))
        offer.receiver.onError(android.credentials.GetCredentialException(android.credentials.GetCredentialException.TYPE_USER_CANCELED))
        assertEquals(1, answers.size)
        assertEquals(PasskeyFixtures.AUTHENTICATION_RESPONSE, (answers.single() as PasskeyCeremony.Done).reveal())
    }

    @Test fun anErrorIsAQuietOutcome() {
        for ((type, expected) in listOf(
            android.credentials.GetCredentialException.TYPE_USER_CANCELED to PasskeyCeremony.Dismissed,
            android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL to PasskeyCeremony.NoCredential,
            android.credentials.GetCredentialException.TYPE_UNKNOWN to PasskeyCeremony.Failed,
        )) {
            var got: PasskeyCeremony? = null
            PasskeyAutofillOffer.of(request) { got = it }.receiver.onError(android.credentials.GetCredentialException(type))
            assertEquals(type, expected, got)
        }
        var other: PasskeyCeremony? = null
        PasskeyAutofillOffer.of(request) { other = it }.receiver.onResult(
            android.credentials.GetCredentialResponse(android.credentials.Credential("android.credentials.TYPE_PASSWORD_CREDENTIAL", Bundle())),
        )
        assertEquals("not a passkey", PasskeyCeremony.Failed, other)
    }

    private fun response(json: String) = android.credentials.GetCredentialResponse(
        android.credentials.Credential(
            androidx.credentials.PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL,
            Bundle().apply { putString(RESPONSE_JSON_KEY, json) },
        ),
    )
}

/**
 * ta-coik.1 end to end: a console on a named host (DNS pinned to loopback, a certificate for that name)
 * whose options name its registrable parent: the prompt is asked for the parent and the answer goes
 * back; options naming the public suffix above it open no prompt and send nothing more. The two halves
 * the autofill offer uses run the same rule.
 */
@RunWith(RobolectricTestRunner::class)
class PasskeyParentRelyingPartyWireTest {
    private val name = "console.example.co.uk"
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var rpId = "example.co.uk"

    @Before fun setUp() {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/healthz" -> MockResponse().setBody("""{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129}""")
                "/api/auth/passkey/login/options" -> MockResponse().setHeader("Content-Type", "application/json").setBody(PasskeyFixtures.loginOptionsJson(rpId))
                "/api/auth/passkey/login/verify" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}""")
                    .addHeader("Set-Cookie", "tether_session=0123456789abcdef0123456789abcdef.YXBwLXBhc3NrZXk; Path=/; HttpOnly; SameSite=Strict")
                else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
            }
        }
        val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName(name).build()
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.start(InetAddress.getLoopbackAddress(), 0)
        clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    }

    private lateinit var clientTls: okhttp3.tls.HandshakeCertificates

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun client() = RealTetherClient(
        settings = InMemorySettings(),
        httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .dns { host -> if (host == name) listOf(InetAddress.getLoopbackAddress()) else throw java.net.UnknownHostException(host) }
            .build(),
        scope = scope,
    )

    private val base get() = "https://$name:${server.port}"

    private fun paths(): List<String> = buildList {
        while (true) add(server.takeRequest(200, TimeUnit.MILLISECONDS)?.path ?: break)
    }

    @Test fun aParentRpIdIsAskedForAndTheAnswerGoesBack() {
        val client = client()
        val passkeys = RecordingPasskeys()
        assertEquals(LoginResult.Success, runBlocking { client.passkeyLogin(base, passkeys) })
        val asked = TetherJson.parseToJsonElement(passkeys.authenticated.single()) as JsonObject
        assertEquals(JsonPrimitive("example.co.uk"), asked["rpId"])
        client.stop()
        val seen = paths()
        assertEquals(listOf("/healthz", "/api/auth/passkey/login/options", "/api/auth/passkey/login/verify"), seen.take(3))
    }

    @Test fun aPublicSuffixRpIdOpensNoPromptAndSendsNothingMore() {
        rpId = "co.uk"
        val client = client()
        val passkeys = RecordingPasskeys()
        assertEquals(LoginResult.PasskeyFailed(PasskeyLoginCopy.WRONG_RP), runBlocking { client.passkeyLogin(base, passkeys) })
        assertTrue("no prompt", passkeys.authenticated.isEmpty())
        assertEquals(listOf("/healthz", "/api/auth/passkey/login/options"), paths())
        client.stop()
    }

    @Test fun theTwoHalvesRunTheSameRuleAndTheAnswerGoesToTheServerThatAsked() {
        val client = client()
        rpId = "co.uk"
        assertEquals(PasskeyLoginStart.Refused(LoginResult.PasskeyFailed(PasskeyLoginCopy.WRONG_RP)), runBlocking { client.passkeyLoginStart(base) })
        rpId = "EXAMPLE.co.uk"
        val start = runBlocking { client.passkeyLoginStart(base) }
        val request = (start as PasskeyLoginStart.Ready).request
        assertEquals(JsonPrimitive("example.co.uk"), (TetherJson.parseToJsonElement(request.requestJson()) as JsonObject)["rpId"])
        assertEquals(name, request.server.host)
        assertTrue("prints nothing of the challenge", !request.toString().contains(PasskeyFixtures.CHALLENGE_ID))
        paths()
        // An unused offer sends nothing; a picked one is the verify call.
        assertEquals(LoginResult.PasskeyDismissed, runBlocking { client.passkeyLoginFinish(request, PasskeyCeremony.Dismissed) })
        assertEquals(emptyList<String>(), paths())
        assertEquals(LoginResult.Success, runBlocking { client.passkeyLoginFinish(request, PasskeyCeremony.Done(PasskeyFixtures.AUTHENTICATION_RESPONSE)) })
        val verify = generateSequence { server.takeRequest(200, TimeUnit.MILLISECONDS) }.first { it.path == "/api/auth/passkey/login/verify" }
        val body = TetherJson.parseToJsonElement(verify.body.readUtf8()) as JsonObject
        assertEquals(JsonPrimitive(PasskeyFixtures.CHALLENGE_ID), body["challengeId"])
        assertNotNull(body["response"])
        client.stop()
    }
}
