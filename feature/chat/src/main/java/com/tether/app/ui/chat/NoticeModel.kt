package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.client.LabelText
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.time.Instant
import java.time.ZoneId

/*
 * T6.6: the notices of one session, read from its projection TREE (the typed projection carries no
 * dismiss keys): chat-view.tsx 3434-3471 (turn tails), 3533-3652 (session notices, the limit card,
 * the scheduled resume), codex-rich-renderers.tsx 366-418 (CodexNotices), lib/interrupt-notice.mjs.
 * Pure. Every server string is cleaned and bounded here (LabelText), before any composable sees it.
 */

/** A notice body's display bound (the reducer already bounds the stored text; this is the screen's). */
internal const val NOTICE_BODY_MAX = 2_000

/** One provider notice (`ProviderNoticeProjection`): a Codex notice, or Claude's model fallback (v124). */
@Immutable
internal data class ProviderNoticeView(
    val noticeId: String,
    /** "info" | "warning" | "error" (anything else was folded to "warning"). */
    val level: String,
    /** The bold lead-in: "<provider> · <code>" or just the provider. */
    val heading: String,
    val message: String,
    /** Null when the fold stamped none: the row then shows no X. */
    val dismissKey: String?,
)

/** A Codex `context_compacted` record of one turn. */
@Immutable
internal data class CompactionView(val itemId: String, val dismissKey: String?)

/** The session notices the transcript foot shows (external advancement, background loss). */
@Immutable
internal data class SessionNoticeView(val kind: String, val text: String, val dismissKey: String?, val dismissLabel: String)

/** A `turn_interrupted` notice, shown in words at its turn's outcome row (issue #184). */
@Immutable
internal data class InterruptNoticeView(val by: String?, val at: Double?, val stoppedTools: Int, val stoppedBackground: Int)

/** `state.rateLimitResume` while it asks for a choice or holds a schedule. */
@Immutable
internal data class RateLimitPromptView(
    /** "awaiting_choice" | "scheduled". */
    val status: String,
    /** The prompt's reset instant, exactly as the wire carries it (the choice is bound to it). */
    val resetsAt: Long,
    val resumeAt: Long,
)

private fun JsValue?.str(): String? = (this as? JsStr)?.value
private fun JsValue?.num(): Double? = (this as? JsNum)?.value?.takeIf { it.isFinite() }

/** A dismiss key as the wire may carry it (protocol-validate: non-empty, ≤ 512 units), else null. */
private fun dismissKeyOf(value: JsValue?): String? = value.str()?.takeIf { it.isNotEmpty() && it.length <= 512 }

/** codex-rich-renderers.tsx:402-404 — `<provider> · <code>` when the notice has a code. */
internal fun providerNoticeHeading(providerLabel: String, code: String?): String {
    val shown = LabelText.label(code)
    return if (shown.isEmpty()) providerLabel else "$providerLabel · $shown"
}

/** A `providerNotices` list (session- or turn-scoped), in order; rows without an id or message are skipped. */
internal fun providerNotices(list: JsValue?, providerLabel: String): List<ProviderNoticeView> =
    (list as? JsArr)?.mapNotNull { item ->
        val o = item as? JsObj ?: return@mapNotNull null
        val id = o["noticeId"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val message = LabelText.clean(o["message"].str(), NOTICE_BODY_MAX)
        val level = o["level"].str().takeIf { it == "info" || it == "error" } ?: "warning"
        ProviderNoticeView(id, level, providerNoticeHeading(providerLabel, o["code"].str()), message, dismissKeyOf(o["dismissKey"]))
    }?.take(LabelText.MAX_ITEMS).orEmpty()

/** A turn's `compactions` (Codex). */
internal fun compactions(turn: JsObj?): List<CompactionView> =
    (turn?.get("compactions") as? JsArr)?.mapNotNull { item ->
        val o = item as? JsObj ?: return@mapNotNull null
        val id = o["itemId"].str() ?: return@mapNotNull null
        CompactionView(id, dismissKeyOf(o["dismissKey"]))
    }?.take(LabelText.MAX_ITEMS).orEmpty()

/**
 * The provider whose notices the chat shows for this session, or null: a rich Codex session's
 * ("Codex") and a Claude session's ("Claude", issue #179 model fallback); nobody else's
 * (chat-view.tsx:3434-3440, 3537-3539).
 */
internal fun noticeProviderLabel(provider: String?, richCodex: Boolean): String? = when {
    richCodex -> "Codex"
    provider == "claude" -> "Claude"
    else -> null
}

/**
 * chat-view.tsx:3555-3604: the session notices at the transcript foot, in their order. A
 * `turn_interrupted` notice is not here (it reads at its turn's outcome row).
 */
internal fun sessionNotices(tree: JsObj?): List<SessionNoticeView> =
    (tree?.get("notices") as? JsArr)?.mapNotNull { item ->
        val o = item as? JsObj ?: return@mapNotNull null
        when (val kind = o["kind"].str()) {
            "external_advancement" -> {
                val turns = o["count"].num() ?: 0.0
                val count = jsCount(turns)
                val plural = if (turns == 1.0) "turn" else "turns"
                val verb = if (turns == 1.0) "reflects" else "reflect"
                SessionNoticeView(
                    kind,
                    "$count $plural advanced outside Tether (resumed in a terminal or another editor) — the latest $plural above $verb that external activity.",
                    dismissKeyOf(o["dismissKey"]),
                    "Dismiss external-advancement notice",
                )
            }
            "background_interrupted", "background_abandoned" -> {
                val outstanding = o["outstanding"].num() ?: 0.0
                val plural = if (outstanding > 1) " (${jsCount(outstanding)} tasks)" else ""
                val reason = when {
                    kind == "background_interrupted" -> "was interrupted by a restart"
                    o["reason"].str() == "engine_died" -> "was dropped because the agent process terminated unexpectedly"
                    else -> "was dropped when its idle session was recycled"
                }
                SessionNoticeView(
                    kind,
                    "A background task $reason and won’t report back$plural — re-run it if you still need the result.",
                    dismissKeyOf(o["dismissKey"]),
                    "Dismiss background-loss notice",
                )
            }
            else -> null
        }
    }?.take(LabelText.MAX_ITEMS).orEmpty()

/** A JS number as `${n}` prints it for the counts these notices carry (whole numbers, no ".0"). */
private fun jsCount(value: Double): String =
    if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/** lib/interrupt-notice.mjs interruptNoticesByTurn: the latest `turn_interrupted` per turn. */
internal fun interruptNoticesByTurn(tree: JsObj?): Map<String, InterruptNoticeView> {
    val byTurn = HashMap<String, InterruptNoticeView>()
    (tree?.get("notices") as? JsArr)?.forEach { item ->
        val o = item as? JsObj ?: return@forEach
        if (o["kind"].str() != "turn_interrupted") return@forEach
        val turnId = o["interruptedTurnId"].str() ?: return@forEach
        byTurn[turnId] = InterruptNoticeView(
            by = o["by"].str(),
            at = o["at"].num(),
            stoppedTools = wholeCount(o["stoppedTools"]),
            stoppedBackground = wholeCount(o["stoppedBackground"]),
        )
    }
    return byTurn
}

/** `Number.isInteger(v) ? v : 0`, clamped to a displayable Int. */
private fun wholeCount(value: JsValue?): Int {
    val d = value.num() ?: return 0
    if (d != Math.floor(d)) return 0
    return d.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
}

private val INTERRUPTED_BY_COPY = mapOf(
    "message-send" to "by your message",
    "operator-stop" to "by Stop",
    "control-api" to "by a Control API client",
    "parent-cancel" to "by its parent session",
    "watchdog" to "by the watchdog (no output for too long)",
    "teardown" to "when the session was ended",
)

/**
 * lib/interrupt-notice.mjs interruptedNoticeCopy: "Interrupted at 09:58:53 by your message · 1 tool
 * stopped · 2 background tasks killed" (the time in the device's zone, like the browser's).
 */
internal fun interruptedNoticeCopy(notice: InterruptNoticeView?, zone: ZoneId = ZoneId.systemDefault()): String {
    notice ?: return ""
    val time = notice.at?.let { at ->
        val local = Instant.ofEpochMilli(at.toLong()).atZone(zone)
        "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
    } ?: ""
    val by = INTERRUPTED_BY_COPY[notice.by] ?: ""
    val head = listOf("Interrupted", if (time.isNotEmpty()) "at $time" else "", by).filter { it.isNotEmpty() }.joinToString(" ")
    val parts = mutableListOf(head)
    fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    if (notice.stoppedTools > 0) parts.add("${plural(notice.stoppedTools, "tool", "tools")} stopped")
    if (notice.stoppedBackground > 0) parts.add("${plural(notice.stoppedBackground, "background task", "background tasks")} killed")
    return parts.joinToString(" · ")
}

/** chat-view.tsx OUTCOME_COPY. */
internal val OUTCOME_COPY = mapOf(
    "cancelled" to "Turn interrupted",
    "error" to "Turn ended with an error",
    "outcome_unknown" to "Outcome unknown — the turn was interrupted before it finished (it may have partially applied)",
)

/**
 * chat-view.tsx:3468-3471: the outcome row's words — the interrupt notice's account (a cancelled
 * turn only), else the turn's error, else the outcome's copy, else the raw outcome.
 */
internal fun outcomeText(outcome: String, error: String?, interrupt: InterruptNoticeView?, zone: ZoneId = ZoneId.systemDefault()): String {
    val interrupted = if (outcome == "cancelled") interruptedNoticeCopy(interrupt, zone) else ""
    return interrupted.ifEmpty { null }
        ?: LabelText.clean(error, NOTICE_BODY_MAX).ifEmpty { null }
        ?: OUTCOME_COPY[outcome]
        ?: LabelText.label(outcome)
}

/** chat-view.tsx API_RETRY_COPY: the SDK's coarse retry reason as a sentence fragment. */
private val API_RETRY_COPY = mapOf(
    "overloaded" to "the model is overloaded",
    "rate_limit" to "rate limited",
    "server_error" to "the provider had a server error",
    "authentication_failed" to "authentication failed",
    "oauth_org_not_allowed" to "this organisation is not allowed",
    "billing_error" to "a billing problem was reported",
    "invalid_request" to "the request was rejected as invalid",
    "model_not_found" to "the model was not found",
    "max_output_tokens" to "the response hit the output-token limit",
    "unknown" to "the provider call failed",
)

/** chat-view.tsx:3459-3460: "<why> — retrying (attempt N[ of M])". */
internal fun apiRetryText(error: String?, attempt: Int, maxRetries: Int?): String {
    val why = API_RETRY_COPY[error ?: ""] ?: "the provider call failed"
    val of = if (maxRetries != null && maxRetries != 0) " of $maxRetries" else ""
    return "$why — retrying (attempt $attempt$of)"
}

/**
 * The limit prompt (chat-view.tsx:3628-3652): the card while it awaits a choice, the scheduled
 * row while a resume is armed; nothing for fired / dismissed. The instants must be positive whole
 * numbers (what `rate-limit-resume.resetsAt` must echo).
 */
internal fun rateLimitPrompt(tree: JsObj?): RateLimitPromptView? {
    val o = tree?.get("rateLimitResume") as? JsObj ?: return null
    val status = o["status"].str()?.takeIf { it == "awaiting_choice" || it == "scheduled" } ?: return null
    val resetsAt = wholeInstant(o["resetsAt"]) ?: return null
    val resumeAt = wholeInstant(o["resumeAt"]) ?: return null
    return RateLimitPromptView(status, resetsAt, resumeAt)
}

private fun wholeInstant(value: JsValue?): Long? {
    val d = value.num() ?: return null
    if (d <= 0 || d != Math.floor(d) || d > 9.0e15) return null
    return d.toLong()
}
