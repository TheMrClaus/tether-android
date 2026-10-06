package com.tether.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens

/**
 * Studio's dialog chrome is written as literals in studio.css (504-514), not tokens: 16px radius,
 * no border, `0 24px 80px rgb(16 30 58 / 0.2), 0 4px 16px rgb(16 30 58 / 0.08)`, and a
 * `rgb(16 25 44 / 0.34)` backdrop.
 */
object StudioDialog {
    val radius: Dp = 16.dp
    val shadows: List<CssShadow> = listOf(
        softShadow(24.dp, 80.dp, Color(16, 30, 58).copy(alpha = 0.2f)),
        softShadow(4.dp, 16.dp, Color(16, 30, 58).copy(alpha = 0.08f)),
    )
    val scrim: Color = Color(16, 25, 44).copy(alpha = 0.34f)
}

/** The skin's modal backdrop (`--scrim`; Studio's literal). */
fun dialogScrim(t: TetherTokens): Color = StudioDialog.scrim

/**
 * The molded case every dialog is (`.confirm-dialog`, globals.css 3584-3632 + material layer
 * 9032-9039): `var(--graphite)`, `1px var(--key-side)` edge, `--radius-lg`, and the skin's
 * `--edge-highlight` + `--shadow-modal` lists. Width `min(24rem, 100vw - 1.5rem)`. Studio
 * (studio.css 504-547): borderless, 16px, its literal soft shadow, `min(440px, 100vw - 32px)`.
 * Header: 1.05rem/650 white title (Studio 22px/700). Footer: right-aligned keys over a `--line`
 * rule. This is the inline surface; [TetherDialog] hosts it in a window.
 */
@Composable
fun TetherDialogSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    /** ta-28i: a title that carries server text drawn by a SafeText rule (wins over [title]). */
    styledTitle: AnnotatedString? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    // ta-5tb: the dialog's title is its pane title, so a screen reader announces the window by name.
    val paneName = styledTitle?.text ?: title
    BoxWithConstraints(if (paneName != null) modifier.semantics { paneTitle = paneName } else modifier) {
        val width = minOf(440.dp, maxWidth - 32.dp)
        val shape = RoundedCornerShape(StudioDialog.radius)
        Column(
            Modifier
                .width(width)
                .cssSurface(
                    shape, t.graphite,
                    null,
                    StudioDialog.shadows,
                ),
        ) {
            Column(
                Modifier
                    .then(if (scrollable) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier)
                    .padding(
                        start = 28.dp,
                        end = 28.dp,
                        top = 28.dp,
                        bottom = 28.dp,
                    ),
            ) {
                if (title != null || styledTitle != null) {
                    Text(
                        text = styledTitle ?: AnnotatedString(title.orEmpty()),
                        color = t.white,
                        style = type.body.copy(fontSize = 22.sp, fontWeight = FontWeight(700), letterSpacing = (-0.025).em, lineHeight = 1.3.em),
                        modifier = Modifier
                            .semantics { heading() }
                            .padding(bottom = 12.dp),
                    )
                }
                content()
            }
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            }
        }
    }
}

/** Dialog body copy (`.confirm-dialog p`: 0.8rem muted, 1.55; Studio 14px, 1.65). */
@Composable
fun TetherDialogText(text: String, modifier: Modifier = Modifier) = TetherDialogText(AnnotatedString(text), modifier)

/** ta-28i: dialog body copy that carries server text drawn by a SafeText rule. */
@Composable
fun TetherDialogText(text: AnnotatedString, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        color = t.muted,
        style = type.body.copy(fontSize = 14.sp, lineHeight = 1.65.em),
        modifier = modifier,
    )
}

/**
 * A modal dialog: the skin's scrim, then [TetherDialogSurface] rising in with `dialog-in`
 * (`opacity 0, translateY(0.5rem) scale(0.99)` → rest over `--duration` `--ease-out`, globals.css
 * 3869/3595). Reduced motion: it simply appears.
 */
@Composable
fun TetherDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NoWindowDim()
        ModalScrim(onDismiss, dialogScrim(t)) {
            val progress = rememberEnterProgress()
            TetherDialogSurface(
                title = title,
                footer = footer,
                content = content,
                modifier = Modifier
                    // ta-5tb: a pointer-only tap swallower, not `clickable` (which merged the whole dialog into one button).
                    .swallowTaps()
                    .graphicsLayer {
                        val p = progress.value
                        alpha = p
                        translationY = (1f - p) * 8.dp.toPx()
                        scaleX = 0.99f + 0.01f * p
                        scaleY = 0.99f + 0.01f * p
                    },
            )
        }
    }
}

/** Our scrim replaces the platform's black window dim, so the backdrop is the skin's colour. */
@Composable
internal fun NoWindowDim() {
    val view = LocalView.current
    SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
}

@Composable
internal fun ModalScrim(onDismiss: () -> Unit, color: Color, alignment: Alignment = Alignment.Center, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(color)
            // ta-5tb/ta-6gw: a pointer-only tap-to-dismiss, not `clickable` (its mergeDescendants would fold the
            // whole dialog or sheet above the scrim back into one node). The window's back press still dismisses.
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = alignment,
    ) { content() }
}

/** 0 → 1 over `--duration` with `--ease-out`; 1 at once under reduced motion. */
@Composable
internal fun rememberEnterProgress(): Animatable<Float, *> {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) progress.animateTo(1f, tween(t.css.duration, easing = t.css.easeOut.toEasing()))
    }
    return progress
}
