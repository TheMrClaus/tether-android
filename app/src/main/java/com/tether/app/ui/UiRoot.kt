package com.tether.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.tether.app.nav.DeepLinkIntents
import com.tether.app.nav.NavContext
import com.tether.app.nav.NavEffect
import com.tether.app.nav.NavigationViewModel
import com.tether.app.nav.SessionLinkOpener
import com.tether.app.ui.chat.CustomTabLinkOpener
import com.tether.app.ui.chat.LocalLinkOpener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.app.client.ConnectionState
import com.tether.app.client.TetherClient
import com.tether.app.push.ForegroundState
import com.tether.app.push.PushScope
import com.tether.app.push.rememberNotificationPermission
import com.tether.app.ui.compat.CompatibilityBanner
import com.tether.app.ui.localnet.LocalNetworkExplainDialog
import com.tether.app.ui.localnet.LocalNetworkNotice
import com.tether.app.ui.localnet.LocalNetworkPhase
import com.tether.app.ui.localnet.LocalNetworkSource
import com.tether.app.ui.localnet.rememberLocalNetworkPrompt
import com.tether.app.ui.prefs.LoginVariant
import com.tether.app.ui.prefs.DataStoreDraftStore
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice

/**
 * Single UI entry point. MainActivity calls UiRoot(ClientLocator.obtain(this)).
 */
@Composable
fun UiRoot(client: TetherClient, launchIntent: Intent? = null) {
    val context = LocalContext.current
    val prefs = remember { UiPrefs(context) }
    val themeChoice by prefs.themeChoice.collectAsStateWithLifecycle(initialValue = ThemeChoice.Default)

    val vm: TetherViewModel = viewModel(
        factory = remember(client) { TetherViewModelFactory(client, DataStoreDraftStore(context)) },
    )

    val configured by client.configured.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()

    // T4.4: every link (the tether:// filter, an http(s) link to the paired server, a
    // notification tap, a session link in a chat) goes through one navigator. A
    // notification tap only opens the app: the server's FCM payload is id-free, so a
    // session in a push intent could only come from another app (T12.1 H1).
    val navigator = viewModel<NavigationViewModel>().navigator
    var inputGuard by remember { mutableStateOf(false) }
    var guardSerial by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val applyNav: (NavEffect?) -> Unit = { effect ->
        when (effect) {
            is NavEffect.Open -> {
                // Focus (and with it the keyboard's input session) does not follow a link into
                // the new session: typing aimed at the previous composer stops here.
                focusManager.clearFocus(force = true)
                vm.openSession(effect.sessionId)
                guardSerial++
            }
            is NavEffect.Notice -> vm.reportLocalError(effect.text)
            null -> Unit
        }
    }
    LaunchedEffect(launchIntent) {
        launchIntent ?: return@LaunchedEffect
        client.storedSettingsLoaded.first { it }
        applyNav(navigator.offer(DeepLinkIntents.parse(launchIntent, client.serverUrl.value), navContextOf(client)))
    }
    LaunchedEffect(client, navigator) {
        client.storedSettingsLoaded.first { it }
        merge(client.configured, client.connection, client.serverUrl, client.sessions)
            .collect { applyNav(navigator.step(navContextOf(client))) }
    }
    // dashboard.tsx selectActiveId: an explicit selection retires a waiting link.
    LaunchedEffect(vm, navigator) {
        vm.selectedSessionId.drop(1).collect { if (it != null) navigator.onUserSelection() }
    }
    // A switch made by a link swallows touches and hardware keys briefly, so input aimed at
    // the previous session cannot land on the new one's controls (another window can fire a
    // link). Each switch restarts the window.
    LaunchedEffect(guardSerial) {
        if (guardSerial == 0) return@LaunchedEffect
        inputGuard = true
        delay(NAV_INPUT_GUARD_MS)
        inputGuard = false
    }
    val linkOpener = remember(client, navigator) {
        SessionLinkOpener(
            delegate = CustomTabLinkOpener,
            pairedBaseUrl = { client.serverUrl.value },
            openInApp = { link -> applyNav(navigator.offer(link, navContextOf(client))) },
        )
    }

    // Android 13+ POST_NOTIFICATIONS: asked once, automatically, after sign-in
    // while notifications are on (UiPrefs default). A denial is never re-asked
    // from here; the settings toggles (T12.2) use notificationPermission.onAllow().
    val notificationPermission = rememberNotificationPermission(prefs)
    val pushEnabled by prefs.pushEnabled.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(configured, pushEnabled, notificationPermission.asked, notificationPermission.granted) {
        notificationPermission.autoRequestIfDue(signedIn = configured, pushEnabled = pushEnabled)
    }

    // Track the currently-selected session's push tag so the FCM service can
    // suppress a notification the user is already looking at. The tag is the
    // shared payload's `tether-<kind>-<sha24>`; the app does not know the
    // server's hash inputs, so for v1 we record a coarse "foreground + any
    // selected session" signal. The session-level filter is a follow-up.
    val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
    LaunchedEffect(selectedId) {
        ForegroundState.activeTag = if (selectedId != null) "fg:$selectedId" else null
    }

    // Open the connection loop once credentials exist; re-kick when regained.
    // A fresh credential also retires the last logout's notice.
    LaunchedEffect(configured) {
        if (configured) {
            vm.dismissLogoutNotice()
            client.start()
        }
    }

    // PLAN D12 / app/login/page.tsx: Retro is the opt-in, otherwise the theme
    // family picks the screen. There is no Studio family in ThemeChoice yet
    // (the Studio skins arrive with the design-system phase), so Instrument is
    // what every current family gets — exactly the web's mapping for them.
    val loginVariant by prefs.loginVariant.collectAsStateWithLifecycle(initialValue = LoginVariant.Instrument)
    val studioFamily = false
    val logoutNotice by vm.logoutNotice.collectAsStateWithLifecycle()

    // Android 17 local-network permission. The client reports LocalNetworkBlocked
    // only for a server on the local network, so remote servers never get here.
    // The retry is reconnectIfIdle(), which re-evaluates the grant.
    val localNetwork = rememberLocalNetworkPrompt(prefs)
    LaunchedEffect(connection) {
        if (connection is ConnectionState.LocalNetworkBlocked) {
            localNetwork.onBlocked(LocalNetworkSource.Connection) { client.reconnectIfIdle() }
        } else {
            localNetwork.clear(LocalNetworkSource.Connection)
        }
    }

    // Foreground/background is process-wide (ProcessLifecycleOwner, wired in
    // TetherApp): client.setAppForeground re-checks the link on return.

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
        val phase = localNetwork.model.phase
        val needsSetup = !configured || connection is ConnectionState.AuthRequired
        // D5: an incompatible server does not lock the user out: the shell (and
        // what it last showed) stays, with a banner saying which side to update.
        val mismatch = (connection as? ConnectionState.VersionMismatch)?.takeIf { !needsSetup }
        val denied = phase is LocalNetworkPhase.Denied
        Column(Modifier.fillMaxSize().background(tokens.mineral)) {
            // Persistent notices, above whichever screen is showing. The first
            // one clears the status bar.
            if (phase is LocalNetworkPhase.Denied) {
                LocalNetworkNotice(
                    canRequest = phase.canRequest,
                    onAllow = localNetwork::onAllow,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                )
            }
            if (mismatch != null) {
                CompatibilityBanner(
                    incompatibility = mismatch.incompatibility,
                    onRetry = client::retryConnection,
                    modifier = Modifier.fillMaxWidth().then(if (denied) Modifier else Modifier.statusBarsPadding()),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onPreviewKeyEvent { inputGuard }
                    // A notice already cleared the status bar.
                    .then(if (denied || mismatch != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                if (needsSetup) {
                    LoginScreen(
                        client = client,
                        surface = loginSurfaceFor(loginVariant, studioFamily),
                        studioFamily = studioFamily,
                        logoutNotice = logoutNotice,
                        onLocalNetworkBlocked = { retry -> localNetwork.onBlocked(LocalNetworkSource.Login, retry) },
                        onLocalNetworkClear = { localNetwork.clear(LocalNetworkSource.Login) },
                    )
                } else {
                    CompositionLocalProvider(LocalLinkOpener provides linkOpener) {
                        MainShell(vm = vm, prefs = prefs)
                    }
                    if (inputGuard) NavInputGuard(Modifier.matchParentSize())
                }
            }
        }
        if (phase == LocalNetworkPhase.Explaining) {
            LocalNetworkExplainDialog(
                onContinue = localNetwork::onExplainContinue,
                onNotNow = localNetwork::onExplainDismissed,
            )
        }
    }
}

/** How long touches are swallowed after a link switched the session (see [NavInputGuard]). */
internal const val NAV_INPUT_GUARD_MS = 500L

internal const val NAV_INPUT_GUARD_TAG = "nav-input-guard"

/**
 * The client's state as the navigator reads it. The connection is read BEFORE the session list:
 * the client publishes the `ready` list before it reports Connected, so "connected and absent"
 * really means the server does not list the session.
 */
internal fun navContextOf(client: TetherClient): NavContext {
    val connection = client.connection.value
    return NavContext(
        signedIn = client.configured.value && connection !is ConnectionState.AuthRequired,
        serverUrl = client.serverUrl.value,
        connected = connection == ConnectionState.Connected,
        sessionIds = client.sessions.value.mapTo(HashSet()) { it.id },
    )
}

/** Consumes every pointer event over the shell while it is composed. Invisible, not focusable. */
@Composable
private fun NavInputGuard(modifier: Modifier) {
    Box(
        modifier
            .testTag(NAV_INPUT_GUARD_TAG)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}
