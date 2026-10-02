package com.tether.app.client

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// T10.5: passkeys through Android Credential Manager, the app's side of the web's two WebAuthn
// ceremonies (hooks/use-sign-in-security.ts registerPasskey, components/login/use-login-flow.ts
// runPasskeyCeremony, tether 887c222). The routes (server.mjs 90fbb9f):
//   POST /api/auth/passkeys/register/options {}                         owner-grade   { challengeId, options }
//   POST /api/auth/passkeys/register/verify  {challengeId,response,label} owner-grade   { passkey }
//   POST /api/auth/passkey/login/options     {}                         public        { challengeId, options }
//   POST /api/auth/passkey/login/verify      {challengeId,response}       public        { ok } + Set-Cookie
// An assertion made by the app carries the origin `android:apk-key-hash:<sha256 of the signing
// certificate>` (lib/assetlinks.mjs), which the server accepts beside its web origin, and a sign-in
// from it is an "app-passkey" cookie session (owner-grade since tether #236).
//
// SECURITY (the guard a browser gives the web for free): a browser binds every ceremony to the page's
// origin, so a page can only ask for its own site's passkeys. The app's origin is the same for EVERY
// Tether server, so a server could hand the app options naming ANOTHER console's rpId and relay the
// signed answer there. The app therefore asks Credential Manager only for the rpId of the server it
// is talking to ([PasskeyRules.rpIdMatches]); anything else is refused before any prompt appears.
// r2 (security F1): and only over https. Neither the rpId nor the android origin carries a scheme, so
// over http an on-path attacker posing as the server could pass the real server's options through and
// relay the answer to its https console. No ceremony starts and nothing is sent for an http server,
// loopback included. (Residual: neither carries a port either, so any service on the same hostname,
// on any port, could relay an app passkey the same way.)
// ─────────────────────────────────────────────────────────────────────────────

/** What one passkey ceremony came to. */
sealed interface PasskeyCeremony {
    /**
     * The authenticator's answer (`RegistrationResponseJSON` / `AuthenticationResponseJSON`): sent once
     * to the server that asked, then dropped. Not a data class: nothing generated prints it, and
     * [toString] prints nothing of it. Never logged, saved or persisted.
     */
    class Done(private val json: String) : PasskeyCeremony {
        fun reveal(): String = json
        override fun toString(): String = "PasskeyCeremony.Done(***)"
    }

    /** The operator closed the prompt (the web's `NotAllowedError`). */
    data object Dismissed : PasskeyCeremony

    /** Registration only: this authenticator already holds a passkey for the server (`InvalidStateError`). */
    data object Duplicate : PasskeyCeremony

    /** Sign-in only: no passkey for this server on this phone. */
    data object NoCredential : PasskeyCeremony

    /** No provider on this phone can run the ceremony. */
    data object Unsupported : PasskeyCeremony

    /** Anything else. */
    data object Failed : PasskeyCeremony
}

/**
 * The seam over Credential Manager: [CredentialManagerPasskeys] on a device, a fake in tests. Each call
 * hands the server's options JSON over as it came and returns the authenticator's answer.
 */
interface PasskeyAuthenticator {
    /** Whether this phone can run a ceremony at all (the web's `browserSupportsWebAuthn()`). */
    val available: Boolean

    suspend fun register(requestJson: String): PasskeyCeremony

    suspend fun authenticate(requestJson: String): PasskeyCeremony

    /** No authenticator (previews, fakes): nothing is ever offered. */
    object None : PasskeyAuthenticator {
        override val available: Boolean get() = false
        override suspend fun register(requestJson: String): PasskeyCeremony = PasskeyCeremony.Unsupported
        override suspend fun authenticate(requestJson: String): PasskeyCeremony = PasskeyCeremony.Unsupported
    }
}

enum class PasskeyPurpose { Register, Login }

/**
 * A server's challenge: [challengeId] names it in the verify call, [options] go to the authenticator
 * as they came, [rpId] is the relying party they ask for. Single use (the server burns it on the first
 * verify). Not a data class; prints nothing of the challenge.
 */
class PasskeyChallenge internal constructor(val challengeId: String, val options: JsonObject, val rpId: String) {
    fun optionsJson(): String = options.toString()
    override fun toString(): String = "PasskeyChallenge(rpId=$rpId)"
}

/** The pure rules, tested on their own. */
object PasskeyRules {
    /** An authenticator answer is well under 8 KiB (`attestation: "none"`); anything past this is refused, not sent. */
    const val MAX_RESPONSE_CHARS = 64 * 1024

    /** The deepest container an answer may hold (`response.transports` sits at 3). */
    const val MAX_RESPONSE_DEPTH = 8

    /** lib/passkeys.mjs mints `randomBytes(16).toString("hex")`; a later server's may differ, never other characters. */
    private val CHALLENGE_ID = Regex("^[A-Za-z0-9_-]{8,128}$")

    /** The `{ challengeId, options }` answer of either options route; null without that shape. */
    fun challenge(o: JsonObject, purpose: PasskeyPurpose): PasskeyChallenge? {
        val id = string(o["challengeId"]) ?: return null
        if (!CHALLENGE_ID.matches(id)) return null
        val options = o["options"] as? JsonObject ?: return null
        string(options["challenge"]) ?: return null
        val rpId = when (purpose) {
            PasskeyPurpose.Register -> string((options["rp"] as? JsonObject)?.get("id"))
            PasskeyPurpose.Login -> string(options["rpId"])
        } ?: return null
        return PasskeyChallenge(id, options, rpId)
    }

    /** r2 (security F1): a ceremony runs only against an https server (no loopback exception). */
    fun ceremonyAllowed(server: HttpUrl): Boolean = server.isHttps

    /** [ceremonyAllowed] for a canonical origin or a typed URL; false when it does not parse. */
    fun ceremonyAllowed(origin: String): Boolean = origin.toHttpUrlOrNull()?.let(::ceremonyAllowed) == true

    /**
     * The anti-relay guard (see the file header): an https server, and options naming exactly its host.
     * A browser also allows a parent domain; the app does not (fail closed). r2 (security F2): compared
     * as ASCII only: OkHttp's host is already lower-case ASCII (an IDN as punycode), so a non-ASCII rpId
     * is refused and the rest is compared after an ASCII-only lower-casing (`Locale.ROOT`), never by
     * Unicode case folding (which would let a dotless ı, a long ſ or the Kelvin sign stand in for i, s, k).
     */
    fun rpIdMatches(rpId: String, server: HttpUrl): Boolean =
        ceremonyAllowed(server) && rpId.isNotEmpty() && rpId.all { it.code < 0x80 } && rpId.lowercase(Locale.ROOT) == server.host

    /** [rpIdMatches] against a canonical origin (`https://host[:port]`). */
    fun rpIdMatches(rpId: String, origin: String): Boolean =
        origin.toHttpUrlOrNull()?.let { rpIdMatches(rpId, it) } == true

    /**
     * The authenticator's answer as the JSON object the verify route takes, or null when it is too
     * large, too deep, not JSON, or not a public-key credential (`{ id, type: "public-key", response: {} }`).
     */
    fun response(done: PasskeyCeremony.Done): JsonObject? {
        val text = done.reveal()
        if (text.length > MAX_RESPONSE_CHARS) return null
        if (com.tether.app.protocol.ServerMessage.nestsDeeperThan(text, MAX_RESPONSE_DEPTH)) return null
        val o = try {
            com.tether.app.protocol.TetherJson.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if (string(o["id"]) == null || string(o["type"]) != "public-key" || o["response"] !is JsonObject) return null
        return o
    }

    private fun string(e: kotlinx.serialization.json.JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
}

/**
 * The device's [PasskeyAuthenticator]: androidx Credential Manager, on the framework provider (minSdk
 * 34). Each ceremony needs the activity on screen ([activity]; none = [PasskeyCeremony.Failed]) and
 * runs on the main thread. Errors map to the web's outcomes ([CredentialManagerOutcomes]).
 */
class CredentialManagerPasskeys(private val activity: () -> Activity?) : PasskeyAuthenticator {
    override val available: Boolean get() = true

    override suspend fun register(requestJson: String): PasskeyCeremony = withContext(Dispatchers.Main) {
        val a = activity()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@withContext PasskeyCeremony.Failed
        try {
            val answer = CredentialManager.create(a).createCredential(a, CreatePublicKeyCredentialRequest(requestJson))
            (answer as? CreatePublicKeyCredentialResponse)?.let { PasskeyCeremony.Done(it.registrationResponseJson) } ?: PasskeyCeremony.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: CreateCredentialException) {
            CredentialManagerOutcomes.of(e)
        }
    }

    override suspend fun authenticate(requestJson: String): PasskeyCeremony = withContext(Dispatchers.Main) {
        val a = activity()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@withContext PasskeyCeremony.Failed
        try {
            val answer = CredentialManager.create(a).getCredential(a, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))))
            (answer.credential as? PublicKeyCredential)?.let { PasskeyCeremony.Done(it.authenticationResponseJson) } ?: PasskeyCeremony.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialException) {
            CredentialManagerOutcomes.of(e)
        }
    }
}

/** Credential Manager's errors as the web's outcomes (use-sign-in-security.ts, use-login-flow.ts). */
object CredentialManagerOutcomes {
    fun of(e: CreateCredentialException): PasskeyCeremony = when (e) {
        is CreateCredentialCancellationException -> PasskeyCeremony.Dismissed
        is CreatePublicKeyCredentialDomException -> when (e.domError) {
            is InvalidStateError -> PasskeyCeremony.Duplicate
            is NotAllowedError -> PasskeyCeremony.Dismissed
            else -> PasskeyCeremony.Failed
        }
        is CreateCredentialNoCreateOptionException,
        is CreateCredentialProviderConfigurationException,
        is CreateCredentialUnsupportedException -> PasskeyCeremony.Unsupported
        else -> PasskeyCeremony.Failed
    }

    fun of(e: GetCredentialException): PasskeyCeremony = when (e) {
        is GetCredentialCancellationException -> PasskeyCeremony.Dismissed
        is NoCredentialException -> PasskeyCeremony.NoCredential
        is GetPublicKeyCredentialDomException -> if (e.domError is NotAllowedError) PasskeyCeremony.Dismissed else PasskeyCeremony.Failed
        is GetCredentialProviderConfigurationException,
        is GetCredentialUnsupportedException -> PasskeyCeremony.Unsupported
        else -> PasskeyCeremony.Failed
    }
}
