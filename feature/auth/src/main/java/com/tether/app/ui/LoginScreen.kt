package com.tether.app.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.LockKeyhole
import com.composables.icons.lucide.Lucide
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.SignInRequirements
import com.tether.app.client.SignedOutReason
import com.tether.app.client.TetherClient
import com.tether.app.ui.components.BrandMark
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.Wordmark
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.delay
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

/**
 * Everything a surface renders, plus the callbacks. One state machine
 * ([LoginScreen]) drives all three surfaces, like use-login-flow.ts on the web.
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
    val probing: Boolean,
    val hostname: String,
    val statusLines: List<String>,
    val onMode: (AuthMode) -> Unit,
    val onBaseUrl: (String) -> Unit,
    val onUsername: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onSubmit: () -> Unit,
) {
    val busy: Boolean get() = phase == LoginPhase.Verifying || phase == LoginPhase.Success
    val usernameRequired: Boolean get() = requirements?.usernameRequired == true

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
    surface: LoginSurface = LoginSurface.Instrument,
    /** Retro's Studio restyle (retro-login.tsx `studio`). */
    studioFamily: Boolean = false,
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
) {
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
    var probeFailed by remember { mutableStateOf(false) }

    val deviceLabel = remember { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device" }

    // Expiry / logout keep the server URL: prefill it once.
    LaunchedEffect(serverUrl) {
        val saved = serverUrl
        if (baseUrl.isEmpty() && !saved.isNullOrEmpty()) baseUrl = saved
    }

    // use-login-flow.ts probes /api/auth/session on load; natively the URL is
    // typed first, so probe once it settles. No credential is sent.
    LaunchedEffect(baseUrl) {
        requirements = null
        probeFailed = false
        if (hostnameOf(baseUrl).isEmpty()) return@LaunchedEffect
        delay(PROBE_DEBOUNCE_MS)
        val probe = client.signInRequirements(baseUrl)
        if (probe == null) probeFailed = true else requirements = probe
    }

    fun submit() {
        if (phase == LoginPhase.Verifying || phase == LoginPhase.Success) return
        val url = baseUrl.trim()
        validateAttempt(mode, url, username, password, code, requirements)?.let {
            error = it
            phase = LoginPhase.Error
            return
        }
        phase = LoginPhase.Verifying
        error = null
        val attemptMode = mode
        scope.launch {
            var blocked = false
            val failure = when (attemptMode) {
                AuthMode.Password -> client.login(url, password, username.trim()).let {
                    blocked = it is LoginResult.LocalNetworkBlocked
                    loginErrorCopy(it)
                }
                AuthMode.Pairing -> client.pair(url, code, deviceLabel).let {
                    blocked = it is PairResult.LocalNetworkBlocked
                    pairErrorCopy(it)
                }
            }
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
            if (blocked) onLocalNetworkBlocked { submit() } else onLocalNetworkClear()
        }
    }

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
        notice = logoutNotice ?: signedOutReason?.let(::signedOutCopy),
        requirements = requirements,
        probing = probing,
        hostname = hostname,
        statusLines = statusLines(hostname, requirements, probeFailed),
        onMode = { next ->
            if (phase != LoginPhase.Verifying && phase != LoginPhase.Success) {
                mode = next
                error = null
                if (phase == LoginPhase.Error) phase = LoginPhase.Ready
            }
        },
        onBaseUrl = { baseUrl = it },
        onUsername = { username = it },
        onPassword = { password = it },
        // Upper-case as typed (the code alphabet is upper-case only); everything
        // else — separators, U→V — is left to the server so the two can never
        // disagree.
        onCode = { code = it.uppercase().take(CODE_FIELD_MAX) },
        onSubmit = ::submit,
    )
    when (surface) {
        LoginSurface.Instrument -> InstrumentLogin(ui)
        LoginSurface.Studio -> StudioLogin(ui)
        LoginSurface.Retro -> RetroLogin(ui, studio = studioFamily)
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

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
        modifier = modifier.semantics { contentDescription = "Operator username" },
        placeholder = "Username",
        singleLine = true,
        enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        fontFamily = fontFamily,
    )
}

@Composable
private fun PasswordField(ui: LoginUi, modifier: Modifier = Modifier, fontFamily: FontFamily = Manrope) {
    TetherInputWell(
        value = ui.password,
        onValueChange = ui.onPassword,
        modifier = modifier.semantics { contentDescription = "Dashboard password" },
        placeholder = "Password",
        singleLine = true,
        enabled = !ui.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { ui.onSubmit() }),
        fontFamily = fontFamily,
    )
}

@Composable
private fun CodeField(ui: LoginUi, modifier: Modifier = Modifier) {
    TetherInputWell(
        value = ui.code,
        onValueChange = ui.onCode,
        modifier = modifier.semantics { contentDescription = "Pairing code" },
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
            variant = if (ui.mode == AuthMode.Password) KeyVariant.Primary else KeyVariant.Secondary,
            label = passwordLabel,
            enabled = !ui.busy,
        )
        TetherKey(
            onClick = { ui.onMode(AuthMode.Pairing) },
            modifier = Modifier.weight(1f).semantics {
                contentDescription = if (ui.mode == AuthMode.Pairing) "$pairingLabel, selected" else pairingLabel
            },
            variant = if (ui.mode == AuthMode.Pairing) KeyVariant.Primary else KeyVariant.Secondary,
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
private fun MonoText(text: String, color: Color, fontSize: TextUnit = 12.sp, modifier: Modifier = Modifier) {
    Text(text = text, color = color, fontFamily = JetBrainsMono, fontSize = fontSize, modifier = modifier)
}

/** A screen-reader announcement of the final outcome (aria-live="polite" on the web). */
private fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

// ---------------------------------------------------------------------------
// Instrument (instrument-login.tsx): the console's own terminal frame
// ---------------------------------------------------------------------------

@Composable
private fun InstrumentLogin(ui: LoginUi) {
    val t = LocalTetherTokens.current
    val statusLabel = instrumentStatusLabel(ui.phase, ui.probing)
    val statusColor = when (ui.phase) {
        LoginPhase.Verifying -> t.violet
        LoginPhase.Success -> t.running
        LoginPhase.Error -> t.danger
        LoginPhase.Ready -> t.faint
    }
    val (feedback, feedbackColor) = when (ui.phase) {
        LoginPhase.Verifying ->
            (if (ui.mode == AuthMode.Pairing) "pairing device" else "checking password") to t.violet
        LoginPhase.Success -> "unlocked · opening console" to t.running
        LoginPhase.Error -> ui.error.orEmpty() to t.danger
        LoginPhase.Ready -> ui.notice.orEmpty() to t.warning
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.mineral)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.semantics { contentDescription = "Tether" },
        ) {
            BrandMark()
            Wordmark()
        }
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .border(1.dp, t.line, RoundedCornerShape(6.dp))
                .background(t.mineralDeep, RoundedCornerShape(6.dp)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText("tether › sign-in", t.faint, 11.sp)
                Spacer(Modifier.weight(1f))
                // Status is the label AND the dot, never colour alone.
                StatusDot(color = statusColor)
                Spacer(Modifier.width(6.dp))
                MonoText(statusLabel, t.muted, 11.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Return to your agents.",
                    color = t.white,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.heading,
                    fontSize = 20.sp,
                    modifier = Modifier.semantics { heading() },
                )
                // The readout: key · value, real readings from the probe.
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ui.statusLines.forEach { line ->
                        val key = line.substringBefore(" · ")
                        val value = line.substringAfter(" · ", "")
                        Row {
                            MonoText(key, t.faint, 11.5.sp)
                            if (value.isNotEmpty()) {
                                MonoText(" · ", t.faint, 11.5.sp)
                                MonoText(value, t.ink, 11.5.sp)
                            }
                        }
                    }
                }

                LabeledRow("server ›", 84) { ServerUrlField(ui, it, JetBrainsMono) }
                ModeSwitch(ui, passwordLabel = "Password", pairingLabel = "Pairing code")

                when (ui.mode) {
                    AuthMode.Password -> if (ui.passwordEnabled) {
                        if (ui.usernameRequired) LabeledRow("username ›", 84) { UsernameField(ui, it, JetBrainsMono) }
                        LabeledRow("password ›", 84) { mod ->
                            Row(mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PasswordField(ui, Modifier.weight(1f), JetBrainsMono)
                                TetherKey(
                                    onClick = ui.onSubmit,
                                    modifier = Modifier.semantics { contentDescription = "Unlock Tether" },
                                    variant = KeyVariant.Primary,
                                    icon = Lucide.ArrowRight,
                                    enabled = !ui.busy,
                                )
                            }
                        }
                    } else {
                        MonoText("password sign-in is off for this console — use a pairing code", t.muted)
                    }
                    AuthMode.Pairing -> {
                        LabeledRow("code ›", 84) { mod ->
                            Row(mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CodeField(ui, Modifier.weight(1f))
                                TetherKey(
                                    onClick = ui.onSubmit,
                                    modifier = Modifier.semantics { contentDescription = "Pair device" },
                                    variant = KeyVariant.Primary,
                                    icon = Lucide.ArrowRight,
                                    enabled = !ui.busy,
                                )
                            }
                        }
                        MonoText(PAIRING_HELP, t.muted, 11.5.sp)
                    }
                }

                Row(modifier = Modifier.politeLiveRegion(), verticalAlignment = Alignment.Top) {
                    MonoText("› ", t.faint)
                    MonoText(feedback, feedbackColor)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "One private console. Every agent.",
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 12.sp,
        )
    }
}

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
            MonoText("Your agents · Your workspace · Anywhere", t.faint, 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.LockKeyhole, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp))
            Text("Private by design. Self-hosted by you.", color = t.faint, fontFamily = Manrope, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StudioForm(ui: LoginUi, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val (feedback, feedbackColor) = when (ui.phase) {
        LoginPhase.Verifying ->
            (if (ui.mode == AuthMode.Pairing) "Pairing this device…" else "Checking your password…") to t.violet
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
                if (ui.usernameRequired) {
                    StudioLabel("Username")
                    UsernameField(ui, Modifier.fillMaxWidth())
                }
                StudioLabel("Password")
                PasswordField(ui, Modifier.fillMaxWidth())
                TetherKey(
                    onClick = ui.onSubmit,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Unlock Tether" },
                    variant = KeyVariant.Primary,
                    label = if (ui.phase == LoginPhase.Verifying) "Opening workspace…" else "Open workspace",
                    icon = Lucide.ArrowRight,
                    enabled = !ui.busy,
                    showSlit = true,
                )
            } else {
                Text(
                    "Password sign-in is turned off for this workspace. Pair this device with a code instead.",
                    color = t.muted,
                    fontFamily = Manrope,
                    fontSize = 13.sp,
                )
            }
            AuthMode.Pairing -> {
                StudioLabel("Pairing code")
                CodeField(ui, Modifier.fillMaxWidth())
                Text(PAIRING_HELP, color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp)
                TetherKey(
                    onClick = ui.onSubmit,
                    modifier = Modifier.fillMaxWidth(),
                    variant = KeyVariant.Primary,
                    label = if (ui.phase == LoginPhase.Verifying) "Pairing…" else "Pair this device",
                    icon = Lucide.ArrowRight,
                    enabled = !ui.busy,
                    showSlit = true,
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
            Icon(Lucide.LockKeyhole, contentDescription = null, tint = t.faint, modifier = Modifier.size(15.dp))
            Text("Your private workspace", color = t.muted, fontFamily = Manrope, fontSize = 12.5.sp)
            if (ui.hostname.isNotEmpty()) MonoText(ui.hostname, t.faint, 11.5.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("One private console. Every agent.", color = t.faint, fontFamily = Manrope, fontSize = 12.sp)
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
private fun RetroLogin(ui: LoginUi, studio: Boolean) {
    val t = LocalTetherTokens.current
    // Boot lines (the web types them in; the typewriter motion is Phase 3 polish).
    val bootLines = listOf(
        if (studio) "Tether console" else "TETHER CONSOLE",
        "host ${ui.hostname.ifEmpty { "…" }}",
    ) + ui.statusLines.drop(1)
    val feedback: Pair<String, Color>? = when (ui.phase) {
        LoginPhase.Verifying -> "authenticating" to t.violet
        LoginPhase.Success -> "ACCESS GRANTED" to t.running
        LoginPhase.Error -> "ACCESS DENIED — ${ui.error.orEmpty()}" to t.danger
        LoginPhase.Ready -> ui.notice?.let { it to t.warning }
    }
    val hint = when {
        ui.mode == AuthMode.Pairing -> "type the code from your browser, ⏎ to send"
        ui.passwordEnabled -> "type your password, ⏎ to send"
        else -> "password sign-in is off for this console — use a pairing code"
    }
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
            MonoText(line, if (index == 0) t.white else t.ink, if (index == 0) 15.sp else 12.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        LabeledRow("server:", 88) { ServerUrlField(ui, it, JetBrainsMono) }
        // Menu items, like the passkey entry on the web: "›" marks the selected one.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RetroMenuItem("password", selected = ui.mode == AuthMode.Password, enabled = !ui.busy) { ui.onMode(AuthMode.Password) }
            RetroMenuItem("pairing code", selected = ui.mode == AuthMode.Pairing, enabled = !ui.busy) { ui.onMode(AuthMode.Pairing) }
        }
        when (ui.mode) {
            AuthMode.Password -> if (ui.passwordEnabled) {
                if (ui.usernameRequired) LabeledRow("login:", 88) { UsernameField(ui, it, JetBrainsMono) }
                LabeledRow("password:", 88) { mod -> RetroPromptWithEnter(mod, ui) { PasswordField(ui, it, JetBrainsMono) } }
            }
            AuthMode.Pairing -> LabeledRow("code:", 88) { mod -> RetroPromptWithEnter(mod, ui) { CodeField(ui, it) } }
        }
        MonoText(hint, t.faint, 11.5.sp)
        feedback?.let { (text, color) -> MonoText(text, color, 12.5.sp, Modifier.politeLiveRegion()) }
    }
}

@Composable
private fun RetroPromptWithEnter(modifier: Modifier, ui: LoginUi, field: @Composable (Modifier) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field(Modifier.weight(1f))
        TetherKey(
            onClick = ui.onSubmit,
            modifier = Modifier.semantics { contentDescription = "Send" },
            variant = KeyVariant.Secondary,
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
        variant = if (selected) KeyVariant.Primary else KeyVariant.Utility,
        label = if (selected) "› $label" else label,
        enabled = enabled,
    )
}
