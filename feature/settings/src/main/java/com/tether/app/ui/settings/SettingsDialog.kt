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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.ConnectionState
import com.tether.app.client.TetherClient
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    /** The draft, or (before the first read lands) the live value it will be seeded from. */
    fun draftOr(live: TetherPreferences): GeneralDraft = draft ?: GeneralDraft.of(live)

    fun edit(live: TetherPreferences, change: (GeneralDraft) -> GeneralDraft) {
        draft = change(draftOr(live))
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
    val layout = currentLayoutClass()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
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
) {
    val t = LocalTetherTokens.current
    val live by prefs.preferences.collectAsStateWithLifecycle(initialValue = null)
    // settings-dialog.tsx:1935: the draft starts as the stored preferences (read once, so a value
    // that lands later never overwrites an edit).
    LaunchedEffect(prefs) { if (state.draft == null) state.draft = GeneralDraft.of(prefs.preferences.first()) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val save: () -> Unit = save@{
        if (saving) return@save
        val draft = state.draftOr(live ?: TetherPreferences.Default)
        saving = true
        // Written before the dialog closes, so leaving composition never cancels the write. Storage
        // that refuses the write closes the dialog all the same (the web's localStorage save is
        // best effort too); nothing is half-applied, the model is written in one edit.
        scope.launch {
            runCatching { prefs.updatePreferences(draft::applyTo) }
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
                if (restartRequired) RestartBanner(narrow)
                Box(Modifier.testTag(SettingsDialogTags.panel(state.tab))) {
                    SettingsPanel(
                        tab = state.tab,
                        prefs = prefs,
                        live = live ?: TetherPreferences.Default,
                        state = state,
                        currentWorkspace = currentWorkspace,
                        narrow = narrow,
                    )
                }
            }
            SettingsFooter(narrow, saving, onCancel = onClose, onSave = save)
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
private fun SettingsFooter(narrow: Boolean, saving: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
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
                enabled = !saving,
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

/** settings-dialog.tsx:2000, the banner's words. */
const val RESTART_REQUIRED = "Some changes need a server restart to take effect."
