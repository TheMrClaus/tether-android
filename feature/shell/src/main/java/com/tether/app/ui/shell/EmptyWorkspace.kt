package com.tether.app.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** One `.provider-availability` entry (the providers list of the `ready` frame). */
data class ProviderAvailability(val label: String, val available: Boolean)

/** What the workspace shows when no session is open (dashboard.tsx:1480-1528). */
sealed interface EmptyStage {
    /** "Start where the work lives." — the welcome stage. */
    data class Welcome(val connected: Boolean, val providers: List<ProviderAvailability>) : EmptyStage

    /**
     * Issue #189: a session is remembered but its snapshot has not arrived — "Reopening your
     * session." while connected, "Reconnecting." while the link is down.
     */
    data class Reopening(val connected: Boolean) : EmptyStage
}

/**
 * `<section className="empty-workspace">` at the phone layout: the empty state lives in the same
 * screen well as the transcript — `--mineral-deep` floor, `1px --line-strong`, `--radius-lg`,
 * margin `space-md`, padding `space-xl space-lg`, content centred
 * (globals.css 2218-2295, 11241-11269, 11713-11717). The orbit turns once per 24s (static under
 * reduced motion). Studio forks this stage into `StudioWelcome` (T8.1); [studioWelcome] is that
 * slot, and until it is filled Studio renders this composition in its own tokens.
 *
 * [expanded] (from 48rem, T4.2): the well is seated in the bay like the chat screen —
 * `margin: calc(space-lg + 7px)`, `padding: space-2xl space-xl`, the contact shade
 * (globals.css 11241-11252; the `--well` / `--bezel` shading was retired at tether 887c222, both
 * transparent in Studio) — and the title follows `clamp(1.7rem, 2.6vw, 2.15rem)`
 * of [viewportWidth] (11257).
 */
@Composable
fun EmptyWorkspace(
    stage: EmptyStage,
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
    studioWelcome: (@Composable () -> Unit)? = null,
    expanded: Boolean = false,
    viewportWidth: Int = 0,
) {
    val t = LocalTetherTokens.current
    if (studioWelcome != null && stage is EmptyStage.Welcome) {
        studioWelcome()
        return
    }
    val type = LocalTetherTypography.current
    Column(
        modifier
            .fillMaxSize()
            .padding(if (expanded) t.css.spaceLg + 7.dp else t.css.spaceMd)
            .cssSurface(
                RoundedCornerShape(t.radiusLg),
                t.mineralDeep,
                CssBorder(1.dp, t.lineStrong),
                if (expanded) listOf(softShadow(2.dp, 6.dp, t.contact.copy(alpha = 0.1f), spread = 7.dp)) else emptyList(),
            )
            .padding(1.dp)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (expanded) t.css.spaceXl else t.css.spaceLg,
                vertical = if (expanded) t.css.space2xl else t.css.spaceXl,
            )
            .semantics { if (stage is EmptyStage.Reopening) stateDescription = "Busy" }
            .testTag(ShellTags.EmptyWorkspace),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyInstrument()
        Spacer(Modifier.height(t.css.space2xl))
        val (title, body) = when (stage) {
            is EmptyStage.Welcome -> "Start where the work lives." to
                "Launch an agent in a real terminal session, then keep it close from any trusted screen."
            is EmptyStage.Reopening -> if (stage.connected) {
                "Reopening your session." to "Loading the conversation you had open."
            } else {
                "Reconnecting." to "Restoring the link to Tether."
            }
        }
        Text(
            title,
            color = t.white,
            textAlign = TextAlign.Center,
            style = cssText(type.ui, if (expanded) (viewportWidth * 0.026f / 16f).coerceIn(1.7f, 2.15f) else 1.7f, 640, trackingEm = -0.035f, lineHeight = 1.18f)
                .copy(lineBreak = LineBreak.Heading),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(t.css.spaceMd))
        Text(
            body,
            color = t.muted,
            textAlign = TextAlign.Center,
            style = cssText(type.ui, 0.95f, 400, lineHeight = 1.65f).copy(lineBreak = LineBreak.Paragraph),
            // `max-width: 38ch` (Manrope's "0" advances ≈ 0.62em); on a phone the well is narrower.
            modifier = Modifier.widthIn(max = (38 * 0.95f * 16f * 0.62f).dp),
        )
        if (stage is EmptyStage.Welcome) {
            Spacer(Modifier.height(t.css.space2xl))
            // `:root .empty-workspace .button-primary`: 3rem tall, 0.78rem legend.
            TetherKey(
                onClick = onStartSession,
                classes = KeyClasses.ButtonPrimary,
                label = "Start first session",
                icon = TetherIcons.Plus,
                iconSize = 17.dp,
                fontSize = 12.48.sp,
                minHeight = 48.dp,
                enabled = stage.connected,
                // TetherKey pads its legend `0 var(--space-lg)`; this key's rule asks `space-xl`.
                modifier = Modifier.extraWidth((t.css.spaceXl - t.css.spaceLg) * 2).testTag(ShellTags.StartSessionKey),
            )
            ProviderAvailabilityRow(stage.providers)
        }
    }
}

/**
 * `.empty-instrument` (7rem): the orbit ring (`--line-strong`) with its two inner rings
 * (`--line` at 0.7rem, `--violet-deep` at 1.45rem) and the violet satellite dot, around the
 * violet 25px radio glyph.
 */
@Composable
private fun EmptyInstrument() {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val angle by if (reduced) {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "orbit").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
            label = "orbitAngle",
        )
    }
    Box(Modifier.size(112.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val px = 1.dp.toPx()
            val c = center
            fun ring(inset: Float, color: androidx.compose.ui.graphics.Color) {
                val r = size.minDimension / 2f - inset - px / 2f
                drawCircle(color, r, c, style = Stroke(px))
            }
            rotate(angle, c) {
                ring(0f, t.lineStrong)
                ring(11.2.dp.toPx(), t.line)
                ring(23.2.dp.toPx(), t.violetDeep)
                val d = 7.68.dp.toPx()
                drawCircle(t.violet, d / 2f, Offset(c.x, -3.84.dp.toPx() + d / 2f))
            }
        }
        Icon(TetherIcons.Radio, contentDescription = null, tint = t.violet, modifier = Modifier.size(25.dp))
    }
}

/**
 * `.provider-availability` ("Available agents"): wrapped, centred, `space-md space-lg` gaps,
 * `margin-top: space-2xl`, a `1px --line` rule with `space-lg` above the entries, mono 0.62rem /
 * 650, tracked 0.06em, uppercase. Available = `--active` dot + `--muted` legend; otherwise both
 * `--faint`. TalkBack hears "available" / "unavailable" — never the dot colour alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderAvailabilityRow(providers: List<ProviderAvailability>) {
    if (providers.isEmpty()) return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier
            .padding(top = t.css.space2xl)
            .fillMaxWidth()
            .drawBehind { drawRect(t.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
            .padding(top = t.css.spaceLg + 1.dp)
            .semantics { contentDescription = "Available agents" },
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        ) {
            providers.forEach { p ->
                val ink = if (p.available) t.muted else t.faint
                Row(
                    Modifier.clearAndSetSemantics {
                        contentDescription = p.label
                        stateDescription = if (p.available) "available" else "unavailable"
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.4.dp),
                ) {
                    StatusDot(if (p.available) t.css.active else t.faint, size = 6.4.dp)
                    Text(
                        p.label.uppercase(),
                        color = ink,
                        style = cssText(type.mono, 0.62f, 650, trackingEm = 0.06f),
                    )
                }
            }
        }
    }
}
