package com.tether.app.ui.usage

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tether.app.client.AccountUsage
import com.tether.app.client.AccountWindow
import com.tether.app.client.AccountsUsage
import com.tether.app.client.ClaudeResetGrantsReading
import com.tether.app.client.CodexResetCredits
import com.tether.app.client.DeepSeekAccount
import com.tether.app.client.DeepSeekUnavailable
import com.tether.app.client.DeepSeekUsage
import com.tether.app.client.LabelText
import com.tether.app.protocol.helpers.ClaudeResetGrantsView
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSeam
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.shell.ChromeIconKey
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.shell.rememberDialogIn
import com.tether.app.ui.shell.rememberIconLook
import com.tether.app.ui.statusline.UsageTone
import com.tether.app.ui.statusline.UsageTrack
import com.tether.app.ui.statusline.UsageTrackPlacement
import com.tether.app.ui.statusline.percentReading
import com.tether.app.ui.statusline.usageTone
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.tabularNums

/*
 * T9.2: the Accounts dialog — components/usage-accounts-dialog.tsx, the top bar's "Accounts"
 * (`onOpenUsage`): every Claude identity's rate-limit windows with its usage-limit reset grants,
 * Codex with its banked resets, and DeepSeek's balances and live pricing. On the
 * `settings-dialog recycle-dialog log-dialog` chrome (the Health dialog's), Studio's
 * `.usage-accounts-dialog` rules (studio.css 837-860, ≤ 640: 958, 1029-1030).
 *
 * This is NOT Settings → Engines' Claude accounts (ta-7rh): that tab manages the profiles; this
 * reads each account's usage, as the web's two surfaces do.
 */

internal object AccountsTags {
    const val Dialog = "usage-accounts-dialog"
    const val Refresh = "usage-accounts-refresh"
    const val Close = "usage-accounts-close"
    const val Done = "usage-accounts-done"
    const val Error = "usage-accounts-error"
    const val Loading = "usage-accounts-loading"
    const val Note = "usage-accounts-note"
    fun card(id: String) = "usage-account-$id"
    fun update(id: String) = "usage-account-update-$id"
    fun useGrant(grantId: String) = "claude-reset-grant-use-$grantId"
    const val BankedResets = "codex-banked-resets"
    const val UseCredit = "codex-reset-credit-use"
    const val DeepSeek = "deepseek-balance"
}

/** The dialog over [state] with its two confirmations, wired to [state]'s source. */
@Composable
fun UsageAccountsDialog(state: UsageAccountsState, codex: CodexResetState, claude: ClaudeResetState) {
    if (state.visible) {
        Dialog(onDismissRequest = state::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            val view = LocalView.current
            SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
            val progress = rememberDialogIn()
            UsageAccountsFrame(
                state = state,
                onUseCredit = { codex.open(it) },
                onUseGrant = { claude.open(it) },
                surfaceModifier = Modifier.graphicsLayer {
                    val p = progress.value
                    alpha = p
                    translationY = (1f - p) * 8.dp.toPx()
                    scaleX = 0.99f + 0.01f * p
                    scaleY = 0.99f + 0.01f * p
                },
            )
        }
    }
    CodexResetDialog(codex)
    ClaudeResetDialog(claude)
}

/** The scrim-filled window with the dialog centred in it (the inline form the goldens shoot). */
@Composable
fun UsageAccountsFrame(
    state: UsageAccountsState,
    onUseCredit: (CodexResetRequest) -> Unit,
    onUseGrant: (ClaudeResetRequest) -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    BoxWithConstraints(modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        val narrow = maxWidth <= 640.dp
        val width = if (narrow) maxWidth - 24.dp else minOf(760.dp, maxWidth - 48.dp)
        val shape = RoundedCornerShape(if (narrow) 14.dp else StudioDialog.radius)
        Column(
            surfaceModifier
                .testTag(AccountsTags.Dialog)
                .width(width)
                .heightIn(max = maxHeight - if (narrow) 32.dp else 48.dp)
                .cssSurface(shape, t.graphite, null, StudioDialog.shadows)
                .clip(shape),
        ) {
            Header(narrow, state)
            Body(state, narrow, width, onUseCredit, onUseGrant, Modifier.weight(1f, fill = false).heightIn(min = minOf(460.dp, this@BoxWithConstraints.maxHeight * 0.5f)))
            TetherSeam()
            Row(
                Modifier.fillMaxWidth().background(t.graphite).padding(horizontal = if (narrow) 20.dp else 28.dp, vertical = if (narrow) 16.dp else 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TetherKey(onClick = state::close, classes = KeyClasses.ButtonSecondary, label = "Done", modifier = Modifier.testTag(AccountsTags.Done))
            }
        }
    }
}

@Composable
private fun Header(narrow: Boolean, state: UsageAccountsState) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (narrow) 76.dp else 88.dp)
                .padding(horizontal = if (narrow) 20.dp else 28.dp, vertical = if (narrow) 18.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // `.section-label` "Tether · account usage" is hidden in Studio (studio.css 549).
            Text(
                "Usage",
                color = t.white,
                style = cssText(type.ui, if (narrow) 1.25f else 1.375f, 700, trackingEm = -0.025f, lineHeight = 1.3f),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            val look = rememberIconLook(t.ink, t.radiusSm, enabled = !state.loading)
            SpinningKey(spinning = state.loading) { angle ->
                ChromeIconKey(
                    { state.refresh("all") },
                    TetherIcons.RefreshCw,
                    "Refresh usage",
                    look,
                    44.dp,
                    44.dp,
                    16.dp,
                    enabled = !state.loading,
                    modifier = Modifier.testTag(AccountsTags.Refresh).rotate(angle),
                )
            }
            ChromeIconKey(state::close, TetherIcons.X, "Close", look, 44.dp, 44.dp, 19.dp, modifier = Modifier.testTag(AccountsTags.Close))
        }
        TetherSeam()
    }
}

/** `.usage-refresh-spin`: one turn per 720 ms while [spinning]; static under reduced motion. */
@Composable
private fun SpinningKey(spinning: Boolean, content: @Composable (Float) -> Unit) {
    val reduced = LocalReducedMotion.current
    val angle by if (!spinning || reduced) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "usage-refresh").animateFloat(0f, 360f, infiniteRepeatable(tween(720, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    }
    content(angle)
}

@Composable
private fun Body(
    state: UsageAccountsState,
    narrow: Boolean,
    width: Dp,
    onUseCredit: (CodexResetRequest) -> Unit,
    onUseGrant: (ClaudeResetRequest) -> Unit,
    modifier: Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val env = LocalUsageEnv.current
    val data = state.data
    Column(
        modifier.fillMaxWidth().background(t.graphite).verticalScroll(rememberScrollState()).padding(horizontal = if (narrow) 20.dp else 28.dp, vertical = if (narrow) 24.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (state.error.isNotEmpty()) {
            val text = buildString {
                append(state.error)
                if (state.retrying) append(" Retrying automatically…")
                if (!state.retrying && data != null) append(" Auto-retry gave up — use the refresh button above to try again.")
                if (data != null) append(" Showing the last reading from ${UsageFormat.longClock(data.generatedAt, env)}.")
            }
            EmptyNote(text, Modifier.testTag(AccountsTags.Error).semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (data != null) androidx.compose.runtime.CompositionLocalProvider(LocalCardPadding provides if (narrow) 18.dp else 20.dp) {
            // `.usage-accounts-grid`: auto-fill columns of at least 15rem (one ≤ 640).
            val inner = width - if (narrow) 40.dp else 56.dp
            val columns = if (narrow) 1 else maxOf(1, ((inner + 20.dp) / (240.dp + 20.dp)).toInt())
            SectionTitle("Claude accounts")
            Grid(columns) {
                data.claude.map { entry ->
                    {
                        AccountCard(
                            entry = entry,
                            generatedAt = data.generatedAt,
                            fallback = "No plan usage available for this account yet.",
                            onRefresh = { state.refresh(entry.id) },
                            refreshing = state.refreshingId == entry.id,
                            onUseGrant = { grant, summary ->
                                onUseGrant(
                                    ClaudeResetRequest(
                                        accountId = entry.id,
                                        accountLabel = entry.label,
                                        grant = grant,
                                        summary = summary,
                                        // A reset clears the windows: re-read this one account (forced);
                                        // any other answer only changed the grant, already in the server's cache.
                                        onDone = { answer ->
                                            val outcome = (answer.body["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                            state.refresh(if (outcome == "reset") entry.id else null)
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            SectionTitle("Codex")
            val codex = data.codex.firstOrNull()
            Grid(columns) {
                buildList<@Composable () -> Unit> {
                    if (codex != null) add { AccountCard(codex, data.generatedAt, UsageAccountsModel.codexFallback(codex)) }
                    val credits = codex?.resetCredits
                    if (codex != null && credits != null && credits.availableCount > 0) {
                        add {
                            BankedResets(credits) {
                                onUseCredit(CodexResetRequest(credits.availableCount, credits.credits, codex.windows, onDone = { state.refresh() }))
                            }
                        }
                    }
                }
            }
            data.deepseek?.let { deepseek ->
                SectionTitle("DeepSeek")
                Grid(columns) { deepSeekCards(deepseek, data.generatedAt) }
                // Issue #193: the balance is half the picture; the rate in force and its card.
                DeepSeekRateCard()
            }
            Row(
                Modifier.padding(top = 16.dp).semantics(mergeDescendants = true) {}.testTag(AccountsTags.Note),
                horizontalArrangement = Arrangement.spacedBy(6.4.dp),
            ) {
                Icon(TetherIcons.ShieldAlert, contentDescription = null, tint = t.faint, modifier = Modifier.padding(top = 2.4.dp).size(12.dp))
                Text(
                    buildString {
                        append("Claude readings are account-wide. Codex is read over a short-lived connection when no session is open — nothing here waits on a running session.")
                        if (data.deepseek != null) append(" DeepSeek shows your API account balance.")
                        if (data.generatedAt > 0) append(" Refreshed ${UsageFormat.longClock(data.generatedAt, env)}.")
                    },
                    color = t.faint,
                    style = cssText(type.ui, 0.75f, 400, lineHeight = 1.6f),
                )
            }
        }
        if (data == null && state.error.isEmpty()) EmptyNote("Loading usage…", Modifier.testTag(AccountsTags.Loading))
    }
}

/** `.recycle-empty` (Studio): a centred faint note, 13px / 1.65, 28px of padding. */
@Composable
private fun EmptyNote(text: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    Text(
        com.tether.app.ui.text.proseText(text),
        color = t.faint,
        textAlign = TextAlign.Center,
        style = cssText(LocalTetherTypography.current.ui, 0.8125f, 400, lineHeight = 1.65f),
        modifier = modifier.fillMaxWidth().padding(28.dp),
    )
}

/** `.usage-accounts-section-title` (Studio): 14px / 650 `--ink`, 24px above, 14px below. */
@Composable
private fun SectionTitle(text: String) {
    val t = LocalTetherTokens.current
    Text(
        text,
        color = t.ink,
        style = cssText(LocalTetherTypography.current.ui, 0.875f, 650),
        modifier = Modifier.padding(top = 24.dp, bottom = 14.dp).semantics { heading() },
    )
}

@Composable
private fun Grid(columns: Int, cards: () -> List<@Composable () -> Unit>) {
    val all = cards()
    if (all.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        all.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { card -> Box(Modifier.weight(1f)) { card() } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** `.usage-account-card` (Studio): `--graphite`, `1px --line`, 12px corners, 20px (18px narrow), 16px apart. */
@Composable
private fun CardFrame(tag: String, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line))
            .padding(1.dp)
            .padding(LocalCardPadding.current)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

private val LocalCardPadding = androidx.compose.runtime.compositionLocalOf { 20.dp }

@Composable
private fun AccountCard(
    entry: AccountUsage,
    generatedAt: Double,
    fallback: String,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    onUseGrant: ((JsValue, JsValue) -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val env = LocalUsageEnv.current
    val rows = UsageAccountsModel.rows(entry)
    // ta-28i: an account's label is server text (the label rule).
    val label = LabelText.label(entry.label).ifEmpty { entry.label }
    CardFrame(AccountsTags.card(entry.id)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).cssSurface(RoundedCornerShape(9.dp), t.mineral), contentAlignment = Alignment.Center) {
                Icon(TetherIcons.UserRound, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label, color = t.white, style = cssText(type.ui, 0.9375f, 650), modifier = Modifier.semantics { heading() })
                val small = when {
                    entry.isDefault -> "Default identity"
                    !entry.managed -> "Unmanaged profile"
                    else -> null
                }
                small?.let { Text(it, color = t.faint, style = cssText(type.ui, 0.75f, 400, lineHeight = 1.5f)) }
            }
            if (onRefresh != null) {
                val look = rememberIconLook(t.ink, t.radiusSm, enabled = !refreshing)
                SpinningKey(spinning = refreshing) { angle ->
                    ChromeIconKey(onRefresh, TetherIcons.RefreshCw, "Update $label", look, 44.dp, 44.dp, 13.dp, enabled = !refreshing, modifier = Modifier.testTag(AccountsTags.update(entry.id)).rotate(angle))
                }
            }
        }
        if (rows.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEach { (rowLabel, window) -> WindowRow(rowLabel, window, env) }
            }
            UsageAccountsModel.noFiveHour(entry)?.let { Note(it, t.faint) }
            UsageAccountsModel.note(entry, generatedAt, env)?.let { (text, tone) ->
                Note(text, if (tone == UsageAccountsModel.NoteTone.Expired || tone == UsageAccountsModel.NoteTone.Throttled) t.warning else t.faint)
            }
        } else {
            Note(UsageAccountsModel.emptyText(entry, generatedAt, fallback), t.faint)
        }
        entry.resetGrants?.let { ClaudeResetGrants(it, generatedAt, onUseGrant) }
    }
}

/** `.usage-account-stale` / `.usage-account-empty` (Studio 12px / 1.6). */
@Composable
private fun Note(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, style = cssText(LocalTetherTypography.current.ui, 0.75f, 400, lineHeight = 1.6f), modifier = modifier)
}

/** `.usage-account-row`: 56px label · the meter · a 40px percent (toned), the reset caption under them. */
@Composable
private fun WindowRow(label: String, window: AccountWindow, env: UsageEnv) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val percent = percentReading(window.usedPercent)
    val caption = Format.resetTime(window.resetsAt?.let(::JsNum), env.now().toDouble()).ifEmpty { "No reset scheduled" }
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = t.muted, style = cssText(type.ui, 0.75f, 400), modifier = Modifier.width(56.dp))
            UsageTrack(percent, label, Modifier.weight(1f), placement = UsageTrackPlacement.Meter)
            Text(
                if (percent == null) "—" else "$percent%",
                color = when (usageTone(percent)) {
                    UsageTone.Critical -> t.danger
                    UsageTone.High -> t.warning
                    UsageTone.None -> t.muted
                },
                textAlign = TextAlign.End,
                style = cssText(type.ui, 0.75f, 400).tabularNums(),
                modifier = Modifier.width(40.dp),
            )
        }
        Text(caption, color = t.faint, style = cssText(type.ui, 0.75f, 400, lineHeight = 1.6f))
    }
}

/**
 * Issue #194: the account's usage-limit reset grants, each stating what is left, what it clears,
 * when it expires unused and — beside its "Use reset…" key — whether the account is walled and how
 * far the week's natural reset is. The key only OPENS the confirmation.
 */
@Composable
private fun ClaudeResetGrants(reading: ClaudeResetGrantsReading, generatedAt: Double, onUse: ((JsValue, JsValue) -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val env = LocalUsageEnv.current
    val note = UsageAccountsModel.grantsNote(reading, generatedAt, env)
    val summaryJs = reading.summary?.let { UsageAccountsModel.js(it) }
    val frame = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp)
        .drawBehind { drawRect(t.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
        .padding(top = 1.dp + 12.dp)
        .semantics { contentDescription = "Limit resets" }
    if (summaryJs == null) {
        if (note != null) Column(frame) { Note(note, t.warning) }
        return
    }
    val grants = ((summaryJs as JsObj)["grants"] as? JsArr).orEmpty()
    if (grants.isEmpty()) {
        Column(frame, verticalArrangement = Arrangement.spacedBy(5.6.dp)) {
            val eligible = summaryJs["eligible"] == JsBool.TRUE
            Note("Limit resets: none${if (!eligible) " — ${ClaudeResetGrantsView.ineligibleReasonLabel(summaryJs["ineligibleReason"])}" else ""}.", t.faint)
            note?.let { Note(it, t.warning) }
        }
        return
    }
    Column(frame, verticalArrangement = Arrangement.spacedBy(5.6.dp)) {
        Text("Limit resets", color = t.muted, style = cssText(type.ui, 0.68f, 600))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grants.forEach { grant ->
                val view = ClaudeResetGrantsView.resetGrantView(grant, summaryJs, generatedAt)
                val tone = (view["tone"] as JsStr).value
                val status = (view["status"] as JsStr).value
                val next = view["next"] == JsBool.TRUE
                val lines = (view["lines"] as JsArr).map { (it as JsStr).value }
                Column(
                    Modifier.fillMaxWidth().alpha(if (tone == "spent" || tone == "expired") 0.7f else 1f),
                    verticalArrangement = Arrangement.spacedBy(1.92.dp),
                ) {
                    Column(Modifier.semantics(mergeDescendants = true) {}) {
                        Text(LabelText.label(((grant as JsObj)["label"] as? JsStr)?.value).ifEmpty { "Usage-limit reset" }, color = t.white, style = cssText(type.ui, 0.72f, 400))
                        Text(
                            buildAnnotatedString {
                                append(status)
                                if (next && tone == "available") withStyle(SpanStyle(color = t.faint, fontWeight = FontWeight(400))) { append(" · next the CLI would use") }
                            },
                            color = t.muted,
                            style = cssText(type.ui, 0.72f, 600).tabularNums(),
                        )
                        lines.forEach { Text(it, color = t.faint, style = cssText(type.ui, 0.66f, 400, lineHeight = 1.35f)) }
                    }
                    val grantId = ((grant as JsObj)["id"] as? JsStr)?.value.orEmpty()
                    if (onUse != null && tone == "available" && (grant as JsObj)["usableNow"] == JsBool.TRUE && !reading.tokenExpired) {
                        TetherKey(
                            onClick = { onUse(grant, summaryJs) },
                            classes = KeyClasses.ButtonSecondary,
                            label = "Use reset…",
                            modifier = Modifier.padding(top = 4.dp).testTag(AccountsTags.useGrant(grantId)),
                        )
                    }
                }
            }
        }
        note?.let { Note(it, t.warning) }
    }
}

/** Issue #110: the Codex card's banked reset credits and their "Use reset". */
@Composable
private fun BankedResets(credits: CodexResetCredits, onUse: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val env = LocalUsageEnv.current
    CardFrame(AccountsTags.BankedResets) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Banked resets", color = t.white, style = cssText(type.ui, 0.9375f, 650), modifier = Modifier.semantics { heading() })
            Text("${UsageFormat.js(credits.availableCount)} available", color = t.faint, style = cssText(type.ui, 0.75f, 400, lineHeight = 1.5f))
        }
        val list = credits.credits.orEmpty()
        if (list.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
                list.forEach { credit ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(LabelText.label(credit.title).ifEmpty { "Reset credit" }, color = t.muted, style = cssText(type.ui, 0.8125f, 400, lineHeight = 1.6f), modifier = Modifier.weight(1f))
                        Text(Format.resetTime(credit.expiresAt?.let(::JsNum), env.now().toDouble()).ifEmpty { "No expiry" }, color = t.faint, style = cssText(type.ui, 0.75f, 400))
                    }
                }
            }
        }
        TetherKey(onClick = onUse, classes = KeyClasses.ButtonSecondary, label = "Use reset", modifier = Modifier.testTag(AccountsTags.UseCredit))
    }
}

private fun deepSeekCards(entry: DeepSeekUsage, generatedAt: Double): List<@Composable () -> Unit> {
    if (!entry.ok) {
        return listOf<@Composable () -> Unit>({
            CardFrame(AccountsTags.DeepSeek) {
                CardTitle("DeepSeek")
                when {
                    entry.reason == "no_key" -> EmptyLine("No DeepSeek API key configured — set DEEPSEEK_API_KEY or configure a key in dsh, Pi, OpenCode or Reasonix.")
                    entry.unavailable.isNotEmpty() -> UnavailableLines(entry.unavailable)
                    else -> EmptyLine("Could not read balance: ${entry.error?.takeIf { it.isNotEmpty() } ?: "unknown error"}.")
                }
            }
        })
    }
    val showFingerprint = entry.accounts.size > 1
    return buildList {
        entry.accounts.forEach { account -> add { DeepSeekAccountCard(account, generatedAt, showFingerprint) } }
        if (entry.unavailable.isNotEmpty()) {
            add {
                CardFrame(AccountsTags.DeepSeek + "-unavailable") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CardTitle("DeepSeek")
                        Text("Unavailable", color = LocalTetherTokens.current.warning, style = cssText(LocalTetherTypography.current.ui, 0.75f, 400, lineHeight = 1.5f))
                    }
                    UnavailableLines(entry.unavailable)
                }
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = LocalTetherTokens.current.white, style = cssText(LocalTetherTypography.current.ui, 0.9375f, 650), modifier = Modifier.semantics { heading() })
}

/** `.usage-account-empty`: may carry the server's words (an error), by the prose rule. */
@Composable
private fun EmptyLine(text: String) {
    Text(com.tether.app.ui.text.proseText(text), color = LocalTetherTokens.current.faint, style = cssText(LocalTetherTypography.current.ui, 0.75f, 400, lineHeight = 1.6f))
}

@Composable
private fun UnavailableLines(unavailable: List<DeepSeekUnavailable>) {
    unavailable.forEach { u ->
        EmptyLine("Could not read the balance for ${UsageAccountsModel.deepSeekSources(u.sources)} (key ${u.fingerprint}): ${u.error}.")
    }
}

/** One card per DeepSeek ACCOUNT (issue #167): balances, and which harnesses' keys reported it. */
@Composable
private fun DeepSeekAccountCard(account: DeepSeekAccount, generatedAt: Double, showFingerprint: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val env = LocalUsageEnv.current
    CardFrame(AccountsTags.DeepSeek) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("DeepSeek")
            if (showFingerprint) Text("Key ${account.fingerprints.joinToString(" · ")}", color = t.faint, style = cssText(type.ui, 0.75f, 400, lineHeight = 1.5f))
            Text(
                if (account.isAvailable) "Active" else "Insufficient balance",
                color = if (account.isAvailable) t.faint else t.warning,
                style = cssText(type.ui, 0.75f, 400, lineHeight = 1.5f),
            )
        }
        if (account.balances.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                account.balances.forEach { b ->
                    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Balance (${b.currency})", color = t.muted, style = cssText(type.ui, 0.75f, 400), modifier = Modifier.width(56.dp))
                            Text(b.total, color = t.white, style = cssText(type.ui, 0.85f, 650).tabularNums(), modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(40.dp))
                        }
                        val paid = b.toppedUp.toDoubleOrNull() ?: 0.0
                        val granted = b.granted.toDoubleOrNull() ?: 0.0
                        if (granted > 0 || paid > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.8.dp)) {
                                if (paid > 0) Text("Paid: ${b.toppedUp}", color = t.faint, style = cssText(type.ui, 0.64f, 400))
                                if (granted > 0) Text("Credits: ${b.granted}", color = t.faint, style = cssText(type.ui, 0.64f, 400))
                            }
                        }
                    }
                }
            }
        } else {
            Note("No balance information returned.", t.faint)
        }
        Note(
            buildString {
                append("Reported by ${UsageAccountsModel.deepSeekSources(account.sources)}.")
                if (account.identity == "reading") {
                    append(
                        if (account.weakEvidence) " Different keys with an identical zero balance — grouped, but that is weak evidence of one account."
                        else " Different keys with an identical balance — presumed one account, not proven.",
                    )
                }
                if (account.stale) append(" Showing last reading — current fetch failed.")
                account.at?.takeIf { it > 0 }?.let { append(" As of ${UsageAccountsModel.lastReadingAge(it, generatedAt, env)}.") }
            },
            t.faint,
        )
    }
}
