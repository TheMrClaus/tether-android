package com.tether.app.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.InputWellStyle
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope

/*
 * T10.6: the small parts of the wizard, dressed as app/studio.css 863-939 dresses the page's
 * .setup-* classes (tether 90fbb9f). Studio's own tokens only: violet for focus / selected, status
 * never by colour alone.
 */

/** A paragraph in [color] at [size], Manrope, [lineHeight] times the size. */
@Composable
internal fun SetupText(
    text: CharSequence,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = LocalTetherTokens.current.muted,
    weight: Int = 500,
    lineHeight: Float = 1.6f,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    val style = TextStyle(
        textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified,
        color = color,
        fontFamily = Manrope,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = (size * lineHeight).sp,
    )
    if (text is AnnotatedString) Text(text, modifier, style = style, maxLines = maxLines, overflow = overflow)
    else Text(text.toString(), modifier, style = style, maxLines = maxLines, overflow = overflow)
}

/** `.setup-step h2` (28sp; 25 on a phone): the step's title and the screen's heading. */
@Composable
internal fun StepTitle(text: String, phone: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Text(
        text,
        modifier = modifier.semantics { heading() },
        style = TextStyle(
            color = t.white,
            fontFamily = Manrope,
            fontWeight = FontWeight(650),
            fontSize = (if (phone) 25f else 28f).sp,
            lineHeight = (if (phone) 25f else 28f).times(1.25f).sp,
            letterSpacing = (-0.025).em,
        ),
    )
}

/** `.setup-copy`: the step's lead paragraph (14sp, 1.7). */
@Composable
internal fun SetupCopy(text: CharSequence, modifier: Modifier = Modifier) = SetupText(text, 14f, modifier, lineHeight = 1.7f)

/** `.setup-footnote` (12sp, faint). */
@Composable
internal fun SetupFootnote(text: String, modifier: Modifier = Modifier) =
    SetupText(text, 12f, modifier, color = LocalTetherTokens.current.faint, lineHeight = 1.7f)

/** [prefix] [code] [suffix], the code in the page's mono (`.setup-copy code`). */
@Composable
internal fun withCode(prefix: String, code: String, suffix: String = ""): AnnotatedString {
    val t = LocalTetherTokens.current
    return buildAnnotatedString {
        append(prefix)
        withStyle(SpanStyle(fontFamily = JetBrainsMono, color = t.ink, fontSize = 12.9.sp)) { append(code) }
        append(suffix)
    }
}

/** `.setup-note`: a quiet line with a small glyph (a lock for what the environment sets; never colour alone). */
@Composable
internal fun SetupNote(icon: ImageVector?, text: CharSequence, modifier: Modifier = Modifier, tint: Color = LocalTetherTokens.current.muted) {
    val t = LocalTetherTokens.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        if (icon != null) Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 2.dp).size(13.dp))
        SetupText(text, 12f, Modifier.weight(1f), color = tint, lineHeight = 1.6f)
    }
}

/** `.setup-field-note`: reserves its line (`min-height: 18px`); `is-warning` is the warning colour and a live region. */
@Composable
internal fun FieldNote(text: String, warning: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    SetupText(
        text.ifEmpty { " " },
        12f,
        modifier.heightIn(min = 18.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (warning) t.warning else t.muted,
        weight = if (warning) 600 else 500,
        lineHeight = 1.5f,
    )
}

/** `.setup-field label` (13sp) over the field. */
@Composable
internal fun FieldLabel(text: String) {
    SetupText(text, 13f, color = LocalTetherTokens.current.ink, weight = 600)
}

/** `.setup-field input` (studio.css 902): 48dp, 8dp radius, graphite, the strong line, 16sp text. */
@Composable
internal fun setupInputStyle(): InputWellStyle {
    val t = LocalTetherTokens.current
    return InputWellStyle(
        radius = 8.dp,
        minHeight = 48.dp,
        horizontalPadding = 12.dp,
        face = t.graphite,
        border = t.lineStrong,
        focusBorder = t.violetStrong,
        focusOutline = t.focusGlow,
        focusOutlineWidth = 3.dp,
        focusOutlineOffset = 0.dp,
        invalidBorder = t.danger,
        disabledAlpha = 0.6f,
    )
}

/** A rounded graphite card with a 1dp line; [selected] is the violet edge (`.harness-item.is-selected`). */
@Composable
internal fun Modifier.setupCard(radius: Int, selected: Boolean = false, face: Color = LocalTetherTokens.current.graphite): Modifier {
    val t = LocalTetherTokens.current
    return cssSurface(RoundedCornerShape(radius.dp), face, CssBorder(1.dp, if (selected) t.violetStrong else t.line))
}

/** The tick box of `.harness-row input` / `.setup-option input` (violet when on; the state is announced). */
@Composable
internal fun SetupCheckGlyph(checked: Boolean, enabled: Boolean = true) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .size(17.dp)
            .cssSurface(RoundedCornerShape(3.dp), if (checked) t.violetStrong else t.graphite, if (checked) null else CssBorder(1.dp, t.muted)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(TetherIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
    }
}

/** A radio dot: a ring, filled violet when chosen. */
@Composable
internal fun SetupRadioGlyph(selected: Boolean) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .size(17.dp)
            .border(BorderStroke(if (selected) 5.dp else 1.dp, if (selected) t.violetStrong else t.muted), CircleShape),
    )
}

/** `.setup-option`: a tick box card with a title and a small line, one toggle for the whole row. */
@Composable
internal fun OptionToggle(checked: Boolean, onChange: (Boolean) -> Unit, title: String, detail: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .setupCard(10, checked, face = if (checked) t.violetWash else t.graphite)
            .toggleable(checked, role = Role.Checkbox, interactionSource = remember { MutableInteractionSource() }, indication = null, onValueChange = onChange)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp)) { SetupCheckGlyph(checked) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SetupText(title, 14f, color = t.white, weight = 650, lineHeight = 1.4f)
            SetupText(detail, 12f, color = t.muted, lineHeight = 1.6f)
        }
    }
}

/** `.setup-choice label`: one radio card of the isolation choice. */
@Composable
internal fun ChoiceCard(selected: Boolean, onSelect: () -> Unit, title: String, detail: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .setupCard(10, selected, face = if (selected) t.violetWash else t.graphite)
            .selectable(selected, role = Role.RadioButton, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp)) { SetupRadioGlyph(selected) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SetupText(title, 14f, color = t.white, weight = 650, lineHeight = 1.4f)
            SetupText(detail, 12f, color = t.muted, lineHeight = 1.6f)
        }
    }
}

/** A tappable line of the picks list (`.setup-browser-list li button`), 48dp. */
@Composable
internal fun PickRow(icon: ImageVector, text: String, pressed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (pressed) t.violetWash else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = pressed }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = t.faint, modifier = Modifier.size(15.dp))
        Text(codeLabel(text), color = t.ink, fontFamily = JetBrainsMono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

