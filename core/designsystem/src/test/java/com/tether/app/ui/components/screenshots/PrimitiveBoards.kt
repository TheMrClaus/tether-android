package com.tether.app.ui.components.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.KeyClass
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeySize
import com.tether.app.ui.components.PerfDivider
import com.tether.app.ui.components.SelectTriggerStyle
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.StatusTone
import com.tether.app.ui.components.TetherChip
import com.tether.app.ui.components.TetherDialogSurface
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherExpandableBlock
import com.tether.app.ui.components.TetherExpandablePre
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherRocker
import com.tether.app.ui.components.TetherSeam
import com.tether.app.ui.components.TetherSelectMenu
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.TetherSelectTrigger
import com.tether.app.ui.components.TetherSheetRow
import com.tether.app.ui.components.TetherSheetSurface
import com.tether.app.ui.components.TetherStatusPill
import com.tether.app.ui.components.TetherWell
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.components.statusColor
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** Every primitive's state board, by golden folder name. */
val PrimitiveBoards: Map<String, @Composable () -> Unit> = linkedMapOf(
    "keys" to { KeysBoard(part = 1) },
    "keys-more" to { KeysBoard(part = 2) },
    "wells" to { WellsBoard() },
    "seams" to { SeamsBoard() },
    "status-pills" to { StatusPillsBoard() },
    "chips" to { ChipsBoard() },
    "select" to { SelectBoard() },
    "dialog" to { DialogBoard() },
    "sheet" to { SheetBoard() },
    "expandable" to { ExpandableBoard() },
    "indicators" to { IndicatorsBoard() },
    "rocker" to { RockerBoard() },
    "keys-dialog-footer" to { DialogFooterKeysBoard() },
    "keys-web" to { WebKeysBoard() },
)

/** The primitives whose layout changes at the web's 48rem breakpoint get a tablet golden too. */
val TabletBoards: Map<String, @Composable () -> Unit> = linkedMapOf(
    "sheet" to { SheetBoard() },
    "dialog" to { DialogBoard() },
    // The expand toggle is 44dp / 0.74rem on a phone, 36dp / 0.7rem from the expanded width.
    "expandable" to { ExpandableBoard() },
)

/** PLAN §4 accessibility: the text-bearing primitives at 1.3× font scale. */
val FontScaleBoards: Map<String, @Composable () -> Unit> = linkedMapOf(
    "keys" to { KeysBoard() },
    "chips" to { ChipsBoard() },
    "expandable" to { ExpandableBoard() },
)

@Composable
fun KeysBoard(part: Int? = null) {
    // A phone-height window holds about ten rows, so the phone goldens split the board in two.
    val sets = when (part) {
        1 -> KeyBoardSets.take(6)
        2 -> KeyBoardSets.drop(6)
        else -> KeyBoardSets
    }
    for ((markup, classes, label) in sets) {
        StateRow("$markup: rest · pressed · focus · disabled", wrap = true) {
            val icon = if (label == null) TetherIcons.Settings else null
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = "k")
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = "k", interactionSource = heldInteraction(pressed = true))
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = "k", interactionSource = heldInteraction(focused = true))
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = "k", enabled = false)
        }
    }
    if (part == 1) return
    StateRow("latched · small · circle cap · icon send · authored legend", wrap = true) {
        TetherKey(onClick = {}, label = "Filter", selected = true)
        TetherKey(onClick = {}, classes = KeyClasses.IconButton, icon = TetherIcons.Paperclip, selected = true, contentDescription = "Attach")
        TetherKey(onClick = {}, label = "Yes", size = KeySize.Small, minHeight = 32.dp)
        TetherKey(onClick = {}, classes = KeyClasses.ChatJump, icon = TetherIcons.ArrowDown, contentDescription = "Jump")
        TetherKey(onClick = {}, classes = KeyClasses.ChatSend, icon = TetherIcons.Send, contentDescription = "Send")
        TetherKey(onClick = {}, label = "use main.kt", fixedVerb = false)
    }
}

/** Every class set the app renders (web markup → legend; null = an icon control). */
val KeyBoardSets: List<Triple<String, Set<KeyClass>, String?>> = listOf(
    Triple("button-primary", KeyClasses.ButtonPrimary, "Approve"),
    Triple("button-secondary", KeyClasses.ButtonSecondary, "Retry"),
    Triple("button-primary button-danger", KeyClasses.ButtonDanger, "Delete"),
    Triple("button-secondary chat-approval-deny", KeyClasses.ApprovalDeny, "Deny"),
    Triple("chat-send", KeyClasses.ChatSend, "Send"),
    Triple("chat-send chat-interrupt", KeyClasses.ChatInterrupt, "Stop"),
    Triple("end-session", KeyClasses.EndSession, "End"),
    Triple("new-session-button", KeyClasses.NewSession, "New session"),
    Triple("chat-attach-btn", KeyClasses.Attach, "Attach"),
    Triple("chat-jump", KeyClasses.ChatJump, "Jump"),
    Triple("icon-button", KeyClasses.IconButton, null),
)

@Composable
fun WellsBoard() {
    StateRow("input: placeholder · value") {
        TetherInputWell("", {}, placeholder = "Message", singleLine = true, modifier = Modifier.weight(1f))
        TetherInputWell("npm test", {}, singleLine = true, modifier = Modifier.weight(1f))
    }
    StateRow("input: focus · disabled") {
        TetherInputWell("focused", {}, singleLine = true, modifier = Modifier.weight(1f), interactionSource = heldInteraction(focused = true))
        TetherInputWell("", {}, placeholder = "Disabled", singleLine = true, enabled = false, modifier = Modifier.weight(1f))
    }
    StateRow("plate · plate focus") {
        val t = LocalTetherTokens.current
        TetherWell(Modifier.size(150.dp, 48.dp)) { Text("12:04", color = t.ink, modifier = Modifier.align(Alignment.Center)) }
        TetherWell(Modifier.size(150.dp, 48.dp), focused = true) { Text("12:04", color = t.ink, modifier = Modifier.align(Alignment.Center)) }
    }
}

@Composable
fun SeamsBoard() {
    val t = LocalTetherTokens.current
    StateRow("horizontal seam · perf divider") {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(24.dp).background(t.graphite))
            TetherSeam()
            Box(Modifier.fillMaxWidth().height(24.dp).background(t.mineral))
            Box(Modifier.fillMaxWidth().background(t.graphite).padding(vertical = 8.dp)) { PerfDivider() }
        }
    }
    StateRow("vertical seam") {
        Row(Modifier.height(48.dp)) {
            Box(Modifier.width(80.dp).height(48.dp).background(t.graphite))
            TetherSeam(vertical = true)
            Box(Modifier.width(80.dp).height(48.dp).background(t.mineral))
        }
    }
}

@Composable
fun StatusPillsBoard() {
    StateRow("active · waiting · ready") {
        TetherStatusPill("Active", StatusTone.Active)
        TetherStatusPill("Waiting", StatusTone.Waiting)
        TetherStatusPill("Ready", StatusTone.Ready)
    }
    StateRow("exited · history") {
        TetherStatusPill("Exited", StatusTone.Exited)
        TetherStatusPill("History", StatusTone.History)
    }
}

@Composable
fun ChipsBoard() {
    StateRow("rest · open · focus", wrap = true) {
        TetherChip("Draft", {})
        TetherChip("Opus 4.1", {}, active = true, trailingIcon = TetherIcons.ChevronDown)
        TetherChip("Focus", {}, interactionSource = heldInteraction(focused = true))
    }
    StateRow("leading glyph · disabled", wrap = true) {
        val t = LocalTetherTokens.current
        TetherChip("notes.md", {}, leading = { androidx.compose.material3.Icon(TetherIcons.FileText, null, tint = t.muted, modifier = Modifier.size(12.dp)) })
        TetherChip("Off", {}, enabled = false)
    }
}

private val selectOptions = listOf(
    TetherSelectOption("default", "Ask before edits", description = "Approve each change"),
    TetherSelectOption("plan", "Plan mode", tag = "read-only"),
    TetherSelectOption("accept", "Accept edits"),
    TetherSelectOption("bypass", "Bypass permissions", description = "Runs anything", danger = true),
    TetherSelectOption("off", "Unavailable", disabled = true),
)

@Composable
fun SelectBoard() {
    StateRow("row trigger: rest · open · focus · disabled") {
        TetherSelectTrigger("Precision", expanded = false, onClick = {})
        TetherSelectTrigger("Precision", expanded = true, onClick = {})
        TetherSelectTrigger("Focus", expanded = false, onClick = {}, interactionSource = heldInteraction(focused = true))
        TetherSelectTrigger("Off", expanded = false, onClick = {}, enabled = false)
    }
    StateRow("field trigger") {
        TetherSelectTrigger("Claude Code", expanded = false, onClick = {}, style = SelectTriggerStyle.Field, modifier = Modifier.weight(1f))
    }
    StateRow("menu: selected · roving focus · tag · danger · disabled") {
        TetherSelectMenu(selectOptions, selectedValue = "default", onSelect = {}, focusedValue = "accept", minWidth = 240.dp)
    }
    StateRow("menu opening up") {
        TetherSelectMenu(selectOptions.take(2), selectedValue = "plan", onSelect = {}, opensUp = true, minWidth = 240.dp)
    }
}

@Composable
fun DialogBoard() {
    val t = LocalTetherTokens.current
    Box(Modifier.fillMaxWidth().background(dialogScrim(t)).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        TetherDialogSurface(
            title = "End session",
            footer = {
                TetherKey(onClick = {}, label = "Cancel")
                TetherKey(onClick = {}, classes = KeyClasses.ButtonDanger, label = "End session")
            },
        ) {
            TetherDialogText("The agent stops and the transcript is kept. You can resume from history.")
        }
    }
}

@Composable
fun SheetBoard() {
    val t = LocalTetherTokens.current
    Box(Modifier.fillMaxWidth().height(360.dp).background(dialogScrim(t)), contentAlignment = Alignment.BottomCenter) {
        TetherSheetSurface(title = "Attach", onClose = {}) {
            TetherSheetRow("Photo library", {}, icon = TetherIcons.Image)
            TetherSheetRow("File", {}, icon = TetherIcons.FileText, interactionSource = heldInteraction(pressed = true))
            TetherSheetRow("Paste from clipboard", {}, icon = TetherIcons.Paperclip, interactionSource = heldInteraction(focused = true))
        }
    }
}

private val longOutput = (1..40).joinToString("\n") { "line $it: compiled module :core:designsystem" }

@Composable
fun ExpandableBoard() {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().background(t.mineralDeep), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StateRow("clamped, counted") {
            TetherExpandablePre(longOutput, clamp = 120.dp, textModifier = Modifier.padding(8.dp))
        }
        StateRow("open") {
            TetherExpandablePre((1..6).joinToString("\n") { "row $it" }, initiallyOpen = true, textModifier = Modifier.padding(8.dp))
        }
        StateRow("fits (no toggle) · generic block (unnumbered)") {
            Column(Modifier.weight(1f)) { TetherExpandablePre("fits in the clamp", textModifier = Modifier.padding(8.dp)) }
            Column(Modifier.weight(1f)) {
                TetherExpandableBlock(clamp = 60.dp) {
                    Column(Modifier.padding(8.dp)) { repeat(8) { Text("block $it", color = t.ink, style = type.codeBlock) } }
                }
            }
        }
    }
}

@Composable
fun IndicatorsBoard() {
    val t = LocalTetherTokens.current
    StateRow("spinner ring · loader · dot · waiting ping") {
        SpinnerRing(statusColor(StatusTone.Active))
        SpinnerRing(t.muted, size = 16.dp, stroke = 2.dp)
        SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 16.dp)
        StatusDot(t.faint)
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { WaitingPingDot(t.violet) }
    }
}

@Composable
fun RockerBoard() {
    StateRow("off · on · focus · disabled on") {
        TetherRocker(checked = false, onCheckedChange = {})
        TetherRocker(checked = true, onCheckedChange = {})
        TetherRocker(checked = false, onCheckedChange = {}, interactionSource = heldInteraction(focused = true))
        TetherRocker(checked = true, onCheckedChange = {}, enabled = false)
    }
}

/**
 * The settings dialog footer keys with the web's own words (settings-general scenario), so the
 * montage against the web reference compares like with like.
 */
@Composable
fun DialogFooterKeysBoard() {
    StateRow("settings footer: secondary · primary with glyph") {
        TetherKey(onClick = {}, label = "Cancel")
        TetherKey(onClick = {}, classes = KeyClasses.ButtonPrimary, label = "Save settings", icon = TetherIcons.Check, iconSize = 17.dp)
    }
}

/**
 * The keys as the web reference scenarios show them, same class sets, glyphs and legends, so the
 * montages compare like with like: approval-pending (Approve / Deny), the composer's icon-only
 * Interrupt and paperclip (streaming), the session header's End session and the jump cap.
 */
@Composable
fun WebKeysBoard() {
    StateRow("approval: button-primary · button-secondary chat-approval-deny") {
        TetherKey(onClick = {}, classes = KeyClasses.ButtonPrimary, label = "Approve", icon = TetherIcons.Check)
        TetherKey(onClick = {}, classes = KeyClasses.ApprovalDeny, label = "Deny", icon = TetherIcons.Ban)
    }
    StateRow("composer interrupt · paperclip · header end-session · jump") {
        TetherKey(onClick = {}, classes = KeyClasses.ChatInterrupt, icon = TetherIcons.CircleStop, iconSize = 18.dp, contentDescription = "Interrupt")
        TetherKey(onClick = {}, classes = KeyClasses.Attach, icon = TetherIcons.Paperclip, iconSize = 18.dp, contentDescription = "Attach")
        TetherKey(onClick = {}, classes = KeyClasses.EndSession, icon = TetherIcons.CircleStop, iconSize = 16.dp, contentDescription = "End session")
        TetherKey(onClick = {}, classes = KeyClasses.ChatJump, icon = TetherIcons.ArrowDown, iconSize = 18.dp, contentDescription = "Jump")
    }
    StateRow("approval disabled (submitted)") {
        TetherKey(onClick = {}, classes = KeyClasses.ButtonPrimary, label = "Approve", icon = TetherIcons.Check, enabled = false)
        TetherKey(onClick = {}, classes = KeyClasses.ApprovalDeny, label = "Deny", icon = TetherIcons.Ban, enabled = false)
    }
}
