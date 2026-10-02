package com.tether.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.client.LabelText
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.ProviderLogos
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** Test tags of the Studio welcome. */
object StudioWelcomeTags {
    const val Root = "studio-welcome"
    const val OpenWorkspace = "studio-welcome-open-workspace"
    const val Providers = "studio-welcome-providers"
    fun provider(index: Int) = "studio-welcome-provider:$index"
}

/**
 * components/studio-welcome.tsx (tether 887c222): Studio's empty Sessions stage — "Room for your
 * next big idea.", Start a session (the draft sheet, the sidebar's "+ New") and Open workspace
 * (the workspace folder picker), both disabled while the link is down; the three workflow tiles;
 * the providers footer. Styled by app/studio.css 413-430 (desktop) and 471-479 (below 48rem;
 * here: the phone shell, PLAN D10).
 *
 * dashboard.tsx 1721-1728 mounts it with no theme condition: Studio light and dark are the only
 * families at 887c222, so it is the empty stage in both.
 *
 * The web's "Explore the interactive demo" link (`/web#demo`, the public landing page) is not
 * ported: it advertises the product to a visitor, and the app has no outbound link to that page
 * (ta-3e7 decision, see docs/parity/screens/shell/README.md).
 */
@Composable
fun StudioWelcome(
    connected: Boolean,
    providers: List<ProviderAvailability>,
    onNewSession: () -> Unit,
    onOpenWorkspace: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val window = LocalWindowInfo.current.containerSize
    val (vw, vh) = with(LocalDensity.current) { window.width.toDp().value to window.height.toDp().value }
    // `.studio-welcome { padding: clamp(2rem, 5vw, 5rem) }`; phone `2.25rem 1.5rem`.
    val padH = if (expanded) (vw * 0.05f).coerceIn(32f, 80f).dp else 24.dp
    val padV = if (expanded) padH else 36.dp
    BoxWithConstraints(modifier.fillMaxSize().background(t.graphite).testTag(StudioWelcomeTags.Root)) {
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = padH, vertical = padV),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // `.studio-welcome-inner { width: min(100%, 64rem); margin: auto }`.
            Column(Modifier.widthIn(max = 1024.dp).fillMaxWidth()) {
                WelcomeHeading(expanded, vw)
                WelcomeCopy(expanded)
                // `.studio-welcome-actions { flex-wrap: wrap; gap: 0.75rem }`.
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WelcomeKey("Start a session", TetherIcons.Plus, KeyClasses.ButtonPrimary, connected, onNewSession, ShellTags.StartSessionKey)
                    WelcomeKey("Open workspace", TetherIcons.FolderOpen, KeyClasses.ButtonSecondary, connected, onOpenWorkspace, StudioWelcomeTags.OpenWorkspace)
                }
                Spacer(Modifier.height(if (expanded) (vh * 0.07f).coerceIn(48f, 96f).dp else 40.dp))
                Workflow(expanded)
                // `.studio-welcome-footer { padding-top: 1.5rem }`.
                Spacer(Modifier.height(24.dp))
                ProvidersFooter(providers)
            }
        }
    }
}

/** `.studio-welcome-heading`: 650, line-height 1.08, −0.04em, `max-width: 14ch`, the second line violet. */
@Composable
private fun WelcomeHeading(expanded: Boolean, vw: Float) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    // Desktop `clamp(2.8rem, 4.7vw, 4.9rem)`; phone `clamp(2.5rem, 9vw, 3.7rem)`.
    val px = if (expanded) (vw * 0.047f).coerceIn(44.8f, 78.4f) else (vw * 0.09f).coerceIn(40f, 59.2f)
    Text(
        buildAnnotatedString {
            append("Room for your\n")
            withStyle(SpanStyle(color = t.violet)) { append("next big idea.") }
        },
        color = t.white,
        style = cssText(type.ui, px / 16f, 650, trackingEm = -0.04f, lineHeight = 1.08f).copy(lineBreak = LineBreak.Heading),
        // 14ch: Manrope's "0" advances ≈ 0.62em.
        modifier = Modifier.widthIn(max = (14 * 0.62f * px).dp).semantics { heading() },
    )
}

/** `.studio-welcome-copy`: `--muted`, line-height 1.8, `max-width: 44ch`; 1rem (phone 0.9rem). */
@Composable
private fun WelcomeCopy(expanded: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val rem = if (expanded) 1f else 0.9f
    Text(
        "Your projects, your agents, one place to make progress. Open a workspace and pick up where inspiration takes you.",
        color = t.muted,
        style = cssText(type.ui, rem, 400, lineHeight = 1.8f).copy(lineBreak = LineBreak.Paragraph),
        modifier = Modifier
            .padding(top = if (expanded) 24.dp else 20.dp, bottom = if (expanded) 32.dp else 24.dp)
            .widthIn(max = (44 * 0.62f * rem * 16f).dp),
    )
}

/** `.studio-welcome-actions .button-*`: 3rem tall, `padding-inline: 1.15rem`, a 17px glyph. */
@Composable
private fun WelcomeKey(label: String, icon: ImageVector, classes: Set<com.tether.app.ui.components.KeyClass>, enabled: Boolean, onClick: () -> Unit, tag: String) {
    TetherKey(
        onClick = onClick,
        classes = classes,
        label = label,
        icon = icon,
        iconSize = 17.dp,
        minHeight = 48.dp,
        enabled = enabled,
        contentPadding = 18.4.dp,
        modifier = Modifier.testTag(tag),
    )
}

private class WorkflowItem(val icon: ImageVector, val title: String, val body: String)

private val WorkflowItems = listOf(
    WorkflowItem(TetherIcons.GitBranch, "Start with your project", "Work in an existing folder or give an idea its own worktree."),
    WorkflowItem(TetherIcons.MessageSquare, "Choose your collaborator", "Bring your preferred coding agent, models, and tools."),
    WorkflowItem(TetherIcons.Radio, "Stay in the conversation", "Follow progress and answer your agents from any screen."),
)

/**
 * `.studio-workflow`: a `1px --line` rule above and below, `padding-block: 1.75rem`; three columns
 * `gap: 2rem` (phone: one column, `gap: 1.4rem`, the glyph in a 1.4rem column beside the text).
 */
@Composable
private fun Workflow(expanded: Boolean) {
    val t = LocalTetherTokens.current
    val rules = Modifier
        .fillMaxWidth()
        .drawBehind {
            val px = 1.dp.toPx()
            drawRect(t.line, Offset.Zero, Size(size.width, px))
            drawRect(t.line, Offset(0f, size.height - px), Size(size.width, px))
        }
        .padding(vertical = 28.dp)
    if (expanded) {
        Row(rules, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            WorkflowItems.forEach { item ->
                Column(Modifier.weight(1f)) {
                    WorkflowIcon(item.icon)
                    WorkflowTitle(item.title, Modifier.padding(top = 14.4.dp, bottom = 8.dp))
                    // `max-width: 28ch` at 0.8rem.
                    WorkflowBody(item.body, Modifier.widthIn(max = (28 * 0.62f * 12.8f).dp))
                }
            }
        }
    } else {
        Column(rules, verticalArrangement = Arrangement.spacedBy(22.4.dp)) {
            WorkflowItems.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.width(22.4.dp).padding(top = 1.6.dp)) { WorkflowIcon(item.icon) }
                    Column(Modifier.weight(1f)) {
                        WorkflowTitle(item.title, Modifier.padding(bottom = 4.8.dp))
                        WorkflowBody(item.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = LocalTetherTokens.current.violet, modifier = Modifier.size(21.dp))
}

/** `.studio-workflow-item h2`: 0.875rem / 700, `--white`. */
@Composable
private fun WorkflowTitle(text: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    Text(text, color = t.white, style = cssText(LocalTetherTypography.current.ui, 0.875f, 700), modifier = modifier.semantics { heading() })
}

/** `.studio-workflow-item p`: 0.8rem, line-height 1.7, `--muted`. */
@Composable
private fun WorkflowBody(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Text(
        text,
        color = t.muted,
        style = cssText(LocalTetherTypography.current.ui, 0.8f, 400, lineHeight = 1.7f).copy(lineBreak = LineBreak.Paragraph),
        modifier = modifier,
    )
}

/**
 * The tile a verified mark gives its container (globals.css 11207-11224,
 * `:root :where(*):has(> svg.provider-logo-svg[data-brand=…])`, which outranks
 * `.studio-welcome-providers .is-available`): the background and the ink.
 */
@Composable
private fun brandTile(provider: String): Pair<Color, Color>? {
    if (ProviderLogos.mark(provider) == null) return null
    val c = LocalTetherTokens.current.css
    return when (provider) {
        "claude" -> c.brandClaude to c.brandPaper
        "codex", "opencode", "pi" -> c.brandInk to c.brandPaper
        "reasonix", "dsh", "gemini" -> c.brandPaper to c.brandBlue
        else -> null
    }
}

/**
 * `.studio-welcome-providers` ("Available agents"): wrapped, `gap: 1rem`; each entry the logo
 * (1rem) and the label at 0.72rem, `--ink` when available, else `--muted`, titled "Available" or
 * "Not configured" (TalkBack hears that state; never the colour alone). A provider with no mark
 * shows the web's fallback letter run into its label. Labels come from the server: drawn through
 * the label rule ([LabelText.label]); one that cleans to nothing spells out its id.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProvidersFooter(providers: List<ProviderAvailability>) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    FlowRow(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Available agents" }
            .testTag(StudioWelcomeTags.Providers),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        providers.forEachIndexed { index, p ->
            val label = LabelText.label(p.label).ifEmpty { LabelText.visibleValue(p.id) }
            val tile = brandTile(p.id)
            val ink = tile?.second ?: if (p.available) t.ink else t.muted
            val mark = ProviderLogos.mark(p.id)
            Row(
                Modifier
                    .then(if (tile != null) Modifier.background(tile.first) else Modifier)
                    .clearAndSetSemantics {
                        contentDescription = label
                        stateDescription = if (p.available) "Available" else "Not configured"
                    }
                    .testTag(StudioWelcomeTags.provider(index)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.4.dp),
            ) {
                val style = cssText(type.ui, 0.72f, 400)
                if (mark != null) {
                    Icon(mark, contentDescription = null, tint = ink, modifier = Modifier.size(LogoSize))
                    Text(label, color = ink, style = style)
                } else {
                    // provider-logo.tsx renders the bare letter: one text run with the label.
                    Text(ProviderLogos.fallbackLetter(p.id) + label, color = ink, style = style)
                }
            }
        }
    }
}

private val LogoSize: Dp = 16.dp
