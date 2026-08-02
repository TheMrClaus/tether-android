package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wrench
import com.tether.app.protocol.model.SubagentEntry
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.elapsedLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// --- JsonElement display helpers -------------------------------------------------

internal fun JsonElement?.str(field: String): String? =
    ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.content

/** One-line input summary per tool, mirroring chat-tool-render.tsx. */
internal fun toolInputSummary(name: String?, input: JsonElement?): String? {
    if (input == null) return null
    val summary = when (name) {
        "Bash" -> input.str("command")
        "Read", "Edit", "Write", "NotebookEdit" -> input.str("file_path")
        "Grep" -> listOfNotNull(input.str("pattern"), input.str("path")).joinToString("  ")
        "Glob" -> input.str("pattern")
        "Task" -> input.str("description") ?: input.str("prompt")
        "WebFetch" -> input.str("url")
        "WebSearch" -> input.str("query")
        else -> null
    }
    if (summary != null) return summary.take(200)
    // Fallback: compact JSON.
    val compact = (input as? JsonObject)?.entries?.joinToString("  ") { (k, v) ->
        "$k: ${(v as? JsonPrimitive)?.content ?: v.toString()}"
    } ?: input.toString()
    return compact.take(200).ifBlank { null }
}

/** Best-effort text extraction from a tool output payload; truncated ~600 chars. */
internal fun toolOutputText(output: JsonElement?): String? {
    val text = when (output) {
        null -> return null
        is JsonPrimitive -> output.content
        is JsonObject -> output.str("text")
            ?: output.str("output")
            ?: output.str("stdout")
            ?: output.str("content")
            ?: (output["content"] as? JsonArray)?.joinToString("\n") { entry ->
                (entry as? JsonObject)?.str("text") ?: entry.toString()
            }
            ?: output.toString()
        is JsonArray -> output.joinToString("\n") { entry ->
            (entry as? JsonObject)?.str("text") ?: (entry as? JsonPrimitive)?.content ?: entry.toString()
        }
    }
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.length > 600) trimmed.take(600) + "…" else trimmed
}

internal data class DiffModel(val filePath: String, val tag: String, val deleted: List<String>, val added: List<String>)

internal fun diffFor(name: String?, input: JsonElement?): DiffModel? {
    if (input == null) return null
    return when (name) {
        "Edit" -> {
            val old = input.str("old_string") ?: return null
            val new = input.str("new_string") ?: return null
            DiffModel(input.str("file_path") ?: "?", "EDIT", old.lines(), new.lines())
        }
        "Write" -> {
            val content = input.str("content") ?: return null
            DiffModel(input.str("file_path") ?: "?", "WRITE", emptyList(), content.lines().take(80))
        }
        else -> null
    }
}

// --- Tool card -------------------------------------------------------------------

/**
 * Tool card (visual-spec §4): mineral-deep, mono header with status icon + name +
 * right-aligned status, input summary / diff, scrollable output, subagent thread.
 */
@Composable
fun ToolCard(block: TurnBlock, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val running = block.done != true
    val isError = block.isError == true
    val shape = RoundedCornerShape(TetherDimens.radiusMd)
    val borderColor = when {
        isError -> t.dangerEdge
        running -> t.lineStrong
        else -> t.line
    }

    val diff = remember(block.name, block.input) { diffFor(block.name, block.input) }
    val summary = remember(block.name, block.input) { if (diff == null) toolInputSummary(block.name, block.input) else null }
    val output = remember(block.output) { toolOutputText(block.output) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.mineralDeep)
            .border(1.dp, borderColor, shape),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                running -> SpinningIcon(Lucide.Loader, tint = t.muted, size = 14.dp)
                isError -> Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(14.dp))
                else -> Icon(Lucide.Wrench, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            }
            Text(
                block.name ?: "tool",
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontWeight = TetherWeights.label,
                fontSize = 12.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            val statusText = when {
                block.aborted == true -> "INTERRUPTED"
                running -> {
                    val secs = block.elapsedSeconds?.toLong()
                    if (secs != null) "RUNNING · ${elapsedLabel(secs)}" else "RUNNING"
                }
                isError -> "ERROR"
                else -> "DONE"
            }
            Text(
                statusText,
                color = if (isError) t.danger else t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 11.5.sp,
                letterSpacing = 0.05.em,
            )
        }

        if (summary != null) {
            Text(
                summary,
                color = t.muted,
                fontFamily = JetBrainsMono,
                fontSize = 12.2.sp,
                lineHeight = 12.2.sp * 1.5f,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }

        if (diff != null) DiffView(diff)

        if (output != null) {
            Box(Modifier.fillMaxWidth().heightIn(max = 176.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .background(t.tintXs),
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                    Text(
                        output,
                        color = t.ink,
                        fontFamily = JetBrainsMono,
                        fontSize = 12.2.sp,
                        lineHeight = 12.2.sp * 1.5f,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        block.subagent?.let { thread ->
            SubagentThreadView(
                order = thread.order,
                entries = thread.entries,
                parentRunning = running,
            )
        }
    }
}

@Composable
private fun DiffView(diff: DiffModel) {
    val t = LocalTetherTokens.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                diff.filePath,
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontSize = 12.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                diff.tag,
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.strong,
                fontSize = 9.9.sp,
                letterSpacing = 0.06.em,
                modifier = Modifier
                    .background(t.tintMd, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            diff.deleted.forEach { line -> DiffRow(gutter = "-", text = line, bg = t.diffDelBg, ink = t.diffDelInk) }
            diff.added.forEach { line -> DiffRow(gutter = "+", text = line, bg = t.diffAddBg, ink = t.diffAddInk) }
        }
    }
}

@Composable
private fun DiffRow(gutter: String, text: String, bg: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color) {
    val t = LocalTetherTokens.current
    Row(Modifier.fillMaxWidth().background(bg)) {
        Box(
            Modifier.width(24.dp).padding(vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(gutter, color = ink, fontFamily = JetBrainsMono, fontSize = 12.2.sp)
        }
        Box(Modifier.width(1.dp).background(t.line))
        Text(
            text.ifEmpty { " " },
            color = ink,
            fontFamily = JetBrainsMono,
            fontSize = 12.2.sp,
            lineHeight = 12.2.sp * 1.4f,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

/** Collapsible subagent thread; open by default while the parent tool runs. */
@Composable
private fun SubagentThreadView(
    order: List<String>,
    entries: Map<String, SubagentEntry>,
    parentRunning: Boolean,
) {
    val t = LocalTetherTokens.current
    var expanded by rememberSaveable { mutableStateOf(parentRunning) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Lucide.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Text(
                "Subagent · ${order.size} step${if (order.size == 1) "" else "s"}",
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 12.2.sp,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = t.faint,
                modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(t.lineStrong),
                )
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    order.mapNotNull { entries[it] }.forEach { entry ->
                        when (entry.kind) {
                            "message" -> Text(
                                entry.text.orEmpty(),
                                color = t.ink,
                                fontFamily = Manrope,
                                fontWeight = TetherWeights.body,
                                fontSize = 13.1.sp,
                                lineHeight = 13.1.sp * 1.5f,
                            )
                            else -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (entry.done != true) {
                                    SpinningIcon(Lucide.Loader, tint = t.muted, size = 12.dp)
                                } else {
                                    Icon(Lucide.Wrench, contentDescription = null, tint = t.faint, modifier = Modifier.size(12.dp))
                                }
                                Text(
                                    entry.name ?: "tool",
                                    color = t.muted,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 11.8.sp,
                                )
                                if (entry.isError == true) {
                                    Text("ERROR", color = t.danger, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 10.6.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

