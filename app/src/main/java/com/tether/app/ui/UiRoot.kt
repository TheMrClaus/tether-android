package com.tether.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.app.client.ConnectionState
import com.tether.app.client.TetherClient
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice

/**
 * Single UI entry point. MainActivity calls UiRoot(ClientLocator.obtain(this)).
 */
@Composable
fun UiRoot(client: TetherClient) {
    val context = LocalContext.current
    val prefs = remember { UiPrefs(context) }
    val themeChoice by prefs.themeChoice.collectAsStateWithLifecycle(initialValue = ThemeChoice.System)

    val vm: TetherViewModel = viewModel(factory = remember(client) { TetherViewModelFactory(client) })

    val configured by client.configured.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()

    // Open the connection loop once credentials exist; re-kick when regained.
    LaunchedEffect(configured) {
        if (configured) client.start()
    }

    // Lifecycle: reconnect promptly when the app returns to the foreground.
    LifecycleResumeEffect(client) {
        client.reconnectIfIdle()
        onPauseOrDispose { }
    }

    // Network: reconnect the moment a default network comes back.
    DisposableEffect(client) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                client.reconnectIfIdle()
            }
        }
        try {
            manager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Missing permission or restricted context: reconnect still happens on resume.
        }
        onDispose {
            try {
                manager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    TetherTheme(choice = themeChoice) {
        val tokens = LocalTetherTokens.current
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(tokens.mineral)) {
            val needsSetup = !configured ||
                connection is ConnectionState.AuthRequired ||
                connection is ConnectionState.VersionMismatch
            if (needsSetup) {
                LoginScreen(
                    client = client,
                    versionMismatch = connection as? ConnectionState.VersionMismatch,
                )
            } else {
                MainShell(vm = vm, prefs = prefs)
            }
        }
    }
}
