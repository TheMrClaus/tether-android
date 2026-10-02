package com.tether.app.client

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T10.5: the pure passkey rules. The anti-relay guard (the options must name exactly the host the app
 * talks to: a browser's origin binding, which the app's one `android:apk-key-hash` origin cannot give),
 * the tolerant reading of the two options answers, the bounded reading of an authenticator's answer,
 * and that no printed form carries a challenge or an answer.
 */
class PasskeyRulesTest {
    private fun obj(text: String) = TetherJson.parseToJsonElement(text) as JsonObject

    // ---- the anti-relay guard -----------------------------------------------------------------

    @Test fun onlyTheServersOwnHostIsARelyingPartyTheAppWillAskFor() {
        val server = "https://console.example.test".toHttpUrl()
        assertTrue(PasskeyRules.rpIdMatches("console.example.test", server))
        assertTrue("case does not matter (a host is case-insensitive)", PasskeyRules.rpIdMatches("Console.Example.TEST", server))
        assertTrue("the port is not part of an rpId", PasskeyRules.rpIdMatches("console.example.test", "https://console.example.test:8443".toHttpUrl()))
        for (other in listOf(
            "other-console.example.test", // another Tether console vouching for the same app
            "example.test", // a parent domain: a browser allows it, the app does not (fail closed)
            "evil.console.example.test", // a child
            "console.example.test.evil", // a suffix trick
            "xconsole.example.test",
            "console.example.tes",
            "",
            " console.example.test",
        )) {
            assertFalse("refused: '$other'", PasskeyRules.rpIdMatches(other, server))
        }
        assertTrue(PasskeyRules.rpIdMatches("console.example.test", "https://console.example.test"))
        assertFalse(PasskeyRules.rpIdMatches("console.example.test", "not a url"))
    }

    /** r2 (security F1): https only, both overloads, no loopback exception; https is the control. */
    @Test fun aCeremonyRunsOnlyAgainstAnHttpsServer() {
        assertTrue("control", PasskeyRules.rpIdMatches("console.example.test", "https://console.example.test".toHttpUrl()))
        assertTrue("control", PasskeyRules.rpIdMatches("console.example.test", "https://console.example.test"))
        for (server in listOf("http://console.example.test", "http://console.example.test:443", "http://localhost:4290", "http://127.0.0.1:4290")) {
            val host = server.toHttpUrl().host
            assertFalse(server, PasskeyRules.rpIdMatches(host, server.toHttpUrl()))
            assertFalse(server, PasskeyRules.rpIdMatches(host, server))
            assertFalse(server, PasskeyRules.ceremonyAllowed(server))
        }
        assertTrue(PasskeyRules.ceremonyAllowed("https://localhost:4290"))
        assertFalse(PasskeyRules.ceremonyAllowed("not a url"))
    }

    /**
     * r2 (security F2): ASCII only. Unicode case folding maps a dotless ı to I, a long ſ to S and the
     * Kelvin sign to k, so each would have matched a host with i, s or k; each is refused. An IDN host
     * arrives from OkHttp as punycode, and the same punycode rpId matches (the positive control).
     */
    @Test fun theRpIdIsComparedAsAsciiOnly() {
        val server = "https://kiosk.example.test".toHttpUrl()
        assertTrue("control", PasskeyRules.rpIdMatches("kiosk.example.test", server))
        assertTrue("ASCII case is still ignored", PasskeyRules.rpIdMatches("KIOSK.Example.TEST", server))
        for (lookalike in listOf("k\u0131osk.example.test", "kio\u017Fk.example.test", "\u212Aiosk.example.test")) {
            assertTrue("control: Unicode folding would accept '$lookalike'", lookalike.equals("kiosk.example.test", ignoreCase = true))
            assertFalse("refused: '$lookalike'", PasskeyRules.rpIdMatches(lookalike, server))
        }
        val idn = "https://b\u00FCcher.example".toHttpUrl()
        assertEquals("xn--bcher-kva.example", idn.host)
        assertTrue("the punycode rpId of an IDN host", PasskeyRules.rpIdMatches("xn--bcher-kva.example", idn))
        assertFalse("its Unicode spelling is not ASCII", PasskeyRules.rpIdMatches("b\u00FCcher.example", idn))
    }

    // ---- the options answers ------------------------------------------------------------------

    @Test fun theOptionsAnswersAreReadAsTheServerSendsThem() {
        val reg = PasskeyRules.challenge(obj(PasskeyFixtures.registerOptionsJson("console.example.test")), PasskeyPurpose.Register)!!
        assertEquals(PasskeyFixtures.CHALLENGE_ID, reg.challengeId)
        assertEquals("console.example.test", reg.rpId)
        // The options go to Credential Manager exactly as they came.
        assertEquals(obj(PasskeyFixtures.registerOptionsJson("console.example.test"))["options"], obj(reg.optionsJson()))
        val login = PasskeyRules.challenge(obj(PasskeyFixtures.loginOptionsJson("console.example.test")), PasskeyPurpose.Login)!!
        assertEquals("console.example.test", login.rpId)
        assertEquals(obj(PasskeyFixtures.loginOptionsJson("console.example.test"))["options"], obj(login.optionsJson()))
        // Each purpose reads its own field: a login answer is not a registration one and back.
        assertNull(PasskeyRules.challenge(obj(PasskeyFixtures.loginOptionsJson("console.example.test")), PasskeyPurpose.Register))
        assertNull(PasskeyRules.challenge(obj(PasskeyFixtures.registerOptionsJson("console.example.test")), PasskeyPurpose.Login))
    }

    @Test fun anOptionsAnswerWithoutItsShapeIsRefused() {
        val bad = listOf(
            """{"options":{"rpId":"h","challenge":"c"}}""",
            """{"challengeId":"","options":{"rpId":"h","challenge":"c"}}""",
            """{"challengeId":"short","options":{"rpId":"h","challenge":"c"}}""",
            """{"challengeId":"0123456789abcdef/../x","options":{"rpId":"h","challenge":"c"}}""",
            """{"challengeId":12345678901234567,"options":{"rpId":"h","challenge":"c"}}""",
            """{"challengeId":"0123456789abcdef","options":"{}"}""",
            """{"challengeId":"0123456789abcdef","options":{"rpId":"h"}}""",
            """{"challengeId":"0123456789abcdef","options":{"rpId":"h","challenge":""}}""",
            """{"challengeId":"0123456789abcdef","options":{"challenge":"c"}}""",
            """{"challengeId":"0123456789abcdef","options":{"rpId":7,"challenge":"c"}}""",
        )
        for (body in bad) assertNull(body, PasskeyRules.challenge(obj(body), PasskeyPurpose.Login))
    }

    // ---- the authenticator's answer -----------------------------------------------------------

    @Test fun anAuthenticatorAnswerIsSentOnlyWhenItIsABoundedPublicKeyCredential() {
        assertNotNull(PasskeyRules.response(PasskeyCeremony.Done(PasskeyFixtures.REGISTRATION_RESPONSE)))
        val read = PasskeyRules.response(PasskeyCeremony.Done(PasskeyFixtures.AUTHENTICATION_RESPONSE))!!
        assertEquals(obj(PasskeyFixtures.AUTHENTICATION_RESPONSE), read)
        val deep = (1..12).fold("{}") { acc, _ -> """{"a":$acc}""" }
        for (answer in listOf(
            "",
            "not json",
            "[]",
            """{"id":"x","type":"public-key"}""",
            """{"id":"x","type":"password","response":{}}""",
            """{"type":"public-key","response":{}}""",
            """{"id":"x","type":"public-key","response":"{}"}""",
            """{"id":"x","type":"public-key","response":$deep}""",
            """{"id":"x","type":"public-key","response":{"p":"${"A".repeat(PasskeyRules.MAX_RESPONSE_CHARS)}"}}""",
        )) {
            assertNull(answer.take(80), PasskeyRules.response(PasskeyCeremony.Done(answer)))
        }
    }

    // ---- nothing printed ----------------------------------------------------------------------

    @Test fun noPrintedFormCarriesAChallengeOrAnAnswer() {
        val done = PasskeyCeremony.Done(PasskeyFixtures.AUTHENTICATION_RESPONSE)
        assertTrue("control: the answer is in there", done.reveal().contains(PasskeyFixtures.SENTINEL))
        assertFalse(done.toString().contains(PasskeyFixtures.SENTINEL))
        val challenge = PasskeyRules.challenge(obj(PasskeyFixtures.loginOptionsJson("console.example.test")), PasskeyPurpose.Login)!!
        assertFalse(challenge.toString().contains("Y2hhbGxlbmdl"))
        assertFalse(challenge.toString().contains(PasskeyFixtures.CHALLENGE_ID))
        for (type in listOf(PasskeyCeremony.Done::class.java, PasskeyChallenge::class.java)) {
            assertFalse("${type.simpleName} has a generated accessor", type.declaredMethods.any { it.name == "copy" || it.name.startsWith("component") })
        }
    }
}

/** T10.5: Credential Manager's errors as the web's outcomes (NotAllowedError = dismissed, InvalidStateError = a duplicate). */
@RunWith(RobolectricTestRunner::class)
class CredentialManagerOutcomesTest {
    @Test fun registrationErrorsMapToTheWebsOutcomes() {
        assertEquals(PasskeyCeremony.Dismissed, CredentialManagerOutcomes.of(CreateCredentialCancellationException()))
        assertEquals(PasskeyCeremony.Dismissed, CredentialManagerOutcomes.of(CreatePublicKeyCredentialDomException(NotAllowedError())))
        assertEquals(PasskeyCeremony.Duplicate, CredentialManagerOutcomes.of(CreatePublicKeyCredentialDomException(InvalidStateError())))
        assertEquals(PasskeyCeremony.Failed, CredentialManagerOutcomes.of(CreatePublicKeyCredentialDomException(SecurityError())))
        assertEquals(PasskeyCeremony.Unsupported, CredentialManagerOutcomes.of(CreateCredentialNoCreateOptionException()))
        assertEquals(PasskeyCeremony.Unsupported, CredentialManagerOutcomes.of(CreateCredentialProviderConfigurationException()))
        assertEquals(PasskeyCeremony.Unsupported, CredentialManagerOutcomes.of(CreateCredentialUnsupportedException()))
        assertEquals(PasskeyCeremony.Failed, CredentialManagerOutcomes.of(CreateCredentialInterruptedException()))
        assertEquals(PasskeyCeremony.Failed, CredentialManagerOutcomes.of(CreateCredentialUnknownException()))
    }

    @Test fun signInErrorsMapToTheWebsOutcomes() {
        assertEquals(PasskeyCeremony.Dismissed, CredentialManagerOutcomes.of(GetCredentialCancellationException()))
        assertEquals(PasskeyCeremony.Dismissed, CredentialManagerOutcomes.of(GetPublicKeyCredentialDomException(NotAllowedError())))
        assertEquals(PasskeyCeremony.Failed, CredentialManagerOutcomes.of(GetPublicKeyCredentialDomException(SecurityError())))
        assertEquals(PasskeyCeremony.NoCredential, CredentialManagerOutcomes.of(NoCredentialException()))
        assertEquals(PasskeyCeremony.Unsupported, CredentialManagerOutcomes.of(GetCredentialProviderConfigurationException()))
        assertEquals(PasskeyCeremony.Unsupported, CredentialManagerOutcomes.of(GetCredentialUnsupportedException()))
        assertEquals(PasskeyCeremony.Failed, CredentialManagerOutcomes.of(GetCredentialInterruptedException()))
        assertEquals(PasskeyCeremony.Failed, CredentialManagerOutcomes.of(GetCredentialUnknownException()))
    }
}
