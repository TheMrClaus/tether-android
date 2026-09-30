package com.tether.app.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.tether.app.client.ConnectionState
import com.tether.app.client.OverviewFeedGate
import com.tether.app.client.TetherClient
import com.tether.app.protocol.overview.OverviewClient
import com.tether.app.ui.components.rememberTickingNow

/**
 * T15.2: the Overview wired to the client — overview.tsx's own state and effects:
 *
 * - the feed is subscribed only while this is composed AND the app is started (ON_START..ON_STOP,
 *   the native `document.hidden`), through [OverviewFeedGate] (T15.1); a filter, page or server
 *   change re-subscribes; leaving (disposal) or ON_STOP unsubscribes;
 * - a filter change returns to the first page (overview.tsx:99-103);
 * - requests that ARRIVE while the operator watches are announced politely (overview.tsx:132-148).
 *
 * [choice] is hoisted to the shell so returning to the Overview keeps it (overview.tsx:42).
 * Read-only: the only frames this causes are `overview-subscribe` / `overview-unsubscribe`.
 */
@Composable
fun OverviewHost(
    client: TetherClient,
    choice: OverviewChoice,
    onChoice: (OverviewChoice) -> Unit,
    actions: OverviewActions,
    modifier: Modifier = Modifier,
    hostUsage: (@Composable () -> Unit)? = null,
) {
    val state by client.overview.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()
    val server by client.serverUrl.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var page by rememberSaveable { mutableIntStateOf(0) }

    val gate = remember(client) { OverviewFeedGate(client) }
    val subscription = OverviewPresentation.subscriptionFor(choice, page)
    LaunchedEffect(gate, started, subscription, server) { gate.update(visible = true, started = started, subscription = subscription, server = server) }
    DisposableEffect(gate) { onDispose { gate.leave() } }

    var announced by remember { mutableStateOf<Set<String>?>(null) }
    var announcement by remember { mutableStateOf("") }
    val panel = state.data?.pending
    LaunchedEffect(panel) {
        panel ?: return@LaunchedEffect
        OverviewPresentation.announcement(announced, panel.items)?.let { announcement = it }
        announced = panel.items.mapTo(HashSet()) { OverviewClient.pendingKey(it) }
    }

    OverviewScreen(
        state = state,
        connected = connection == ConnectionState.Connected,
        choice = choice,
        onChoice = { next ->
            if (next != choice) page = 0
            onChoice(next)
        },
        page = state.data?.page ?: page,
        onPage = { page = it },
        now = rememberTickingNow(),
        actions = actions,
        modifier = modifier,
        announcement = announcement,
        hostUsage = hostUsage,
    )
}
