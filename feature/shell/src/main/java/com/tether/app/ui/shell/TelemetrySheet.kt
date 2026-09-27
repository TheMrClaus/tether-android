package com.tether.app.ui.shell

import androidx.compose.foundation.gestures.detectTapGestures
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.CssBorder
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/**
 * `<section className="telemetry-sheet is-open">` at the phone layout (components/telemetry-sheet.tsx):
 * the narrow-viewport home of the full telemetry set. Not a modal and not a floating bottom sheet —
 * the web's v5x form is a collapsible panel IN the workspace column, right under the header, that
 * claims the column's space while open (`.telemetry-sheet.is-open + .terminal-stage { display: none }`)
 * and collapses back into its handle, the header gauge (globals.css 3775-3852, 11911-11921).
 *
 * `--graphite` panel with a `1px --line` top edge; header `padding: space-sm space-lg` with a
 * `1px --line` rule, "Session details" at 1rem / 650; a scrolling body at `space-md space-lg`.
 * Opening plays `dialog-in` (fade + 0.5rem rise + 0.99 scale over `--duration`); reduced motion
 * jumps. Focus moves into the panel on open (the web focuses the section).
 *
 * [body] is the inspector slot (T9.1: the same `Inspector` the expanded layout's third column shows).
 *
 * [floating] (the expanded layout between 48rem and 100rem, T4.2): "details float beside the
 * conversation" — the same panel as a card over the stage, `1px --line-strong` all round,
 * `--radius-lg`, `--shadow-floating`; the stage stays visible (globals.css 11894-11910). The host
 * places it (`top: 4.5rem; right/bottom: space-sm; width: min(23rem, 100% - 1rem)`).
 */
@Composable
fun TelemetrySheet(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val progress = rememberDialogIn()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
                scaleX = 0.99f + 0.01f * p
                scaleY = 0.99f + 0.01f * p
            }
            .then(
                if (floating) {
                    Modifier
                        .cssSurface(RoundedCornerShape(t.radiusLg), t.graphite, CssBorder(1.dp, t.lineStrong), t.css.shadowFloating)
                        .clip(RoundedCornerShape(t.radiusLg))
                } else {
                    Modifier.drawBehind {
                        drawRect(t.graphite)
                        drawRect(t.line, Offset.Zero, Size(size.width, 1.dp.toPx()))
                    }
                },
            )
            // The panel is opaque to touches: nothing below it (the collapsed stage) reacts.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(if (floating) PaddingValues(1.dp) else PaddingValues(top = 1.dp))
            .semantics { paneTitle = "Session details" }
            .focusRequester(focus)
            .focusTarget()
            .testTag(ShellTags.TelemetrySheet),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                .padding(bottom = 1.dp)
                .padding(horizontal = t.css.spaceLg, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg),
        ) {
            Text(
                "Session details",
                color = t.white,
                style = cssText(type.ui, 1f, 650, trackingEm = -0.02f),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            ChromeIconKey(
                onClick = onClose,
                icon = TetherIcons.X,
                contentDescription = "Close",
                look = rememberIconLook(t.ink, t.radiusSm),
                width = 44.dp,
                height = 44.dp,
                iconSize = 19.dp,
                modifier = Modifier.testTag(ShellTags.TelemetryClose),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = t.css.spaceLg, vertical = t.css.spaceMd),
                content = body,
            )
        }
    }
}
