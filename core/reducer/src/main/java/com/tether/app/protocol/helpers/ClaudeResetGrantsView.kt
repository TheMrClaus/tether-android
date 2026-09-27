package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * T2.2: faithful port of lib/claude-reset-grants-view.mjs — Claude usage-limit reset grants
 * (issue #194). The one host-locale branch (claimOutcomeCopy's `weeklyResetsAt` sentence) takes a
 * [Locale]/[ZoneId] and renders the en-US shape (corpus-uncovered).
 */
object ClaudeResetGrantsView {

    // lib/claude-reset-grants-view.mjs:15
    private val WINDOW_LABELS = mapOf(
        "five_hour" to "5 h",
        "seven_day" to "weekly",
        "seven_day_overage_included" to "weekly",
        "seven_day_opus" to "weekly Opus",
        "seven_day_sonnet" to "weekly Sonnet",
    )

    // lib/claude-reset-grants-view.mjs:25 — "5 h + weekly"; "" for none.
    fun windowListLabel(windows: JsValue?): String {
        val labels = ArrayList<String>()
        for (key in windows.arrOrEmpty()) {
            val label = WINDOW_LABELS[jsToString(key)]
            if (label != null && label !in labels) labels.add(label)
        }
        return labels.joinToString(" + ")
    }

    // lib/claude-reset-grants-view.mjs:35 — "45 min", "6 h", "3 d", "2 d 4 h".
    fun durationLabel(ms: Double): String {
        val minutes = max(0.0, jsRound(ms / 60_000))
        if (minutes < 60) return "${n(max(1.0, minutes))} min"
        val hours = jsRound(minutes / 60)
        if (hours < 48) return "${n(hours)} h"
        val days = floor(hours / 24)
        val rest = hours - days * 24
        return if (rest > 0 && days < 7) "${n(days)} d ${n(rest)} h" else "${n(days)} d"
    }

    // lib/claude-reset-grants-view.mjs:50 — `{ tone, status, lines, next }` for one grant row.
    fun resetGrantView(grant: JsValue?, summary: JsValue?, nowMs: Double): JsObj {
        val left = grant["resetsLeft"].finite ?: 0.0
        val endsAt = grant["endsAt"].finite
        val expired = endsAt != null && endsAt <= nowMs
        val clears = windowListLabel(grant["clears"])
        val lines = ArrayList<String>()

        var tone = "available"
        val status: String
        if (expired) {
            tone = "expired"
            status = if (left > 0) "Expired unused" else "Expired"
        } else if (left <= 0) {
            tone = "spent"
            status = "Already used"
        } else if (truthy(grant["paused"])) {
            tone = "paused"
            status = "${n(left)} ${if (left == 1.0) "reset" else "resets"} left · paused"
        } else {
            status = "${n(left)} ${if (left == 1.0) "reset" else "resets"} left"
        }
        if (clears.isNotEmpty()) lines.add("Clears $clears")

        if (tone == "available" || tone == "paused") {
            if (endsAt != null) lines.add("Expires unused in ${durationLabel(endsAt - nowMs)}")
            val walled = summary["atLimit"] == JsBool.TRUE
            val exhausted = windowListLabel(summary["exhausted"])
            val weeklyAt = summary["weeklyResetsAt"].finite
            val weekly = if (weeklyAt != null && weeklyAt > nowMs) durationLabel(weeklyAt - nowMs) else null
            if (walled) {
                lines.add(
                    "At the limit now${if (exhausted.isNotEmpty()) " ($exhausted)" else ""}" +
                        (if (weekly != null) " · weekly resets naturally in $weekly" else ""),
                )
            } else if (weekly != null) {
                lines.add("Not at a limit · weekly resets naturally in $weekly — wait unless you need it within $weekly")
            } else {
                lines.add("Not at a limit")
            }
            if (truthy(grant["useRequiresLimit"]) && !walled) {
                lines.add("Usable only once a limit is hit")
            } else if (!truthy(grant["usableNow"]) && !truthy(grant["paused"])) {
                lines.add("Not usable right now")
            }
            val cooldownUntil = summary["cooldownUntil"].finite
            if (cooldownUntil != null && cooldownUntil > nowMs) lines.add("Cooling down for ${durationLabel(cooldownUntil - nowMs)}")
        }

        return JsObj.of(
            "tone" to JsStr(tone),
            "status" to JsStr(status),
            "lines" to jsStrings(lines),
            "next" to js(strictEquals(summary["nextGrantId"], grant["id"])),
        )
    }

    // lib/claude-reset-grants-view.mjs:102 — "1 reset left · expires in 26 d", "All resets used", or null.
    fun resetGrantsHeadline(summary: JsValue?, nowMs: Double): String? {
        val grants = summary["grants"].arrOrEmpty()
        if (grants.isEmpty()) return null
        val live = grants.filter { grant ->
            val endsAt = grant["endsAt"].finite
            jsToNumber(grant["resetsLeft"]) > 0 && !(endsAt != null && endsAt <= nowMs)
        }
        if (live.isEmpty()) return "All resets used"
        val left = live.fold(0.0) { sum, grant -> sum + jsToNumber(grant["resetsLeft"]) }
        val soonest = live.mapNotNull { it["endsAt"].finite }.sorted().firstOrNull()
        val expires = if (soonest != null && soonest != 0.0) " · expires in ${durationLabel(soonest - nowMs)}" else ""
        return "${n(left)} ${if (left == 1.0) "reset" else "resets"} left$expires"
    }

    // lib/claude-reset-grants-view.mjs:120
    fun ineligibleReasonLabel(reason: JsValue?): String = when ((reason as? JsStr)?.value) {
        "surface" -> "the request was not recognised as Claude Code — Tether could not read this account's resets"
        "cli_version" -> "this Claude Code version is too old for limit resets"
        "tier" -> "not offered on this plan"
        "tenure" -> "not offered to this account yet"
        "no_weekly_limit" -> "this plan has no weekly limit to reset"
        "extra_usage" -> "not offered while extra usage is on"
        "unavailable" -> "limit resets are unavailable right now"
        else -> "no limit resets offered to this account"
    }

    // lib/claude-reset-grants-view.mjs:136
    private val CLAIM_WINDOW_NAMES = mapOf(
        "five_hour" to "5-hour limit",
        "seven_day" to "weekly limit",
        "seven_day_overage_included" to "weekly limit (overage included)",
        "seven_day_opus" to "weekly Opus limit",
        "seven_day_sonnet" to "weekly Sonnet limit",
    )

    // lib/claude-reset-grants-view.mjs:145 — [{ code, label }] for every window a grant clears.
    fun claimClearsList(clears: JsValue?): JsArr = JsArr.of(
        clears.arrOrEmpty()
            .filter { it is JsStr && CLAIM_WINDOW_NAMES.containsKey(it.value) }
            .map { JsObj.of("code" to it, "label" to JsStr(CLAIM_WINDOW_NAMES.getValue((it as JsStr).value))) },
    )

    // lib/claude-reset-grants-view.mjs:152
    private val CLAIM_REASONS = mapOf(
        "paused" to "this grant is paused",
        "expired" to "this grant has expired",
        "unknown_grant" to "Anthropic does not know this grant",
        "not_next_grant" to "another grant has to be used first",
        "grant_id_required" to "a grant id is required",
        "not_limited" to "the account is not at a limit",
        "already_used" to "this reset was already used",
        "cooldown" to "resets are cooling down",
        "stamp_indeterminate" to "Anthropic could not confirm the account's limit state",
        "reset_unconfirmed" to "Anthropic could not confirm the reset",
    )

    // lib/claude-reset-grants-view.mjs:165
    fun claimReasonLabel(reason: JsValue?): String? = (reason as? JsStr)?.let { CLAIM_REASONS[it.value] }

    private fun copy(settled: Boolean, success: Boolean, text: String) =
        JsObj.of("settled" to js(settled), "success" to js(success), "text" to JsStr(text))

    // lib/claude-reset-grants-view.mjs:175 — `{ settled, success, text }` for a claim response.
    fun claimOutcomeCopy(
        response: JsValue?,
        nowMs: Double,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): JsObj {
        val outcome = response["outcome"]
        val resetsLeft = response["resetsLeft"].finite
        val left = if (resetsLeft != null) " ${n(resetsLeft)} ${if (resetsLeft == 1.0) "reset" else "resets"} left." else ""
        val reason = claimReasonLabel(response["reason"])
        val because = if (reason != null) " — $reason" else ""
        val weeklyAt = response["weeklyResetsAt"].finite
        // Host-locale on the web: toLocaleString(undefined, { weekday: "short", month: "short",
        // day: "numeric", hour: "numeric", minute: "2-digit" }) → en-US "Thu, Sep 24, 9:05 AM".
        val weekly = if (weeklyAt != null) {
            val date = jsDate(weeklyAt, zone)
            val text = if (date == null) "Invalid Date" else DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", locale).format(date)
            " Your weekly reset day stays $text."
        } else {
            ""
        }
        fun retryIn(at: JsValue?): String {
            val ms = at.finite
            return if (ms != null && ms > nowMs) " Try again in ${durationLabel(ms - nowMs)}." else ""
        }
        val notSent = response["sent"] == JsBool.FALSE
        return when ((outcome as? JsStr)?.value) {
            "reset" -> {
                val cleared = claimClearsList(response["cleared"]).joinToString(", ") { (it["label"] as JsStr).value }
                copy(true, true, "Reset applied${if (cleared.isNotEmpty()) " — refilled your $cleared" else ""}.$weekly$left")
            }
            "already_used" -> copy(true, false, "Already used — nothing changed just now.$left")
            "not_limited" -> copy(true, false, "Not applied — the account is not at a limit.$left")
            "cooldown" -> copy(true, false, "Not applied — resets are cooling down.${retryIn(response["cooldownUntil"])}$left")
            "ineligible" -> copy(true, false, "Not applied — not eligible$because.$left")
            "unavailable" -> copy(true, false, "Not applied — limit resets are unavailable right now$because.$left")
            "rate_limited" -> copy(
                false,
                false,
                if (notSent) {
                    "Not sent — Anthropic is rate-limiting this account.${retryIn(response["retryAfter"])}"
                } else {
                    "Not applied — Anthropic rate-limited the request (429).${retryIn(response["retryAfter"])}"
                },
            )
            "token_expired" -> copy(
                false,
                false,
                if (notSent) {
                    "Not sent — this account's token has expired. Run any Claude Code command on it, then try again."
                } else {
                    "Not applied — Anthropic refused this account's token (401). Run any Claude Code command on it, then try again."
                },
            )
            "no_organization" -> copy(true, false, "Not sent — this account has no OAuth organization on file, so there is nothing to claim against.")
            "no_cli_version" -> copy(true, false, "Not sent — no Claude Code CLI version could be resolved to identify as.")
            "in_flight" -> copy(false, false, "A claim for this account is already in progress.")
            "invalid_request" -> copy(true, false, "Not sent — the grant id was not valid.")
            "unreachable" -> copy(
                false,
                false,
                "No answer from Anthropic — it is unknown whether the reset was applied. Reopen Usage to check before retrying; " +
                    "a retry resends the same request id.",
            )
            else -> copy(
                false,
                false,
                "Anthropic answered in a shape Tether does not recognise — it may have acted. Reopen Usage to check before retrying; " +
                    "a retry resends the same request id.",
            )
        }
    }

    private fun n(d: Double) = numberToString(d)
}
