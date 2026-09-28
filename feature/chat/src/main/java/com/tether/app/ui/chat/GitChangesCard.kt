package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.ServerMessage
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * T6.2: components/git-changes-card.tsx (#159 #2, v110) — the live diff of a repo session against
 * its base ref, with per-file hunks fetched only when a file is expanded (`git-diff-file`).
 * Read-only: Tether shows git state; it never stages, commits or pushes. Status is always words.
 * Its host, the repository panel, is T8.3's; the card and the request/reply state are here.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

@Immutable
data class WorktreeDiffEntry(val path: String, val status: String)

/** `WorktreeDiffSummary` (lib/protocol.ts:1627), the fields the card reads. */
@Immutable
data class WorktreeDiffSummaryView(
    val baseRef: String,
    val commitsAhead: Double,
    val committed: List<WorktreeDiffEntry>,
    val uncommitted: List<WorktreeDiffEntry>,
) {
    /** `nothing`: no commits ahead and no entries at all. */
    val nothing: Boolean get() = commitsAhead == 0.0 && committed.isEmpty() && uncommitted.isEmpty()
}

/** The raw `worktree-diff` summary as the card reads it; null for a null (non-repo) diff. */
fun worktreeDiffSummary(diff: JsonObject?): WorktreeDiffSummaryView? {
    if (diff == null) return null
    fun entries(key: String) = (diff[key] as? JsonArray)?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val path = (o["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        WorktreeDiffEntry(path, (o["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")
    } ?: emptyList()
    return WorktreeDiffSummaryView(
        baseRef = (diff["baseRef"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
        commitsAhead = (diff["commitsAhead"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0,
        committed = entries("committed"),
        uncommitted = entries("uncommitted"),
    )
}

/** `COMMITTED_WORD`: a name-status letter as a word. */
private val COMMITTED_WORD = mapOf(
    "A" to "Added",
    "M" to "Modified",
    "D" to "Deleted",
    "R" to "Renamed",
    "C" to "Copied",
    "T" to "Type changed",
    "U" to "Unmerged",
)

/** `committedWord`: the first letter's word, else the status verbatim. */
internal fun committedWord(status: String): String = COMMITTED_WORD[status.take(1)] ?: status

/** `uncommittedWord`: porcelain v1's two columns as "Staged modified · Unstaged deleted"; `??` = Untracked. */
internal fun uncommittedWord(status: String): String {
    if (status == "??") return "Untracked"
    val padded = status.padEnd(2, ' ')
    val index = padded.substring(0, 1)
    val tree = padded.substring(1, 2)
    val parts = ArrayList<String>()
    if (index.isNotBlankJs()) parts.add("Staged ${COMMITTED_WORD[index]?.lowercase(Locale.ROOT) ?: index}")
    if (tree.isNotBlankJs()) parts.add("Unstaged ${COMMITTED_WORD[tree]?.lowercase(Locale.ROOT) ?: tree}")
    return parts.joinToString(" · ").ifEmpty { "Changed" }
}

private fun String.isNotBlankJs(): Boolean = jsTrim(this).isNotEmpty()

/** "3 ahead" / "vs base". */
internal fun changesCount(diff: WorktreeDiffSummaryView): String =
    if (diff.commitsAhead > 0) "${com.tether.app.protocol.tree.JsNumberFormat.toJsString(diff.commitsAhead)} ahead" else "vs base"

/** "No changes vs origin/main" / "Compared against origin/main". */
internal fun changesBase(diff: WorktreeDiffSummaryView): String =
    if (diff.nothing) "No changes vs ${diff.baseRef}" else "Compared against ${diff.baseRef}"

/** The hunk line's kind (`hunkLineClass`): the glyph AND a tint, never colour alone. */
internal fun hunkLineKind(line: String): String = when {
    line.startsWith("+") && !line.startsWith("+++") -> "add"
    line.startsWith("-") && !line.startsWith("---") -> "del"
    line.startsWith("@@") -> "hunk"
    else -> "context"
}

/**
 * `GitChangesCard`: head (diff glyph, "Changes", "N ahead" / "vs base"), the base line, then the
 * committed and working-tree groups; one file open at a time. Opening a file whose hunks are not
 * cached asks for them ([onRequestFile], the `git-diff-file` request); its reply lands in
 * [fileDiffs]. Styles: git-changes-card.module.css.
 */
@Composable
fun GitChangesCard(
    diff: WorktreeDiffSummaryView,
    fileDiffs: Map<String, ServerMessage.GitDiffFile>?,
    onRequestFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle: (String) -> Unit = { path ->
        expanded = if (expanded == path) {
            null
        } else {
            if (fileDiffs?.get(path) == null) onRequestFile(path)
            path
        }
    }
    val shape = RoundedCornerShape(t.radiusMd)
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .maxWidthFraction(cardFraction())
                .fillMaxWidth()
                .cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.dp, t.line))
                .padding(1.dp)
                .clip(RoundedCornerShape(t.radiusMd - 1.dp))
                .semantics { contentDescription = "Changes" }
                .testTag("git-changes-card"),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                HeadIcon(TetherIcons.FileDiff, t.muted)
                Text("Changes", style = TextStyle(fontFamily = type.mono, fontSize = rem(0.78f), fontWeight = FontWeight(650)), color = t.ink)
                Spacer(Modifier.weight(1f))
                val count = changesCount(diff)
                Text(
                    count.uppercase(Locale.ROOT),
                    style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f), fontWeight = FontWeight(650), letterSpacing = 0.04.em),
                    color = t.muted,
                    modifier = Modifier.semantics { contentDescription = count },
                )
            }
            Text(
                changesBase(diff),
                style = type.body.copy(fontSize = rem(0.72f)),
                color = t.muted,
                modifier = Modifier.padding(start = t.css.spaceMd, end = t.css.spaceMd, bottom = t.css.spaceSm),
            )
            if (diff.committed.isNotEmpty()) {
                GroupLabel("Committed (${diff.committed.size})")
                diff.committed.take(GIT_MAX_FILES).forEach { entry ->
                    FileRow(entry.path, committedWord(entry.status), expanded == entry.path, fileDiffs?.get(entry.path), toggle)
                }
                if (diff.committed.size > GIT_MAX_FILES) MoreFiles(diff.committed.size - GIT_MAX_FILES)
            }
            if (diff.uncommitted.isNotEmpty()) {
                GroupLabel("Working tree (${diff.uncommitted.size})")
                diff.uncommitted.take(GIT_MAX_FILES).forEach { entry ->
                    FileRow(entry.path, uncommittedWord(entry.status), expanded == entry.path, fileDiffs?.get(entry.path), toggle)
                }
                if (diff.uncommitted.size > GIT_MAX_FILES) MoreFiles(diff.uncommitted.size - GIT_MAX_FILES)
            }
        }
    }
}

/** Rows one file group draws; the rest is counted (the server also caps the list). */
internal const val GIT_MAX_FILES = 500

@Composable
private fun MoreFiles(count: Int) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        "+${localeCount(count)} more file${if (count == 1) "" else "s"}",
        style = type.body.copy(fontSize = rem(0.72f)),
        color = t.muted,
        modifier = Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
    )
}

/** `.groupLabel`: 0.66rem/650/0.04em uppercase muted under a rule. */
@Composable
private fun GroupLabel(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text.uppercase(Locale.ROOT),
        style = type.body.copy(fontSize = rem(0.66f), fontWeight = FontWeight(650), letterSpacing = 0.04.em),
        color = t.muted,
        modifier = Modifier
            .fillMaxWidth()
            .topRule(t.line)
            .padding(top = 1.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs)
            .semantics { contentDescription = text },
    )
}

/**
 * `FileRow`: a 44dp button — the status word (min 6.5rem, 0.66rem uppercase muted) and the path
 * (mono 0.74rem, clipped at the START so the file name stays visible, the web's `direction: rtl`).
 * Open: "Loading diff…", the error, "Binary file — no text diff.", the hunks or "No textual
 * changes."; a truncated diff says so.
 */
@Composable
private fun FileRow(fullPath: String, word: String, open: Boolean, fileDiff: ServerMessage.GitDiffFile?, onToggle: (String) -> Unit) {
    // R4-L1: drawn and announced cut at PATH_MAX; the request still names the full path.
    val path = cutLine(fullPath, PATH_MAX)
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onToggle(fullPath) }
                .semantics(mergeDescendants = true) {
                    contentDescription = "$word $path"
                    stateDescription = if (open) "Expanded" else "Collapsed"
                }
                .heightIn(min = 44.dp)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs)
                .testTag("git-file-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Text(
                word.uppercase(Locale.ROOT),
                style = type.body.copy(fontSize = rem(0.66f), fontWeight = FontWeight(650), letterSpacing = 0.03.em),
                color = t.muted,
                modifier = Modifier.widthIn(min = 104.dp),
            )
            Text(
                path,
                style = TextStyle(fontFamily = type.mono, fontSize = rem(0.74f)),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().background(t.mineralDeep).topRule(t.line).padding(top = 1.dp)) {
                when {
                    fileDiff == null -> HunkNote("Loading diff…")
                    !fileDiff.error.isNullOrEmpty() -> HunkNote(fileDiff.error!!)
                    fileDiff.binary -> HunkNote("Binary file — no text diff.")
                    fileDiff.hunks.isNotEmpty() -> {
                        HunkPre(fileDiff.hunks)
                        if (fileDiff.truncated) HunkNote("Diff truncated at the size limit.")
                    }
                    else -> HunkNote("No textual changes.")
                }
            }
        }
    }
}

@Composable
private fun HunkNote(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(text, style = type.body.copy(fontSize = rem(0.72f)), color = t.muted, modifier = Modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm))
}

/**
 * `.pre`: mono 0.72rem/1.5 `white-space: pre`, capped at 22rem and scrollable both ways (this card
 * lives in the repository panel, not the transcript, so the web lets it scroll in place).
 */
@Composable
private fun HunkPre(hunks: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = type.codeBlock.copy(fontSize = rem(0.72f), lineHeight = 1.5.em)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 352.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "Diff hunks" }
            .testTag("git-hunks"),
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)) {
            // Round 4: the same caps as the transcript's diffs, so the host (T8.3) cannot ship it
            // uncapped — DIFF_CARD_MAX_ROWS lines, each cut at UNIFIED_LINE_MAX, then "+N more lines".
            // R4-M2: walked with indexOf, at most DIFF_CARD_MAX_ROWS lines built, the rest only counted.
            val (lines, totalLines) = remember(hunks) { boundedLines(hunks, DIFF_CARD_MAX_ROWS) }
            lines.forEach { raw ->
                val line = cutLine(raw, UNIFIED_LINE_MAX)
                val (bg, ink) = when (hunkLineKind(line)) {
                    "add" -> t.diffAddBg to t.diffAddInk
                    "del" -> t.diffDelBg to t.diffDelInk
                    "hunk" -> Color.Transparent to t.accent
                    else -> Color.Transparent to t.muted
                }
                Text(line.ifEmpty { " " }, style = style, color = ink, softWrap = false, modifier = Modifier.fillMaxWidth().background(bg))
            }
            if (totalLines > lines.size) {
                Text(moreLinesLabel(totalLines - lines.size), style = style.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = t.faint, softWrap = false)
            }
        }
    }
}
