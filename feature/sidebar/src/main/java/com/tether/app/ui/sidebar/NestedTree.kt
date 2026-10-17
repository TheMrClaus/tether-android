package com.tether.app.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tether.app.ui.icons.ProviderBrand
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.LocalReducedMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue

/**
 * ta-q2t6 slice 2 (the phone's open tree, web studio.css 1381-1405): the session row's place in a group. Kept in its own file so the
 * very large `SessionRow` composable gets no new logic (S3a VerifyError guard); the row only branches to [NestedLead].
 */
internal enum class SessionVariant { Top, Member, Delegate }

internal object NestedTags {
    const val Lead = "nested-elbow-lead"
    const val Mark = "nested-elbow-mark"
}

internal object NestedTree {
    /** The head spine's x (the head tile's centre), from the group's origin. */
    val SpineX = 24.dp
    /** `.session-chain-rows, .session-children-rows` padding-left (1.875rem). */
    val RowsPad = 30.dp
    /** WC1: the elbow's horizontal run = [RowsPad] - [SpineX] (the web's own formula), and the nested rows' title padding. */
    val Run = 6.dp
    val MemberSlot = 36.dp
    val DelegateSlot = 28.dp
    val Radius = 6.dp
}

/** A member row's lead: `↳` (16, `faint`), plus a 16 provider mark when [provider] differs from [headProvider] (web `differsFromHead`). */
@Composable
internal fun ElbowLead(headProvider: String?, provider: String) {
    val t = LocalTetherTokens.current
    val differs = !headProvider.isNullOrEmpty() && headProvider != provider
    Row(Modifier.testTag(NestedTags.Lead), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SmallIcon(TetherIcons.CornerDownRight, t.faint, 16.dp)
        if (differs) {
            val branded = ProviderBrand.tile(provider, t.css) != null
            Box(Modifier.size(16.dp).clearAndSetSemantics { testTag = NestedTags.Mark }) {
                ProviderTile(
                    provider,
                    Modifier.size(16.dp),
                    shape = if (branded) RoundedCornerShape(4.dp) else androidx.compose.ui.graphics.RectangleShape,
                    color = t.ink,
                    markSize = if (branded) 12.dp else 16.dp,
                )
            }
        }
    }
}

/** The phone's leading slot of a nested row (replaces the 48 dp handle): no drag handle, no tile for a member, the 22 glyph for a delegate. */
@Composable
internal fun NestedLead(variant: SessionVariant, provider: String, headProvider: String?, selected: Boolean) {
    if (variant == SessionVariant.Member) {
        Box(Modifier.width(NestedTree.MemberSlot).heightIn(min = PhoneLook.RowMinHeight), contentAlignment = Alignment.Center) {
            ElbowLead(headProvider, provider)
        }
    } else {
        Box(Modifier.width(NestedTree.DelegateSlot).heightIn(min = PhoneLook.RowMinHeight), contentAlignment = Alignment.Center) {
            ProviderCap(provider, PhoneLook.ChildTile, selected = selected, letterRem = 0.65f, monochrome = PhoneLook.MonochromeProviderTile, phoneTile = true)
        }
    }
}

/**
 * The connector of one nested row, drawn by its wrapper (no semantics): from the spine (6 dp before the wrapper, the wrapper sits
 * inside [NestedTree.RowsPad]) an elbow to the row's edge at mid-height (radius 6, 1 dp), and, unless [isLast], the spine on to the bottom.
 * Mirrors in RTL.
 */
internal fun Modifier.nestedConnector(isLast: Boolean, color: Color): Modifier = drawBehind {
    val flip = layoutDirection == LayoutDirection.Rtl
    val run = NestedTree.Run.toPx()
    fun x(fromStart: Float) = if (flip) size.width - fromStart else fromStart
    val mid = size.height / 2
    val r = minOf(NestedTree.Radius.toPx(), run)
    val stroke = Stroke(1.dp.toPx())
    val path = Path().apply {
        moveTo(x(-run), 0f)
        lineTo(x(-run), mid - r)
        quadraticTo(x(-run), mid, if (flip) x(-run) - r else x(-run) + r, mid)
        lineTo(x(0f), mid)
    }
    drawPath(path, color, style = stroke)
    if (!isLast) {
        drawLine(color, androidx.compose.ui.geometry.Offset(x(-run), mid), androidx.compose.ui.geometry.Offset(x(-run), size.height), stroke.width)
    }
}

/** The head spine through a group toggle: CLOSED the slice-1 └ into the toggle's mid-height, OPEN straight on (web `bottom:0`). */
internal fun Modifier.headSpine(open: Boolean, color: Color): Modifier = drawBehind {
    val flip = layoutDirection == LayoutDirection.Rtl
    fun x(v: Float) = if (flip) size.width - v.dp.toPx() else v.dp.toPx()
    val stroke = Stroke(1.dp.toPx())
    if (open) {
        drawLine(color, androidx.compose.ui.geometry.Offset(x(24f), 0f), androidx.compose.ui.geometry.Offset(x(24f), size.height), stroke.width)
        return@drawBehind
    }
    val mid = size.height / 2
    val r = 6.dp.toPx()
    val path = Path().apply {
        moveTo(x(24f), 0f)
        lineTo(x(24f), mid - r)
        quadraticTo(x(24f), mid, if (flip) x(24f) - r else x(24f) + r, mid)
        lineTo(x(36f), mid)
    }
    drawPath(path, color, style = stroke)
}

/** The rows under a group's toggle: phone = indented 30 (the connectors are each row's own), the rail keeps its 1 dp rule. */
@Composable
internal fun NestedRows(phone: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    if (phone) {
        Column(Modifier.padding(start = NestedTree.RowsPad), content = content)
    } else {
        Column(
            Modifier
                .padding(start = 1.1f.rem)
                .drawBehind { drawRect(t.line, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
                .padding(start = 1.dp + 0.2f.rem),
            content = content,
        )
    }
}

/** One nested row with its connector (phone only; the rail draws the row as is). */
@Composable
internal fun NestedItem(phone: Boolean, isLast: Boolean, content: @Composable () -> Unit) {
    if (!phone) {
        content()
        return
    }
    val color = LocalTetherTokens.current.lineStrong
    Box(Modifier.nestedConnector(isLast, color)) { content() }
}

/** The phone's "N delegates" toggle: the chain toggle's voice (chevron 16 turning 90 degrees when open, 13/500) and the same head spine. */
@Composable
internal fun PhoneDelegateToggle(count: Int, open: Boolean, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val rotation by animateFloatAsState(
        if (open) 90f else if (rtl) 180f else 0f,
        if (reduced) snap() else tween(t.css.durationFast, easing = t.css.easeOut.toEasing()),
        label = "delegateChevron",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PhoneDrawer.Floor)
            .headSpine(open, t.lineStrong)
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = 38.dp, end = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(TetherIcons.ChevronRight, t.faint, 16.dp, Modifier.padding(end = 2.dp).rotate(rotation))
            Text("$count delegate${if (count == 1) "" else "s"}", style = css(type.ui, 0.8125f, 500, lineHeight = 1.3846f).ltrWords(), color = t.muted, maxLines = 1)
        }
    }
}
