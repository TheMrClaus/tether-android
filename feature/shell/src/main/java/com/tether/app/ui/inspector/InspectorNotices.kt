package com.tether.app.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.LabelText
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.WrapUpNotice
import com.tether.app.ui.statusline.wrapUpReading
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import kotlinx.coroutines.delay
import kotlin.math.max

/*
 * T6.6: the inspector's limit and MCP readings (inspector.tsx 56-130, 512-556; mcp-health-card.tsx;
 * opencode-rich-renderers.tsx 151-160). Read-only: nothing here sends anything. Every server string
 * is cleaned and bounded (LabelText) before it is drawn.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/** inspector.tsx RATE_LIMIT_COPY. */
private val RATE_LIMIT_COPY = mapOf(
    "allowed_warning" to "Approaching the rate limit",
    "rejected" to "Rate limited — requests are being rejected",
)

/**
 * `RateLimitNotice`'s words: "<status copy>[ (<limit type>)][ — resets in …]", or null when the
 * state is `allowed` / absent, or its `resetsAt` has passed (the CLI may never send the clearing
 * event; the web expires it on a timer).
 */
fun rateLimitNoticeText(state: SessionView?, nowMs: Double): String? {
    val rateLimit = state?.obj?.get("rateLimit") as? JsObj ?: return null
    val status = (rateLimit["status"] as? JsStr)?.value ?: return null
    if (status == "allowed") return null
    val resetsAt = rateLimit["resetsAt"] as? JsNum
    if (resetsAt != null && resetsAt.value.isFinite() && nowMs >= resetsAt.value) return null
    val limitType = (rateLimit["limitType"] as? JsStr)?.value?.let { LabelText.label(it.replace('_', ' ')) }?.ifEmpty { null }
    val reset = Format.resetTime(resetsAt, nowMs)
    return buildString {
        append(RATE_LIMIT_COPY[status] ?: LabelText.label(status))
        if (limitType != null) append(" ($limitType)")
        if (reset.isNotEmpty()) append(" — $reset")
    }
}

/**
 * inspector.tsx:520-523: the Wrap-Up notice while Claude's allowance covers the turn in flight,
 * else the generic rate-limit notice (`.telemetry-empty`, `role="status"`), which expires on its
 * own at `resetsAt`.
 */
@Composable
fun InspectorLimitNotice(state: SessionView?, modifier: Modifier = Modifier, env: () -> ReadingEnv = ReadingEnv::current) {
    if (state != null && wrapUpReading(state, env()) != null) {
        WrapUpNotice(state, modifier.padding(top = LocalTetherTokens.current.css.spaceXl), env = env)
        return
    }
    val resetsAt = ((state?.obj?.get("rateLimit") as? JsObj)?.get("resetsAt") as? JsNum)?.value
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(resetsAt) {
        if (resetsAt == null || !resetsAt.isFinite()) return@LaunchedEffect
        delay(max(0.0, resetsAt - env().nowMs).toLong() + 1)
        tick++
    }
    val text = remember(state, tick) { rateLimitNoticeText(state, env().nowMs) } ?: return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    Text(
        text,
        style = TextStyle(fontFamily = type.ui, fontSize = rem(0.65f), lineHeight = 1.5.em),
        color = t.faint,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = t.css.spaceXl)
            .drawBehind { drawRect(line, Offset.Zero, size.copy(height = 1.dp.toPx())) }
            .padding(top = t.css.spaceLg)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("inspector-rate-limit"),
    )
}

/** One `McpHealthProjection`, cleaned for display. */
@Immutable
data class McpServerView(val name: String, val status: String, val error: String?) {
    val problem: Boolean get() = status == "failed" || status == "needs-auth"
}

/** mcp-health-card.tsx HEALTH_COPY. */
fun mcpStatusCopy(status: String): String = when (status) {
    "starting" -> "Starting"
    "ready" -> "Ready"
    "failed" -> "Failed"
    "cancelled" -> "Cancelled"
    "needs-auth" -> "Needs authentication"
    "disabled" -> "Disabled"
    else -> "Unknown"
}

/** `Object.values(state.mcpHealth)` sorted by name (bounded to the display cap). */
fun mcpServers(state: SessionView?): List<McpServerView> {
    val health = state?.obj?.get("mcpHealth") as? JsObj ?: return emptyList()
    return health.entries.mapNotNull { (_, value) ->
        val o = value as? JsObj ?: return@mapNotNull null
        val name = (o["name"] as? JsStr)?.value ?: return@mapNotNull null
        val error = ((o["error"] as? JsStr)?.value ?: (o["failureReason"] as? JsStr)?.value)?.let { LabelText.error(it) }?.ifEmpty { null }
        McpServerView(LabelText.label(name).ifEmpty { LabelText.visibleValue(name) }, (o["status"] as? JsStr)?.value ?: "unknown", error)
    }.sortedWith(compareBy(java.text.Collator.getInstance()) { it.name }).take(LabelText.MAX_ITEMS)
}

/** The compact card's count: "N issue(s)" while any server is failed / needs auth, else "r/n ready". */
fun mcpCountText(servers: List<McpServerView>): String {
    val problems = servers.count { it.problem }
    if (problems > 0) return "$problems ${if (problems == 1) "issue" else "issues"}"
    return "${servers.count { it.status == "ready" }}/${servers.size} ready"
}

/**
 * `McpHealthCard` (compact in the inspector, full as opencode's "Plugins" card): hidden when there
 * are no servers. Compact is a disclosure (open by default while there is a problem): the Network
 * glyph, the label, the count, a chevron; each row the server name (mono, ellipsised), its status in
 * words (`--warning` when failed / needs auth), and its error behind "View error".
 */
@Composable
fun McpHealthCard(
    servers: List<McpServerView>,
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    count: (Int) -> String = { n -> "$n server${if (n == 1) "" else "s"}" },
) {
    if (servers.isEmpty()) return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val problems = servers.count { it.problem }
    var open by rememberSaveable(label, problems > 0) { androidx.compose.runtime.mutableStateOf(problems > 0) }
    val shown = !compact || open
    val countText = if (compact) mcpCountText(servers) else count(servers.size)
    val shape = RoundedCornerShape(t.radiusMd)
    val line = t.line
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = t.css.spaceLg)
            .then(
                if (compact) Modifier.drawBehind { drawRect(line, Offset.Zero, size.copy(height = 1.dp.toPx())) }
                else Modifier.cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.dp, t.line)),
            )
            .testTag("mcp-health"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .then(
                    if (compact) {
                        Modifier
                            .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "$label, $countText"
                                stateDescription = if (open) "Expanded" else "Collapsed"
                            }
                    } else {
                        Modifier.padding(horizontal = t.css.spaceMd).semantics(mergeDescendants = true) { contentDescription = "$label, $countText" }
                    },
                )
                .padding(vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.Network, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
            Text(
                label,
                style = TextStyle(fontFamily = if (compact) type.ui else type.mono, fontSize = rem(0.78f), fontWeight = FontWeight(650)),
                color = t.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (compact) countText else countText.uppercase(),
                style = TextStyle(fontFamily = type.mono, fontSize = rem(if (compact) 0.62f else 0.68f), fontWeight = FontWeight(650), letterSpacing = if (compact) 0.em else 0.04.em),
                color = if (compact && problems > 0) t.warning else t.muted,
                textAlign = TextAlign.End,
            )
            if (compact) Icon(TetherIcons.ChevronDown, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp).rotate(if (open) 180f else 0f))
        }
        if (shown) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            servers.forEachIndexed { i, server ->
                McpServerRow(server, compact, last = i == servers.lastIndex)
            }
        }
    }
}

@Composable
private fun McpServerRow(server: McpServerView, compact: Boolean, last: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var errorOpen by rememberSaveable(server.name) { androidx.compose.runtime.mutableStateOf(false) }
    val tint = t.tintSm
    val statusColor = if (server.problem) t.warning else t.muted
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (last) Modifier else Modifier.drawBehind { drawRect(tint, Offset(0f, size.height - 1.dp.toPx()), size.copy(height = 1.dp.toPx())) })
            .padding(horizontal = if (compact) 0.dp else t.css.spaceMd, vertical = t.css.spaceSm)
            .testTag("mcp-server"),
        verticalArrangement = Arrangement.spacedBy((0.15f * TetherTypography.SP_PER_REM).dp),
    ) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd)) {
            Text(
                server.name,
                style = TextStyle(fontFamily = type.mono, fontSize = rem(if (compact) 0.7f else 0.76f)),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                mcpStatusCopy(server.status),
                style = TextStyle(fontFamily = type.ui, fontSize = rem(if (compact) 0.7f else 0.76f)),
                color = statusColor,
                textAlign = TextAlign.End,
                modifier = if (compact) Modifier.widthIn(max = 112.dp) else Modifier,
            )
        }
        server.error?.let { error ->
            val errorStyle = TextStyle(fontFamily = type.ui, fontSize = rem(if (compact) 0.7f else 0.76f), lineHeight = 1.4.em)
            if (compact) {
                Text(
                    "View error",
                    style = TextStyle(fontFamily = type.ui, fontSize = rem(0.68f)),
                    color = t.warning,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { errorOpen = !errorOpen }
                        .semantics { stateDescription = if (errorOpen) "Expanded" else "Collapsed" }
                        .padding(vertical = 12.dp),
                )
                if (errorOpen) Text(error, style = errorStyle, color = statusColor, modifier = Modifier.padding(bottom = t.css.spaceSm))
            } else {
                Text(error, style = errorStyle, color = statusColor)
            }
        }
    }
}

/**
 * inspector.tsx:533-556: the session's MCP health (every provider but opencode), or opencode-serve's
 * "Plugins" card ("N loaded").
 */
@Composable
fun InspectorMcpHealth(provider: String, engineGeneration: String?, state: SessionView?, modifier: Modifier = Modifier) {
    val servers = remember(state) { mcpServers(state) }
    when {
        provider != "opencode" -> McpHealthCard(servers, "MCP health", modifier, compact = true)
        engineGeneration == "opencode-serve-v2" -> McpHealthCard(servers, "Plugins", modifier, compact = false, count = { n -> "$n loaded" })
    }
}
