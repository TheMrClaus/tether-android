package com.tether.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
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
import com.tether.app.ui.state.rememberRetained
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CredentialRequestData
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import com.tether.app.ui.components.InputWellStyle
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.resolveKey
import com.tether.app.ui.components.Wordmark
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    const val Connection = "login-connection"

    /** The brand panel's "Private by design" line (studio-login.tsx `.brandFooter`). */
    const val BrandFooter = "login-brand-footer"

    /** studio-login.tsx `.brandPanel` and `.formPanel`, the wide layout's two columns (ta-coik.50). */
    const val BrandPanel = "login-brand-panel"
    const val FormPanel = "login-form-panel"

    /** `.welcomeMark` (the form's brand mark in its 46px box) and `.formFooter`. */
    const val WelcomeMark = "login-welcome-mark"
    const val FormFooter = "login-form-footer"
}

/** use-login-flow.ts `lastAttempt` ("passkey" | "password"), plus the app's pairing path. */
enum class LoginAttempt { Password, Passkey, Pairing }

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
    /** use-login-flow.ts `lastAttempt`: the kind of the latest sign-in that went out. */
    val lastAttempt: LoginAttempt? = null,
) {
    /**
     * studio-login.tsx:43 `passwordRefused = flow.phase === "error" && flow.lastAttempt === "password"`:
     * the username and password inputs wear the danger border until the next attempt (or anything
     * else) moves the phase on.
     */
    val passwordRefused: Boolean get() = phase == LoginPhase.Error && lastAttempt == LoginAttempt.Password

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
 * The password and the pairing code are held with `rememberRetained` (ta-coik.20), NOT
 * `rememberSaveable`: a secret must not be written into the saved-instance-state
 * bundle (which the system persists across process death). They survive a rotation in
 * the activity's memory only; a process death starts them empty, as a page reload.
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
    /**
     * T10.6: the typed server is a first-run server (`/healthz` says `setupRequired: true`): the host opens
     * the setup wizard for this address, as the web's /login redirects to /setup. Called by a sign-in or
     * pairing attempt that learns it, and by the address probe once it settles ([autoOpenSetup]).
     */
    onSetupRequired: (baseUrl: String) -> Unit = {},
    /** T10.6: the address to start from (after the wizard: the server just set up); else the saved one. */
    initialBaseUrl: String? = null,
    /** T10.6: false right after the wizard was left, so the address probe does not open it again at once. */
    autoOpenSetup: Boolean = true,
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
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl.orEmpty()) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberRetained { "" }
    var code by rememberRetained { "" }
    var phase by remember { mutableStateOf(LoginPhase.Ready) }
    var error by remember { mutableStateOf<String?>(null) }
    var requirements by remember { mutableStateOf<SignInRequirements?>(null) }
    // ta-coik.1 r2 (security F2): the address (trimmed) [requirements] was read for. A reading counts for
    // the passkey only while it is the address now typed, so an edit never inherits the last one's.
    var requirementsFor by remember { mutableStateOf<String?>(null) }
    var probeFailed by remember { mutableStateOf(false) }
    // The web's flow.notice after a dismissed passkey prompt; cleared by the next attempt.
    var passkeyNotice by remember { mutableStateOf<String?>(null) }
    // use-login-flow.ts:86 `lastAttempt`: set when an attempt goes out (submitPassword, a passkey ceremony).
    var lastAttempt by remember { mutableStateOf<LoginAttempt?>(null) }

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

    val onSetupRequiredNow by rememberUpdatedState(onSetupRequired)

    // use-login-flow.ts probes /api/auth/session on load; natively the URL is
    // typed first, so probe once it settles. No credential is sent.
    LaunchedEffect(baseUrl) {
        val url = baseUrl
        forgetRequirements()
        if (hostnameOf(url).isEmpty()) return@LaunchedEffect
        delay(PROBE_DEBOUNCE_MS)
        val probe = client.signInRequirements(url)
        adoptProbe(url, probe)
        // T10.6: a first-run server has no sign-in reading (its /api answers 503); its /healthz says why.
        if (probe == null && autoOpenSetup && client.setupRequired(url)) onSetupRequiredNow(url.trim())
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
            // T10.6: a first-run server: the wizard, not an error line.
            result is LoginResult.SetupRequired -> {
                phase = LoginPhase.Ready
                onSetupRequiredNow(baseUrl.trim())
            }
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
        lastAttempt = LoginAttempt.Passkey
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
        lastAttempt = LoginAttempt.Passkey
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
        lastAttempt = if (attemptMode == AuthMode.Password) LoginAttempt.Password else LoginAttempt.Pairing
        val attempt = attempts.begin()
        val sentUsername = username.trim()
        val usernameHint = usernameHintFor(requirements, sentUsername)
        scope.launch {
            var blocked = false
            var superseded = false
            var setup = false
            val failure = when (attemptMode) {
                AuthMode.Password -> client.login(url, password, sentUsername).let {
                    blocked = it is LoginResult.LocalNetworkBlocked
                    superseded = it is LoginResult.Superseded
                    setup = it is LoginResult.SetupRequired
                    loginErrorCopy(it, usernameHint)
                }
                AuthMode.Pairing -> client.pair(url, code, deviceLabel).let {
                    blocked = it is PairResult.LocalNetworkBlocked
                    superseded = it is PairResult.Superseded
                    setup = it is PairResult.SetupRequired
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
                // T10.6: a first-run server has no sign-in: its setup wizard opens (nothing typed is kept).
                setup -> {
                    phase = LoginPhase.Ready
                    password = ""
                    code = ""
                    onSetupRequiredNow(url)
                }
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
        lastAttempt = lastAttempt,
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

/**
 * studio-login.module.css `.field input` (tether 90fbb9f). Retro's prompts keep the plain well.
 *  - :67 `height: 50px` (held as a floor, so a larger font scale still fits), `border: 1px solid
 *    var(--line-strong)`, `border-radius: 9px`, `padding: 0 14px`, `background: var(--graphite)`,
 *    `font-size: 16px` (the well's body role already is 1rem).
 *  - :68 `:focus-visible`: `outline: 3px solid color-mix(in srgb, var(--accent) 16%, transparent)`,
 *    `outline-offset: 1px`, `border-color: var(--accent)`, `box-shadow: none`.
 *  - :69 `.field[data-error="true"] input { border-color: var(--danger) }` (see [LoginUi.passwordRefused]).
 *  - :70 `:disabled { opacity: 0.6 }`.
 */
@Composable
private fun studioFieldInput(): InputWellStyle {
    val t = LocalTetherTokens.current
    return InputWellStyle(
        radius = 9.dp,
        minHeight = 50.dp,
        horizontalPadding = 14.dp,
        face = t.graphite,
        border = t.lineStrong,
        focusBorder = t.accent,
        focusOutline = t.accent.copy(alpha = t.accent.alpha * 0.16f),
        focusOutlineWidth = 3.dp,
        focusOutlineOffset = 1.dp,
        invalidBorder = t.danger,
        disabledAlpha = 0.6f,
    )
}

@Composable
private fun ServerUrlField(ui: LoginUi, modifier: Modifier = Modifier, fontFamily: FontFamily = Manrope, input: InputWellStyle? = null) {
    TetherInputWell(
        value = ui.baseUrl,
        onValueChange = ui.onBaseUrl,
        modifier = modifier.semantics { contentDescription = "Server URL" },
        placeholder = "https://tether.example.com",
        singleLine = true,
        enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        fontFamily = fontFamily,
        style = input,
    )
}

@Composable
private fun UsernameField(ui: LoginUi, modifier: Modifier = Modifier, fontFamily: FontFamily = Manrope, input: InputWellStyle? = null) {
    TetherInputWell(
        value = ui.username,
        onValueChange = ui.onUsername,
        modifier = modifier.semantics {
            contentDescription = if (ui.usernameOptional) "Operator username, optional" else "Operator username"
            // studio-login.tsx:124 `aria-invalid={passwordRefused || undefined}`.
            if (input != null && ui.passwordRefused) error(ui.error.orEmpty())
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
        style = input,
        invalid = input != null && ui.passwordRefused,
    )
}

@Composable
private fun PasswordField(
    ui: LoginUi,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = Manrope,
    description: String = "Dashboard password",
    input: InputWellStyle? = null,
) {
    TetherInputWell(
        value = ui.password,
        onValueChange = ui.onPassword,
        modifier = modifier.semantics {
            contentDescription = description
            // studio-login.tsx:139 `aria-invalid={passwordRefused || undefined}`.
            if (input != null && ui.passwordRefused) error(ui.error.orEmpty())
        },
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
        style = input,
        invalid = input != null && ui.passwordRefused,
    )
}

@Composable
private fun CodeField(ui: LoginUi, modifier: Modifier = Modifier, description: String = "Pairing code", input: InputWellStyle? = null) {
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
        style = input,
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

/**
 * studio-login.module.css literals (tether 90fbb9f). "The brand panel is always cobalt, whichever
 * lighting the form panel wears": the same in both skins, and not tokens on the web either.
 */
private object StudioLoginColors {
    /** `.brandPanel { background: #2548b8; color: #fff }`. */
    val Cobalt = Color(0xFF2548B8)

    /** `.brandPeriod`. */
    val Period = Color(0xFFB7C8FF)

    /** `.brandStory > p`, `.connection figcaption`, `.brandFooter`. */
    val Story = Color(0xFFD3DFFF)

    /** `.connectionLine`. */
    val Line = Color(0xFF7C97E3)

    /** `.connectionHub { border: 1px solid #90a8ed }`. */
    val HubEdge = Color(0xFF90A8ED)

    /** `.devices` (and `.providers > span`, which the brand tiles outrank). */
    val Device = Color(0xFFE4ECFF)
}

/** A text rule of the sign-in CSS in the UI face: [px] at 1px = 1sp, a unitless line-height as em, CSS's centred line box. */
private fun loginText(px: Float, weight: Int = 400, trackingEm: Float = 0f, lineHeight: Float? = null): TextStyle = TextStyle(
    fontFamily = Manrope,
    fontSize = px.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = if (trackingEm == 0f) TextUnit.Unspecified else trackingEm.em,
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    lineHeightStyle = CssLineHeight,
)

@Composable
private fun StudioLogin(ui: LoginUi) {
    val t = LocalTetherTokens.current
    // `.shell` and `.formPanel`: the canvas colour, --graphite.
    BoxWithConstraints(Modifier.fillMaxSize().background(t.graphite)) {
        // studio-login.module.css `@media (max-width: 700px)` stacks the panels (and drops the
        // connection figure); above 700px they sit side by side, as here.
        val wide = maxWidth > 700.dp
        val narrowViewport = maxWidth <= 900.dp
        if (wide) {
            // `.brandPanel { padding: clamp(32px, 4.8vw, 76px) }` and `.brandStory h2 { font-size:
            // clamp(44px, 4.5vw, 64px) }`; 36px and 48px at most 900px.
            val brandPadding = if (narrowViewport) 36.dp else (maxWidth * 0.048f).coerceIn(32.dp, 76.dp)
            val storySize = if (narrowViewport) 48f else (maxWidth.value * 0.045f).coerceIn(44f, 64f)
            // `.shell { overflow: auto }`: the two-column page scrolls as a whole, not one panel inside it.
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                StudioColumns(
                    viewport = maxHeight,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    brand = {
                        StudioBrandPanel(
                            Modifier.background(StudioLoginColors.Cobalt).padding(brandPadding),
                            narrowViewport = narrowViewport,
                            storySize = storySize,
                        )
                    },
                    form = { StudioFormPanel(ui, narrowViewport) },
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()),
            ) {
                // `@media (max-width: 700px)`: `.brandPanel { padding: 28px }`, `.formPanel { padding: 38px 28px 26px }`.
                StudioBrandPanel(Modifier.fillMaxWidth().background(StudioLoginColors.Cobalt).padding(28.dp), compact = true)
                // `.formPanel { align-items: center }` (studio-login.module.css:59, kept at most 700px):
                // the 380px `.formContent` and the static `.formFooter` below it are centred across the panel.
                Column(
                    Modifier.testTag(LoginTags.FormPanel).fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 38.dp, bottom = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StudioForm(ui, Modifier, compact = true)
                    // `.formFooter { position: static; margin-top: 40px }` (studio-login.module.css:117).
                    Spacer(Modifier.height(40.dp))
                    Text("One private console. Every agent.", color = t.faint, style = loginText(11f), modifier = Modifier.testTag(LoginTags.FormFooter))
                }
            }
        }
    }
}

/** `.shell { grid-template-columns: minmax(0, 0.96fr) minmax(0, 1.04fr) }`: the brand column's share of the width. */
internal fun studioBrandColumnWidth(width: Int): Int = (width * 0.48f).roundToInt()

/**
 * studio-login.module.css `.shell` (a two-column grid, 0.96fr / 1.04fr, `overflow: auto`) over
 * `.brandPanel` and `.formPanel` (each `min-height: 100dvh`): the columns split the width, both at
 * least [viewport] tall and both as tall as the taller (the grid row stretches them), so the page
 * grows and scrolls when either panel's content does not fit. The form is measured once, at least
 * as tall as the brand panel's own content; the brand panel then takes that height.
 */
@Composable
private fun StudioColumns(viewport: Dp, modifier: Modifier, brand: @Composable () -> Unit, form: @Composable () -> Unit) {
    Layout(contents = listOf(brand, form), modifier = modifier) { (brandSlot, formSlot), constraints ->
        val width = constraints.maxWidth
        val left = studioBrandColumnWidth(width)
        val right = width - left
        val brandPanel = brandSlot.single()
        val floor = maxOf(viewport.roundToPx(), brandPanel.maxIntrinsicHeight(left))
        val formPanel = formSlot.single().measure(Constraints(minWidth = right, maxWidth = right, minHeight = floor))
        val height = formPanel.height
        val brandPlaced = brandPanel.measure(Constraints.fixed(left, height))
        layout(width, height) {
            brandPlaced.place(0, 0)
            formPanel.place(left, 0)
        }
    }
}

/**
 * Studio's `.brand-mark` (studio.css 288-290): a 1.85rem tile of radius 0.58rem, no border, two
 * white 0.19x0.72rem bars of radius 3px, `rotate(32deg) translateY(-0.22rem)` and `translateY(0.32rem)`,
 * both opaque. [tile] is the face: --accent by default (the form's welcome mark), transparent in the
 * brand panel (`.brandPanel .wordmark .brand-mark`, `.connectionHub .brand-mark`: `background:
 * transparent`, bars `#fff`; their `border-color` draws nothing on Studio's `border: 0`).
 */
@Composable
private fun StudioBrandMark(tile: Color, modifier: Modifier = Modifier, size: Dp = 29.6.dp) {
    Canvas(modifier.size(size)) {
        if (tile.alpha > 0f) drawRoundRect(tile, cornerRadius = CornerRadius(9.28.dp.toPx()))
        val w = 3.04.dp.toPx()
        val h = 11.52.dp.toPx()
        val radius = CornerRadius(minOf(3.dp.toPx(), w / 2f))
        val c = center
        rotate(32f, c) {
            for (d in listOf(-3.52.dp.toPx(), 5.12.dp.toPx())) {
                drawRoundRect(Color.White, Offset(c.x - w / 2f, c.y - h / 2f + d), Size(w, h), radius)
            }
        }
    }
}

/**
 * `.brandPanel`: wordmark, story and footer spread down the panel (`justify-content: space-between`,
 * so in a tall window the footer sits at its foot). The compact panel (`@media (max-width: 700px)`)
 * keeps the wordmark (23px) and the heading (36px / 1.09) only.
 */
@Composable
private fun StudioBrandPanel(modifier: Modifier, compact: Boolean = false, narrowViewport: Boolean = false, storySize: Float = 48f) {
    Column(
        Modifier.testTag(LoginTags.BrandPanel).then(modifier),
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.SpaceBetween,
    ) {
        // `.wordmark`: 26px, 750, -0.035em, line-height 1, an 11px gap; "Tether" then the period, no gap.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier.semantics { contentDescription = "Tether" },
        ) {
            StudioBrandMark(Color.Transparent)
            Text(
                buildAnnotatedString {
                    append("Tether")
                    withStyle(SpanStyle(color = StudioLoginColors.Period)) { append(".") }
                },
                color = Color.White,
                style = loginText(if (compact) 23f else 26f, 750, trackingEm = -0.035f, lineHeight = 1f),
            )
        }
        // `.brandStory { padding: 72px 0 64px }` (30px 0 4px compact).
        Column(Modifier.padding(top = if (compact) 30.dp else 72.dp, bottom = if (compact) 4.dp else 64.dp)) {
            Text(
                text = "Good work.\nWithin reach.",
                color = Color.White,
                style = loginText(if (compact) 36f else storySize, 650, trackingEm = -0.04f, lineHeight = if (compact) 1.09f else 1.07f),
            )
            if (!compact) {
                Spacer(Modifier.height(26.dp))
                Text(
                    text = "Your agents, on your machine.\nOne quiet place to keep them moving,\nfrom any screen.",
                    color = StudioLoginColors.Story,
                    style = loginText(16f, lineHeight = 1.75f),
                )
                ConnectionFigure(narrowViewport)
            }
        }
        if (!compact) {
            // `.brandFooter`: #d3dfff, 12px / 1.6, a 9px gap after the 14px lock.
            Row(Modifier.testTag(LoginTags.BrandFooter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(TetherIcons.LockKeyhole, contentDescription = null, tint = StudioLoginColors.Story, modifier = Modifier.size(14.dp))
                Text("Private by design. Self-hosted by you.", color = StudioLoginColors.Story, style = loginText(12f, lineHeight = 1.6f))
            }
        }
    }
}

/**
 * studio-login.tsx:71-80 `.connection` (studio-login.module.css 46-55, 97-105): 64px below the story
 * (48px at most 900px), at most 365px wide. The path: the three harness marks in their brand tiles
 * (globals.css 11204-11224 outranks `.providers > span`: 34x40, 27 wide at most 900px), a #7c97e3
 * line, the hub (58px, 50 at most 900px, a #90a8ed edge) holding the 29px brand mark, a line, the
 * laptop and phone in #e4ecff. Under it the caption's three words spread apart (11px / 1.4, 10px at
 * most 900px, 17px below). The web hides it at most 700px, where the compact panel takes over here
 * too. The figure's label is the web's `aria-label`.
 */
@Composable
private fun ConnectionFigure(narrowViewport: Boolean) {
    val lineGap = if (narrowViewport) 6.dp else 10.dp
    Column(Modifier.padding(top = if (narrowViewport) 48.dp else 64.dp).widthIn(max = 365.dp).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = "Your coding agents connect through Tether to your laptop and phone"
                    testTag = LoginTags.Connection
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (provider in listOf("claude", "codex", "opencode")) {
                ProviderTile(provider, Modifier.size(width = if (narrowViewport) 27.dp else 34.dp, height = 40.dp), color = Color.White, markSize = 22.dp)
            }
            Box(Modifier.weight(1f).widthIn(min = 16.dp).padding(horizontal = lineGap).height(1.dp).background(StudioLoginColors.Line))
            Box(
                Modifier.size(if (narrowViewport) 50.dp else 58.dp).border(1.dp, StudioLoginColors.HubEdge, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // `.connectionHub .brand-mark` (studio-login.module.css:53): 29px.
                StudioBrandMark(Color.Transparent, size = 29.dp)
            }
            Box(Modifier.weight(1f).widthIn(min = 16.dp).padding(horizontal = lineGap).height(1.dp).background(StudioLoginColors.Line))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(TetherIcons.Laptop, contentDescription = null, tint = StudioLoginColors.Device, modifier = Modifier.size(26.dp))
                Icon(TetherIcons.Smartphone, contentDescription = null, tint = StudioLoginColors.Device, modifier = Modifier.size(21.dp))
            }
        }
        val caption = loginText(if (narrowViewport) 10f else 11f, lineHeight = 1.4f)
        Row(Modifier.fillMaxWidth().padding(top = 17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Your agents", color = StudioLoginColors.Story, style = caption)
            Text("Your workspace", color = StudioLoginColors.Story, style = caption, modifier = Modifier.padding(start = 4.dp))
            Text("Anywhere", color = StudioLoginColors.Story, style = caption)
        }
    }
}

/**
 * `.formPanel`: the form centred in the column (`padding: 80px 48px`, 64px 32px at most 900px), and
 * `.formFooter` held 28px off the panel's foot (`position: absolute; bottom: 28px`), centred.
 */
@Composable
private fun StudioFormPanel(ui: LoginUi, narrowViewport: Boolean) {
    val t = LocalTetherTokens.current
    Box(Modifier.testTag(LoginTags.FormPanel), contentAlignment = Alignment.Center) {
        StudioForm(ui, Modifier.padding(horizontal = if (narrowViewport) 32.dp else 48.dp, vertical = if (narrowViewport) 64.dp else 80.dp))
        Text(
            "One private console. Every agent.",
            color = t.faint,
            style = loginText(11f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).testTag(LoginTags.FormFooter),
        )
    }
}

/** The primary key's legend colour, for a trailing icon drawn beside it. */
@Composable
private fun primaryInk(enabled: Boolean): Color =
    resolveKey(LocalTetherTokens.current, KeyClasses.ButtonPrimary, if (enabled) KeyState.Rest else KeyState.Disabled, layout = currentLayoutClass()).ink

/**
 * `.submitButton`: `padding: 10px 16px`, the legend left and the 17px arrow right. Its `min-height: 49px`
 * (studio-login.module.css:71) loses to studio.css:269 `:root .button-primary { min-height: 2.75rem }`
 * (0,2,0 over 0,1,0), so the key is the 44dp key floor; Compose's touch target still reaches 48dp.
 */
@Composable
private fun StudioSubmit(label: String, ui: LoginUi, modifier: Modifier = Modifier) {
    val ink = primaryInk(!ui.busy)
    TetherKey(
        onClick = ui.onSubmit,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        classes = KeyClasses.ButtonPrimary,
        label = label,
        enabled = !ui.busy,
        contentArrangement = Arrangement.SpaceBetween,
        contentPadding = 16.dp,
        trailing = { Icon(TetherIcons.ArrowRight, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp)) },
    )
}

@Composable
private fun StudioForm(ui: LoginUi, modifier: Modifier, compact: Boolean = false) {
    val t = LocalTetherTokens.current
    // `.feedback[data-tone]`: busy --accent, success --running, error --danger, a notice in .feedback's --muted.
    val (feedback, feedbackColor) = when (ui.phase) {
        LoginPhase.Checking -> "Checking sign-in…" to t.accent
        LoginPhase.Verifying ->
            (if (ui.mode == AuthMode.Pairing) "Pairing this device…" else "Checking your password…") to t.accent
        LoginPhase.VerifyingPasskey -> "Waiting for your passkey…" to t.accent
        LoginPhase.Success -> "You’re in. Opening your workspace…" to t.running
        LoginPhase.Error -> ui.error.orEmpty() to t.danger
        LoginPhase.Ready -> ui.notice.orEmpty() to t.muted
    }
    Column(modifier.widthIn(max = 380.dp).fillMaxWidth()) {
        if (!compact) {
            // `.welcomeMark`: a 46px box, 12px radius, 1px --line edge on --mineral, Studio's accent brand mark
            // inside, 32px above the heading; hidden at most 700px.
            val box = RoundedCornerShape(12.dp)
            Box(
                Modifier.testTag(LoginTags.WelcomeMark).size(46.dp).background(t.mineral, box).border(1.dp, t.line, box),
                contentAlignment = Alignment.Center,
            ) { StudioBrandMark(t.accent) }
            Spacer(Modifier.height(32.dp))
        }
        // `.heading`: --white, 34px / 1.18, 700, -0.035em (28px compact).
        Text(
            text = "Welcome back.",
            color = t.white,
            style = loginText(if (compact) 28f else 34f, 700, trackingEm = -0.035f, lineHeight = 1.18f),
            modifier = Modifier.semantics { heading() },
        )
        // `.subheading`: --muted, 14px / 1.6, `margin: 12px 0 34px` (13px, 10px 0 28px compact).
        Spacer(Modifier.height(if (compact) 10.dp else 12.dp))
        Text("Your workspace is right where you left it.", color = t.muted, style = loginText(if (compact) 13f else 14f, lineHeight = 1.6f))
        Spacer(Modifier.height(if (compact) 28.dp else 34.dp))

        // `.form { gap: 20px }`, each `.field` a label over its input 9px apart.
        val input = studioFieldInput()
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (ui.probing) Text("Connecting to your workspace…", color = t.muted, style = loginText(13f))
            StudioField("Server") { ServerUrlField(ui, Modifier.fillMaxWidth(), input = input) }
            ModeSwitch(ui, passwordLabel = "Password", pairingLabel = "Pairing code")
            when (ui.mode) {
                AuthMode.Password -> if (ui.passwordEnabled) {
                    StudioPasskey(ui)
                    if (ui.usernameShown) {
                        StudioField(if (ui.usernameOptional) "Username (optional)" else "Username") {
                            UsernameField(ui, Modifier.fillMaxWidth(), input = input)
                            if (ui.usernameOptional) Text(USERNAME_OPTIONAL_HINT, color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp)
                        }
                    }
                    StudioField("Password") { PasswordField(ui, Modifier.fillMaxWidth(), input = input) }
                    StudioSubmit(
                        label = when (ui.phase) {
                            LoginPhase.Checking -> "Checking sign-in…"
                            LoginPhase.Verifying -> "Opening workspace…"
                            else -> "Open workspace"
                        },
                        ui = ui,
                        modifier = Modifier.semantics { contentDescription = "Unlock Tether" },
                    )
                } else {
                    StudioPasskey(ui)
                    Text(
                        "Password sign-in is turned off for this workspace. Pair this device with a code instead.",
                        color = t.muted,
                        style = loginText(13f, lineHeight = 1.7f),
                    )
                }
                AuthMode.Pairing -> {
                    // ta-coik.1: the passkey on this path too (the web offers it wherever you sign in).
                    StudioPasskey(ui)
                    StudioField("Pairing code") {
                        CodeField(ui, Modifier.fillMaxWidth(), input = input)
                        Text(PAIRING_HELP, color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp)
                    }
                    StudioSubmit(label = if (ui.phase == LoginPhase.Verifying) "Pairing…" else "Pair this device", ui = ui)
                }
            }
        }

        // `.feedback`: always holds its line (min-height 20px), `margin: 15px 0 22px` (18px below compact), 12px / 1.6.
        Spacer(Modifier.height(15.dp))
        Box(Modifier.heightIn(min = 20.dp)) {
            if (feedback.isNotEmpty()) {
                Text(feedback, color = feedbackColor, style = loginText(12f, lineHeight = 1.6f), modifier = Modifier.politeLiveRegion())
            }
        }
        Spacer(Modifier.height(if (compact) 18.dp else 22.dp))

        // `.workspaceNote`: a --line rule, 24px (20px compact) above the 15px lock and its two lines:
        // "Your private workspace" in --muted, the host below it in --faint, 12px / 1.6.
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        Row(Modifier.padding(top = if (compact) 20.dp else 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TetherIcons.LockKeyhole, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 2.dp).size(15.dp))
            Column {
                Text("Your private workspace", color = t.muted, style = loginText(12f, lineHeight = 1.6f))
                if (ui.hostname.isNotEmpty()) {
                    Text(ui.hostname, color = t.faint, style = loginText(12f, lineHeight = 1.6f), modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/**
 * studio-login.tsx's passkey key (`.promptButton`: at least 44px by studio.css:269, the 19px fingerprint 10px before the
 * legend), above the password form, with its "or" separator (T10.5).
 */
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
        iconSize = 19.dp,
        enabled = !ui.busy,
        contentArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        contentPadding = 16.dp,
    )
    // studio-login.tsx:112's separator, and the app's own for its Pairing path (ta-coik.1).
    val or = when {
        ui.mode == AuthMode.Pairing -> PASSKEY_OR_PAIRING
        ui.passwordEnabled -> "or continue with your password"
        else -> null
    }
    if (or != null) {
        // `.or`: --muted 11px between two --line rules, 14px apart, `margin: 24px 0` (the form's 20px gap plus 4).
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(LoginTags.PasskeyOr),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(t.line))
            Text(or, color = t.muted, style = loginText(11f), textAlign = TextAlign.Center)
            Box(Modifier.weight(1f).height(1.dp).background(t.line))
        }
    }
}

/** `.field`: the label (`.fieldLabel`: --ink, 13px, 650) 9px over its input, and anything under it. */
@Composable
private fun StudioField(label: String, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, color = t.ink, style = loginText(13f, 650))
        content()
    }
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
