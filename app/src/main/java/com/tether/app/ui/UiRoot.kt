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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalFocusManager
import com.tether.app.nav.DeepLinkIntents
import com.tether.app.nav.NavContext
import com.tether.app.nav.NavEffect
import com.tether.app.nav.NavigationViewModel
import com.tether.app.nav.SessionLinkOpener
import com.tether.app.ui.chat.CustomTabLinkOpener
import com.tether.app.ui.chat.LocalLinkOpener
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.app.client.ConnectionState
import com.tether.app.client.DefaultNetworkWatch
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
import com.tether.app.ui.setup.SetupWizard
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode

/**
 * Single UI entry point. MainActivity calls UiRoot(ClientLocator.obtain(this)).
 */
@Composable
fun UiRoot(client: TetherClient, launchIntent: Intent? = null) {
    val context = LocalContext.current
    val prefs = remember { UiPrefs(context) }
    // ta-coik.52: the server's own theme (the web's preferences are per origin), its sign-in page included.
    val themeMode by remember(prefs, client) { prefs.themeMode(client.serverUrl) }.collectAsStateWithLifecycle(initialValue = ThemeMode.Default)

    val vm: TetherViewModel = viewModel(
        factory = remember(client) { TetherViewModelFactory(client, DataStoreDraftStore(context)) },
    )

    val configured by client.configured.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()
    val serverUrl by client.serverUrl.collectAsStateWithLifecycle()

    // T4.4: every link (the tether:// filter, an http(s) link to the paired server, a
    // notification tap, a session link in a chat) goes through one navigator. A
    // notification tap only opens the app: the server's FCM payload is id-free, so a
    // session in a push intent could only come from another app (T12.1 H1).
    val navigator = viewModel<NavigationViewModel>().navigator
    val focusManager = LocalFocusManager.current
    val applyNav: (NavEffect?) -> Unit = { effect ->
        when (effect) {
            is NavEffect.Open -> {
                // Focus (and with it the keyboard's input session) does not follow a link into
                // the new session: the web remounts its ChatView per session (dashboard.tsx
                // `key={activeSession.id}`), so the previous composer's focus goes with it.
                // ta-coik.22: no timed input pause follows, as on the web; the chat's acting
                // controls drop a press that began on a control which has since changed
                // (StaleTapGuard, ta-coik.13).
                focusManager.clearFocus(force = true)
                vm.openSession(effect.sessionId)
            }
            is NavEffect.Notice -> vm.reportLocalError(effect.text)
            null -> Unit
        }
    }
    // T15.4 r2: the intent the app was launched with (a later one arrives through onNewIntent). A
    // link on it is a cold-start link: it wins the console's boot view (Sessions, nothing behind),
    // even while it waits for the stored settings and the session list.
    val bootIntent = remember { launchIntent }
    LaunchedEffect(launchIntent) {
        launchIntent ?: return@LaunchedEffect
        val atBoot = launchIntent === bootIntent && DeepLinkIntents.mayOpenSession(launchIntent)
        if (atBoot) vm.setBootLinkPending(true)
        client.storedSettingsLoaded.first { it }
        applyNav(navigator.offer(DeepLinkIntents.parse(launchIntent, client.serverUrl.value), navContextOf(client)))
        if (atBoot) vm.setBootLinkPending(navigator.pendingSessionId != null)
    }
    LaunchedEffect(client, navigator) {
        client.storedSettingsLoaded.first { it }
        merge(client.configured, client.connection, client.serverUrl, client.sessions)
            .collect {
                applyNav(navigator.step(navContextOf(client)))
                if (navigator.pendingSessionId == null) vm.setBootLinkPending(false)
            }
    }
    // T11.2: a share from another app waits in the inbox until the user is signed in and picks
    // where it goes (the chooser below, over the shell).
    val share by com.tether.app.share.ShareInbox.pending.collectAsStateWithLifecycle()
    val shareScope = androidx.compose.runtime.rememberCoroutineScope()
    val shareDelivery = remember(vm) { com.tether.app.share.ShareDelivery(vm) }
    fun deliverShare(id: Long, target: com.tether.app.share.ShareTarget) {
        val taken = com.tether.app.share.ShareInbox.take(id) ?: return
        if (target is com.tether.app.share.ShareTarget.Session) focusManager.clearFocus(force = true)
        shareScope.launch { shareDelivery.deliver(taken, target) }
    }
    // T6.2: downloaded tool-media clips belong to one sign-in on one server (ToolMediaCache).
    LaunchedEffect(client) { com.tether.app.ui.chat.syncToolMediaCache(client, context.cacheDir) }
    // dashboard.tsx selectActiveId: an explicit selection retires a waiting link (ta-coik.42: a pick,
    // not a pending target becoming listed).
    LaunchedEffect(vm, navigator) {
        vm.activeId.drop(1).collect {
            if (it != null) {
                navigator.onUserSelection()
                vm.setBootLinkPending(false)
            }
        }
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

    // T10.6: a typed server whose /healthz says `setupRequired: true` is set up here, in the wizard (the web's
    // /login redirects to /setup), and the address goes on to sign-in. Only the address is saved across
    // process death; what the wizard holds (the operator's password) is not.
    var setupUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var signInUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var autoOpenSetup by rememberSaveable { mutableStateOf(true) }

    // app/login/page.tsx: Retro is the opt-in layout, otherwise Studio's own sign-in.
    // ta-coik.52: the configured server's own choice, as the web's sign-in page reads its origin's.
    val loginVariant by remember(prefs, client) { prefs.loginVariant(client.serverUrl) }.collectAsStateWithLifecycle(initialValue = LoginVariant.Default)
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

    // Network: reconnect the moment a default network comes back. ta-coik.32 (R1): when the default
    // network CHANGED, a socket opened on the previous one is dead: it is replaced at once rather
    // than pinged for 8 s (the web's `online` only pings).
    DisposableEffect(client) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val watch = DefaultNetworkWatch<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (watch.available(network)) client.onDefaultNetworkChanged() else client.reconnectIfIdle()
            }

            override fun onLost(network: Network) {
                watch.lost(network)
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

    TetherTheme(mode = themeMode) {
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
                    // A notice already cleared the status bar.
                    .then(if (denied || mismatch != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                val wizardUrl = setupUrl
                if (needsSetup && wizardUrl != null) {
                    SetupWizard(
                        client = client,
                        baseUrl = wizardUrl,
                        // Done (or already done: a 401), or left: on to sign-in for that server, which is not
                        // sent straight back into the wizard it just left.
                        onSignIn = {
                            signInUrl = wizardUrl
                            autoOpenSetup = false
                            setupUrl = null
                        },
                        onCancel = {
                            signInUrl = wizardUrl
                            autoOpenSetup = false
                            setupUrl = null
                        },
                    )
                } else if (needsSetup) {
                    LoginScreen(
                        client = client,
                        surface = loginSurfaceFor(loginVariant),
                        logoutNotice = logoutNotice,
                        onLocalNetworkBlocked = { retry -> localNetwork.onBlocked(LocalNetworkSource.Login, retry) },
                        onLocalNetworkClear = { localNetwork.clear(LocalNetworkSource.Login) },
                        onSetupRequired = { url -> setupUrl = url },
                        initialBaseUrl = signInUrl,
                        autoOpenSetup = autoOpenSetup,
                    )
                } else {
                    CompositionLocalProvider(LocalLinkOpener provides linkOpener) {
                        MainShell(vm = vm, prefs = prefs)
                    }
                    share?.let { pending ->
                        val sessions by client.sessions.collectAsStateWithLifecycle()
                        com.tether.app.ui.share.ShareTargetDialog(
                            summary = com.tether.app.ui.share.ShareTargetCopy.summary(!pending.text.isNullOrEmpty(), pending.files.size),
                            sessions = com.tether.app.ui.share.shareTargets(sessions),
                            onNewSession = { deliverShare(pending.id, com.tether.app.share.ShareTarget.NewSession) },
                            onSession = { id -> deliverShare(pending.id, com.tether.app.share.ShareTarget.Session(id)) },
                            onDismiss = { com.tether.app.share.ShareInbox.discard(pending.id) },
                        )
                    }
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
