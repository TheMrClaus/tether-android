package com.tether.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogSurface
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherExpandableBlock
import com.tether.app.ui.components.TetherExpandablePre
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherRocker
import com.tether.app.ui.components.TetherSeam
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectMenu
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.TetherSelectTrigger
import com.tether.app.ui.components.TetherSheet
import com.tether.app.ui.components.TetherSheetRow
import com.tether.app.ui.components.TetherSheetSurface
import com.tether.app.ui.components.TetherStatusPill
import com.tether.app.ui.components.TetherWell
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.components.statusColor
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.coroutines.delay

/**
 * Every gallery section, in screen order. The primitive sections mirror the T3.3 state boards
 * (`core/designsystem/src/test/.../PrimitiveBoards.kt`, whose goldens already cover them); the
 * catalogue sections (type roles, icons, provider logos, launcher icon) are new and have their own
 * goldens ([GalleryGoldens]).
 */
val GallerySections: List<GallerySection> = listOf(
    GallerySection("keys", "Keys") { KeysSection() },
    GallerySection("wells", "Wells") { WellsSection() },
    GallerySection("seams", "Seams") { SeamsSection() },
    GallerySection("chips", "Chips") { ChipsSection() },
    GallerySection("status-pills", "Status pills") { StatusPillsSection() },
    GallerySection("select", "Select") { SelectSection() },
    GallerySection("dialog", "Dialog") { DialogSection() },
    GallerySection("sheet", "Sheet") { SheetSection() },
    GallerySection("expandable", "Expandable") { ExpandableSection() },
    GallerySection("rocker", "Rocker") { RockerSection() },
    GallerySection("indicators", "Spinner and ping") { IndicatorsSection() },
    GallerySection("typography", "Typography") { TypographySection() },
    GallerySection("icons", "Icons (${TetherIcons.byWebName.size})") { IconGrid(IconNames) },
    GallerySection("provider-logos", "Provider logos") { ProviderLogosSection() },
    GallerySection("launcher-icon", "Launcher icon") { LauncherIconSection() },
)

/** A labelled row of states (same layout as the T3.3 boards' StateRow). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryRow(caption: String, wrap: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, color = t.faint, style = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = 10.sp))
        if (!wrap) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** An interaction source held pressed / keyboard-focused, so a static state shows in the gallery. */
@Composable
fun heldInteraction(pressed: Boolean = false, focused: Boolean = false): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        // The interactions flow does not replay: let the primitive's collectors subscribe first.
        delay(50)
        if (pressed) source.emit(PressInteraction.Press(Offset.Zero))
        if (focused) source.emit(FocusInteraction.Focus())
    }
    return source
}

/** Every class set the app renders (web markup, legend; null legend = an icon control). */
val GalleryKeySets: List<Triple<String, Set<KeyClass>, String?>> = listOf(
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
private fun KeysSection() {
    for ((markup, classes, label) in GalleryKeySets) {
        GalleryRow("$markup: rest · pressed · focus · disabled") {
            val icon = if (label == null) TetherIcons.Settings else null
            val cd = label ?: "Settings"
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = cd)
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = cd, interactionSource = heldInteraction(pressed = true))
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = cd, interactionSource = heldInteraction(focused = true))
            TetherKey(onClick = {}, classes = classes, label = label, icon = icon, contentDescription = cd, enabled = false)
        }
    }
    GalleryRow("latched · small · circle cap · icon send · authored legend") {
        TetherKey(onClick = {}, label = "Filter", selected = true)
        TetherKey(onClick = {}, classes = KeyClasses.IconButton, icon = TetherIcons.Paperclip, selected = true, contentDescription = "Attach")
        TetherKey(onClick = {}, label = "Yes", size = KeySize.Small, minHeight = 32.dp)
        TetherKey(onClick = {}, classes = KeyClasses.ChatJump, icon = TetherIcons.ArrowDown, contentDescription = "Jump")
        TetherKey(onClick = {}, classes = KeyClasses.ChatSend, icon = TetherIcons.Send, contentDescription = "Send")
        TetherKey(onClick = {}, label = "use main.kt", fixedVerb = false)
    }
    var presses by remember { mutableStateOf(0) }
    GalleryRow("live (tap it): $presses") {
        TetherKey(onClick = { presses++ }, classes = KeyClasses.ButtonPrimary, label = "Press me")
        TetherKey(onClick = { presses = 0 }, label = "Reset")
    }
}

@Composable
private fun WellsSection() {
    var text by remember { mutableStateOf("") }
    GalleryRow("input: live · value", wrap = false) {
        TetherInputWell(text, { text = it }, placeholder = "Message", singleLine = true, modifier = Modifier.weight(1f))
        TetherInputWell("npm test", {}, singleLine = true, modifier = Modifier.weight(1f))
    }
    GalleryRow("input: focus · disabled", wrap = false) {
        TetherInputWell("focused", {}, singleLine = true, modifier = Modifier.weight(1f), interactionSource = heldInteraction(focused = true))
        TetherInputWell("", {}, placeholder = "Disabled", singleLine = true, enabled = false, modifier = Modifier.weight(1f))
    }
    GalleryRow("plate · plate focus") {
        val t = LocalTetherTokens.current
        TetherWell(Modifier.size(150.dp, 48.dp)) { Text("12:04", color = t.ink, modifier = Modifier.align(Alignment.Center)) }
        TetherWell(Modifier.size(150.dp, 48.dp), focused = true) { Text("12:04", color = t.ink, modifier = Modifier.align(Alignment.Center)) }
    }
}

@Composable
private fun SeamsSection() {
    val t = LocalTetherTokens.current
    GalleryRow("horizontal seam · perf divider", wrap = false) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(24.dp).background(t.graphite))
            TetherSeam()
            Box(Modifier.fillMaxWidth().height(24.dp).background(t.mineral))
            Box(Modifier.fillMaxWidth().background(t.graphite).padding(vertical = 8.dp)) { PerfDivider() }
        }
    }
    GalleryRow("vertical seam", wrap = false) {
        Row(Modifier.height(48.dp)) {
            Box(Modifier.width(80.dp).height(48.dp).background(t.graphite))
            TetherSeam(vertical = true)
            Box(Modifier.width(80.dp).height(48.dp).background(t.mineral))
        }
    }
}

@Composable
private fun ChipsSection() {
    val t = LocalTetherTokens.current
    var on by remember { mutableStateOf(false) }
    GalleryRow("rest · open · focus · live toggle") {
        TetherChip("Draft", {})
        TetherChip("Opus 4.1", {}, active = true, trailingIcon = TetherIcons.ChevronDown)
        TetherChip("Focus", {}, interactionSource = heldInteraction(focused = true))
        TetherChip(if (on) "On" else "Off", { on = !on }, active = on)
    }
    GalleryRow("leading glyph · disabled") {
        TetherChip("notes.md", {}, leading = { Icon(TetherIcons.FileText, null, tint = t.muted, modifier = Modifier.size(12.dp)) })
        TetherChip("Off", {}, enabled = false)
    }
}

@Composable
private fun StatusPillsSection() {
    GalleryRow("active · waiting · ready · exited · history") {
        TetherStatusPill("Active", StatusTone.Active)
        TetherStatusPill("Waiting", StatusTone.Waiting)
        TetherStatusPill("Ready", StatusTone.Ready)
        TetherStatusPill("Exited", StatusTone.Exited)
        TetherStatusPill("History", StatusTone.History)
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
private fun SelectSection() {
    var value by remember { mutableStateOf("default") }
    GalleryRow("live select (row · field)") {
        TetherSelect(selectOptions, value, { value = it.value }, contentDescription = "Permission mode")
        TetherSelect(selectOptions, value, { value = it.value }, style = SelectTriggerStyle.Field, contentDescription = "Permission mode field")
    }
    GalleryRow("row trigger: rest · open · focus · disabled") {
        TetherSelectTrigger("Precision", expanded = false, onClick = {})
        TetherSelectTrigger("Precision", expanded = true, onClick = {})
        TetherSelectTrigger("Focus", expanded = false, onClick = {}, interactionSource = heldInteraction(focused = true))
        TetherSelectTrigger("Off", expanded = false, onClick = {}, enabled = false)
    }
    GalleryRow("menu: selected · roving focus · tag · danger · disabled") {
        TetherSelectMenu(selectOptions, selectedValue = "default", onSelect = {}, focusedValue = "accept", minWidth = 240.dp)
    }
    GalleryRow("menu opening up") {
        TetherSelectMenu(selectOptions.take(2), selectedValue = "plan", onSelect = {}, opensUp = true, minWidth = 240.dp)
    }
}

@Composable
private fun DialogSection() {
    val t = LocalTetherTokens.current
    var open by remember { mutableStateOf(false) }
    GalleryRow("live modal") {
        TetherKey(onClick = { open = true }, label = "Open dialog")
    }
    GalleryRow("surface on the scrim", wrap = false) {
        Box(Modifier.fillMaxWidth().background(dialogScrim(t)).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            EndSessionDialogSurface(onDismiss = {})
        }
    }
    if (open) {
        TetherDialog(
            onDismiss = { open = false },
            title = "End session",
            footer = {
                TetherKey(onClick = { open = false }, label = "Cancel")
                TetherKey(onClick = { open = false }, classes = KeyClasses.ButtonDanger, label = "End session")
            },
        ) {
            TetherDialogText(EndSessionText)
        }
    }
}

private const val EndSessionText = "The agent stops and the transcript is kept. You can resume from history."

@Composable
private fun EndSessionDialogSurface(onDismiss: () -> Unit) {
    TetherDialogSurface(
        title = "End session",
        footer = {
            TetherKey(onClick = onDismiss, label = "Cancel")
            TetherKey(onClick = onDismiss, classes = KeyClasses.ButtonDanger, label = "End session")
        },
    ) {
        TetherDialogText(EndSessionText)
    }
}

@Composable
private fun SheetSection() {
    val t = LocalTetherTokens.current
    var open by remember { mutableStateOf(false) }
    GalleryRow("live modal") {
        TetherKey(onClick = { open = true }, label = "Open sheet")
    }
    GalleryRow("surface: rest · pressed · focus rows", wrap = false) {
        Box(Modifier.fillMaxWidth().height(360.dp).background(dialogScrim(t)), contentAlignment = Alignment.BottomCenter) {
            TetherSheetSurface(title = "Attach", onClose = {}) {
                TetherSheetRow("Photo library", {}, icon = TetherIcons.Image)
                TetherSheetRow("File", {}, icon = TetherIcons.FileText, interactionSource = heldInteraction(pressed = true))
                TetherSheetRow("Paste from clipboard", {}, icon = TetherIcons.Paperclip, interactionSource = heldInteraction(focused = true))
            }
        }
    }
    if (open) {
        TetherSheet(onDismiss = { open = false }, title = "Attach") {
            TetherSheetRow("Photo library", { open = false }, icon = TetherIcons.Image)
            TetherSheetRow("File", { open = false }, icon = TetherIcons.FileText)
            TetherSheetRow("Paste from clipboard", { open = false }, icon = TetherIcons.Paperclip)
        }
    }
}

private val longOutput = (1..40).joinToString("\n") { "line $it: compiled module :core:designsystem" }

@Composable
private fun ExpandableSection() {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().background(t.mineralDeep), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryRow("clamped, counted", wrap = false) {
            TetherExpandablePre(longOutput, clamp = 120.dp, textModifier = Modifier.padding(8.dp))
        }
        GalleryRow("open", wrap = false) {
            TetherExpandablePre((1..6).joinToString("\n") { "row $it" }, initiallyOpen = true, textModifier = Modifier.padding(8.dp))
        }
        GalleryRow("fits (no toggle) · generic block (unnumbered)", wrap = false) {
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
private fun RockerSection() {
    var on by remember { mutableStateOf(true) }
    GalleryRow("live · off · on · focus · disabled on") {
        TetherRocker(checked = on, onCheckedChange = { on = it }, contentDescription = "Live rocker")
        TetherRocker(checked = false, onCheckedChange = {})
        TetherRocker(checked = true, onCheckedChange = {})
        TetherRocker(checked = false, onCheckedChange = {}, interactionSource = heldInteraction(focused = true))
        TetherRocker(checked = true, onCheckedChange = {}, enabled = false)
    }
}

@Composable
private fun IndicatorsSection() {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    GalleryRow("spinner ring · loader · dot · waiting ping (2s)${if (reduced) " · reduced motion: static frame" else ""}") {
        SpinnerRing(statusColor(StatusTone.Active))
        SpinnerRing(t.muted, size = 16.dp, stroke = 2.dp)
        SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 16.dp)
        StatusDot(t.faint)
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { WaitingPingDot(t.violet) }
    }
}
