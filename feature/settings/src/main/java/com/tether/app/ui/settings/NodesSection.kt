package com.tether.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.LabelText
import com.tether.app.client.NodeCredential
import com.tether.app.client.NodeRegistryRules
import com.tether.app.client.NodeRequestOutcome
import com.tether.app.client.TextCut
import com.tether.app.protocol.NodeSummary
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendSafe
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.util.relativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import com.tether.app.ui.state.rememberRetained

/*
 * T10.3: Settings -> Nodes (components/nodes-settings.tsx at 887c222, drawn by settings-dialog.tsx
 * :2104). The console's registry of peer Tether installs: register one by pasting its credential
 * bundle, probe it, remove it. The list arrives secret-free for every principal (`nodes`); the
 * three actions are node-add / node-probe / node-remove, each matched to its own `node-result` by
 * requestId in the client (TetherClient.addNode & co.), sent once, never queued or retried, and
 * only on a socket opened for the server this screen was drawn from.
 *
 * The credential bundle carries the peer's node BEARER. ta-coik.5: as on the web it is a plain
 * textarea (no mask, copy and cut work; a copy is marked sensitive), held in `rememberRetained` (memory only, kept through a rotation)
 * (never saved state, never a preference, never logged), sent only by Add node.
 */

/** Tags of the Nodes panel's parts. */
object NodeTags {
    const val Section = "nodes-section"
    const val Label = "nodes-add-label"
    const val BaseUrl = "nodes-add-base-url"
    const val Credential = "nodes-add-credential"
    const val Add = "nodes-add"
    const val Notice = "nodes-notice"
    const val Empty = "nodes-empty"
    fun row(nodeId: String) = "node:$nodeId"
    fun status(nodeId: String) = "node-status:$nodeId"
    fun skew(nodeId: String) = "node-skew:$nodeId"
    fun probe(nodeId: String) = "node-probe:$nodeId"
    fun remove(nodeId: String) = "node-remove:$nodeId"
}

/** The web's words (nodes-settings.tsx), and the app's own where the web has none. */
object NodesCopy {
    const val TITLE = "Nodes"
    const val CAPTION_HEAD = "Other Tether installs you can see from this console. Mint a credential on the other host ("
    const val CAPTION_CODE = "tether-control-token mint --kind node"
    const val CAPTION_TAIL = ") and paste its bundle below. Federated session listing and creation arrive in a later phase."
    const val ADD_HEADING = "Add a node"
    const val LABEL_PLACEHOLDER = "Label (e.g. workstation)"
    const val BASE_URL_PLACEHOLDER = "Base URL (optional — e.g. http://10.0.0.2:4173)"
    const val CREDENTIAL_PLACEHOLDER = "Credential bundle from the other host"
    const val CREDENTIAL_LABEL = "Credential bundle"
    const val ADD = "Add node"
    const val PROBE = "Probe"
    const val REMOVE = "Remove"
    const val DONE = "Done."
    const val FAILED = "That did not work."
    const val EMPTY = "No nodes yet. Registering another host lets this console reach the accounts and project " +
        "folders that live there — a private network (e.g. WireGuard) or a shared SSO host is the recommended way to connect them."
    fun probeLabel(label: String) = "Probe $label"
    fun removeLabel(label: String) = "Remove $label"
    fun lastSeen(relative: String) = "last seen $relative"
    fun skew(peer: Int, console: Int) =
        "This node speaks protocol v$peer; this console speaks v$console. Upgrade the older host before driving its sessions."

    // The app's own (the web has no busy state, and its send() cannot lose an answer).
    const val ADDING = "Adding…"
    const val PROBING = "Probing…"
    const val REMOVING = "Removing…"
    const val LINK_LOST = "The connection dropped before the server answered, so this may or may not have happened. The list shows what the server holds; nothing was sent again."
    const val TIMED_OUT = "The server has not answered yet. The list will show what it holds; nothing was sent again."
    const val TOO_LONG = "That credential is longer than the server accepts. Re-copy the bundle from the other host."
}

/**
 * nodes-settings.tsx `statusPresentation`: a status's words and glyph (never colour alone). Any
 * other value (a later server's) reads "Not probed yet", as on the web.
 */
enum class NodeStatusLook(val label: String, val warn: Boolean, val ok: Boolean) {
    Reachable("Reachable", warn = false, ok = true),
    Unreachable("Unreachable", warn = true, ok = false),
    Unauthorized("Credential rejected", warn = true, ok = false),
    IdentityMismatch("Identity mismatch", warn = true, ok = false),
    Skew("Protocol mismatch", warn = true, ok = false),
    Error("Error", warn = true, ok = false),
    NotProbed("Not probed yet", warn = false, ok = false),
    ;

    val icon: ImageVector get() = when {
        ok -> TetherIcons.Check
        warn -> TetherIcons.TriangleAlert
        else -> TetherIcons.Timer
    }

    companion object {
        fun of(status: String): NodeStatusLook = when (status) {
            "reachable" -> Reachable
            "unreachable" -> Unreachable
            "unauthorized" -> Unauthorized
            "identity_mismatch" -> IdentityMismatch
            "skew" -> Skew
            "error" -> Error
            else -> NotProbed
        }
    }
}

/** How a node's server-sent text is drawn (all of it untrusted). */
internal object NodeText {
    /** The server caps a base URL at 512 (lib/node-registry.mjs MAX_URL_LENGTH); a longer one is cut here. */
    const val MAX_URL = 512
    const val MAX_VERSION = 64

    /** The label by the label rule; one that cleans to nothing is the node id, spelled out. */
    fun label(node: NodeSummary): String =
        LabelText.title(node.label, LabelText.MAX_LABEL).ifEmpty { LabelText.visibleValue(node.nodeId) }

    /** The base URL's SOURCE, bounded (drawn by the line rule: hidden controls as visible tokens). */
    fun url(node: NodeSummary): String =
        if (node.baseUrl.length <= MAX_URL) node.baseUrl else TextCut.cut(node.baseUrl, MAX_URL - 1) + "…"

    fun version(node: NodeSummary): String? = node.peerVersion?.let { LabelText.clean(it, MAX_VERSION) }?.takeIf { it.isNotEmpty() }

    /** nodes-settings.tsx: `peerProtocolVersion !== null && peerProtocolVersion !== PROTOCOL_VERSION` (the console's). */
    fun skew(node: NodeSummary, console: Int?): Int? =
        node.peerProtocolVersion?.takeIf { console != null && it != console }
}

/**
 * Where the panel's requests go. The app's is the client ([com.tether.app.client.TetherClient.addNode]
 * & co.): one frame tagged with a fresh requestId, on a live socket opened for [origin] only, ended
 * by its own `node-result` (or a correlated `error`), the socket going, or the timeout.
 */
interface NodesWriter {
    suspend fun add(origin: String, credential: NodeCredential, label: String?, baseUrl: String?): NodeRequestOutcome
    suspend fun probe(origin: String, nodeId: String): NodeRequestOutcome
    suspend fun remove(origin: String, nodeId: String): NodeRequestOutcome

    /** No client (previews, a signed-out frame): nothing is ever sent. */
    object None : NodesWriter {
        override suspend fun add(origin: String, credential: NodeCredential, label: String?, baseUrl: String?) = NodeRequestOutcome.NotSent
        override suspend fun probe(origin: String, nodeId: String) = NodeRequestOutcome.NotSent
        override suspend fun remove(origin: String, nodeId: String) = NodeRequestOutcome.NotSent
    }
}

enum class NodeAction { Add, Probe, Remove }

/** The request in flight, for [origin]: [nodeId] is the node it names (null for Add). */
data class NodeBusy(val origin: String, val action: NodeAction, val nodeId: String?, internal val id: Long)

/**
 * The status line after an action (the web's `result`): [text] is the server's message cleaned by
 * the label rule (or the web's / the app's own words), for [origin]. [heldByServer]: an Add the
 * server answered naming a node (it now holds that node's bearer, whatever the probe said), so the
 * form lets go of the credential. Nothing secret is ever in it.
 */
data class NodeNotice(
    val origin: String,
    val action: NodeAction,
    val nodeId: String?,
    val ok: Boolean,
    val text: String,
    val heldByServer: Boolean,
    val serial: Long,
)

/** What an outcome says on the screen. Pure. */
internal object NodeOutcomes {
    fun notice(origin: String, action: NodeAction, nodeId: String?, outcome: NodeRequestOutcome, serial: Long): NodeNotice = when (outcome) {
        is NodeRequestOutcome.Answered -> {
            val r = outcome.result
            NodeNotice(
                origin, action, r.nodeId ?: nodeId, r.ok,
                LabelText.error(r.message).ifEmpty { if (r.ok) NodesCopy.DONE else NodesCopy.FAILED },
                heldByServer = action == NodeAction.Add && r.nodeId != null,
                serial = serial,
            )
        }
        // T6.7: the server's words, cleaned (a refusal that comes as an `error` frame, too).
        is NodeRequestOutcome.ServerError -> NodeNotice(origin, action, nodeId, false, LabelText.error(outcome.message).ifEmpty { NodesCopy.FAILED }, false, serial)
        is NodeRequestOutcome.Invalid -> NodeNotice(origin, action, nodeId, false, LabelText.error(outcome.message).ifEmpty { NodesCopy.FAILED }, false, serial)
        NodeRequestOutcome.NotSent -> NodeNotice(origin, action, nodeId, false, NodeRegistryRules.NOT_SENT_MESSAGE, false, serial)
        NodeRequestOutcome.LinkLost -> NodeNotice(origin, action, nodeId, false, NodesCopy.LINK_LOST, false, serial)
        NodeRequestOutcome.TimedOut -> NodeNotice(origin, action, nodeId, false, NodesCopy.TIMED_OUT, false, serial)
    }
}

/**
 * The panel's requests, held by the dialog (not the tab), so switching tabs neither cancels a
 * request nor loses its answer; never saved state, so a recreation starts with none and sends
 * nothing. One request at a time per server: [busy] is set in the tap's own frame, so a second tap
 * (a double tap, another node's key) starts nothing until the answer. An answer for a request
 * that is no longer the current one (after a server switch) is dropped; [noticeFor] / [busyFor]
 * show only the drawing server's.
 */
@Stable
class NodesActions(private val writer: NodesWriter, private val scope: CoroutineScope, initial: NodeNotice? = null) {
    var busy: NodeBusy? by mutableStateOf(null)
        private set
    var notice: NodeNotice? by mutableStateOf(initial)
        private set
    private var serial = initial?.serial ?: 0L
    private var nextId = 0L

    fun busyFor(origin: String?): NodeBusy? = busy?.takeIf { origin != null && it.origin == origin }
    fun noticeFor(origin: String?): NodeNotice? = notice?.takeIf { origin != null && it.origin == origin }

    /**
     * node-add with the form's text (trimmed by the client; a blank label or base URL is omitted).
     * False: nothing started (no server, busy, an empty credential, or one the server would refuse
     * for its length, which says so).
     */
    fun add(origin: String?, credential: String, label: String, baseUrl: String): Boolean {
        val o = origin ?: return false
        if (busyFor(o) != null) return false
        val length = credential.trim().length
        if (length == 0) return false
        if (length > NodeRegistryRules.CREDENTIAL_MAX_LENGTH) {
            post(o, NodeAction.Add, null, NodeRequestOutcome.Invalid(NodesCopy.TOO_LONG))
            return false
        }
        val bundle = NodeCredential(credential)
        return start(o, NodeAction.Add, null) { writer.add(o, bundle, label, baseUrl) }
    }

    fun probe(origin: String?, nodeId: String): Boolean {
        val o = origin ?: return false
        if (busyFor(o) != null) return false
        return start(o, NodeAction.Probe, nodeId) { writer.probe(o, nodeId) }
    }

    fun remove(origin: String?, nodeId: String): Boolean {
        val o = origin ?: return false
        if (busyFor(o) != null) return false
        return start(o, NodeAction.Remove, nodeId) { writer.remove(o, nodeId) }
    }

    private fun start(origin: String, action: NodeAction, nodeId: String?, call: suspend () -> NodeRequestOutcome): Boolean {
        val id = ++nextId
        busy = NodeBusy(origin, action, nodeId, id)
        scope.launch {
            var outcome: NodeRequestOutcome? = null
            try {
                outcome = call()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never silent: shown as the web's generic failure below.
            } finally {
                if (busy?.id == id) {
                    busy = null
                    post(origin, action, nodeId, outcome)
                }
            }
        }
        return true
    }

    private fun post(origin: String, action: NodeAction, nodeId: String?, outcome: NodeRequestOutcome?) {
        serial += 1
        notice = if (outcome == null) {
            NodeNotice(origin, action, nodeId, false, NodesCopy.FAILED, false, serial)
        } else {
            NodeOutcomes.notice(origin, action, nodeId, outcome, serial)
        }
    }
}

/** [NodesActions] for a composition (the dialog's; a test's or a golden's own). */
@Composable
fun rememberNodesActions(writer: NodesWriter, initial: NodeNotice? = null): NodesActions {
    val scope = rememberCoroutineScope()
    return remember(writer) { NodesActions(writer, scope, initial) }
}

/**
 * What the Nodes panel draws, for ONE server ([origin]): [list] the last `nodes` frame (the client
 * drops it on a server switch, a sign-out and a new sign-in), [actions] where its requests go,
 * [consoleProtocol] the server's own protocol version (the skew warning), [now] the clock the
 * "last seen" line reads.
 */
data class NodesBinding(
    val list: List<NodeSummary>,
    val origin: String?,
    val actions: NodesActions? = null,
    val consoleProtocol: Int? = null,
    val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        val None = NodesBinding(emptyList(), null)
    }
}

/** The label and base URL fields' bounds (lib/protocol-validate.mjs: UTF-8 bytes); an edit past them is refused whole. */
internal object NodeFormRules {
    fun labelFits(text: String) = text.toByteArray(Charsets.UTF_8).size <= NodeRegistryRules.LABEL_MAX_BYTES
    fun baseUrlFits(text: String) = text.toByteArray(Charsets.UTF_8).size <= NodeRegistryRules.BASE_URL_MAX_BYTES

    /** A paste far past the server's bound is refused whole; one just past it is held and said on Add. */
    fun credentialFits(text: String) = text.length <= NodeRegistryRules.CREDENTIAL_MAX_LENGTH * 2
}

/**
 * NodesSection (nodes-settings.tsx), in the web's order: the heading and its caption, the add form
 * (label, base URL, credential, Add node), the last result, one row per node (label; status, URL,
 * version, last seen; the skew warning; Probe and Remove), and the empty note. Remove has no
 * confirmation, as on the web. Signed out (no [NodesBinding.origin]): nothing but the heading.
 */
@Composable
internal fun NodesSection(binding: NodesBinding, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val caption = buildAnnotatedString {
        append(NodesCopy.CAPTION_HEAD)
        withStyle(SpanStyle(fontFamily = type.mono, color = t.ink)) { append(NodesCopy.CAPTION_CODE) }
        append(NodesCopy.CAPTION_TAIL)
    }
    SettingsSection(NodesCopy.TITLE, caption, narrow, modifier = Modifier.testTag(NodeTags.Section), last = true) {
        val origin = binding.origin ?: return@SettingsSection
        val actions = binding.actions
        val busy = actions?.busyFor(origin)
        val notice = actions?.noticeFor(origin)
        NodeAddForm(origin, actions, busy, notice, narrow)
        notice?.let { NodeNoticeLine(it) }
        // r2 (security F1): each row keyed by its node, so a `nodes` broadcast that adds, drops or
        // reorders rows never moves a row's state or a tap in flight onto another node.
        binding.list.forEach { node -> key(node.nodeId) { NodeRow(node, binding, actions, busy, narrow) } }
        if (binding.list.isEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                RowRule()
                Text(
                    NodesCopy.EMPTY,
                    color = t.faint,
                    style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f),
                    modifier = Modifier.testTag(NodeTags.Empty).padding(vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * `.settings-node-add`: the form. Label, base URL and the credential are plain fields, as on the
 * web. A browser keeps them across a resize and drops them on a reload (ta-coik.20): a rotation keeps
 * them (label and URL saved, the credential in memory only, never the saved-instance Bundle); a process
 * death, a close, a tab change or another server starts it empty, and nothing in it is ever
 * sent but by Add node. The credential is let go once the server holds the node
 * ([NodeNotice.heldByServer]); after any other answer (a refusal, nothing sent, no answer) the form
 * keeps it, so a deliberate second tap can send it again. Nothing is resent by itself. ta-coik.15:
 * the app going to the background clears nothing (the web has no such clear).
 */
@Composable
private fun NodeAddForm(origin: String, actions: NodesActions?, busy: NodeBusy?, notice: NodeNotice?, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    // ta-coik.20: the label and URL survive a rotation (saved); the credential survives it in memory only
    // (never the saved-instance Bundle).
    var label by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var credential by rememberRetained { "" }
    // The answer that was already shown when this form appeared is not this form's.
    val shownBefore = remember { notice?.serial }
    LaunchedEffect(notice?.serial) {
        if (notice != null && notice.serial != shownBefore && notice.action == NodeAction.Add && notice.heldByServer) {
            credential = ""
        }
    }
    val ui = settingsText(type.ui, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
    val mono = serverFieldStyle(narrow)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // `.section-label` under Studio (studio.css 630): 13, faint, 720, no capitals.
        Text(NodesCopy.ADD_HEADING, color = t.faint, style = settingsText(type.ui, 13f, 720))
        NodeField(label, { if (NodeFormRules.labelFits(it)) label = it }, NodesCopy.LABEL_PLACEHOLDER, NodeTags.Label, ui, KeyboardType.Text)
        NodeField(baseUrl, { if (NodeFormRules.baseUrlFits(it)) baseUrl = it }, NodesCopy.BASE_URL_PLACEHOLDER, NodeTags.BaseUrl, ui, KeyboardType.Uri)
        CredentialField(credential, { if (NodeFormRules.credentialFits(it)) credential = it }, mono, Modifier.fillMaxWidth().heightIn(min = 104.dp))
        val adding = busy?.action == NodeAction.Add
        TetherKey(
            onClick = { actions?.add(origin, credential, label, baseUrl) },
            classes = KeyClasses.ButtonSecondary,
            label = if (adding) NodesCopy.ADDING else NodesCopy.ADD,
            enabled = actions != null && busy == null && credential.isNotBlank(),
            modifier = Modifier.testTag(NodeTags.Add),
        )
    }
}

/** `.settings-node-add input`: a 44dp field, the placeholder as its name (the web's only label). */
@Composable
private fun NodeField(value: String, onChange: (String) -> Unit, placeholder: String, tag: String, style: TextStyle, keyboard: KeyboardType) {
    val t = LocalTetherTokens.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = placeholder }.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner -> ServerFieldBox(true, focused, style, if (value.isEmpty()) AnnotatedString(placeholder) else null, inner) },
    )
}

/**
 * The credential (`.settings-node-add textarea`: mono, at least 104dp, wrapping): the password
 * keyboard (nothing learned or suggested), copy and cut as the web's textarea (a copy marked
 * sensitive), no keyboard action that sends: only Add node does.
 */
@Composable
private fun CredentialField(value: String, onChange: (String) -> Unit, style: TextStyle, modifier: Modifier) {
    val t = LocalTetherTokens.current
    var focused by remember { mutableStateOf(false) }
    SensitiveClipScope(true) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = false,
            textStyle = style.copy(color = t.ink),
            cursorBrush = SolidColor(t.violet),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Default),
            modifier = modifier.testTag(NodeTags.Credential).semantics { contentDescription = NodesCopy.CREDENTIAL_LABEL }.onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                ServerFieldBox(true, focused, style, if (value.isEmpty()) AnnotatedString(NodesCopy.CREDENTIAL_PLACEHOLDER) else null, inner, alignTop = true)
            },
        )
    }
}

/**
 * The result line (`role="status"`): `.settings-devices-status` with a Check when it worked, the
 * `.settings-warning` wash with a warning glyph when not. Words and glyph, never colour alone.
 */
@Composable
private fun NodeNoticeLine(notice: NodeNotice) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f)
    val base = Modifier
        .testTag(NodeTags.Notice)
        .fillMaxWidth()
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    if (notice.ok) {
        Row(base.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Icon(TetherIcons.Check, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
            Text(notice.text, color = t.muted, style = style)
        }
    } else {
        Row(
            base.padding(bottom = 16.dp).background(t.attentionBg, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 3.dp).size(14.dp))
            Text(notice.text, color = t.attentionInk, style = style)
        }
    }
}

/**
 * One node (`.settings-row.settings-device`): its label; `small`: the status (glyph + words), the
 * base URL, `v<peerVersion>` and "last seen <relative>" joined by " · "; the skew warning; then
 * Probe and Remove. Every server text is untrusted: the label by the label rule, the URL by the
 * line rule (hidden controls as visible tokens), the version cleaned.
 */
@Composable
private fun NodeRow(node: NodeSummary, binding: NodesBinding, actions: NodesActions?, busy: NodeBusy?, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val origin = binding.origin
    val label = NodeText.label(node)
    val look = NodeStatusLook.of(node.status)
    val small = settingsText(type.ui, 12f, 400, lineHeight = 1.6f)
    val details = buildAnnotatedString {
        append(look.label)
        append(" · ")
        appendSafe(NodeText.url(node), SafeText.Rule.Line, tokenStyle(t))
        NodeText.version(node)?.let { append(" · v$it") }
        if (node.lastSeenAt > 0) append(" · " + NodesCopy.lastSeen(relativeTime(node.lastSeenAt, binding.now())))
    }
    SettingsRow(
        kind = RowKind.Device,
        narrow = narrow,
        modifier = Modifier.testTag(NodeTags.row(node.nodeId)),
        text = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, color = t.ink, style = settingsText(type.ui, 14f, 650, lineHeight = 1.5f))
                // The status glyph leads the line (`.settings-node-status`: no colour of its own, so the line's ink).
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                    Icon(look.icon, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
                    Text(details, color = t.muted, style = small, modifier = Modifier.weight(1f, fill = false).testTag(NodeTags.status(node.nodeId)))
                }
                NodeText.skew(node, binding.consoleProtocol)?.let { peer ->
                    Row(
                        Modifier
                            .testTag(NodeTags.skew(node.nodeId))
                            // r2 (verifier L3): the web's `role="alert"`, announced at once.
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .background(t.attentionBg, RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 3.dp).size(13.dp))
                        Text(NodesCopy.skew(peer, binding.consoleProtocol!!), color = t.attentionInk, style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f))
                    }
                }
            }
        },
        control = { m ->
            // `.settings-node-actions`: 8dp apart. On a phone (max-width 35rem) every
            // `.settings-device .button-secondary` is the row's full width, so the two stack.
            val keys: @Composable (Modifier) -> Unit = { k ->
                val enabled = actions != null && origin != null && busy == null
                TetherKey(
                    onClick = { actions?.probe(origin, node.nodeId) },
                    classes = KeyClasses.ButtonSecondary,
                    label = if (busy?.action == NodeAction.Probe && busy.nodeId == node.nodeId) NodesCopy.PROBING else NodesCopy.PROBE,
                    icon = TetherIcons.RefreshCw,
                    iconSize = 14.dp,
                    enabled = enabled,
                    contentDescription = NodesCopy.probeLabel(label),
                    modifier = k.testTag(NodeTags.probe(node.nodeId)),
                )
                TetherKey(
                    onClick = { actions?.remove(origin, node.nodeId) },
                    classes = KeyClasses.ButtonSecondary,
                    label = if (busy?.action == NodeAction.Remove && busy.nodeId == node.nodeId) NodesCopy.REMOVING else NodesCopy.REMOVE,
                    enabled = enabled,
                    contentDescription = NodesCopy.removeLabel(label),
                    modifier = k.testTag(NodeTags.remove(node.nodeId)),
                )
            }
            if (LocalSettingsRowsStack.current) {
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) { keys(Modifier.fillMaxWidth()) }
            } else {
                Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { keys(Modifier) }
            }
        },
    )
}
