package com.tether.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CredentialRequestData
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.CredentialManagerPasskeys
import com.tether.app.client.LoginResult
import com.tether.app.client.PasskeyAuthenticator
import com.tether.app.client.PasskeyCeremony
import com.tether.app.client.PasskeyLoginRequest
import com.tether.app.client.PasskeyLoginStart
import com.tether.app.client.PairResult
import com.tether.app.client.SignInRequirements
import com.tether.app.client.SignedOutReason
import com.tether.app.client.TetherClient
import com.tether.app.ui.components.BrandMark
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.Wordmark
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Upper bound on the code field. The code itself is 8 characters, but a pasted
 * one may still carry the separators the server strips (spaces/hyphens/dots/
 * underscores), so leave room for them rather than truncating a valid paste —
 * normalisation is the server's call, not ours.
 */
private const val CODE_FIELD_MAX = 12

/** Wait for typing to settle before asking a server what its sign-in needs. */
private const val PROBE_DEBOUNCE_MS = 500L

/** Pause before a submit re-asks a probe that just failed (one retry, ta-s4r). */
private const val PROBE_RETRY_MS = 400L

/** Tags of the login screen's passkey parts (T10.5). */
object LoginTags {
    const val Passkey = "login-passkey"
    const val PasskeyOr = "login-passkey-or"
    const val PasskeyNeedsHttps = "login-passkey-needs-https"
}

/**
 * Everything a surface renders, plus the callbacks. One state machine
 * ([LoginScreen]) drives both surfaces, like use-login-flow.ts on the web.
 */
class LoginUi(
    val mode: AuthMode,
    val baseUrl: String,
    val username: String,
    val password: String,
    val code: String,
    val phase: LoginPhase,
    val error: String?,
    /** A server-side sign-out reason or the logout notice (idle feedback). */
    val notice: String?,
    val requirements: SignInRequirements?,
    /** The probe answered nothing readable (see [usernameFieldShown]). */
    val probeFailed: Boolean,
    val probing: Boolean,
    val hostname: String,
    val statusLines: List<String>,
    val onMode: (AuthMode) -> Unit,
    val onBaseUrl: (String) -> Unit,
    val onUsername: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onSubmit: () -> Unit,
    /**
     * T10.5: use-login-flow.ts `passkeyReady` (see [passkeyReady]). ta-coik.1: on every path, as the web
     * offers it on its one sign-in screen (the Pairing path too, not the Password path only).
     */
    val passkeyReady: Boolean = false,
    /** r2 (security F1): a passkey would be ready but the address is http: none offered, and why. */
    val passkeyNeedsHttps: Boolean = false,
    val onPasskey: () -> Unit = {},
    /**
     * ta-coik.1: the web's conditional offer (`autocomplete="current-password webauthn"`): the pending
     * Credential Manager request the password field carries, so from Android 15 the passkey is among
     * its autofill suggestions. Null while none is armed.
     */
    val passkeyAutofill: CredentialRequestData? = null,
) {
    val busy: Boolean get() = phase == LoginPhase.Checking || phase == LoginPhase.Verifying || phase == LoginPhase.VerifyingPasskey || phase == LoginPhase.Success

    /** The username line is on screen: required, or the probe failed ([usernameOptional]). */
    val usernameShown: Boolean get() = usernameFieldShown(requirements, probeFailed)
    val usernameOptional: Boolean get() = usernameFieldOptional(requirements, probeFailed)

    /** Web `passwordLoginEnabled`: on unless the probe said off. */
    val passwordEnabled: Boolean get() = requirements?.passwordLoginEnabled != false
}

/**
 * First-launch / re-auth screen (PLAN D12). [surface] picks the web's sign-in
 * screen (see [loginSurfaceFor]); every surface offers the native-only parts
 * too: the server URL and the pairing-code path (servers behind an SSO proxy,
 * where the browser password page is unreachable from the app).
 *
 * The password and the pairing code are held with `remember`, NOT
 * `rememberSaveable`: a secret must not be written into the saved-instance-state
 * bundle (which the system persists across process death).
 */
@Composable
fun LoginScreen(
    client: TetherClient,
    surface: LoginSurface = LoginSurface.Studio,
    /**
     * Set after a user logout when the server side needs a word (see
     * logoutNoticeFor); the host clears it once a sign-in succeeds.
     */
    logoutNotice: String? = null,
    /**
     * Android 17: the server is on the local network and access is not granted.
     * The host (UiRoot) runs the permission flow and calls [retry] once access is
     * granted. The screen itself only reports the block.
     */
    onLocalNetworkBlocked: (retry: () -> Unit) -> Unit = {},
    /** The latest attempt was not blocked, so any earlier local-network prompt is stale. */
    onLocalNetworkClear: () -> Unit = {},
    /**
     * T10.5: the passkey ceremony. Null = this activity's Credential Manager; tests hand a fake. A
     * passkey is offered only when the probe says one is registered and usable (use-login-flow.ts).
     */
    passkeys: PasskeyAuthenticator? = null,
) {
    val context = LocalContext.current
    val authenticator = passkeys ?: remember(context) {
        val activity = context.findActivity()
        CredentialManagerPasskeys { activity }
    }
    val scope = rememberCoroutineScope()
    val signedOutReason by client.signedOutReason.collectAsStateWithLifecycle()
    val serverUrl by client.serverUrl.collectAsStateWithLifecycle()

    var mode by rememberSaveable {
        mutableStateOf(if (signedOutReason == SignedOutReason.DeviceUnpaired) AuthMode.Pairing else AuthMode.Password)
    }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(LoginPhase.Ready) }
    var error by remember { mutableStateOf<String?>(null) }
    var requirements by remember { mutableStateOf<SignInRequirements?>(null) }
    // ta-coik.1 r2 (security F2): the address (trimmed) [requirements] was read for. A reading counts for
    // the passkey only while it is the address now typed, so an edit never inherits the last one's.
    var requirementsFor by remember { mutableStateOf<String?>(null) }
    var probeFailed by remember { mutableStateOf(false) }
    // The web's flow.notice after a dismissed passkey prompt; cleared by the next attempt.
    var passkeyNotice by remember { mutableStateOf<String?>(null) }

    val deviceLabel = remember { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device" }

    // Expiry / logout keep the server URL: prefill it once.
    LaunchedEffect(serverUrl) {
        val saved = serverUrl
        if (baseUrl.isEmpty() && !saved.isNullOrEmpty()) baseUrl = saved
    }

    // A probe reading lands only while it is still news: a newer answer (the
    // submit's own probe) is never overwritten by a failure.
    fun adoptProbe(url: String, probe: SignInRequirements?) {
        if (probe != null) {
            requirements = probe
            requirementsFor = url.trim()
            probeFailed = false
        } else if (requirements == null) {
            probeFailed = true
        }
    }

    /** The reading no longer describes the address (it was edited). */
    fun forgetRequirements() {
        requirements = null
        requirementsFor = null
        probeFailed = false
    }

    // use-login-flow.ts probes /api/auth/session on load; natively the URL is
    // typed first, so probe once it settles. No credential is sent.
    LaunchedEffect(baseUrl) {
        val url = baseUrl
        forgetRequirements()
        if (hostnameOf(url).isEmpty()) return@LaunchedEffect
        delay(PROBE_DEBOUNCE_MS)
        adoptProbe(url, client.signInRequirements(url))
    }

    // The local-network retry re-enters submit (so it re-probes too); set below.
    var submitAgain: () -> Unit = {}
    var passkeyAgain: () -> Unit = {}

    // ta-coik.1: offered on every path, as the web's one sign-in screen offers it (use-login-flow.ts
    // passkeyReady, studio-login.tsx:41, retro-login.tsx:164), the Pairing path included.
    // ta-coik.1 r2 (security F2): and only from a reading taken for the address now typed. In the frame
    // after an edit the old reading is still held; without this the autofill offer below would ask the
    // edited text for a challenge (a /healthz and an options POST per keystroke, before any debounce).
    val passkeyPossible = hostnameOf(baseUrl).isNotEmpty() && requirementsFor == baseUrl.trim() &&
        passkeyReady(requirements, authenticator.available)
    // r2 (security F1): never offered for an http address.
    val passkeyOffered = passkeyPossible && passkeyAddressAllowed(baseUrl)
    fun busyNow() = phase == LoginPhase.Checking || phase == LoginPhase.Verifying || phase == LoginPhase.VerifyingPasskey || phase == LoginPhase.Success

    // ta-coik.1 r3: the order of this screen's sign-in attempts (see [AttemptOrder]).
    val attempts = remember { AttemptOrder() }

    /**
     * ta-coik.1 r4 (ta-5csf I1): [attempt] ended Superseded (another sign-in or a sign-out came first)
     * and shows nothing of its own. If no other attempt is still running, nothing else will settle the
     * screen either, so a screen still busy with it goes back to ready (never over a success).
     */
    fun supersededAlone(attempt: Long) {
        if (!attempts.dropped(attempt)) return
        if (phase == LoginPhase.Checking || phase == LoginPhase.Verifying || phase == LoginPhase.VerifyingPasskey) {
            phase = LoginPhase.Ready
        }
    }

    // ta-coik.1: the armed autofill offer, and the arming run that claimed its address (once per page,
    // as the web). r2 (security F2): a claim is an object, so only the run that made it can release it.
    var autofill by remember { mutableStateOf<ArmedAutofill?>(null) }
    var autofillClaim by remember { mutableStateOf<AutofillClaim?>(null) }

    /** use-login-flow.ts signInWithPasskey's outcomes, for the prompt and the autofill offer alike. */
    fun passkeyOutcome(attempt: Long, result: LoginResult) {
        // ta-coik.1 r3: another sign-in (or a sign-out) came first; its outcome stands, this one is dropped.
        if (result is LoginResult.Superseded) return supersededAlone(attempt)
        if (!attempts.settle(attempt)) return
        val blocked = result is LoginResult.LocalNetworkBlocked
        when {
            blocked -> phase = LoginPhase.Ready
            result is LoginResult.Success -> {
                phase = LoginPhase.Success
                password = ""
                code = ""
            }
            result is LoginResult.PasskeyDismissed -> {
                passkeyNotice = PASSKEY_DISMISSED_NOTICE
                phase = LoginPhase.Ready
            }
            else -> {
                error = loginErrorCopy(result) ?: "Passkey sign-in failed."
                phase = LoginPhase.Error
            }
        }
        if (blocked) onLocalNetworkBlocked { passkeyAgain() } else onLocalNetworkClear()
    }

    /**
     * use-login-flow.ts signInWithPasskey: one ceremony, nothing retried. A dismissed prompt is the
     * web's notice and back to ready; a refusal is the error line. The client asks Credential Manager
     * only for this server's own host as the rpId (ta-coik.1 r2; see Passkeys.kt).
     */
    fun passkey() {
        if (busyNow()) return
        if (!passkeyOffered) return
        val url = baseUrl.trim()
        phase = LoginPhase.VerifyingPasskey
        error = null
        passkeyNotice = null
        // The web's modal ceremony supersedes a pending conditional one (AbortError, quiet), not re-armed.
        autofill = null
        val attempt = attempts.begin()
        scope.launch { passkeyOutcome(attempt, client.passkeyLogin(url, authenticator)) }
    }

    /**
     * ta-coik.1, use-login-flow.ts:180-216: the operator picked the passkey from the password field's
     * suggestions. Only the offer still armed for this address counts; the offer is used up either way.
     * Anything but an answer stays quiet (the web keeps an unused or failed conditional offer quiet);
     * an answer is an attempt from here on, sent to the server that issued the challenge.
     * r2 (verifier finding 2): whatever else is in flight. The web's conditional branch checks no phase:
     * a pick during "verifying-password" sets "verifying-passkey" and verifies, and the password fetch is
     * neither aborted nor awaited; each attempt settles on its own (use-login-flow.ts:186-201). So here:
     * no busy check, and the password attempt's coroutine is left running.
     */
    fun autofillAnswered(armed: ArmedAutofill, answer: PasskeyCeremony) {
        if (autofill !== armed) return
        autofill = null
        if (answer !is PasskeyCeremony.Done) return
        if (!passkeyOffered || baseUrl.trim() != armed.url) return
        phase = LoginPhase.VerifyingPasskey
        error = null
        passkeyNotice = null
        val attempt = attempts.begin()
        scope.launch { passkeyOutcome(attempt, client.passkeyLoginFinish(armed.request, answer)) }
    }
    // An answer can come long after the offer was armed: it is judged by the screen as it is then.
    val onAutofillAnswer by rememberUpdatedState<(ArmedAutofill, PasskeyCeremony) -> Unit> { a, c -> autofillAnswered(a, c) }

    // ta-coik.1, use-login-flow.ts:224-246: once a passkey is ready, ask for a challenge and offer it
    // through the sign-in field's autofill suggestions (Android 15+, as the web arms it only where
    // `browserSupportsWebAuthnAutofill()`). Once per page: a used, superseded or dismissed offer is not
    // re-armed for the same address; the key (and Retro's Enter) remain. A failure stays quiet.
    val offerUrl = baseUrl.trim()
    LaunchedEffect(passkeyOffered, offerUrl) {
        if (autofill != null && autofill?.url != offerUrl) autofill = null
        if (autofillClaim != null && autofillClaim?.url != offerUrl) autofillClaim = null
        if (!passkeyOffered || !authenticator.autofillAvailable || autofillClaim?.url == offerUrl) return@LaunchedEffect
        val claim = AutofillClaim(offerUrl)
        autofillClaim = claim
        var armedNow = false
        try {
            val request = (client.passkeyLoginStart(offerUrl) as? PasskeyLoginStart.Ready)?.request ?: return@LaunchedEffect
            var armed: ArmedAutofill? = null
            val offer = authenticator.autofillOffer(request.requestJson()) { answer ->
                // The framework may answer on any thread; the screen's state is only written on its own.
                scope.launch { armed?.let { onAutofillAnswer(it, answer) } }
            } ?: return@LaunchedEffect
            armed = ArmedAutofill(offerUrl, request, CredentialRequestData(offer.request, offer.receiver))
            autofill = armed
            armedNow = true
        } finally {
            // Cancelled before it was armed (the address moved on): the next visit may arm again. r2
            // (security F2): only while this run still holds the claim. A cancelled run's finally waits
            // for its blocking options call, so it can land after a newer run claimed the same address;
            // clearing that claim would let a third run arm the address twice.
            if (!armedNow && autofill == null && autofillClaim === claim && !isActive) autofillClaim = null
        }
    }

    fun send(url: String) {
        validateAttempt(mode, url, username, password, code, requirements)?.let {
            error = it
            phase = LoginPhase.Error
            return
        }
        phase = LoginPhase.Verifying
        error = null
        val attemptMode = mode
        val attempt = attempts.begin()
        val sentUsername = username.trim()
        val usernameHint = usernameHintFor(requirements, sentUsername)
        scope.launch {
            var blocked = false
            var superseded = false
            val failure = when (attemptMode) {
                AuthMode.Password -> client.login(url, password, sentUsername).let {
                    blocked = it is LoginResult.LocalNetworkBlocked
                    superseded = it is LoginResult.Superseded
                    loginErrorCopy(it, usernameHint)
                }
                AuthMode.Pairing -> client.pair(url, code, deviceLabel).let {
                    blocked = it is PairResult.LocalNetworkBlocked
                    superseded = it is PairResult.Superseded
                    pairErrorCopy(it)
                }
            }
            // ta-coik.1 r3: another sign-in (or a sign-out) came first; its outcome stands, this one is dropped.
            if (superseded) return@launch supersededAlone(attempt)
            // ta-coik.1 r3 (verifier r2, Low): a newer attempt has already settled; this older one's
            // outcome (a late refusal after a picked passkey signed in, say) leaves the screen as it is.
            if (!attempts.settle(attempt)) return@launch
            when {
                blocked -> phase = LoginPhase.Ready
                failure != null -> {
                    error = failure
                    phase = LoginPhase.Error
                    // retro-login.tsx clears the password after a refusal.
                    if (surface == LoginSurface.Retro) password = ""
                }
                else -> {
                    phase = LoginPhase.Success
                    // Nothing secret outlives the attempt that used it.
                    password = ""
                    code = ""
                }
            }
            // Blocked: the host shows the explanation / persistent notice and
            // retries this same connect once access is granted. Never a silent
            // failure, and never an automatic retry while access is denied.
            if (blocked) onLocalNetworkBlocked { submitAgain() } else onLocalNetworkClear()
        }
    }

    fun submit() {
        if (phase == LoginPhase.Checking || phase == LoginPhase.Verifying || phase == LoginPhase.VerifyingPasskey || phase == LoginPhase.Success) return
        // retro-login.tsx: Enter on an empty password line is the passkey, when one is ready. ta-coik.1:
        // the Pairing path's line too, so the hint's "⏎ on an empty line = passkey" holds on either path.
        val lineEmpty = if (mode == AuthMode.Password) password.isEmpty() else code.isEmpty()
        if (surface == LoginSurface.Retro && lineEmpty && passkeyOffered) return passkey()
        passkeyNotice = null
        val url = baseUrl.trim()
        // ta-s4r: a password never goes out with the sign-in requirements
        // unknown. A console with a username refuses a password-only login with
        // the same 401 as a wrong password, so a submit that beat the probe (or
        // followed a failed one, e.g. before local-network access was granted)
        // asks again first. The fields are disabled meanwhile, so the URL holds.
        if (mode == AuthMode.Password && url.isNotEmpty() && requirements == null) {
            val attempts = if (probeFailed) 1 else 2
            phase = LoginPhase.Checking
            error = null
            scope.launch {
                var probe: SignInRequirements? = null
                for (attempt in 0 until attempts) {
                    if (attempt > 0) delay(PROBE_RETRY_MS)
                    probe = requirements ?: client.signInRequirements(url)
                    if (probe != null) break
                }
                adoptProbe(url, probe)
                // Still unknown: the username line is now shown as optional and
                // the attempt goes out with whatever it holds; a refusal says
                // the username may be the missing part.
                phase = LoginPhase.Ready
                send(url)
            }
            return
        }
        send(url)
    }

    submitAgain = ::submit
    passkeyAgain = ::passkey

    val hostname = hostnameOf(baseUrl)
    val probing = hostname.isNotEmpty() && requirements == null && !probeFailed
    val ui = LoginUi(
        mode = mode,
        baseUrl = baseUrl,
        username = username,
        password = password,
        code = code,
        phase = phase,
        error = error,
        notice = passkeyNotice ?: logoutNotice ?: signedOutReason?.let(::signedOutCopy),
        requirements = requirements,
        probeFailed = probeFailed,
        probing = probing,
        hostname = hostname,
        statusLines = statusLines(hostname, requirements, probeFailed),
        onMode = { next ->
            if (phase != LoginPhase.Checking && phase != LoginPhase.Verifying && phase != LoginPhase.VerifyingPasskey && phase != LoginPhase.Success) {
                mode = next
                error = null
                passkeyNotice = null
                if (phase == LoginPhase.Error) phase = LoginPhase.Ready
            }
        },
        onBaseUrl = { next ->
            // ta-coik.1 r2 (security F2): the reading is dropped with the keystroke, not a frame later.
            if (next != baseUrl) forgetRequirements()
            baseUrl = next
        },
        onUsername = { username = it },
        onPassword = { password = it },
        // Upper-case as typed (the code alphabet is upper-case only); everything
        // else — separators, U→V — is left to the server so the two can never
        // disagree.
        onCode = { code = it.uppercase().take(CODE_FIELD_MAX) },
        onSubmit = ::submit,
        passkeyReady = passkeyOffered,
        passkeyNeedsHttps = passkeyPossible && !passkeyOffered,
        onPasskey = ::passkey,
        passkeyAutofill = autofill?.takeIf { passkeyOffered && it.url == baseUrl.trim() }?.data,
    )
    when (surface) {
        LoginSurface.Studio -> StudioLogin(ui)
        LoginSurface.Retro -> RetroLogin(ui)
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** ta-coik.1: one armed autofill offer: the address, the checked challenge, what the password field carries. */
private class ArmedAutofill(val url: String, val request: PasskeyLoginRequest, val data: CredentialRequestData)

/** ta-coik.1 r2: one arming run's claim on [url]; compared by identity, so a run releases only its own. */
private class AutofillClaim(val url: String)

/**
 * ta-coik.1 r3 (verifier r2, Low): a password or pairing send, the passkey key and a picked autofill
 * passkey can be in flight together (the web lets a pick go ahead during a password attempt). Each takes
 * a number when it begins; its outcome is shown only if no newer attempt has settled since, so an older
 * attempt's late answer never covers a newer one's (a refusal over a passkey sign-in). Main thread only.
 */
private class AttemptOrder {
    private var begun = 0L
    private var settled = 0L

    /** ta-coik.1 r4: the attempts still running. */
    private val pending = HashSet<Long>()

    fun begin(): Long = (++begun).also { pending += it }

    /** Whether [attempt]'s outcome may land: true, and it is the latest settled, unless a newer one has. */
    fun settle(attempt: Long): Boolean {
        pending -= attempt
        if (attempt < settled) return false
        settled = attempt
        return true
    }

    /** ta-coik.1 r4: [attempt] ended with no outcome to show; true when no other attempt is still running. */
    fun dropped(attempt: Long): Boolean {
        pending -= attempt
        return pending.isEmpty()
    }
}

/** The activity hosting [this] context, or null (Credential Manager needs it for its prompt). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ServerUrlField(ui: LoginUi, modifier: Modifier = Modifier, fontFamily: FontFamily = Manrope) {
    TetherInputWell(
        value = ui.baseUrl,
        onValueChange = ui.onBaseUrl,
        modifier = modifier.semantics { contentDescription = "Server URL" },
        placeholder = "https://tether.example.com",
        singleLine = true,
        enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        fontFamily = fontFamily,
    )
}

@Composable
private fun UsernameField(ui: LoginUi, modifier: Modifier = Modifier, fontFamily: FontFamily = Manrope) {
    TetherInputWell(
        value = ui.username,
        onValueChange = ui.onUsername,
        modifier = modifier.semantics {
            contentDescription = if (ui.usernameOptional) "Operator username, optional" else "Operator username"
        },
        placeholder = if (ui.usernameOptional) "Username (optional)" else "Username",
        singleLine = true,
        enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        fontFamily = fontFamily,
        contentType = ContentType.Username,
    )
}

@Composable
private fun PasswordField(ui: LoginUi, modifier: Modifier = Modifier, fontFamily: FontFamily = Manrope, description: String = "Dashboard password") {
    TetherInputWell(
        value = ui.password,
        onValueChange = ui.onPassword,
        modifier = modifier.semantics { contentDescription = description },
        placeholder = "Password",
        singleLine = true,
        enabled = !ui.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { ui.onSubmit() }),
        fontFamily = fontFamily,
        contentType = ContentType.Password,
        // ta-coik.1: studio-login.tsx:137 / retro-login.tsx:208 `autocomplete="current-password webauthn"`.
        credentialRequest = ui.passkeyAutofill,
    )
}

@Composable
private fun CodeField(ui: LoginUi, modifier: Modifier = Modifier, description: String = "Pairing code") {
    TetherInputWell(
        value = ui.code,
        onValueChange = ui.onCode,
        modifier = modifier.semantics { contentDescription = description },
        placeholder = "8-character code",
        singleLine = true,
        enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { ui.onSubmit() }),
        fontFamily = JetBrainsMono,
        letterSpacing = 0.18.em,
    )
}

private const val PAIRING_HELP =
    "Open Tether in a browser, choose “Pair a device”, and type the code it shows. " +
        "It is valid for five minutes and can be used once."

/**
 * Password / pairing switch. Selection is carried by the key variant AND the
 * label semantics, never by colour alone (visual-spec §2.2).
 */
@Composable
private fun ModeSwitch(ui: LoginUi, passwordLabel: String, pairingLabel: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TetherKey(
            onClick = { ui.onMode(AuthMode.Password) },
            modifier = Modifier.weight(1f).semantics {
                contentDescription = if (ui.mode == AuthMode.Password) "$passwordLabel, selected" else passwordLabel
            },
            classes = if (ui.mode == AuthMode.Password) KeyClasses.ButtonPrimary else KeyClasses.ButtonSecondary,
            label = passwordLabel,
            enabled = !ui.busy,
        )
        TetherKey(
            onClick = { ui.onMode(AuthMode.Pairing) },
            modifier = Modifier.weight(1f).semantics {
                contentDescription = if (ui.mode == AuthMode.Pairing) "$pairingLabel, selected" else pairingLabel
            },
            classes = if (ui.mode == AuthMode.Pairing) KeyClasses.ButtonPrimary else KeyClasses.ButtonSecondary,
            label = pairingLabel,
            enabled = !ui.busy,
        )
    }
}

@Composable
private fun LabeledRow(label: String, labelWidth: Int, content: @Composable (Modifier) -> Unit) {
    val t = LocalTetherTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            color = t.faint,
            fontFamily = JetBrainsMono,
            fontSize = 12.sp,
            modifier = Modifier.width(labelWidth.dp),
        )
        content(Modifier.weight(1f))
    }
}

@Composable
private fun MonoText(text: String, color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    Text(text = text, color = color, fontFamily = JetBrainsMono, fontSize = fontSize, modifier = modifier)
}

/** A screen-reader announcement of the final outcome (aria-live="polite" on the web). */
private fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

// ---------------------------------------------------------------------------
// Studio (studio-login.tsx): brand panel + welcome form
// ---------------------------------------------------------------------------

@Composable
private fun StudioLogin(ui: LoginUi) {
    val t = LocalTetherTokens.current
    BoxWithConstraints(Modifier.fillMaxSize().background(t.mineral)) {
        val wide = maxWidth >= 840.dp
        if (wide) {
            Row(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                StudioBrandPanel(Modifier.weight(1f).fillMaxSize().background(t.mineralDeep).padding(40.dp))
                Box(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    StudioForm(ui, Modifier.padding(40.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()),
            ) {
                StudioBrandPanel(Modifier.fillMaxWidth().background(t.mineralDeep).padding(horizontal = 24.dp, vertical = 20.dp), compact = true)
                StudioForm(ui, Modifier.padding(horizontal = 24.dp, vertical = 24.dp))
            }
        }
    }
}

@Composable
private fun StudioBrandPanel(modifier: Modifier, compact: Boolean = false) {
    val t = LocalTetherTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.semantics { contentDescription = "Tether" },
        ) {
            BrandMark()
            Text("Tether", color = t.white, fontFamily = Manrope, fontWeight = TetherWeights.heading, fontSize = 17.sp)
            Text(".", color = t.violet, fontFamily = Manrope, fontWeight = TetherWeights.heading, fontSize = 17.sp)
        }
        Text(
            text = "Good work.\nWithin reach.",
            color = t.white,
            fontFamily = Manrope,
            fontWeight = TetherWeights.heading,
            fontSize = if (compact) 22.sp else 34.sp,
        )
        if (!compact) {
            Text(
                text = "Your agents, on your machine.\nOne quiet place to keep them moving,\nfrom any screen.",
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 14.sp,
            )
            MonoText("Your agents · Your workspace · Anywhere", t.faint, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(TetherIcons.LockKeyhole, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp))
            Text("Private by design. Self-hosted by you.", color = t.faint, fontFamily = Manrope, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StudioForm(ui: LoginUi, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val (feedback, feedbackColor) = when (ui.phase) {
        LoginPhase.Checking -> "Checking sign-in…" to t.violet
        LoginPhase.Verifying ->
            (if (ui.mode == AuthMode.Pairing) "Pairing this device…" else "Checking your password…") to t.violet
        LoginPhase.VerifyingPasskey -> "Waiting for your passkey…" to t.violet
        LoginPhase.Success -> "You’re in. Opening your workspace…" to t.running
        LoginPhase.Error -> ui.error.orEmpty() to t.danger
        LoginPhase.Ready -> ui.notice.orEmpty() to t.warning
    }
    Column(modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandMark()
        Text(
            text = "Welcome back.",
            color = t.white,
            fontFamily = Manrope,
            fontWeight = TetherWeights.heading,
            fontSize = 24.sp,
            modifier = Modifier.semantics { heading() },
        )
        Text("Your workspace is right where you left it.", color = t.muted, fontFamily = Manrope, fontSize = 14.sp)
        if (ui.probing) Text("Connecting to your workspace…", color = t.faint, fontFamily = Manrope, fontSize = 12.5.sp)

        StudioLabel("Server")
        ServerUrlField(ui, Modifier.fillMaxWidth())
        ModeSwitch(ui, passwordLabel = "Password", pairingLabel = "Pairing code")
        when (ui.mode) {
            AuthMode.Password -> if (ui.passwordEnabled) {
                StudioPasskey(ui)
                if (ui.usernameShown) {
                    StudioLabel(if (ui.usernameOptional) "Username (optional)" else "Username")
                    UsernameField(ui, Modifier.fillMaxWidth())
                    if (ui.usernameOptional) Text(USERNAME_OPTIONAL_HINT, color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp)
                }
                StudioLabel("Password")
                PasswordField(ui, Modifier.fillMaxWidth())
                TetherKey(
                    onClick = ui.onSubmit,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Unlock Tether" },
                    classes = KeyClasses.ButtonPrimary,
                    label = when (ui.phase) {
                        LoginPhase.Checking -> "Checking sign-in…"
                        LoginPhase.Verifying -> "Opening workspace…"
                        else -> "Open workspace"
                    },
                    icon = TetherIcons.ArrowRight,
                    enabled = !ui.busy,
                )
            } else {
                StudioPasskey(ui)
                Text(
                    "Password sign-in is turned off for this workspace. Pair this device with a code instead.",
                    color = t.muted,
                    fontFamily = Manrope,
                    fontSize = 13.sp,
                )
            }
            AuthMode.Pairing -> {
                // ta-coik.1: the passkey on this path too (the web offers it wherever you sign in).
                StudioPasskey(ui)
                StudioLabel("Pairing code")
                CodeField(ui, Modifier.fillMaxWidth())
                Text(PAIRING_HELP, color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp)
                TetherKey(
                    onClick = ui.onSubmit,
                    modifier = Modifier.fillMaxWidth(),
                    classes = KeyClasses.ButtonPrimary,
                    label = if (ui.phase == LoginPhase.Verifying) "Pairing…" else "Pair this device",
                    icon = TetherIcons.ArrowRight,
                    enabled = !ui.busy,
                )
            }
        }
        if (feedback.isNotEmpty()) {
            Text(
                feedback,
                color = feedbackColor,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 12.8.sp,
                modifier = Modifier.politeLiveRegion(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(TetherIcons.LockKeyhole, contentDescription = null, tint = t.faint, modifier = Modifier.size(15.dp))
            Text("Your private workspace", color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp)
            if (ui.hostname.isNotEmpty()) MonoText(ui.hostname, t.faint, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("One private console. Every agent.", color = t.faint, fontFamily = Manrope, fontSize = 12.sp)
    }
}

/** studio-login.tsx's passkey key, above the password form, with its "or" separator (T10.5). */
@Composable
private fun StudioPasskey(ui: LoginUi) {
    val t = LocalTetherTokens.current
    if (ui.passkeyNeedsHttps) {
        Text(PASSKEY_NEEDS_HTTPS, color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp, modifier = Modifier.testTag(LoginTags.PasskeyNeedsHttps))
        return
    }
    if (!ui.passkeyReady) return
    TetherKey(
        onClick = ui.onPasskey,
        modifier = Modifier.fillMaxWidth().testTag(LoginTags.Passkey),
        classes = KeyClasses.ButtonPrimary,
        label = if (ui.phase == LoginPhase.VerifyingPasskey) "Waiting for your passkey…" else "Sign in with a passkey",
        icon = TetherIcons.Fingerprint,
        enabled = !ui.busy,
    )
    // studio-login.tsx:112's separator, and the app's own for its Pairing path (ta-coik.1).
    val or = when {
        ui.mode == AuthMode.Pairing -> PASSKEY_OR_PAIRING
        ui.passwordEnabled -> "or continue with your password"
        else -> null
    }
    if (or != null) {
        Text(
            or,
            color = t.faint,
            fontFamily = Manrope,
            fontSize = 12.5.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag(LoginTags.PasskeyOr),
        )
    }
}

@Composable
private fun StudioLabel(text: String) {
    val t = LocalTetherTokens.current
    Text(text, color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 12.5.sp)
}

// ---------------------------------------------------------------------------
// Retro (retro-login.tsx): the opt-in boot console
// ---------------------------------------------------------------------------

@Composable
private fun RetroLogin(ui: LoginUi) {
    val t = LocalTetherTokens.current
    // Boot lines (the web types them in; the typewriter motion is Phase 3 polish).
    val bootLines = listOf(
        "Tether console",
        "host ${ui.hostname.ifEmpty { "…" }}",
    ) + ui.statusLines.drop(1)
    val feedback: Pair<String, Color>? = when (ui.phase) {
        LoginPhase.Checking -> "checking sign-in" to t.violet
        LoginPhase.Verifying -> "authenticating" to t.violet
        LoginPhase.VerifyingPasskey -> "authenticating with passkey" to t.violet
        LoginPhase.Success -> "ACCESS GRANTED" to t.running
        LoginPhase.Error -> "ACCESS DENIED — ${ui.error.orEmpty()}" to t.danger
        LoginPhase.Ready -> ui.notice?.let { it to t.warning }
    }
    val passwordHint = when {
        ui.mode == AuthMode.Pairing -> "type the code from your browser, ⏎ to send"
        ui.passwordEnabled && ui.usernameOptional -> "login only if this console has a username · type your password, ⏎ to send"
        ui.passwordEnabled -> "type your password, ⏎ to send"
        else -> "password sign-in is off for this console — use a pairing code"
    }
    // retro-login.tsx: the passkey part leads, joined by the web's wide separator.
    val hint = if (ui.passkeyReady) "⏎ on an empty line = passkey    ·    $passwordHint" else passwordHint
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.mineralDeep)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        bootLines.forEachIndexed { index, line ->
            MonoText(line, if (index == 0) t.white else t.ink, fontSize = if (index == 0) 15.sp else 12.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        LabeledRow("server:", 88) { ServerUrlField(ui, it, JetBrainsMono) }
        // Menu items, like the passkey entry on the web: "›" marks the selected one.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RetroMenuItem("password", selected = ui.mode == AuthMode.Password, enabled = !ui.busy) { ui.onMode(AuthMode.Password) }
            RetroMenuItem("pairing code", selected = ui.mode == AuthMode.Pairing, enabled = !ui.busy) { ui.onMode(AuthMode.Pairing) }
        }
        if (ui.passkeyNeedsHttps) MonoText("passkeys need an https:// address", t.faint, Modifier.testTag(LoginTags.PasskeyNeedsHttps), fontSize = 11.5.sp)
        if (ui.passkeyReady) {
            // retro-login.tsx's `retroMenuItem`: "›" then the line, 44dp like every key.
            TetherKey(
                onClick = ui.onPasskey,
                modifier = Modifier.testTag(LoginTags.Passkey),
                classes = KeyClasses.ChatJump,
                label = if (ui.phase == LoginPhase.VerifyingPasskey) "› waiting for your passkey…" else "› sign in with passkey",
                enabled = !ui.busy,
            )
        }
        when (ui.mode) {
            AuthMode.Password -> if (ui.passwordEnabled) {
                if (ui.usernameShown) LabeledRow("login:", 88) { UsernameField(ui, it, JetBrainsMono) }
                LabeledRow("password:", 88) { mod -> RetroPromptWithEnter(mod, ui) { PasswordField(ui, it, JetBrainsMono, if (ui.passkeyReady) RETRO_PASSWORD_WITH_PASSKEY else "Dashboard password") } }
            }
            AuthMode.Pairing -> LabeledRow("code:", 88) { mod ->
                RetroPromptWithEnter(mod, ui) { CodeField(ui, it, if (ui.passkeyReady) RETRO_CODE_WITH_PASSKEY else "Pairing code") }
            }
        }
        MonoText(hint, t.faint, fontSize = 11.5.sp)
        feedback?.let { (text, color) -> MonoText(text, color, Modifier.politeLiveRegion(), fontSize = 12.5.sp) }
    }
}

@Composable
private fun RetroPromptWithEnter(modifier: Modifier, ui: LoginUi, field: @Composable (Modifier) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field(Modifier.weight(1f))
        TetherKey(
            onClick = ui.onSubmit,
            modifier = Modifier.semantics { contentDescription = "Send" },
            classes = KeyClasses.ButtonSecondary,
            label = "⏎",
            enabled = !ui.busy,
        )
    }
}

@Composable
private fun RetroMenuItem(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TetherKey(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = if (selected) "$label, selected" else label },
        classes = if (selected) KeyClasses.ButtonPrimary else KeyClasses.ChatJump,
        label = if (selected) "› $label" else label,
        enabled = enabled,
    )
}
