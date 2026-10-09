package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.TetherSheet
import com.tether.app.ui.components.TetherSheetSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * ta-a5jl: the detail sheet a tap on an activity row opens. Its body is the SAME renderer the transcript drew before the
 * rows existed (the generic card, the engines' rich cards, the thinking text), at the sheet's full width and on the
 * sheet's own clamp, so nothing the expanded card showed is lost: the head, the input and its diffs, the sub-agent thread
 * with its paging, the interrupted evidence, the pictures, the output with "Show more", the copy keys.
 */

/**
 * The sheet for [target] (resolved live by the host from the projection, so streaming output, a run ending and
 * sub-agent paging update in place). A phone docks it as every sheet here; wider windows get a centred card up to 720 dp.
 */
@Composable
internal fun ActivitySheet(target: ActivityTarget, flags: ToolRenderFlags, onDismiss: () -> Unit) {
    val model = remember(target) { target.rowModel() }
    TetherSheet(
        onDismiss = onDismiss,
        title = model.verb,
        icon = model.glyph.icon(),
        cardWidth = activitySheetCardWidth(),
    ) {
        ActivitySheetBody(target, flags)
    }
}

/** The sheet's surface drawn in place (no window): what the boards shoot, since a Dialog is not in a node capture. */
@Composable
internal fun ActivitySheetSurface(target: ActivityTarget, flags: ToolRenderFlags, modifier: Modifier = Modifier, docked: Boolean = currentLayoutClass() == TetherLayoutClass.Phone) {
    val model = remember(target) { target.rowModel() }
    TetherSheetSurface(
        title = model.verb,
        modifier = modifier,
        docked = docked,
        onClose = {},
        icon = model.glyph.icon(),
        cardWidth = activitySheetCardWidth(),
    ) {
        ActivitySheetBody(target, flags)
    }
}

private fun ActivityTarget.rowModel(): ActivityRowModel = when (this) {
    is ActivityTarget.Tool -> activityRowModel(raw)
    is ActivityTarget.Thinking -> thinkingRowModel(block)
}

/** A phone's sheet is docked edge to edge; a wider window gets a centred card up to 720 dp. */
@Composable
private fun activitySheetCardWidth(): Dp {
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val screenWidth = LocalConfiguration.current.screenWidthDp
    return if (phone) 352.dp else minOf(720, screenWidth - 48).coerceAtLeast(0).dp
}

@Composable
private fun ColumnScope.ActivitySheetBody(target: ActivityTarget, flags: ToolRenderFlags) {
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    CompositionLocalProvider(
        LocalFullWidthCards provides true,
        LocalToolClamp provides if (phone) 320.dp else 480.dp,
    ) {
        // The words are selectable and copyable here, as the card's were in the transcript (the row itself is a button).
        SelectableRow {
            when (target) {
                is ActivityTarget.Tool -> ToolBlockView(target.raw, flags)
                is ActivityTarget.Thinking -> ThinkingSheetBody(target.block.text.orEmpty())
            }
        }
    }
}

/** The thinking text as the collapsed card's body drew it (muted 0.82rem / 1.6 markdown), without its 13rem clamp: the sheet scrolls. */
@Composable
private fun ThinkingSheetBody(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val blocks = remember(text) { parseMarkdown(text) }
    Box(Modifier.fillMaxWidth().padding(8.dp)) {
        MarkdownBody(blocks, type.chatBody.copy(fontSize = 13.12.sp, lineHeight = 1.6.em), t.muted)
    }
}
