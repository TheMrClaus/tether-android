package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.CodexCatalog
import com.tether.app.client.CodexRateWindow
import com.tether.app.client.CodexSnapshot
import com.tether.app.client.OpencodeCatalog
import com.tether.app.client.OpencodeSnapshot
import com.tether.app.client.ProviderControlsState
import com.tether.app.client.ReviewTarget
import com.tether.app.client.SessionControl
import com.tether.app.client.SessionControlsGuard
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.SelectTriggerStyle
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/**
 * components/codex-controls.tsx `CodexControlsPanel`: the Codex v2 session's one-shot actions
 * (review, compaction, skills) and its read-only catalogs (hooks, apps, MCP health, rate limits).
 * Model / effort / mode live in the composer row, as on the web. Every action is a tap on its key;
 * [onControl] goes through the client's guard, which re-checks the value against the snapshot.
 * Android hosts this in the session sheet (the web keeps it in Settings → Advanced).
 */
@Composable
internal fun CodexControlsPanel(state: ProviderControlsState<CodexSnapshot>?, locked: Boolean, onControl: (SessionControl) -> Unit) {
    val snapshot = state?.snapshot
    val busy = state?.busy == true
    Column(Modifier.fillMaxWidth().testTag("codex-controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelTitle("Codex")
        if (snapshot == null) {
            PanelStatus(if (busy) "Loading bounded provider catalogs…" else "Send a message to open this Codex session, then reopen this panel.")
            return@Column
        }
        PanelSection("Model, effort & mode", TetherIcons.Sparkles) {
            PanelNote("These moved to the composer row under the message box, where they apply immediately — the same place Claude and opencode sessions set theirs.")
        }
        PanelSection("Review & compaction", TetherIcons.GitPullRequest) {
            if (snapshot.reviewStatus != "ready" || snapshot.compactionStatus != "ready") {
                PanelStatus(
                    if (snapshot.reviewStatus == "unsupported" && snapshot.compactionStatus == "unsupported") {
                        "Review and compaction were not advertised by this Codex session."
                    } else {
                        "Review or compaction is unavailable while the Codex engine is offline."
                    },
                )
            }
            ReviewForm(snapshot, enabled = !busy && !locked, onControl = onControl)
            ArmedPanelKey(
                snapshot.revision, "compact", "Compact context", icon = TetherIcons.RotateCcw,
                enabled = !busy && !locked && snapshot.compactionStatus == "ready", tag = "codex-compact",
            ) { onControl(SessionControl.CodexCompaction(snapshot.revision)) }
        }
        PanelSection("Skills", TetherIcons.PackageCheck) {
            if (snapshot.skills.ready && snapshot.skills.items.isNotEmpty()) {
                snapshot.skills.items.forEach { skill ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ItemCopy(skill.name, skill.description, "${skill.scope} · ${if (skill.enabled) "Enabled" else "Disabled"}", Modifier.weight(1f))
                        ArmedPanelKey(
                            snapshot.revision, "skill:${skill.id}:${skill.enabled}", if (skill.enabled) "Disable" else "Enable",
                            enabled = !busy && !locked, tag = "codex-skill-${skill.id}",
                            contentDescription = "${if (skill.enabled) "Disable" else "Enable"} ${skill.name} skill",
                        ) { onControl(SessionControl.CodexSkill(skill.id, !skill.enabled, snapshot.revision)) }
                    }
                }
            } else {
                EmptyCatalog(snapshot.skills, "No skills reported.")
            }
        }
        PanelSection("Hooks", TetherIcons.Braces) {
            if (snapshot.hooks.ready && snapshot.hooks.items.isNotEmpty()) {
                snapshot.hooks.items.forEach { hook ->
                    ItemCopy(hook.name, "${hook.event} · ${hook.handler}", "${if (hook.enabled) "Enabled" else "Disabled"} · ${hook.trust}${if (hook.managed) " · Managed" else ""}")
                }
            } else {
                EmptyCatalog(snapshot.hooks, "No hooks reported.")
            }
        }
        PanelSection("Apps & plugins", TetherIcons.AppWindow) {
            if (snapshot.apps.ready && snapshot.apps.items.isNotEmpty()) {
                snapshot.apps.items.forEach { app ->
                    ItemCopy(
                        app.name,
                        app.description.ifEmpty { "No description provided" },
                        "${if (app.enabled) "Enabled" else "Disabled"} · ${if (app.accessible) "Accessible" else "Access required"}" +
                            if (app.plugins.isNotEmpty()) "\nPlugins · ${app.plugins.joinToString(", ")}" else "",
                    )
                }
            } else {
                EmptyCatalog(snapshot.apps, "No apps or plugins reported.")
            }
        }
        PanelSection("MCP health", TetherIcons.Box) {
            if (snapshot.mcpServers.ready && snapshot.mcpServers.items.isNotEmpty()) {
                snapshot.mcpServers.items.forEach { server ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ItemCopy(server.name, "${server.toolCount} tool${if (server.toolCount == 1L) "" else "s"} reported", null, Modifier.weight(1f))
                        StatusWord(server.statusLabel, problem = server.status != "ready")
                    }
                }
            } else {
                EmptyCatalog(snapshot.mcpServers, "No MCP servers reported.")
            }
        }
        PanelSection("Rate limits", TetherIcons.Gauge) {
            if (snapshot.rateLimits.ready && snapshot.rateLimits.items.isNotEmpty()) {
                snapshot.rateLimits.items.forEach { limit ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(limit.name, style = body(13.sp, 600), color = LocalTetherTokens.current.white, modifier = Modifier.weight(1f))
                        StatusWord(limit.statusLabel, problem = limit.status == "limited")
                    }
                    limit.primary?.let { RateWindow(limit.name, "Primary limit", it) }
                    limit.secondary?.let { RateWindow(limit.name, "Secondary limit", it) }
                    if (limit.hasCredits != null || limit.unlimitedCredits != null) {
                        PanelNote("Credits · ${if (limit.unlimitedCredits == true) "Unlimited" else if (limit.hasCredits == true) "Available" else "Unavailable"}")
                    }
                }
            } else {
                EmptyCatalog(snapshot.rateLimits, "No rate-limit data reported.")
            }
        }
        Feedback(if (busy) "Applying operator action…" else state.message)
    }
}

/** The review target + delivery form; "Start review" sends only a target the server's validator accepts. */
@Composable
private fun ReviewForm(snapshot: CodexSnapshot, enabled: Boolean, onControl: (SessionControl) -> Unit) {
    var kind by remember { mutableStateOf("uncommittedChanges") }
    var value by remember { mutableStateOf("") }
    var delivery by remember { mutableStateOf("inline") }
    val target: ReviewTarget = when (kind) {
        "baseBranch" -> ReviewTarget.BaseBranch(value.trim())
        "commit" -> ReviewTarget.Commit(value.trim())
        "custom" -> ReviewTarget.Custom(value.trim())
        else -> ReviewTarget.UncommittedChanges
    }
    val ready = snapshot.reviewStatus == "ready" && SessionControlsGuard.validReviewTarget(target)
    FieldLabel("Review target")
    TetherSelect(
        options = listOf(
            TetherSelectOption("uncommittedChanges", "Uncommitted changes"),
            TetherSelectOption("baseBranch", "Base branch"),
            TetherSelectOption("commit", "Commit"),
            TetherSelectOption("custom", "Custom instructions"),
        ),
        selectedValue = kind,
        onSelect = { kind = it.value; value = "" },
        style = SelectTriggerStyle.Field,
        enabled = enabled,
        contentDescription = "Review target",
    )
    if (kind != "uncommittedChanges") {
        FieldLabel(when (kind) { "baseBranch" -> "Branch"; "commit" -> "Commit SHA"; else -> "Instructions" })
        val max = if (kind == "custom") 4096 else 200
        TetherInputWell(
            value = value,
            onValueChange = { value = com.tether.app.client.ConsentGuard.cutCodePoints(it, max) },
            singleLine = kind != "custom",
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("codex-review-value"),
        )
    }
    FieldLabel("Review delivery")
    TetherSelect(
        options = listOf(TetherSelectOption("inline", "Current thread"), TetherSelectOption("detached", "Detached thread")),
        selectedValue = delivery,
        onSelect = { delivery = it.value },
        style = SelectTriggerStyle.Field,
        enabled = enabled,
        contentDescription = "Review delivery",
    )
    ArmedPanelKey(snapshot.revision, "review", "Start review", enabled = enabled && ready, tag = "codex-start-review") {
        if (ready) onControl(SessionControl.CodexReview(target, delivery, snapshot.revision))
    }
}

/**
 * components/opencode-serve-controls.tsx `OpencodeServeControlsPanel`: stage a model (+ `--variant`)
 * or an agent locally, then Apply — nothing is sent until the Apply tap. The drafts re-seed when the
 * session's applied values or the catalog revision move. A danger agent applies like any other.
 */
@Composable
internal fun OpencodeControlsPanel(
    state: ProviderControlsState<OpencodeSnapshot>?,
    selectedModel: String,
    selectedVariant: String,
    selectedMode: String,
    locked: Boolean,
    onControl: (SessionControl) -> Unit,
    /** Round 2 (M2): the agent rows drawn in `--warning`, the same rule as the Mode row. Styling only. */
    markedDanger: (String) -> Boolean,
) {
    val snapshot = state?.snapshot
    val busy = state?.busy == true
    Column(Modifier.fillMaxWidth().testTag("opencode-controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelTitle("opencode")
        if (snapshot == null) {
            PanelStatus(if (busy) "Loading bounded provider catalogs…" else "Send a message to open this opencode session, then reopen this panel.")
            return@Column
        }
        val seed = listOf(selectedModel, selectedVariant, selectedMode, snapshot.revision)
        var modelDraft by remember(seed) { mutableStateOf(selectedModel) }
        var variantDraft by remember(seed) { mutableStateOf(selectedVariant) }
        var modeDraft by remember(seed) { mutableStateOf(selectedMode) }
        PanelSection("Model & reasoning effort", TetherIcons.Sparkles) {
            if (!snapshot.models.ready) {
                OpencodeEmpty(snapshot.models, "No models reported.")
            } else {
                val active = snapshot.models.items.firstOrNull { it.value == modelDraft }
                val variants = active?.variants.orEmpty()
                FieldLabel("Model")
                TetherSelect(
                    options = snapshot.models.items.map { TetherSelectOption(it.value, it.displayName.ifEmpty { it.value }, tag = it.providerLabel) },
                    selectedValue = modelDraft,
                    onSelect = { modelDraft = it.value; variantDraft = "" },
                    style = SelectTriggerStyle.Field,
                    enabled = !busy && !locked,
                    contentDescription = "opencode model",
                )
                FieldLabel("Reasoning effort (--variant)")
                TetherSelect(
                    options = if (variants.isNotEmpty()) {
                        listOf(TetherSelectOption("", "Provider default")) + variants.map { TetherSelectOption(it.value, it.label.ifEmpty { it.value }) }
                    } else {
                        listOf(TetherSelectOption("", "Not offered by this model"))
                    },
                    selectedValue = if (variants.isNotEmpty()) variantDraft else "",
                    onSelect = { variantDraft = it.value },
                    style = SelectTriggerStyle.Field,
                    enabled = !busy && !locked && variants.isNotEmpty(),
                    contentDescription = "opencode reasoning-effort variant",
                )
                ArmedPanelKey(snapshot.revision, "apply-model", "Apply model settings", enabled = !busy && !locked && modelDraft.isNotEmpty(), tag = "opencode-apply-model") {
                    onControl(SessionControl.OpencodeModelSelection(modelDraft, if (variants.isNotEmpty() && variantDraft.isNotEmpty()) variantDraft else null, snapshot.revision))
                }
            }
        }
        PanelSection("Agent / mode", TetherIcons.Bot) {
            if (!snapshot.modes.ready) {
                OpencodeEmpty(snapshot.modes, "No agents reported.")
            } else {
                FieldLabel("Agent (--agent)")
                TetherSelect(
                    options = snapshot.modes.items.map {
                        TetherSelectOption(
                            it.value,
                            com.tether.app.client.ComposerControlsModel.opencodeAgentLabel(it.value, it.label),
                            description = it.hint.ifEmpty { null },
                            danger = markedDanger(it.value),
                        )
                    },
                    selectedValue = modeDraft,
                    onSelect = { modeDraft = it.value },
                    style = SelectTriggerStyle.Field,
                    enabled = !busy && !locked,
                    contentDescription = "opencode agent/mode",
                )
                ArmedPanelKey(snapshot.revision, "apply-mode", "Apply agent / mode", enabled = !busy && !locked && modeDraft.isNotEmpty(), tag = "opencode-apply-mode") {
                    // opencode-serve-controls.tsx:132-139: any agent, a danger one too, applies on the tap.
                    onControl(SessionControl.OpencodeMode(modeDraft, snapshot.revision))
                }
            }
        }
        Feedback(if (busy) "Applying operator action…" else state.message)
    }
}

/**
 * Round 2 (L2): a provider action key, armed for the snapshot it was drawn from (T6.3/T6.4): usable
 * [CONSENT_ARM_DELAY_MS] after the panel (or a new catalog revision) appears, and again after it
 * moves; touches through an overlay are refused. Only its tap calls [onTap].
 */
@Composable
private fun ArmedPanelKey(
    revision: String,
    id: String,
    label: String,
    enabled: Boolean,
    tag: String,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    onTap: () -> Unit,
) {
    val arming = rememberArmedControl(revision to id, enabled)
    TetherKey(
        onClick = { if (arming.armed) onTap() },
        classes = KeyClasses.ButtonSecondary,
        label = label,
        icon = icon,
        enabled = enabled && arming.armed,
        contentDescription = contentDescription,
        modifier = arming.modifier.testTag(tag),
    )
}

@Composable
private fun PanelTitle(provider: String) {
    val t = LocalTetherTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(provider.uppercase(), style = body(10.4.sp, 700).copy(letterSpacing = 0.08.em), color = t.faint, modifier = Modifier.clearAndSetSemantics { contentDescription = provider })
        Text("Provider controls", style = body(15.2.sp, 650), color = t.white, modifier = Modifier.semantics { heading() })
        Text("Changes apply only after an explicit action.", style = body(11.2.sp, 400), color = t.muted)
    }
}

@Composable
private fun PanelSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.mineralDeep)
            .padding(t.css.spaceMd),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp))
            Text(title, style = body(13.6.sp, 650), color = t.white, modifier = Modifier.semantics { heading() })
        }
        content()
    }
}

@Composable
private fun ItemCopy(name: String, detail: String?, meta: String?, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = body(13.sp, 600), color = t.white)
        detail?.takeIf { it.isNotEmpty() }?.let { Text(it, style = body(11.5.sp, 400), color = t.muted) }
        meta?.let { Text(it, style = body(11.sp, 500), color = t.faint) }
    }
}

/** A status spoken in words with its glyph (Check / CircleAlert): never colour alone. */
@Composable
private fun StatusWord(label: String, problem: Boolean) {
    val t = LocalTetherTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (problem) TetherIcons.CircleAlert else TetherIcons.Check, contentDescription = null, tint = if (problem) t.warning else t.muted, modifier = Modifier.size(14.dp))
        Text(label, style = body(11.5.sp, 600), color = if (problem) t.warning else t.muted)
    }
}

@Composable
private fun RateWindow(limitName: String, label: String, window: CodexRateWindow) {
    val t = LocalTetherTokens.current
    val used = Math.round(window.usedPercent * 10) / 10.0
    val shown = if (used == Math.floor(used)) used.toLong().toString() else used.toString()
    val tone = when {
        used >= 90 -> t.danger
        used >= 75 -> t.warning
        else -> t.violet
    }
    val reset = window.resetsAt?.let { formatUtc(it * 1000) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = body(11.sp, 600), color = t.muted)
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(t.line)
                .semantics {
                    contentDescription = "$limitName $label usage"
                    progressBarRangeInfo = ProgressBarRangeInfo(used.toFloat().coerceIn(0f, 100f), 0f..100f)
                },
        ) {
            Box(Modifier.fillMaxWidth((used / 100.0).toFloat().coerceIn(0f, 1f)).height(6.dp).background(tone))
        }
        Text("$shown% used", style = body(12.sp, 650), color = t.ink)
        Text(
            (if (reset != null) "Resets $reset" else "Reset time unavailable") + (window.windowDurationMins?.let { " · $it minute window" } ?: ""),
            style = body(11.sp, 400),
            color = t.faint,
        )
    }
}

/** `Intl.DateTimeFormat("en", {month:"short", day:"numeric", hour:"2-digit", minute:"2-digit", timeZone:"UTC", timeZoneName:"short"})`. */
internal fun formatUtc(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("MMM d, hh:mm a 'UTC'", java.util.Locale.ENGLISH)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date(ms))
}

@Composable
private fun EmptyCatalog(catalog: CodexCatalog<*>, empty: String) = PanelNote(
    when (catalog.status) {
        "unsupported" -> "Not advertised by this Codex session."
        "unavailable" -> "Unavailable while the Codex engine is offline."
        else -> empty
    },
)

@Composable
private fun OpencodeEmpty(catalog: OpencodeCatalog<*>, empty: String) = PanelNote(
    when (catalog.status) {
        "unsupported" -> "Not advertised by this opencode-serve session."
        "unavailable" -> "Unavailable while the opencode-serve engine is offline."
        "error" -> catalog.error ?: "The opencode provider list could not be read."
        else -> empty
    },
)

@Composable
private fun FieldLabel(text: String) {
    val t = LocalTetherTokens.current
    Text(text, style = body(11.5.sp, 600), color = t.muted, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun PanelNote(text: String) {
    val t = LocalTetherTokens.current
    Text(text, style = body(12.sp, 400).copy(lineHeight = 1.45.em), color = t.muted)
}

@Composable
private fun PanelStatus(text: String) {
    val t = LocalTetherTokens.current
    Text(text, style = body(12.sp, 400), color = t.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Feedback(message: String?) {
    val t = LocalTetherTokens.current
    Text(
        message.orEmpty(),
        style = body(12.sp, 500),
        color = t.ink,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("provider-feedback"),
    )
}

@Composable
private fun body(size: androidx.compose.ui.unit.TextUnit, weight: Int) = LocalTetherTypography.current.body.copy(fontSize = size, fontWeight = FontWeight(weight))
