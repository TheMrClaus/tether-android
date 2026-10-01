package com.tether.app.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

// The dialog's shared parts (globals.css 3039-3092 under the Studio finish, studio.css 556-637):
// a section's heading and caption, the `.settings-row` (title over caption, a control beside it),
// the `.settings-toggle` switch, the `.theme-choice` radio row and the `.settings-tip`. The later
// slices build their rows from these.

/** A text role at [sizeSp] with the web's centred CSS line box. */
internal fun settingsText(
    family: FontFamily,
    sizeSp: Float,
    weight: Int,
    lineHeight: Float? = null,
    trackingEm: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = family,
    fontSize = sizeSp.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = if (trackingEm == 0f) TextUnit.Unspecified else trackingEm.em,
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    lineHeightStyle = CssLineHeight,
)

/**
 * `.settings-body section`: 28dp padding (24 × 20 on a phone, studio.css 970), an `<h3>` (16/700,
 * -0.015em) and its `<p>` caption (13 muted, 1.65; 7dp above, 20 below). [last]: no rule under it
 * (`section:last-child { border-bottom: 0 }`).
 */
@Composable
internal fun SettingsSection(
    title: String,
    caption: AnnotatedString?,
    narrow: Boolean,
    modifier: Modifier = Modifier,
    last: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (narrow) 20.dp else 28.dp, vertical = if (narrow) 24.dp else 28.dp),
        ) {
            Text(
                title,
                color = t.white,
                style = settingsText(type.ui, 16f, 700, trackingEm = -0.015f),
                modifier = Modifier.semantics { heading() },
            )
            if (caption != null) {
                Text(
                    caption,
                    color = t.muted,
                    style = settingsText(type.ui, 13f, 400, lineHeight = 1.65f),
                    modifier = Modifier.padding(top = 7.dp, bottom = 20.dp),
                )
            } else {
                Box(Modifier.height(20.dp))
            }
            content()
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** `.settings-row` title (`strong`, 14/650, 1.5) and caption (`small`, 12 muted, 1.6). */
@Composable
internal fun SettingsRowText(
    title: String,
    caption: AnnotatedString?,
    modifier: Modifier = Modifier,
    tip: String? = null,
    titleColor: Color = LocalTetherTokens.current.ink,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var tipOpen by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = titleColor, style = settingsText(type.ui, 14f, 650, lineHeight = 1.5f))
            if (tip != null) SettingsTip(tip, open = tipOpen, onToggle = { tipOpen = !tipOpen })
        }
        if (caption != null) {
            Text(caption, color = t.muted, style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f))
        }
        if (tip != null && tipOpen) {
            // `.settings-tip-bubble` (studio.css 622): 12dp/14dp padding, 8dp radius, the case's
            // graphite with a soft lift, 12sp. Shown in the row rather than floating over it.
            Text(
                tip,
                color = t.ink,
                style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                modifier = Modifier
                    .testTag(SettingsTags.TipBubble)
                    .padding(top = 4.dp)
                    .cssSurface(RoundedCornerShape(8.dp), t.graphite, null, listOf(softShadow(6.dp, 28.dp, Color(16, 30, 58).copy(alpha = 0.2f))))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * `.settings-tip` (settings-dialog.tsx:91-108): the 11dp Info glyph after a row's title. The web
 * shows its bubble on hover or focus; a tap here opens and closes it. Its accessible name is the
 * tip itself (the web's `aria-label={text}`). Compose extends the glyph's hit area to the 48dp
 * minimum touch target, so the small glyph still takes a full-size touch.
 */
@Composable
internal fun SettingsTip(text: String, open: Boolean, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .padding(start = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                role = Role.Button
                onClick(label = if (open) "Hide tip" else "Show tip") { onToggle(); true }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .size(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TetherIcons.Info, contentDescription = null, tint = if (open) t.ink else t.muted, modifier = Modifier.size(11.dp))
    }
}

/** The rule above every `.settings-row` (`border-top: 1px solid var(--line)`). */
@Composable
internal fun RowRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalTetherTokens.current.line))
}

/**
 * `.settings-row`: at least 78dp, 17dp above and below, the text beside its control 24dp apart
 * (16 on a phone). On a phone (globals.css 3219) the control drops under the text and fills the
 * row, unless [inline] (the `.settings-toggle` switch keeps its row, globals.css 3224).
 */
@Composable
internal fun SettingsRow(
    narrow: Boolean,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    text: @Composable (Modifier) -> Unit,
    control: (@Composable (Modifier) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        RowRule()
        if (narrow && !inline) {
            Column(
                modifier.fillMaxWidth().heightIn(min = 78.dp).padding(vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                text(Modifier.fillMaxWidth())
                control?.invoke(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier.fillMaxWidth().heightIn(min = 78.dp).padding(vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 24.dp),
            ) {
                text(Modifier.weight(1f))
                control?.invoke(Modifier)
            }
        }
    }
}

/**
 * `.settings-toggle` (settings-dialog.tsx:2012): the whole row is the switch (`role="switch"`,
 * `aria-checked`); the track is Studio's 40×24 pill (studio.css 576-592): `--line-strong` off,
 * `--violet-strong` on, the 18dp white knob sliding 16dp. State is announced, never colour alone.
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    caption: String,
    tip: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    narrow: Boolean,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        narrow = narrow,
        inline = true,
        modifier = modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Switch,
            onValueChange = { onToggle() },
        ),
        text = { m -> SettingsRowText(title, AnnotatedString(caption), m, tip = tip) },
        control = { SettingsSwitchTrack(checked) },
    )
}

/** The switch's painted track and knob (decorative: the row carries the semantics). */
@Composable
internal fun SettingsSwitchTrack(checked: Boolean) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val knob by animateDpAsState(
        if (checked) 16.dp else 0.dp,
        animationSpec = if (reduced) tween(0) else tween(t.css.durationFast, easing = t.css.easeOut.toEasing()),
        label = "settings-switch",
    )
    Box(
        Modifier
            .size(40.dp, 24.dp)
            .background(if (checked) t.violetStrong else t.lineStrong, CircleShape),
    ) {
        Box(
            Modifier
                .offset { IntOffset((3.dp + knob).roundToPx(), 3.dp.roundToPx()) }
                .size(18.dp)
                .cssSurface(CircleShape, Color.White, null, listOf(softShadow(1.dp, 3.dp, Color(16, 30, 58).copy(alpha = 0.14f)))),
        )
    }
}

/**
 * One option of a `ThemeAxis` radiogroup (settings-dialog.tsx:900-937; `.theme-choice`,
 * globals.css 8515-8548, studio.css 492, 636-637): the leading glyph, label over hint, and the
 * tick. Selection is the tick, the stronger label and the wash, never colour alone; the row is a
 * radio button so TalkBack announces "selected, n of m".
 */
@Composable
internal fun ThemeChoiceRow(
    icon: ImageVector,
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    narrow: Boolean,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth()) {
        RowRule()
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 78.dp)
                .background(if (selected) t.violetWash else Color.Transparent, shape)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = if (narrow) 16.dp else 17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 24.dp),
        ) {
            Box(Modifier.width(42.dp).height(28.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = if (selected) t.white else t.muted, modifier = Modifier.size(17.dp))
            }
            SettingsRowText(label, AnnotatedString(hint), Modifier.weight(1f), titleColor = if (selected) t.white else t.ink)
            Icon(
                TetherIcons.Check,
                contentDescription = null,
                tint = t.violet,
                modifier = Modifier.size(16.dp).alpha(if (selected) 1f else 0f),
            )
        }
    }
}

/** `ThemeAxis`'s `role="radiogroup"` wrapper. */
@Composable
internal fun ThemeChoices(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().selectableGroup(), content = content)
}

/**
 * A panel (or a part of one) a later task fills in: what it will hold, in the section's own words.
 * Tagged so a test can find every slot that is still waiting.
 */
@Composable
internal fun ComingSoonNote(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier
            .testTag(SettingsTags.ComingSoon)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(8.dp), t.mineral, null, emptyList())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(TetherIcons.Info, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
        Text(text, color = t.muted, style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f))
    }
}
