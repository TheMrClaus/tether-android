package com.tether.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.ConnectionState
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.TetherClient
import com.tether.app.ui.components.BrandMark
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.Wordmark
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.launch

/** The two ways in: the browser password, or a code minted by a browser session. */
private enum class AuthMode { Password, Pairing }

/**
 * Upper bound on the code field. The code itself is 8 characters, but a pasted
 * one may still carry the separators the server strips (spaces/hyphens/dots/
 * underscores), so leave room for them rather than truncating a valid paste —
 * normalisation is the server's call, not ours.
 */
private const val CODE_FIELD_MAX = 12

/**
 * First-launch / re-auth screen. Two modes:
 * - Password  -> client.login()  (direct-to-server deployments)
 * - Pairing   -> client.pair()   (server behind an SSO proxy, where the browser
 *                                 login page is unreachable from the app)
 */
@Composable
fun LoginScreen(
    client: TetherClient,
    versionMismatch: ConnectionState.VersionMismatch? = null,
) {
    val t = LocalTetherTokens.current
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(AuthMode.Password) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val deviceLabel = remember { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device" }

    fun versionCopy(required: Int) =
        "This server speaks protocol v$required, which this app does not. Update the app to connect."

    fun connect() {
        if (busy) return
        val url = baseUrl.trim()
        if (url.isEmpty()) {
            error = "Enter the server URL."
            return
        }
        if (mode == AuthMode.Pairing && code.isBlank()) {
            error = "Enter the pairing code from your browser."
            return
        }
        busy = true
        error = null
        scope.launch {
            error = when (mode) {
                AuthMode.Password -> when (val result = client.login(url, password)) {
                    is LoginResult.Success -> null
                    is LoginResult.BadPassword -> result.message.ifBlank { "That password was not accepted." }
                    is LoginResult.RateLimited -> result.message.ifBlank { "Too many attempts — wait a moment and try again." }
                    is LoginResult.Unreachable -> result.message.ifBlank { "Could not reach the server." }
                    is LoginResult.VersionMismatch -> versionCopy(result.requiredVersion)
                }
                AuthMode.Pairing -> when (val result = client.pair(url, code, deviceLabel)) {
                    is PairResult.Success -> null
                    is PairResult.Rejected -> result.message.ifBlank { "That pairing code is not valid or has expired." }
                    is PairResult.RateLimited -> result.message.ifBlank { "Too many pairing attempts — wait a few minutes." }
                    is PairResult.NotSupported -> result.message.ifBlank { "This server does not support device pairing." }
                    is PairResult.Unreachable -> result.message.ifBlank { "Could not reach the server." }
                    is PairResult.VersionMismatch -> versionCopy(result.requiredVersion)
                }
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.mineral)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandMark()
            Wordmark()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "PRIVATE RUNTIME",
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = TetherWeights.heading,
            fontSize = 9.9.sp,
            letterSpacing = 0.11.em,
        )
        Spacer(Modifier.height(32.dp))

        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Connect to your Tether server",
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 16.8.sp,
            )

            // Mode picker: selected = the primary (accent) key, unselected stays
            // a neutral key face. Selection is carried by the label too, never
            // by colour alone (visual-spec §2.2).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TetherKey(
                    onClick = { if (!busy) { mode = AuthMode.Password; error = null } },
                    modifier = Modifier.weight(1f),
                    variant = if (mode == AuthMode.Password) KeyVariant.Primary else KeyVariant.Secondary,
                    label = "Password",
                    enabled = !busy,
                )
                TetherKey(
                    onClick = { if (!busy) { mode = AuthMode.Pairing; error = null } },
                    modifier = Modifier.weight(1f),
                    variant = if (mode == AuthMode.Pairing) KeyVariant.Primary else KeyVariant.Secondary,
                    label = "Pairing code",
                    enabled = !busy,
                )
            }

            TetherInputWell(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "https://tether.example.com",
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            when (mode) {
                AuthMode.Password -> TetherInputWell(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Password",
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                AuthMode.Pairing -> {
                    TetherInputWell(
                        value = code,
                        // Upper-case as typed (the code alphabet is upper-case
                        // only); everything else — separators, U→V — is left to
                        // the server so the two can never disagree.
                        onValueChange = { code = it.uppercase().take(CODE_FIELD_MAX) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "8-character code",
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                        ),
                        fontFamily = JetBrainsMono,
                        letterSpacing = 0.18.em,
                    )
                    Text(
                        text = "Open Tether in a browser, choose “Pair a device”, and type the code it shows. " +
                            "It is valid for five minutes and can be used once.",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 12.5.sp,
                    )
                }
            }

            versionMismatch?.let {
                Text(
                    text = "This server requires protocol v${it.requiredVersion}. Update the app, then reconnect.",
                    color = t.warning,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.8.sp,
                )
            }
            error?.let {
                Text(
                    text = it,
                    color = t.danger,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.8.sp,
                )
            }

            TetherKey(
                onClick = { connect() },
                modifier = Modifier.fillMaxWidth(),
                variant = KeyVariant.Primary,
                label = when {
                    busy && mode == AuthMode.Pairing -> "Pairing…"
                    busy -> "Connecting…"
                    mode == AuthMode.Pairing -> "Pair device"
                    else -> "Connect"
                },
                enabled = !busy,
                showSlit = true,
            )
        }
    }
}
