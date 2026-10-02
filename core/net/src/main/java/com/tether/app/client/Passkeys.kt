package com.tether.app.client

import android.app.Activity
import android.os.Build
import android.os.OutcomeReceiver
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.Credential
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
import java.util.concurrent.atomic.AtomicBoolean

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
// origin, so a page can only ask for passkeys of its own site: the rpId must be the page's host or a
// registrable domain suffix of it (WebAuthn §5.1.3/§5.1.4 step 8, HTML "is a registrable domain
// suffix of or is equal to"). The app's origin is the same for EVERY Tether server, so the app applies
// that same rule itself against the server it is talking to ([PasskeyRules.relyingParty]): the host,
// or a parent of it that is not a public suffix (Public Suffix List, the one OkHttp ships), compared
// as canonical ASCII. Anything else is refused before any prompt appears, and Credential Manager is
// handed the canonical rpId that was checked ([PasskeyRules.ceremonyOptions]), never another spelling.
// ta-coik.1: this replaces T10.5's exact-host-only rule (a browser allows the parent; so does the app).
// r2 (security F1): and only over https, as a browser requires a secure context. Neither the rpId nor
// the android origin carries a scheme, so over http an on-path attacker posing as the server could pass
// the real server's options through and relay the answer to its https console. No ceremony starts and
// nothing is sent for an http server, loopback included. A browser also counts http://localhost as a
// secure context; the app does not need that exception, because Credential Manager verifies the rpId
// against https://<rpId>/.well-known/assetlinks.json and cannot do so for localhost (or an IP address,
// which WebAuthn refuses as an rpId anyway), so a passkey cannot work there whatever the app allowed.
// (Residual: neither carries a port either, so any service on the same hostname, on any port, could
// relay an app passkey the same way; with a parent rpId, so could any host under that parent, exactly
// as any page under that parent can in a browser.)
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

    /**
     * ta-coik.1: whether this phone can offer a passkey among a sign-in field's autofill suggestions
     * (the web's `browserSupportsWebAuthnAutofill()`). Android 15 (API 35) and later: the framework's
     * pending credential request on an autofill node. False here unless an authenticator says so.
     */
    val autofillAvailable: Boolean get() = false

    /**
     * ta-coik.1: the web's conditional ceremony for [requestJson]: an offer a sign-in field carries
     * (Compose `semantics { credentialRequest }`), answered through [onAnswer] at most once, when the
     * operator picks the passkey from the field's suggestions (or the pick fails). Null when
     * [autofillAvailable] is false.
     */
    fun autofillOffer(requestJson: String, onAnswer: (PasskeyCeremony) -> Unit): PasskeyAutofillOffer? = null

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
class PasskeyChallenge internal constructor(
    val challengeId: String,
    val options: JsonObject,
    val rpId: String,
    private val purpose: PasskeyPurpose,
) {
    /** The options as they came. What goes to Credential Manager is [PasskeyRules.ceremonyOptions]. */
    fun optionsJson(): String = options.toString()

    /**
     * The options naming [relyingParty] (the canonical spelling [PasskeyRules.relyingParty] checked) in
     * the field this purpose reads: `rp.id` for a registration, `rpId` for a sign-in. Identical to
     * [optionsJson] when the server already sent it that way (lower-case ASCII), which a Tether server does.
     */
    internal fun optionsJsonFor(relyingParty: String): String {
        if (relyingParty == rpId) return optionsJson()
        val named = when (purpose) {
            PasskeyPurpose.Register -> {
                val rp = options["rp"] as? JsonObject ?: return optionsJson()
                JsonObject(options + ("rp" to JsonObject(rp + ("id" to JsonPrimitive(relyingParty)))))
            }
            PasskeyPurpose.Login -> JsonObject(options + ("rpId" to JsonPrimitive(relyingParty)))
        }
        return named.toString()
    }

    override fun toString(): String = "PasskeyChallenge(rpId=$rpId)"
}

/**
 * ta-coik.1: a sign-in challenge that passed [PasskeyRules.ceremonyOptions] for [server]: what the
 * authenticator is given ([requestJson], the canonical rpId in it) and, once the operator answers, which
 * server and challenge the answer goes back to ([TetherClient.passkeyLoginFinish]). Minted only by the
 * client that asked for it. Single use. Not a data class; prints nothing of the challenge.
 */
class PasskeyLoginRequest internal constructor(
    val server: HttpUrl,
    internal val challengeId: String,
    private val requestJson: String,
) {
    fun requestJson(): String = requestJson
    override fun toString(): String = "PasskeyLoginRequest(${server.host})"
}

/** ta-coik.1: the first half of a passkey sign-in ([TetherClient.passkeyLoginStart]). */
sealed interface PasskeyLoginStart {
    /** A checked challenge, ready for a prompt or an autofill offer. */
    class Ready(val request: PasskeyLoginRequest) : PasskeyLoginStart

    /** No challenge: what [TetherClient.passkeyLogin] would have answered instead. */
    data class Refused(val result: LoginResult) : PasskeyLoginStart
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
        return PasskeyChallenge(id, options, rpId, purpose)
    }

    /** r2 (security F1): a ceremony runs only against an https server (no loopback exception). */
    fun ceremonyAllowed(server: HttpUrl): Boolean = server.isHttps

    /** [ceremonyAllowed] for a canonical origin or a typed URL; false when it does not parse. */
    fun ceremonyAllowed(origin: String): Boolean = origin.toHttpUrlOrNull()?.let(::ceremonyAllowed) == true

    /**
     * The anti-relay guard (see the file header), the browser's own rule: for an https [server], the
     * relying party [rpId] names as canonical ASCII when it is the server's host or a registrable domain
     * suffix of it; null otherwise. WebAuthn §5.1.3/§5.1.4 step 8 by way of HTML's "is a registrable
     * domain suffix of or is equal to":
     *  - the rpId is parsed as a host, as a browser's URL host parser does it: percent-decoded, UTS #46
     *    mapped (so ASCII case and compatibility forms fold the way a browser folds them) and punycoded
     *    (OkHttp's own IDNA table, identical on the JVM and Android). r2 (security F2) stays closed a
     *    different way: the spelling that is checked is the spelling Credential Manager is given
     *    ([ceremonyOptions]), so no lookalike can be checked as one name and signed as another;
     *  - it must be a valid domain: no forbidden domain code point, not an IP address (WebAuthn refuses
     *    an IP origin and an IP rpId; nor could Credential Manager verify assetlinks for one);
     *  - equal to the server's host: allowed;
     *  - otherwise the host must end with "." + rpId, the rpId must not be a public suffix, and both must
     *    share one registrable domain (Public Suffix List, private section included, as browsers use it;
     *    a trailing dot handled as the URL Standard's "public suffix" does). `co.uk`, `github.io` or a
     *    bare TLD are refused; so is anything the list cannot be read for (fail closed: the exact host
     *    still works).
     */
    fun relyingParty(rpId: String, server: HttpUrl): String? = relyingParty(rpId, server, ::registrableDomainOf)

    /** [relyingParty] with the PSL lookup handed in (tests: the fail-closed path). */
    internal fun relyingParty(rpId: String, server: HttpUrl, registrable: (String) -> String?): String? {
        if (!ceremonyAllowed(server)) return null
        val host = server.host
        if (!isValidDomain(host)) return null
        val rp = canonicalHost(rpId)?.takeIf(::isValidDomain) ?: return null
        if (rp == host) return rp
        if (!host.endsWith(".$rp")) return null
        val rpRegistrable = registrable(rp) ?: return null // rp is a public suffix (or the list is unreadable)
        val hostRegistrable = registrable(host) ?: return null
        return rp.takeIf { rpRegistrable == hostRegistrable }
    }

    /** [relyingParty] against a canonical origin (`https://host[:port]`). */
    fun relyingParty(rpId: String, origin: String): String? =
        origin.toHttpUrlOrNull()?.let { relyingParty(rpId, it) }

    fun rpIdMatches(rpId: String, server: HttpUrl): Boolean = relyingParty(rpId, server) != null

    /** [rpIdMatches] against a canonical origin (`https://host[:port]`). */
    fun rpIdMatches(rpId: String, origin: String): Boolean = relyingParty(rpId, origin) != null

    /**
     * What Credential Manager is given for [challenge] from [server]: its options naming the canonical
     * relying party [relyingParty] checked, or null (no prompt) when that rule refuses it.
     */
    fun ceremonyOptions(challenge: PasskeyChallenge, server: HttpUrl): String? =
        relyingParty(challenge.rpId, server)?.let(challenge::optionsJsonFor)

    /** [ceremonyOptions] against a canonical origin (`https://host[:port]`). */
    fun ceremonyOptions(challenge: PasskeyChallenge, origin: String): String? =
        origin.toHttpUrlOrNull()?.let { ceremonyOptions(challenge, it) }

    /**
     * [raw] parsed as a host (OkHttp's canonical form: percent-decoded, IDNA-mapped, punycode, lower
     * case), or null when it is not one. Only the host component is set, so no URL syntax in [raw] can
     * move a boundary; OkHttp refuses its own invalid host characters, and the URL Standard's remaining
     * forbidden domain code points are refused here.
     */
    private fun canonicalHost(raw: String): String? {
        if (raw.isEmpty()) return null
        val host = try {
            HttpUrl.Builder().scheme("https").host(raw).build().host
        } catch (_: IllegalArgumentException) {
            return null
        }
        return host.takeIf { h -> h.isNotEmpty() && h.none { it.code <= 0x20 || it.code >= 0x7F || it in FORBIDDEN_DOMAIN_CHARS } }
    }

    /** URL Standard "forbidden domain code point" (ASCII part; controls, space and DEL are checked apart). */
    private const val FORBIDDEN_DOMAIN_CHARS = "#%/:<>?@[\\]^|"

    /**
     * A domain, not an IP address: no ':' (IPv6, as OkHttp prints it) and not "ending in a number" (the
     * URL Standard's IPv4 test: the last label, a trailing dot aside, all digits or `0x` hex).
     */
    private fun isValidDomain(host: String): Boolean {
        if (host.isEmpty() || ':' in host) return false
        val last = host.removeSuffix(".").substringAfterLast('.')
        if (last.isEmpty()) return false
        if (last.all { it in '0'..'9' }) return false
        if ((last.startsWith("0x") || last.startsWith("0X")) && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        return true
    }

    /**
     * The registrable domain (eTLD+1) of a canonical [host] by the Public Suffix List OkHttp ships
     * (`HttpUrl.topPrivateDomain`, the list's private section included); null for a public suffix, or
     * when the list cannot be read. A trailing dot is set aside for the lookup and kept on the answer,
     * as the URL Standard's "obtain a public suffix" does.
     */
    private fun registrableDomainOf(host: String): String? {
        val dot = if (host.endsWith(".")) "." else ""
        val bare = host.removeSuffix(".")
        if (bare.isEmpty()) return null
        return try {
            HttpUrl.Builder().scheme("https").host(bare).build().topPrivateDomain()?.let { it + dot }
        } catch (_: Exception) {
            null
        }
    }

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

    /** ta-coik.1: `ViewStructure#setPendingCredentialRequest`, which Compose fills in from API 35. */
    override val autofillAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override fun autofillOffer(requestJson: String, onAnswer: (PasskeyCeremony) -> Unit): PasskeyAutofillOffer? =
        if (autofillAvailable) PasskeyAutofillOffer.of(requestJson, onAnswer) else null

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

/**
 * ta-coik.1: one autofill-style passkey offer: the framework request a sign-in field's autofill node
 * carries ([request], built as androidx's own conversion builds it, from the same
 * [GetPublicKeyCredentialOption] a prompt uses) and the receiver the framework answers once the
 * operator picks the passkey ([receiver]: [onAnswer] at most once; a cancel or any error is
 * [PasskeyCeremony.Dismissed] / [PasskeyCeremony.Failed], which the login screen keeps quiet, as the
 * web keeps an unused conditional offer quiet). Prints nothing of the request.
 */
class PasskeyAutofillOffer private constructor(
    val request: android.credentials.GetCredentialRequest,
    val receiver: OutcomeReceiver<android.credentials.GetCredentialResponse, android.credentials.GetCredentialException>,
) {
    override fun toString(): String = "PasskeyAutofillOffer(***)"

    companion object {
        /** The offer for [requestJson] (options [PasskeyRules.ceremonyOptions] checked). */
        fun of(requestJson: String, onAnswer: (PasskeyCeremony) -> Unit): PasskeyAutofillOffer {
            val option = GetPublicKeyCredentialOption(requestJson)
            val metadata = GetCredentialRequest.getRequestMetadataBundle(GetCredentialRequest(listOf(option)))
            val request = android.credentials.GetCredentialRequest.Builder(metadata)
                .addCredentialOption(
                    android.credentials.CredentialOption.Builder(option.type, option.requestData, option.candidateQueryData)
                        .setIsSystemProviderRequired(option.isSystemProviderRequired)
                        .setAllowedProviders(option.allowedProviders)
                        .build(),
                )
                .build()
            val answered = AtomicBoolean(false)
            fun answer(c: PasskeyCeremony) {
                if (answered.compareAndSet(false, true)) onAnswer(c)
            }
            val receiver = object : OutcomeReceiver<android.credentials.GetCredentialResponse, android.credentials.GetCredentialException> {
                override fun onResult(result: android.credentials.GetCredentialResponse) {
                    val credential = try {
                        Credential.createFrom(result.credential)
                    } catch (_: Exception) {
                        null
                    }
                    answer((credential as? PublicKeyCredential)?.let { PasskeyCeremony.Done(it.authenticationResponseJson) } ?: PasskeyCeremony.Failed)
                }

                override fun onError(error: android.credentials.GetCredentialException) {
                    answer(
                        when (error.type) {
                            android.credentials.GetCredentialException.TYPE_USER_CANCELED -> PasskeyCeremony.Dismissed
                            android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL -> PasskeyCeremony.NoCredential
                            else -> PasskeyCeremony.Failed
                        },
                    )
                }
            }
            return PasskeyAutofillOffer(request, receiver)
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
