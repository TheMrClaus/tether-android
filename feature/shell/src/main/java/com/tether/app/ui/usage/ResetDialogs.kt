package com.tether.app.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tether.app.client.AccountWindows
import com.tether.app.client.ClaudeClaimAnswer
import com.tether.app.client.CodexResetCredit
import com.tether.app.client.LabelText
import com.tether.app.client.UsageCall
import com.tether.app.client.UsageFailure
import com.tether.app.client.UsageSource
import com.tether.app.protocol.helpers.ClaudeResetGrantsView
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.shell.rememberDialogIn
import com.tether.app.ui.statusline.percentReading
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.tabularNums
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * T9.2: the two irreversible reset confirmations — components/codex-reset-credit-dialog.tsx and
 * components/claude-reset-grant-dialog.tsx — on the `.confirm-dialog` chrome (Studio: 28px body,
 * 22px title, 14px copy; the Codex one is 480px wide). Each confirms exactly as the web does and
 * sends exactly the web's request; the server decides (both routes are owner-grade, which the paired
 * app is). A resolution from an earlier open is never shown in a later one ([attempt]); while the
 * request is in flight neither Cancel nor Back closes the dialog. Like the web's `<dialog>`, a tap
 * on the backdrop does nothing.
 */

internal object ResetTags {
    const val CodexDialog = "codex-reset-credit-dialog"
    const val ClaudeDialog = "claude-reset-grant-dialog"
    const val Confirm = "reset-confirm"
    const val Cancel = "reset-cancel"
    const val Done = "reset-done"
    const val Error = "reset-error"
    const val Outcome = "reset-outcome"
}

// ── Codex ────────────────────────────────────────────────────────────────────

/** `CodexResetCreditRequest`. [sessionId]: the inspector's (a hint for the warm engine); absent from Usage. */
data class CodexResetRequest(
    val availableCount: Double,
    val credits: List<CodexResetCredit>?,
    val windows: AccountWindows?,
    val sessionId: String? = null,
    /** Called once on a settled answer (any 2xx), never on a failure. */
    val onDone: () -> Unit = {},
)

/** The five outcomes, each its own honest copy; "unknown" never reads as success. */
object CodexResetCopy {
    fun outcome(code: String?, weeklyOnly: Boolean): String = when (code) {
        "reset" -> if (weeklyOnly) "Reset applied. Your weekly window has been restored." else "Reset applied. Your 5-hour and weekly windows have been restored."
        "alreadyRedeemed" -> "That reset was already redeemed."
        "nothingToReset" -> "There was nothing to reset right now."
        "noCredit" -> "That credit is no longer available."
        else -> "Request completed, but the response wasn't recognized. Reopen this dialog to see the current state."
    }

    /** The web's error line: the server's `error`, else "Redeem failed (status)."; a fetch that never got through, the browser's message. */
    fun error(failure: UsageFailure): String = when (failure) {
        is UsageFailure.Http -> failure.error ?: "Redeem failed (${failure.code})."
        UsageFailure.Unreachable -> "Failed to fetch"
        is UsageFailure.Blocked -> "${UsageDashboardModel.GATEWAY}."
        UsageFailure.SignedOut -> "Not signed in to Tether."
        UsageFailure.LocalNetworkBlocked -> com.tether.app.client.ServiceOpenSource.LOCAL_NETWORK
        UsageFailure.OtherServer -> "Signed in to another server now — reopen Usage."
        is UsageFailure.Unusable -> "Redeem failed."
    }
}

@Stable
class CodexResetState(private val scope: CoroutineScope, private val source: () -> UsageSource, private val origin: () -> String?) {
    var request by mutableStateOf<CodexResetRequest?>(null)
        private set
    var selectedCreditId by mutableStateOf<String?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf("")
        private set
    private var attempt = 0

    fun open(next: CodexResetRequest) {
        attempt += 1
        request = next
        selectedCreditId = next.credits?.firstOrNull()?.id
        outcome = null
        error = ""
        pending = false
    }

    /** Cancel / Done / Back; refused while the redeem is in flight. */
    fun close() {
        if (pending) return
        request = null
    }

    fun confirm() {
        val req = request ?: return
        if (pending) return
        val generation = attempt
        pending = true
        error = ""
        val asked = origin()
        scope.launch {
            val call = source().consumeResetCredit(asked, selectedCreditId, req.sessionId)
            if (attempt != generation) return@launch // superseded: dropped silently
            pending = false
            when (call) {
                is UsageCall.Ok -> {
                    outcome = call.value.outcome?.takeIf { it in KNOWN } ?: "unknown"
                    req.onDone()
                }
                is UsageCall.Failed -> error = CodexResetCopy.error(call.failure)
            }
        }
    }

    private companion object {
        val KNOWN = setOf("reset", "alreadyRedeemed", "nothingToReset", "noCredit")
    }
}

@Composable
fun CodexResetDialog(state: CodexResetState) {
    val request = state.request ?: return
    val env = LocalUsageEnv.current
    val windows = request.windows
    val weeklyOnly = windows?.weekly != null && windows.fiveHour == null
    val selected = request.credits?.firstOrNull { it.id == state.selectedCreditId }
    ResetDialogHost(onBack = state::close, width = 480.dp, tag = ResetTags.CodexDialog) { compact ->
        ConfirmBody(compact, "Use a banked reset?") {
            val outcome = state.outcome
            if (outcome != null) {
                BodyText(CodexResetCopy.outcome(outcome, weeklyOnly), Modifier.testTag(ResetTags.Outcome).semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                BodyText("Redeeming instantly restores your ${if (weeklyOnly) "weekly rate-limit window" else "5-hour and weekly rate-limit windows"}. This cannot be undone.")
                CreditRow(
                    count = request.availableCount,
                    title = selected?.let { LabelText.label(it.title).ifEmpty { "Reset credit" } },
                    expiry = selected?.let { com.tether.app.protocol.helpers.Format.resetTime(it.expiresAt?.let(::JsNum), env.now().toDouble()).ifEmpty { "No expiry" } } ?: "No expiry",
                )
                if (windows?.fiveHour != null || windows?.weekly != null) {
                    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        windows.fiveHour?.let { WindowSummary("5 hour", it.usedPercent) }
                        windows.weekly?.let { WindowSummary("Weekly", it.usedPercent) }
                    }
                }
                if (state.error.isNotEmpty()) ErrorLine(state.error)
            }
        }
        ConfirmFooter(compact) {
            if (state.outcome != null) {
                TetherKey(onClick = state::close, classes = KeyClasses.ButtonSecondary, label = "Done", modifier = Modifier.testTag(ResetTags.Done))
            } else {
                TetherKey(onClick = state::close, classes = KeyClasses.ButtonSecondary, label = "Cancel", enabled = !state.pending, modifier = Modifier.testTag(ResetTags.Cancel))
                TetherKey(
                    onClick = state::confirm,
                    classes = KeyClasses.ButtonDanger,
                    label = if (state.pending) "Redeeming…" else "Use reset",
                    enabled = !state.pending,
                    modifier = Modifier.testTag(ResetTags.Confirm),
                )
            }
        }
    }
}

// ── Claude ───────────────────────────────────────────────────────────────────

/** `ClaudeResetGrantRequest`: [grant] and [summary] are the reading's objects as sent (the view helpers read them). */
data class ClaudeResetRequest(
    val accountId: String,
    val accountLabel: String,
    val grant: JsValue,
    val summary: JsValue,
    /** Called once Anthropic answered (`sent`), with the answer; never on a refusal or failure. */
    val onDone: (ClaudeClaimAnswer) -> Unit = {},
)

object ClaudeResetCopy {
    /** The web's error line (claude-reset-grant-dialog.tsx:82-93). */
    fun error(failure: UsageFailure): String = when (failure) {
        is UsageFailure.Http -> failure.error ?: "Claim failed (HTTP ${failure.code})."
        UsageFailure.Unreachable -> "Could not reach Tether — it is unknown whether the claim was sent. Reopen Usage to check before retrying."
        is UsageFailure.Blocked -> "${UsageDashboardModel.GATEWAY}."
        UsageFailure.SignedOut -> "Not signed in to Tether."
        UsageFailure.LocalNetworkBlocked -> com.tether.app.client.ServiceOpenSource.LOCAL_NETWORK
        UsageFailure.OtherServer -> "Signed in to another server now — reopen Usage."
        is UsageFailure.Unusable -> "Claim failed."
    }
}

@Stable
class ClaudeResetState(
    private val scope: CoroutineScope,
    private val source: () -> UsageSource,
    private val origin: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var request by mutableStateOf<ClaudeResetRequest?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var response by mutableStateOf<ClaudeClaimAnswer?>(null)
        private set
    var error by mutableStateOf("")
        private set
    var openedAt by mutableStateOf(0L)
        private set
    private var attempt = 0

    fun open(next: ClaudeResetRequest) {
        attempt += 1
        request = next
        response = null
        error = ""
        pending = false
        openedAt = clock()
    }

    fun close() {
        if (pending) return
        request = null
    }

    fun confirm() {
        val req = request ?: return
        if (pending) return
        val generation = attempt
        pending = true
        error = ""
        val asked = origin()
        val grantId = (req.grant["id"] as? JsStr)?.value.orEmpty()
        scope.launch {
            val call = source().claimResetGrant(asked, req.accountId, grantId)
            if (attempt != generation) return@launch
            pending = false
            when (call) {
                is UsageCall.Ok -> {
                    response = call.value
                    if (call.value.sent) req.onDone(call.value)
                }
                is UsageCall.Failed -> error = ClaudeResetCopy.error(call.failure)
            }
        }
    }

    /** Test seam (goldens): an answer as if the server had sent it. */
    internal fun seed(answer: ClaudeClaimAnswer?, error: String = "", pending: Boolean = false) {
        response = answer
        this.error = error
        this.pending = pending
    }
}

private operator fun JsValue?.get(key: String): JsValue? = (this as? JsObj)?.get(key)

private fun JsValue?.num(): Double? = (this as? JsNum)?.value?.takeIf { it.isFinite() }

@Composable
fun ClaudeResetDialog(state: ClaudeResetState) {
    val request = state.request ?: return
    val env = LocalUsageEnv.current
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val grant = request.grant
    val summary = request.summary
    val openedAt = state.openedAt.toDouble()
    val clears = ClaudeResetGrantsView.claimClearsList(grant["clears"])
    val weeklyAt = summary["weeklyResetsAt"].num()
    val weeklyIn = if (weeklyAt != null && weeklyAt > openedAt) ClaudeResetGrantsView.durationLabel(weeklyAt - openedAt) else null
    val exhausted = ClaudeResetGrantsView.windowListLabel(summary["exhausted"])
    val atLimit = summary["atLimit"] == JsBool.TRUE
    val nearNaturalReset = !atLimit && weeklyAt != null && weeklyAt - openedAt < 24 * 3_600_000.0
    val response = state.response
    val copy = response?.let { ClaudeResetGrantsView.claimOutcomeCopy(UsageAccountsModel.js(it.body), env.now().toDouble(), env.locale, env.zone) }
    val settled = copy?.get("settled") == JsBool.TRUE
    val accountLabel = LabelText.label(request.accountLabel).ifEmpty { "this account" }
    ResetDialogHost(onBack = state::close, width = 480.dp, tag = ResetTags.ClaudeDialog) { compact ->
        ConfirmBody(compact, "Use a limit reset on $accountLabel?") {
            if (copy != null && response != null) {
                BodyText((copy["text"] as? JsStr)?.value.orEmpty(), Modifier.testTag(ResetTags.Outcome).semantics { liveRegion = LiveRegionMode.Polite })
                val result = (response.body["result"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                val reason = (response.body["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                if (!result.isNullOrEmpty()) {
                    val code = SpanStyle(fontFamily = type.mono, fontSize = cssText(type.ui, 0.7f, 400).fontSize, color = t.faint)
                    Text(
                        buildAnnotatedString {
                            append("Anthropic answered ")
                            withStyle(code) { append(LabelText.label(result)) }
                            if (!reason.isNullOrEmpty()) {
                                append(" · reason ")
                                withStyle(code) { append(LabelText.label(reason)) }
                            }
                        },
                        color = t.faint,
                        style = cssText(type.ui, 0.72f, 400, lineHeight = 1.65f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                CreditRow(
                    count = null,
                    title = LabelText.label((grant["label"] as? JsStr)?.value).ifEmpty { "Usage-limit reset" },
                    expiry = grant["endsAt"].num()?.let { "expires ${UsageFormat.weekdayDateTime(it, env)}" },
                    leading = "${UsageFormat.js(grant["resetsLeft"].num() ?: 0.0)} of ${UsageFormat.js(grant["resetsTotal"].num() ?: 0.0)} left",
                )
                BodyText("Refills now:")
                Column(Modifier.padding(start = 17.6.dp, bottom = 12.dp)) {
                    clears.forEach { entry ->
                        val label = ((entry as JsObj)["label"] as JsStr).value
                        val code = (entry["code"] as JsStr).value
                        Text(
                            buildAnnotatedString {
                                append("•  $label ")
                                withStyle(SpanStyle(fontFamily = type.mono, fontSize = cssText(type.ui, 0.7f, 400).fontSize, color = t.faint)) { append(code) }
                            },
                            color = t.muted,
                            style = cssText(type.ui, 0.78f, 400, lineHeight = 1.55f),
                        )
                    }
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = t.ink)) { append("Your weekly reset day does not move") }
                        append(if (weeklyAt != null) " — it stays ${UsageFormat.weekdayDateTime(weeklyAt, env)}${if (weeklyIn != null) " (in $weeklyIn)" else ""}." else ".")
                    },
                    color = t.muted,
                    style = cssText(type.ui, 0.875f, 400, lineHeight = 1.65f),
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    (if (atLimit) "At the limit now${if (exhausted.isNotEmpty()) " ($exhausted)" else ""}."
                    else "Not at a limit right now${if (weeklyIn != null) " · the week frees itself in $weeklyIn" else ""}.") +
                        if (nearNaturalReset) " Spending a reset this close to the natural reset refunds very little — wait unless you need it sooner." else "",
                    color = t.muted,
                    style = cssText(type.ui, 0.78f, 400, lineHeight = 1.65f),
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (grant["useRequiresLimit"] == JsBool.TRUE && !atLimit) {
                    Text(
                        "Anthropic only applies this reset once a limit is hit — expect “not_limited”.",
                        color = t.muted,
                        style = cssText(type.ui, 0.78f, 400, lineHeight = 1.65f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                BodyText("This uses one single-use grant and cannot be undone.")
                if (state.error.isNotEmpty()) ErrorLine(state.error)
            }
        }
        ConfirmFooter(compact) {
            if (settled) {
                TetherKey(onClick = state::close, classes = KeyClasses.ButtonSecondary, label = "Done", modifier = Modifier.testTag(ResetTags.Done))
            } else {
                TetherKey(onClick = state::close, classes = KeyClasses.ButtonSecondary, label = "Cancel", enabled = !state.pending, modifier = Modifier.testTag(ResetTags.Cancel))
                TetherKey(
                    onClick = state::confirm,
                    classes = KeyClasses.ButtonDanger,
                    label = when {
                        state.pending -> "Claiming…"
                        copy != null -> "Retry"
                        else -> "Use reset"
                    },
                    enabled = !state.pending,
                    modifier = Modifier.testTag(ResetTags.Confirm),
                )
            }
        }
    }
}

// ── The shared confirm chrome ────────────────────────────────────────────────

/** True draws both confirmations in place (no window), as the goldens shoot them over the Accounts frame. */
internal val LocalResetDialogsInline = staticCompositionLocalOf { false }

/** A modal window with the skin's scrim; Back closes it only through [onBack] (which refuses while pending). */
@Composable
private fun ResetDialogHost(onBack: () -> Unit, width: Dp, tag: String, content: @Composable ColumnScope.(compact: Boolean) -> Unit) {
    if (LocalResetDialogsInline.current) {
        ResetDialogFrame(width = width, tag = tag, content = content)
        return
    }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        val progress = rememberDialogIn()
        ResetDialogFrame(
            width = width,
            tag = tag,
            surfaceModifier = Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
                scaleX = 0.99f + 0.01f * p
                scaleY = 0.99f + 0.01f * p
            },
            content = content,
        )
    }
}

/** The scrim-filled window with the confirm card centred in it (the inline form the goldens shoot). */
@Composable
internal fun ResetDialogFrame(width: Dp, tag: String, modifier: Modifier = Modifier, surfaceModifier: Modifier = Modifier, content: @Composable ColumnScope.(compact: Boolean) -> Unit) {
    val t = LocalTetherTokens.current
    BoxWithConstraints(modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        val compact = maxWidth <= 640.dp
        Column(
            surfaceModifier
                .width(minOf(width, maxWidth - 32.dp))
                .heightIn(max = maxHeight - 48.dp)
                .cssSurface(RoundedCornerShape(StudioDialog.radius), t.graphite, null, StudioDialog.shadows)
                .testTag(tag),
        ) { content(compact) }
    }
}

@Composable
private fun ColumnScope.ConfirmBody(compact: Boolean, title: String, body: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(28.dp)) {
        Text(
            title,
            color = t.white,
            style = cssText(type.ui, if (compact) 1.25f else 1.375f, 700, trackingEm = -0.025f, lineHeight = 1.3f),
            modifier = Modifier.semantics { heading() },
        )
        body()
    }
}

@Composable
private fun ConfirmFooter(compact: Boolean, keys: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 20.dp else 28.dp, vertical = if (compact) 16.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { keys() }
}

/** `.confirm-dialog p` (Studio): 12px above, 14px / 1.65 `--muted`. */
@Composable
private fun BodyText(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Text(text, color = t.muted, style = cssText(LocalTetherTypography.current.ui, 0.875f, 400, lineHeight = 1.65f), modifier = modifier.padding(top = 12.dp))
}

/** `.codex-reset-credit-error` (role="alert"): the server's words by the prose rule. */
@Composable
private fun ErrorLine(text: String) {
    val t = LocalTetherTokens.current
    Text(
        com.tether.app.ui.text.proseText(text),
        color = t.danger,
        style = cssText(LocalTetherTypography.current.ui, 0.74f, 400, lineHeight = 1.65f),
        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Assertive }.testTag(ResetTags.Error),
    )
}

/** `.codex-reset-credit-row`: count / title / expiry, wrapping, 13px `--muted` (title white 600, expiry 12px faint). */
@Composable
private fun CreditRow(count: Double?, title: String?, expiry: String?, leading: String? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val base = cssText(type.ui, 0.8125f, 400, lineHeight = 1.6f).tabularNums()
    FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
        count?.let { Text("${UsageFormat.js(it)} available", color = t.muted, style = base) }
        title?.let { Text(it, color = t.white, style = base.copy(fontWeight = FontWeight(600))) }
        leading?.let { Text(it, color = t.muted, style = base) }
        expiry?.let { Text(it, color = t.faint, style = cssText(type.ui, 0.75f, 400, lineHeight = 1.6f)) }
    }
}

/** `.codex-reset-credit-window`: the label over its percent. */
@Composable
private fun WindowSummary(label: String, usedPercent: Double?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val percent = percentReading(usedPercent)
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.6.dp)) {
        Text(label, color = t.muted, style = cssText(type.ui, 0.8125f, 400, lineHeight = 1.6f))
        Text(if (percent == null) "—" else "$percent%", color = t.white, style = cssText(type.ui, 0.85f, 700).tabularNums())
    }
}
