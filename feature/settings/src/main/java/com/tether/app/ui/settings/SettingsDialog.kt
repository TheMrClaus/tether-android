package com.tether.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.ConnectionState
import com.tether.app.client.ConfirmedEngineWrite
import com.tether.app.client.ProvidersList
import com.tether.app.client.ServerSettingsView
import com.tether.app.client.TetherClient
import com.tether.app.client.serverOrigin
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.protocol.ClientMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of the dialog's parts. */
object SettingsDialogTags {
    const val Dialog = "settings-dialog"
    const val Tabs = "settings-tabs"
    const val Body = "settings-body"
    const val RestartBanner = "settings-restart-banner"
    const val Save = "settings-save"
    const val Cancel = "settings-cancel"
    const val Close = "settings-close"
    fun tab(tab: SettingsTab) = "settings-tab-${tab.id}"
    fun panel(tab: SettingsTab) = "settings-panel-${tab.id}"
}

/** Tags of the shared parts in SettingsKit.kt. */
internal object SettingsTags {
    const val TipBubble = "settings-tip-bubble"
    const val ComingSoon = "settings-coming-soon"
    const val EnvLock = "settings-env-lock"
}

/**
 * The open dialog's own state: the tab on show and General's Save draft. Both survive a rotation;
 * neither outlives the dialog (a fresh open starts on General from the stored preferences, as the
 * web's `onClose` resets both). [draft] is null until the stored preferences have been read once.
 */
@Stable
class SettingsDialogState(tab: SettingsTab = SettingsTab.General, draft: GeneralDraft? = null) {
    var tab by mutableStateOf(tab)
    var draft by mutableStateOf(draft)

    /** False until the stored preferences have been read once: General cannot be edited or saved. */
    val ready: Boolean get() = draft != null

    /** The draft, or (before the first read lands) the live value it will be seeded from, shown only. */
    fun draftOr(live: TetherPreferences): GeneralDraft = draft ?: GeneralDraft.of(live)

    /**
     * An edit of the draft. Before the first read it does nothing: a draft started from the
     * defaults would make Save write defaults over the stored fields the operator never touched.
     */
    fun edit(change: (GeneralDraft) -> GeneralDraft) {
        draft = draft?.let(change)
    }

    companion object {
        val Saver: Saver<SettingsDialogState, Any> = listSaver(
            save = { s ->
                val d = s.draft
                if (d == null) listOf(s.tab.id) else listOf(s.tab.id, d.defaultWorkspace, d.showEndedSessions, d.confirmBeforeEnd, d.showThinking)
            },
            restore = { v ->
                val tab = SettingsTab.entries.firstOrNull { it.id == v[0] } ?: SettingsTab.General
                val draft = if (v.size == 5) GeneralDraft(v[1] as String, v[2] as Boolean, v[3] as Boolean, v[4] as Boolean) else null
                SettingsDialogState(tab, draft)
            },
        )
    }
}

/**
 * T10.1 (components/settings-dialog.tsx): Settings, opened by the top bar and the sidebar footer.
 * On a phone it fills the screen; on an expanded window it is the web's centred dialog. Like the
 * web's `<dialog>`, a tap on the backdrop does nothing; Back (the web's Esc), Close and Cancel
 * drop the draft, Save settings writes it.
 *
 * [currentWorkspace] is the sidebar's current workspace (use-tether.ts `currentWorkspace`), what
 * General's "Use current" takes. Opening asks for the server settings (dashboard.tsx:1332-1334
 * `openFullSettings`), whose `restartRequired` drives the banner.
 */
@Composable
fun SettingsDialog(
    client: TetherClient,
    prefs: UiPrefs,
    currentWorkspace: String,
    onDismiss: () -> Unit,
) {
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()
    LaunchedEffect(connection) { if (connection == ConnectionState.Connected) client.requestServerSettings() }
    val state = rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState() }
    // ta-9q2: Claude accounts are read for the server signed in to, and only for it.
    val server by client.serverUrl.collectAsStateWithLifecycle()
    val configured by client.configured.collectAsStateWithLifecycle()
    val claudeAccounts = ClaudeAccountsBinding(client.claudeAccounts, if (configured) serverOrigin(server) else null)
    // ta-t7l: Advanced and Metadata, read from the client's per-server frames and written back to
    // the server they were drawn from (the client refuses a write once the socket is another's).
    val advanced by client.advancedSettings.collectAsStateWithLifecycle()
    LaunchedEffect(connection) { if (connection == ConnectionState.Connected) client.requestAdvancedSettings() }
    val writer = remember(client) { ClientSettingsWriter(client) }
    val freshSettings: () -> ServerSettingsView? = remember(client) { { client.serverSettings.value?.let(ServerSettingsView::of) } }
    val origin = if (configured) serverOrigin(server) else null
    val replies by client.serverSettingsReplies.collectAsStateWithLifecycle()
    // ta-dh1 (ta-cc5, the engines part): the frame is read once per frame, not per recomposition.
    val signedIn = origin != null
    val view = remember(serverSettings, signedIn) { serverSettings?.takeIf { signedIn }?.let(ServerSettingsView::of) }
    val serverBinding = ServerSettingsBinding(
        // r2: signed out (no origin), no settings: the client also drops the frames then.
        settings = view,
        advanced = advanced,
        origin = origin,
        writer = writer,
        replies = replies,
        // r2: a confirmed write is built from the client's newest frame, never a composed one behind it.
        fresh = freshSettings,
    )
    // ta-q6p: the custom-providers registry, asked for on open (the web asks on connect); every
    // write goes through the client's set-providers check, built from its newest list.
    val profiles by client.providerProfiles.collectAsStateWithLifecycle()
    LaunchedEffect(connection) { if (connection == ConnectionState.Connected) client.requestProviders() }
    val providersWriter = remember(client) {
        object : ProvidersWriter {
            override fun setProviders(write: com.tether.app.client.ProvidersWrite, origin: String) = client.setProviders(write, origin)
            override fun status() = client.providersWriteStatus()
        }
    }
    val freshProfiles: () -> ProvidersList? = remember(client) { { client.providerProfiles.value } }
    val providersBinding = ProvidersBinding(
        list = profiles?.takeIf { signedIn },
        origin = origin,
        writer = providersWriter,
        fresh = freshProfiles,
    )
    // T10.3: the node registry (sent to every principal on hello, secret-free) and the requests
    // the Nodes panel makes, held here so a tab change neither cancels one nor loses its answer.
    val nodeList by client.nodes.collectAsStateWithLifecycle()
    val consoleProtocol by client.serverProtocolVersion.collectAsStateWithLifecycle()
    val nodesWriter = remember(client) { ClientNodesWriter(client) }
    val nodeActions = rememberNodesActions(nodesWriter)
    val nodesBinding = NodesBinding(
        list = if (signedIn) nodeList else emptyList(),
        origin = origin,
        actions = nodeActions,
        consoleProtocol = consoleProtocol,
    )
    // T10.4: one controller per server, held here (a tab change keeps a call and the code on
    // screen; closing Settings drops them, the code's clipboard copy with it).
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairingClipboard = remember(context) { AndroidPairingClipboard.forApp(context) }
    // T10.5: passkey registration runs on this activity's Credential Manager prompt.
    val activity = context.findActivity()
    val passkeys = remember(activity) { com.tether.app.client.CredentialManagerPasskeys { activity } }
    val devicesController = rememberDevicesController(client.deviceSecurity, origin, clipboard = pairingClipboard, authenticator = passkeys)
    val devicesBinding = DevicesBinding(devicesController)
    val layout = currentLayoutClass()
    Dialog(onDismissRequest = onDismiss, properties = SettingsDialogProperties) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        val progress = rememberDialogIn()
        SettingsFrame(
            prefs = prefs,
            state = state,
            restartRequired = serverSettings?.restartRequired == true,
            currentWorkspace = currentWorkspace,
            onClose = onDismiss,
            layout = layout,
            claudeAccounts = claudeAccounts,
            serverSettings = serverBinding,
            providers = providersBinding,
            nodes = nodesBinding,
            devices = devicesBinding,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            surfaceModifier = Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            },
        )
    }
}

/**
 * The dialog in place (the inline form the goldens shoot): on [TetherLayoutClass.Phone] the case
 * fills the window; on [TetherLayoutClass.Expanded] it sits centred on the skin's scrim at
 * `min(880px, 100vw - 48px)` (studio.css 556), at most `100dvh - 48px` tall (511).
 */
@Composable
fun SettingsFrame(
    prefs: UiPrefs,
    state: SettingsDialogState,
    restartRequired: Boolean,
    currentWorkspace: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    layout: TetherLayoutClass = currentLayoutClass(),
    /**
     * The stored preferences when the caller has already read them (the screenshot harness, with
     * a [state] seeded from the same read): the first frame is then the loaded dialog. Null (the
     * app): the frame reads them itself and General waits for that read.
     */
    initialPreferences: TetherPreferences? = null,
    claudeAccounts: ClaudeAccountsBinding = ClaudeAccountsBinding.None,
    /** ta-t7l: the Advanced and Metadata tabs' server settings; its `restartRequired` also raises the banner. */
    serverSettings: ServerSettingsBinding = ServerSettingsBinding.None,
    /** ta-q6p: the Engines tab's Custom providers editor. */
    providers: ProvidersBinding = ProvidersBinding.None,
    /** T10.3: the Nodes tab. */
    nodes: NodesBinding = NodesBinding.None,
    /** T10.4: the Devices tab's sign-in security and paired devices. */
    devices: DevicesBinding = DevicesBinding.None,
) {
    val t = LocalTetherTokens.current
    val live by prefs.preferences.collectAsStateWithLifecycle(initialValue = initialPreferences)
    // settings-dialog.tsx:1935: the draft starts as the stored preferences (read once, so a value
    // that lands later never overwrites an edit).
    LaunchedEffect(prefs) { if (state.draft == null) state.draft = GeneralDraft.of(prefs.preferences.first()) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val save: () -> Unit = save@{
        if (saving) return@save
        // Never before the first read (see [SettingsDialogState.edit]).
        val draft = state.draft ?: return@save
        saving = true
        // Written before the dialog closes. Storage that refuses the write closes the dialog all the
        // same (the web's localStorage save is best effort too); nothing is half-applied, the model
        // is written in one edit. ta-t7l r3: the write is NonCancellable, so the dialog leaving
        // composition while it is in flight (Back during a slow write) cannot drop it.
        scope.launch {
            withContext(NonCancellable) { runCatching { prefs.updatePreferences(draft::applyTo) } }
            saving = false
            onClose()
        }
    }
    val narrow = layout == TetherLayoutClass.Phone
    val backdrop = if (narrow) t.graphite else dialogScrim(t)
    BoxWithConstraints(Modifier.fillMaxSize().background(backdrop).then(modifier), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(if (narrow) 0.dp else StudioDialog.radius)
        val caseModifier = if (narrow) {
            Modifier.fillMaxSize()
        } else {
            Modifier.width(minOf(880.dp, maxWidth - 48.dp)).heightIn(max = maxHeight - 48.dp)
        }
        val bodyMin = if (narrow) 0.dp else minOf(460.dp, maxHeight * 0.5f)
        Column(
            surfaceModifier
                .testTag(SettingsDialogTags.Dialog)
                .semantics { paneTitle = "Settings" }
                .then(caseModifier)
                .cssSurface(shape, t.graphite, null, if (narrow) emptyList() else StudioDialog.shadows)
                .clip(shape)
                // The <dialog> swallows taps: only Back, Close, Cancel and Save dismiss it. A plain
                // gesture sink, not a clickable, so the panels' text is never merged into one node.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            SettingsHeader(narrow, onClose)
            SettingsTabStrip(state.tab, narrow, onSelect = { state.tab = it })
            Column(
                Modifier
                    .testTag(SettingsDialogTags.Body)
                    .weight(1f, fill = narrow)
                    .heightIn(min = bodyMin)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // settings-dialog.tsx:1998: the server's own `restartRequired`, as its last reply says.
                if (restartRequired || serverSettings.settings?.restartRequired == true) RestartBanner(narrow)
                Box(Modifier.testTag(SettingsDialogTags.panel(state.tab))) {
                    SettingsPanel(
                        tab = state.tab,
                        prefs = prefs,
                        live = live ?: TetherPreferences.Default,
                        state = state,
                        currentWorkspace = currentWorkspace,
                        narrow = narrow,
                        claudeAccounts = claudeAccounts,
                        serverSettings = serverSettings,
                        providers = providers,
                        nodes = nodes,
                        devices = devices,
                    )
                }
            }
            SettingsFooter(narrow, saving || !state.ready, onCancel = onClose, onSave = save)
        }
    }
}

/** `.settings-dialog > header` (studio.css 522-537, 960-964): "Settings" and Close over a rule. */
@Composable
private fun SettingsHeader(narrow: Boolean, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (narrow) 76.dp else 88.dp)
                .padding(horizontal = if (narrow) 20.dp else 28.dp, vertical = if (narrow) 18.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Settings",
                color = t.white,
                style = settingsText(type.ui, if (narrow) 20f else 22f, 700, lineHeight = 1.3f, trackingEm = -0.025f),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TetherKey(
                onClick = onClose,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 19.dp,
                contentDescription = "Close",
                modifier = Modifier.testTag(SettingsDialogTags.Close),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/**
 * `.settings-tabs` (settings-dialog.tsx:1980-1996; globals.css 3002-3035 and 10769-10797 under
 * studio.css 557-565, 968): an inset strip (12dp above, 24dp in from the case, 12dp on a phone)
 * in a 1px `--line-strong` edge at `--radius-key`; one row that never wraps. Each tab is a 44dp pill
 * (`flex: 1 0 auto`: its own width plus an equal share of what is left), the selected one in the
 * violet wash; when they do not fit (a phone) the row scrolls sideways. Tabs announce as tabs
 * with their selection.
 */
@Composable
private fun SettingsTabStrip(selected: SettingsTab, narrow: Boolean, onSelect: (SettingsTab) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusKey)
    val padH = if (narrow) 12.dp else 24.dp
    val padV = if (narrow) 10.dp else 12.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(start = if (narrow) 12.dp else 24.dp, end = if (narrow) 12.dp else 24.dp, top = 12.dp),
    ) {
        val inner = maxWidth - 2.dp - padH * 2
        Layout(
            modifier = Modifier
                .testTag(SettingsDialogTags.Tabs)
                .fillMaxWidth()
                .cssSurface(shape, t.graphite, CssBorder(1.dp, t.lineStrong), emptyList())
                .clip(shape)
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .padding(horizontal = padH, vertical = padV),
            content = {
                SettingsTab.entries.forEach { tab ->
                    val active = tab == selected
                    Box(
                        Modifier
                            .testTag(SettingsDialogTags.tab(tab))
                            .heightIn(min = 44.dp)
                            .background(if (active) t.violetWash else Color.Transparent, RoundedCornerShape(8.dp))
                            .selectable(
                                selected = active,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(tab) },
                            )
                            .padding(horizontal = if (narrow) 12.dp else 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tab.label,
                            color = if (active) t.violetStrong else t.muted,
                            maxLines = 1,
                            style = settingsText(type.ui, 13f, 650),
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val gap = 4.dp.roundToPx()
            val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
            val used = natural.sum() + gap * (measurables.size - 1)
            val extra = ((inner.roundToPx() - used).coerceAtLeast(0)) / measurables.size.coerceAtLeast(1)
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(natural[i] + extra)) }
            val height = placeables.maxOf { it.height }
            val width = placeables.sumOf { it.width } + gap * (placeables.size - 1)
            layout(width, height) {
                var x = 0
                placeables.forEach { p ->
                    p.place(x, (height - p.height) / 2)
                    x += p.width + gap
                }
            }
        }
    }
}

/**
 * `.settings-restart-banner` (settings-dialog.tsx:1998-2002; globals.css 3071-3078, studio.css
 * 612-621): the attention wash with its ink, a RefreshCw glyph and the web's sentence. A status
 * region, so it is announced when it appears.
 */
@Composable
private fun RestartBanner(narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .testTag(SettingsDialogTags.RestartBanner)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = if (narrow) 20.dp else 24.dp, end = if (narrow) 20.dp else 24.dp, top = 16.dp)
            .fillMaxWidth()
            .background(t.attentionBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TetherIcons.RefreshCw, contentDescription = null, tint = t.attentionInk, modifier = Modifier.size(14.dp))
        Text(
            RESTART_REQUIRED,
            color = t.attentionInk,
            style = settingsText(type.ui, 13f, 600, lineHeight = 1.6f),
        )
    }
}

/** `.settings-dialog > footer` (settings-dialog.tsx:2456; studio.css 538-545, 965-967). */
@Composable
private fun SettingsFooter(narrow: Boolean, saveBlocked: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    val t = LocalTetherTokens.current
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (narrow) 20.dp else 28.dp, vertical = if (narrow) 16.dp else 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TetherKey(
                onClick = onCancel,
                classes = KeyClasses.ButtonSecondary,
                label = "Cancel",
                modifier = Modifier.testTag(SettingsDialogTags.Cancel),
            )
            TetherKey(
                onClick = onSave,
                classes = KeyClasses.ButtonPrimary,
                label = "Save settings",
                icon = TetherIcons.Check,
                iconSize = 17.dp,
                enabled = !saveBlocked,
                modifier = Modifier.testTag(SettingsDialogTags.Save),
            )
        }
    }
}

/** `dialog-in`: 0 -> 1 over `--duration` with `--ease-out`; at once under reduced motion. */
@Composable
private fun rememberDialogIn(): Animatable<Float, *> {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) progress.animateTo(1f, tween(t.css.duration, easing = t.css.easeOut.toEasing()))
    }
    return progress
}

/**
 * The dialog's window. r2: FLAG_SECURE (SecureOn) for the whole dialog, since its Advanced tab can
 * reveal the server's password and proxy token: no screenshot, screen recording or Recents
 * snapshot holds them.
 */
internal val SettingsDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
    securePolicy = SecureFlagPolicy.SecureOn,
)

/** settings-dialog.tsx:2000, the banner's words. */
const val RESTART_REQUIRED = "Some changes need a server restart to take effect."

/** T10.3: the app's [NodesWriter]: the client's origin-bound, requestId-matched node requests. */
private class ClientNodesWriter(private val client: TetherClient) : NodesWriter {
    override suspend fun add(origin: String, credential: com.tether.app.client.NodeCredential, label: String?, baseUrl: String?) =
        client.addNode(origin, credential, label, baseUrl)
    override suspend fun probe(origin: String, nodeId: String) = client.probeNode(origin, nodeId)
    override suspend fun remove(origin: String, nodeId: String) = client.removeNode(origin, nodeId)
}

/** The app's [ServerSettingsWriter]: the client's origin-bound settings writes. */
private class ClientSettingsWriter(private val client: TetherClient) : ServerSettingsWriter {
    override fun patch(patch: JsonObject, origin: String) = client.setServerSettings(patch, origin)
    override fun cliVersion(message: ClientMessage.SetAdvancedSettings, origin: String) = client.setAdvancedSettings(message, origin)
    override fun detectEngines(origin: String) = client.detectEngines(origin)
    override fun confirmed(write: ConfirmedEngineWrite, origin: String) = client.setConfirmedEngineValue(write, origin)
}
