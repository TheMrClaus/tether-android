package com.tether.app.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.ArchiveStaleReply
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

object ArchiveStaleTags {
    const val Entry = "sidebar-archive-stale"
    const val Dialog = "archive-stale-dialog"
    const val Status = "archive-stale-status"
    const val Skipped = "archive-stale-skipped"
    const val Warning = "archive-stale-warning"
    const val Note = "archive-stale-note"
    const val Run = "archive-stale-run"
    const val Cancel = "archive-stale-cancel"
    const val Stop = "archive-stale-stop"
    const val Done = "archive-stale-done"
    fun day(days: Int) = "archive-stale-day:$days"
}

/**
 * components/archive-stale-dialog.tsx: "Archive idle sessions". Opening asks for a preview of the chosen
 * threshold (7, 15 or 30 days; 30 first); the Archive key runs bounded batches, one frame in flight at a
 * time, until nothing remains, one fails, or the operator stops it. Like the web's `<dialog>`, it cannot be
 * dismissed (Back, a tap outside) while a run is in flight; Stop settles it.
 *
 * The state lives here, in the sidebar's composition, so the threshold chosen last is the one offered
 * next time (the web's dialog stays mounted in the sidebar). [reply] is the client's latest
 * `archive-stale-result`; each new one (by [ArchiveStaleReply.seq]) steps a running batch.
 */
@Composable
internal fun ArchiveStaleDialog(
    state: ArchiveStaleState,
    reply: ArchiveStaleReply?,
    onState: (ArchiveStaleState) -> Unit,
    onRequest: (ArchiveStaleRequest) -> Unit,
    onClose: () -> Unit,
) {
    val result = reply?.result
    val latestState by rememberUpdatedState(state)
    // archive-stale-dialog.tsx:46-58 — one step per reply object: a run reply tallies and continues or settles.
    LaunchedEffect(reply?.seq) {
        val r = reply?.result ?: return@LaunchedEffect
        val step = ArchiveStaleModel.onReply(latestState, r)
        if (step.state != latestState) onState(step.state)
        step.request?.let(onRequest)
    }

    val running = state.running
    val body = ArchiveStaleModel.body(state, result)
    val eligible = ArchiveStaleModel.eligible(state, result)
    TetherDialog(
        onDismiss = { if (!running) onClose() },
        title = ArchiveStaleCopy.TITLE,
        footer = {
            when {
                state.done -> TetherKey(onClick = onClose, classes = KeyClasses.ButtonSecondary, label = ArchiveStaleCopy.DONE, modifier = Modifier.testTag(ArchiveStaleTags.Done))
                running -> TetherKey(
                    onClick = { onState(ArchiveStaleModel.stop(state)) },
                    classes = KeyClasses.ButtonSecondary,
                    label = ArchiveStaleCopy.STOP,
                    modifier = Modifier.testTag(ArchiveStaleTags.Stop),
                )
                else -> {
                    TetherKey(onClick = onClose, classes = KeyClasses.ButtonSecondary, label = ArchiveStaleCopy.CANCEL, modifier = Modifier.testTag(ArchiveStaleTags.Cancel))
                    TetherKey(
                        onClick = {
                            val step = ArchiveStaleModel.start(state)
                            onState(step.state)
                            step.request?.let(onRequest)
                        },
                        classes = KeyClasses.ButtonDanger,
                        label = if (eligible != null && eligible > 0) ArchiveStaleCopy.archiveKey(eligible) else ArchiveStaleCopy.NOTHING,
                        enabled = eligible != null && eligible > 0,
                        modifier = Modifier.testTag(ArchiveStaleTags.Run),
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(ArchiveStaleTags.Dialog), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DayChoices(state.days, enabled = !running) { days ->
                val step = ArchiveStaleModel.choose(state, days)
                onState(step.state)
                step.request?.let(onRequest)
            }
            when (body) {
                is ArchiveStaleBody.Done -> Status(AnnotatedString(body.text))
                is ArchiveStaleBody.Running -> Status(AnnotatedString(body.text))
                ArchiveStaleBody.Checking -> Status(AnnotatedString(ArchiveStaleCopy.CHECKING))
                is ArchiveStaleBody.Preview -> {
                    Status(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(ArchiveStaleCopy.previewCount(body.eligible)) }
                            append(ArchiveStaleCopy.previewAfter(body.eligible, body.days))
                        },
                    )
                    body.skipped?.let { Faint(it, ArchiveStaleTags.Skipped) }
                    body.retention?.let { Warning(it) }
                    Faint(ArchiveStaleCopy.WORKTREE_NOTE, ArchiveStaleTags.Note)
                }
            }
        }
    }
}

/** `.archive-stale-days` (globals.css 11347): three equal chips, 44px targets, violet only on the chosen one. */
@Composable
private fun DayChoices(selected: Int, enabled: Boolean, onChoose: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .semantics { contentDescription = ArchiveStaleCopy.DAYS_LABEL },
        horizontalArrangement = Arrangement.spacedBy(LocalTetherTokens.current.css.spaceSm),
    ) {
        ArchiveStaleModel.THRESHOLDS.forEach { days -> DayChip(days, days == selected, enabled, onChoose) }
    }
}

@Composable
private fun RowScope.DayChip(days: Int, on: Boolean, enabled: Boolean, onChoose: (Int) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.6f))
            .cssSurface(shape, if (on) t.violetWash else androidx.compose.ui.graphics.Color.Transparent, CssBorder(1.dp, if (on) t.violetStrong else t.line), emptyList())
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = on
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.RadioButton) { onChoose(days) }
            .testTag(ArchiveStaleTags.day(days)),
        contentAlignment = Alignment.Center,
    ) {
        Text(ArchiveStaleCopy.dayLabel(days), style = css(type.ui, 0.8f, 400), color = if (on) t.white else t.muted)
    }
}

/** A `<p role="status">`: announced when it changes. */
@Composable
private fun Status(text: AnnotatedString) {
    TetherDialogText(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ArchiveStaleTags.Status))
}

/** `.archive-stale-skipped` (0.78rem, --faint). */
@Composable
private fun Faint(text: String, tag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(text, color = t.faint, style = css(type.ui, 0.78f, 400, lineHeight = 1.55f), modifier = Modifier.testTag(tag))
}

/** `.archive-stale-warning` (0.8rem, --white, a 2px --line-strong rule on the left). */
@Composable
private fun Warning(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        color = t.white,
        style = css(type.ui, 0.8f, 400, lineHeight = 1.55f),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehindRule(t.lineStrong)
            .padding(start = t.css.spaceSm + 2.dp)
            .testTag(ArchiveStaleTags.Warning),
    )
}

private fun Modifier.drawBehindRule(color: androidx.compose.ui.graphics.Color): Modifier = this.then(
    Modifier.drawBehind { drawRect(color, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) },
)
