package com.tether.app.ui

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.ConnectionState
import com.tether.app.client.LoginResult
import com.tether.app.client.TetherClient
import com.tether.app.ui.components.BrandMark
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.Wordmark
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.launch

/** First-launch / re-auth screen: server URL + password -> client.login(). */
@Composable
fun LoginScreen(
    client: TetherClient,
    versionMismatch: ConnectionState.VersionMismatch? = null,
) {
    val t = LocalTetherTokens.current
    val scope = rememberCoroutineScope()

    var baseUrl by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun connect() {
        if (busy) return
        val url = baseUrl.trim()
        if (url.isEmpty()) {
            error = "Enter the server URL."
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = client.login(url, password)
            busy = false
            error = when (result) {
                is LoginResult.Success -> null
                is LoginResult.BadPassword -> result.message.ifBlank { "That password was not accepted." }
                is LoginResult.RateLimited -> result.message.ifBlank { "Too many attempts — wait a moment and try again." }
                is LoginResult.Unreachable -> result.message.ifBlank { "Could not reach the server." }
                is LoginResult.VersionMismatch ->
                    "This server speaks protocol v${result.requiredVersion}, which this app does not. Update the app to connect."
            }
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
            TetherInputWell(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "https://tether.example.com",
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TetherInputWell(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Password",
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

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
                label = if (busy) "Connecting…" else "Connect",
                enabled = !busy,
                showSlit = true,
            )
        }
    }
}
