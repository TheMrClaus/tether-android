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
import com.tether.app.ui.components.KeyShape
import com.tether.app.ui.components.KeySize
import com.tether.app.ui.components.KeyVariant
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
    "keys" to { KeysBoard() },
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
)

/** The primitives whose layout changes at the web's 48rem breakpoint get a tablet golden too. */
val TabletBoards: Map<String, @Composable () -> Unit> = linkedMapOf(
    "sheet" to { SheetBoard() },
    "dialog" to { DialogBoard() },
)

@Composable
fun KeysBoard() {
    for (variant in KeyVariant.entries) {
        StateRow("${variant.name.lowercase()}: rest · pressed · focus · disabled") {
            val icon = if (variant == KeyVariant.Quiet) TetherIcons.Settings else null
            val label = if (variant == KeyVariant.Quiet) null else when (variant) {
                KeyVariant.Primary -> "Send"
                KeyVariant.Brick -> "Stop"
                KeyVariant.Utility -> "Jump"
                else -> "Retry"
            }
            TetherKey(onClick = {}, variant = variant, label = label, icon = icon, contentDescription = "k")
            TetherKey(onClick = {}, variant = variant, label = label, icon = icon, contentDescription = "k", interactionSource = heldInteraction(pressed = true))
            TetherKey(onClick = {}, variant = variant, label = label, icon = icon, contentDescription = "k", interactionSource = heldInteraction(focused = true))
            TetherKey(onClick = {}, variant = variant, label = label, icon = icon, contentDescription = "k", enabled = false)
        }
    }
    StateRow("latched · small · slit · circle cap · authored legend") {
        TetherKey(onClick = {}, label = "Filter", selected = true)
        TetherKey(onClick = {}, variant = KeyVariant.Quiet, icon = TetherIcons.Paperclip, selected = true, contentDescription = "Attach")
        TetherKey(onClick = {}, label = "Yes", size = KeySize.Small, minHeight = 32.dp)
        TetherKey(onClick = {}, variant = KeyVariant.Primary, label = "Approve", showSlit = true)
    }
    StateRow("") {
        TetherKey(onClick = {}, variant = KeyVariant.Brick, label = "Deny", showSlit = true)
        TetherKey(onClick = {}, variant = KeyVariant.Utility, icon = TetherIcons.ArrowDown, shape = KeyShape.Circle, contentDescription = "Jump")
        TetherKey(onClick = {}, variant = KeyVariant.Primary, icon = TetherIcons.Send, contentDescription = "Send")
        TetherKey(onClick = {}, label = "use main.kt", fixedVerb = false)
    }
}

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
    StateRow("rest · open · focus") {
        TetherChip("Draft", {})
        TetherChip("Opus 4.1", {}, active = true, trailingIcon = TetherIcons.ChevronDown)
        TetherChip("Focus", {}, interactionSource = heldInteraction(focused = true))
    }
    StateRow("leading glyph · disabled") {
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
                TetherKey(onClick = {}, variant = KeyVariant.Brick, label = "End session", showSlit = true)
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
        TetherKey(onClick = {}, variant = KeyVariant.Primary, label = "Save settings", icon = TetherIcons.Check, iconSize = 16.dp)
    }
}
