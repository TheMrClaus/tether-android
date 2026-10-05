package com.tether.app.ui.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.PendingMetadataDraft
import com.tether.app.client.TetherClient
import com.tether.app.protocol.MetadataDraft
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogSurface
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeText
import com.tether.app.ui.text.putOnClipboard
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * T8.5: components/metadata-draft-panel.tsx (tether 90fbb9f :12-137), mounted at the dashboard's
 * root (dashboard.tsx:1886-1887, "Draft results stay visible even when the phone details panel
 * hides chat") and so here at the shell's, over the phone's telemetry sheet and the tablet's
 * inspector column alike. The `.folder-dialog` modal surfaces ONE draft at a time, the latest
 * reply; dismissing it (×, Dismiss, Back or the scrim, the web's Esc) drops that one, and the one
 * before it, if any, shows next. Non-injecting: the draft is only copied, never sent to the agent.
 * The list is the client's ([TetherClient.metadataDrafts]): console-wide, not per session.
 */

object MetadataDraftTags {
    const val Dialog = "metadata-draft-dialog"
    const val Title = "metadata-draft-title"
    const val Close = "metadata-draft-close"
    const val Dismiss = "metadata-draft-dismiss"
    const val Error = "metadata-draft-error"
    const val CopyMessage = "metadata-draft-copy-message"
    const val CopyTitle = "metadata-draft-copy-title"
    const val CopyBody = "metadata-draft-copy-body"
    const val Text = "metadata-draft-text"
    const val PrTitle = "metadata-draft-pr-title"
    const val PrBody = "metadata-draft-pr-body"
}

/** The words of metadata-draft-panel.tsx, verbatim. */
object MetadataDraftCopy {
    const val COMMIT_TITLE = "Draft commit message"
    const val PULL_REQUEST_TITLE = "Draft pull request"
    const val FAILED_TITLE = "Draft failed"
    const val DISMISS = "Dismiss"
    const val FALLBACK_ERROR = "The draft could not be generated."
    const val COPY_MESSAGE = "Copy message"
    const val COPY_MESSAGE_FAILED = "Copy failed — try again"
    const val COPY = "Copy"
    const val COPY_FAILED = "Failed"
    const val COPIED = "Copied"
    const val TITLE = "Title"
    const val BODY = "Body"

    /** :60-62. */
    fun title(draft: MetadataDraft): String = when (draft) {
        is MetadataDraft.CommitMessage -> COMMIT_TITLE
        is MetadataDraft.PullRequest -> PULL_REQUEST_TITLE
        is MetadataDraft.Failure -> FAILED_TITLE
    }

    /** :73 `draft.error || "The draft could not be generated."`. */
    fun error(draft: MetadataDraft.Failure): String = draft.error.ifEmpty { FALLBACK_ERROR }
}

/** dashboard.tsx:1887: `{pendingDrafts.length > 0 && <MetadataDraftPanel … onDismiss={dismissDraft} />}`. */
@Composable
fun MetadataDraftPanelHost(client: TetherClient) {
    val drafts by client.metadataDrafts.collectAsStateWithLifecycle()
    if (drafts.isNotEmpty()) MetadataDraftPanel(drafts, client::dismissMetadataDraft)
}

/** :12-53: the modal over the latest draft; its copied / failed keys live as long as it is mounted. */
@Composable
fun MetadataDraftPanel(drafts: List<PendingMetadataDraft>, onDismiss: (requestId: String) -> Unit) {
    val draft = drafts.lastOrNull() ?: return
    val copy = rememberCopyState()
    val dismiss = { onDismiss(draft.requestId) }
    TetherDialog(onDismiss = dismiss, footer = { MetadataDraftFooter(dismiss) }) {
        MetadataDraftBody(draft, copy, dismiss)
    }
}

/** The panel drawn in place over the skin's scrim (the goldens; the modal hosts the same surface). */
@Composable
internal fun MetadataDraftPanelFrame(draft: PendingMetadataDraft, copiedKey: String? = null, copyErrorKey: String? = null) {
    val t = LocalTetherTokens.current
    val copy = rememberCopyState(copiedKey, copyErrorKey)
    Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        TetherDialogSurface(footer = { MetadataDraftFooter {} }) { MetadataDraftBody(draft, copy) {} }
    }
}

/** :17-18 `copiedKey` / `copyErrorKey`, keyed `${requestId}::text|title|body`. */
internal class CopyState(copiedKey: String?, copyErrorKey: String?) {
    var copiedKey by mutableStateOf(copiedKey)
    var copyErrorKey by mutableStateOf(copyErrorKey)

    /** :40-49: the one copied key on success; the one failed key on failure. */
    fun result(key: String, ok: Boolean) {
        copiedKey = if (ok) key else null
        copyErrorKey = if (ok) null else key
    }
}

@Composable
private fun rememberCopyState(copiedKey: String? = null, copyErrorKey: String? = null) = remember { CopyState(copiedKey, copyErrorKey) }

@Composable
private fun RowScope.MetadataDraftFooter(onDismiss: () -> Unit) {
    // :134: the footer's "Dismiss".
    TetherKey(onClick = onDismiss, classes = KeyClasses.ButtonSecondary, label = MetadataDraftCopy.DISMISS, modifier = Modifier.testTag(MetadataDraftTags.Dismiss))
}

@Composable
private fun ColumnScope.MetadataDraftBody(pending: PendingMetadataDraft, copy: CopyState, onDismiss: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val draft = pending.draft
    val title = MetadataDraftCopy.title(draft)
    Column(Modifier.fillMaxWidth().testTag(MetadataDraftTags.Dialog).semantics { paneTitle = title }) {
        // :57-68: the heading and the × ("Dismiss").
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = t.white,
                style = type.body.copy(fontSize = 22.sp, fontWeight = FontWeight(700), letterSpacing = (-0.025).em, lineHeight = 1.3.em),
                modifier = Modifier.weight(1f).semantics { heading() }.testTag(MetadataDraftTags.Title),
            )
            TetherKey(
                onClick = onDismiss,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 19.dp,
                contentDescription = MetadataDraftCopy.DISMISS,
                modifier = Modifier.testTag(MetadataDraftTags.Close),
            )
        }
        Box(Modifier.padding(top = t.css.spaceMd)) {
            when (draft) {
                is MetadataDraft.Failure -> FailureBody(draft)
                is MetadataDraft.CommitMessage -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.css.spaceMd)) {
                    // :77-93: the message, then "Copy message".
                    Pre(draft.text, Modifier.testTag(MetadataDraftTags.Text))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CopyKey(
                            key = "${pending.requestId}::text",
                            text = draft.text,
                            idle = MetadataDraftCopy.COPY_MESSAGE,
                            failed = MetadataDraftCopy.COPY_MESSAGE_FAILED,
                            label = "commit message",
                            copy = copy,
                            tag = MetadataDraftTags.CopyMessage,
                        )
                    }
                }
                is MetadataDraft.PullRequest -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.css.spaceLg)) {
                    // :97-131: Title and Body, each with its own Copy.
                    Field(MetadataDraftCopy.TITLE, "${pending.requestId}::title", draft.title, "pull request title", copy, MetadataDraftTags.CopyTitle, MetadataDraftTags.PrTitle)
                    Field(MetadataDraftCopy.BODY, "${pending.requestId}::body", draft.body, "pull request body", copy, MetadataDraftTags.CopyBody, MetadataDraftTags.PrBody)
                }
            }
        }
    }
}

/** :70-75 `.model-browser-empty` role=status: the glyph and the server's words (or the fallback). */
@Composable
private fun FailureBody(draft: MetadataDraft.Failure) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = t.css.spaceXl)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(MetadataDraftTags.Error),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.faint, modifier = Modifier.size(24.dp))
        Text(MetadataDraftCopy.error(draft), color = t.muted, textAlign = TextAlign.Center, style = LocalTetherTypography.current.body.copy(fontSize = 12.48.sp))
    }
}

/** `.metadata-draft-field`: the section label and its Copy over the value. */
@Composable
private fun Field(heading: String, key: String, text: String, label: String, copy: CopyState, copyTag: String, valueTag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                heading.uppercase(),
                color = t.muted,
                style = TextStyle(fontFamily = type.ui, fontSize = 10.56.sp, fontWeight = FontWeight(720), letterSpacing = 0.07.em),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            CopyKey(key, text, MetadataDraftCopy.COPY, MetadataDraftCopy.COPY_FAILED, label, copy, copyTag)
        }
        Pre(text, Modifier.testTag(valueTag))
    }
}

/** A copy key: its idle words, "Copied" with a check, or its failure words with the warning glyph. */
@Composable
private fun CopyKey(key: String, text: String, idle: String, failed: String, label: String, copy: CopyState, tag: String) {
    val context = LocalContext.current
    val copied = copy.copiedKey == key
    val error = copy.copyErrorKey == key
    TetherKey(
        onClick = { copy.result(key, putOnClipboard(context, text, label)) },
        classes = KeyClasses.ButtonSecondary,
        label = when {
            copied -> MetadataDraftCopy.COPIED
            error -> failed
            else -> idle
        },
        icon = when {
            copied -> TetherIcons.Check
            error -> TetherIcons.TriangleAlert
            else -> TetherIcons.Copy
        },
        iconSize = 13.dp,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** `<pre><code>`: the text by SafeText's code rule, kept as written (it scrolls sideways, like a pre). */
@Composable
private fun Pre(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    Box(
        modifier
            .fillMaxWidth()
            .cssSurface(shape, t.mineralDeep, CssBorder(1.dp, t.line), emptyList())
            .horizontalScroll(rememberScrollState())
            .padding(t.css.spaceMd),
    ) {
        Text(codeText(text), color = t.ink, softWrap = false, style = TextStyle(fontFamily = type.mono, fontSize = 12.sp, lineHeight = 1.5.em))
    }
}
