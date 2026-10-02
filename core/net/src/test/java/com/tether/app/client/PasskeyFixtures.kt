package com.tether.app.client

import java.util.concurrent.CopyOnWriteArrayList

/**
 * T10.5: bodies shaped as tether 90fbb9f answers the passkey routes (lib/passkeys.mjs over
 * @simplewebauthn/server 14: generateRegistrationOptions / generateAuthenticationOptions) and as an
 * authenticator answers (tests/helpers/fake-authenticator.mjs register/authenticate). Nothing here is
 * a live credential: the challenge is the word "challenge", the signature carries [SENTINEL].
 */
object PasskeyFixtures {
    /** Looked for in every printed form and log line it must never reach. */
    const val SENTINEL = "U0lHTkFUVVJFLVNFTlRJTkVM"

    const val CHALLENGE_ID = "0123456789abcdef0123456789abcdef"

    fun registerOptionsJson(rpId: String, challengeId: String = CHALLENGE_ID) = """{"challengeId":"$challengeId","options":{
        "challenge":"Y2hhbGxlbmdlLXJlZ2lzdGVy","rp":{"name":"Tether","id":"$rpId"},
        "user":{"id":"b3BlcmF0b3ItaGFuZGxlLTAwMDAwMDAwMDAwMDAwMDA","name":"operator","displayName":"operator"},
        "pubKeyCredParams":[{"alg":-8,"type":"public-key"},{"alg":-7,"type":"public-key"},{"alg":-257,"type":"public-key"}],
        "timeout":60000,"attestation":"none",
        "excludeCredentials":[{"id":"cred-AbC_123","type":"public-key","transports":["internal"]}],
        "authenticatorSelection":{"residentKey":"required","userVerification":"required","requireResidentKey":true},
        "extensions":{"credProps":true},"hints":[]}}"""

    fun loginOptionsJson(rpId: String, challengeId: String = CHALLENGE_ID) = """{"challengeId":"$challengeId","options":{
        "rpId":"$rpId","challenge":"Y2hhbGxlbmdlLWxvZ2lu","timeout":60000,"userVerification":"required"}}"""

    /** `CreatePublicKeyCredentialResponse.registrationResponseJson` (RegistrationResponseJSON). */
    const val REGISTRATION_RESPONSE = """{"id":"dGV0aGVyLXRlc3QtY3JlZGVudGlhbC0wMDAx","rawId":"dGV0aGVyLXRlc3QtY3JlZGVudGlhbC0wMDAx","type":"public-key","clientExtensionResults":{"credProps":{"rk":true}},"authenticatorAttachment":"platform","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIn0","attestationObject":"o2NmbXRkbm9uZWdhdHRTdG10oA","transports":["internal","hybrid"],"publicKeyAlgorithm":-7}}"""

    /** `PublicKeyCredential.authenticationResponseJson` (AuthenticationResponseJSON); [SENTINEL] is the signature. */
    const val AUTHENTICATION_RESPONSE = """{"id":"dGV0aGVyLXRlc3QtY3JlZGVudGlhbC0wMDAx","rawId":"dGV0aGVyLXRlc3QtY3JlZGVudGlhbC0wMDAx","type":"public-key","clientExtensionResults":{},"authenticatorAttachment":"platform","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0","authenticatorData":"SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MdAAAAAQ","signature":"$SENTINEL","userHandle":"b3BlcmF0b3ItaGFuZGxlLTAwMDAwMDAwMDAwMDAwMDA"}}"""

    const val PASSKEY_JSON = """{"passkey":{"id":"dGV0aGVyLXRlc3QtY3JlZGVudGlhbC0wMDAx","label":"Pixel","createdAt":1759400000000,"lastUsedAt":0,"deviceType":"multiDevice","backedUp":true,"transports":["internal","hybrid"]}}"""
}

/** A fake Credential Manager: records each request JSON and answers with [answer] (a seam for every test). */
class RecordingPasskeys(
    @Volatile var answer: PasskeyCeremony = PasskeyCeremony.Done(PasskeyFixtures.AUTHENTICATION_RESPONSE),
    override val available: Boolean = true,
) : PasskeyAuthenticator {
    val registered = CopyOnWriteArrayList<String>()
    val authenticated = CopyOnWriteArrayList<String>()

    override suspend fun register(requestJson: String): PasskeyCeremony {
        registered += requestJson
        return answer
    }

    override suspend fun authenticate(requestJson: String): PasskeyCeremony {
        authenticated += requestJson
        return answer
    }
}
