package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// T2.1 unit B (notices): background_interrupted/background_abandoned, turn_interrupted,
// external_advancement and notice_dismissed 2753-2862; provider_notice 2047-2071 with 1059;
// context_compacted 1968-1985. The dismissal helpers (dismiss keys, isNoticeDismissed,
// applyNoticeDismissal) are shared and live in TurnOps.kt (H1).
internal fun foldNotices(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "context_compacted" -> foldContextCompacted(state, event)
    "provider_notice" -> foldProviderNotice(state, event)
    "background_interrupted", "background_abandoned" -> foldBackgroundNotice(state, event)
    "turn_interrupted" -> foldTurnInterrupted(state, event)
    "external_advancement" -> foldExternalAdvancement(state, event)
    "notice_dismissed" -> foldNoticeDismissed(state, event)
    else -> state
}

// events.mjs:1968
private fun foldContextCompacted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val itemId = boundedIdentifier(event["itemId"])
    if (itemId.isNullOrEmpty()) return state
    val dismissKey = compactionDismissKey(event["turnId"], js(itemId))
    if (isNoticeDismissed(state, dismissKey)) return state
    return updateTurn(state) { turn ->
        val compactions = turn["compactions"].arr!!
        if (compactions.any { strictEquals(it.obj?.get("itemId"), JsStr(itemId)) }) {
            turn
        } else {
            turn.put(
                "compactions",
                compactions.add(JsObj.of("itemId" to js(itemId), "dismissKey" to js(dismissKey)))
                    .slice(-Limits.PROVIDER_PROJECTION_LIMITS.compactions),
            )
        }
    }
}

// events.mjs:1059
private fun appendProviderNotice(notices: JsArr, notice: JsObj): JsArr {
    if (notices.any { strictEquals(it.obj?.get("noticeId"), notice["noticeId"]) }) return notices
    return notices.add(notice).slice(-Limits.PROVIDER_PROJECTION_LIMITS.providerNotices)
}

// events.mjs:2047
private fun foldProviderNotice(state: JsObj, event: JsObj): JsObj {
    val noticeId = boundedIdentifier(event["noticeId"])
    val message = boundedDisplayText(event["message"], Limits.PROVIDER_PROJECTION_LIMITS.proseChars)
    if (noticeId.isNullOrEmpty() || message.isNullOrEmpty()) return state
    val level = event["level"].str
    var notice = JsObj.of(
        "noticeId" to js(noticeId),
        "level" to js(if (level != null && level in Limits.PROVIDER_NOTICE_LEVELS) level else "warning"),
        "message" to js(message),
    )
    val code = boundedDisplayText(event["code"], Limits.PROVIDER_PROJECTION_LIMITS.labelChars)
    if (code != null) notice = notice.put("code", js(code))
    val dismissKey = providerNoticeDismissKey(event["turnId"], js(noticeId))
    notice = notice.put("dismissKey", js(dismissKey))
    if (isNoticeDismissed(state, dismissKey)) return state
    if (isNullish(event["turnId"])) {
        val current = state["providerNotices"].arr!!
        val providerNotices = appendProviderNotice(current, notice)
        return if (providerNotices === current) state else state.put("providerNotices", providerNotices)
    }
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        val current = turn["providerNotices"].arr!!
        val providerNotices = appendProviderNotice(current, notice)
        if (providerNotices === current) turn else turn.put("providerNotices", providerNotices)
    }
}

/** `seq !== undefined && state.notices.some((notice) => notice.seq === seq)` (2766, 2792, 2841). */
private fun hasNoticeSeq(state: JsObj, seq: JsValue?): Boolean =
    seq != null && state["notices"].arr!!.any { strictEquals(it.obj?.get("seq"), seq) }

// events.mjs:2753
private fun foldBackgroundNotice(state: JsObj, event: JsObj): JsObj {
    val seq = event["seq"]
    if (hasNoticeSeq(state, seq)) return state
    val dismissKey = backgroundNoticeDismissKey(event["type"], seq)
    if (isNoticeDismissed(state, dismissKey)) return state
    var notice = JsObj.of(
        "kind" to event["type"],
        "outstanding" to coalesce(event["outstanding"], js(0)),
        "seq" to seq,
        "dismissKey" to js(dismissKey),
    )
    if (event["reason"] != null) notice = notice.put("reason", event["reason"])
    return state.put("notices", state["notices"].arr!!.add(notice))
}

// events.mjs:2798
private fun interruptCount(value: JsValue?): JsValue =
    if (isInteger(value) && value.num!! > 0) value!! else js(0)

private fun isTurnInterruptedNotice(item: JsValue): Boolean = item.obj?.get("kind").str == "turn_interrupted"

// events.mjs:2784
private fun foldTurnInterrupted(state: JsObj, event: JsObj): JsObj {
    val seq = event["seq"]
    if (hasNoticeSeq(state, seq)) return state
    val by = event["by"].str
    if (by == null || by !in Limits.TURN_INTERRUPT_SOURCES) return state
    val interruptedTurnId = boundedIdentifier(event["interruptedTurnId"])
    if (interruptedTurnId.isNullOrEmpty()) return state
    val dismissKey = backgroundNoticeDismissKey(js("turn_interrupted"), seq)
    if (isNoticeDismissed(state, dismissKey)) return state
    var notice = JsObj.of(
        "kind" to js("turn_interrupted"),
        "by" to js(by),
        "interruptedTurnId" to js(interruptedTurnId),
        "stoppedTools" to interruptCount(event["stoppedTools"]),
        "stoppedBackground" to interruptCount(event["stoppedBackground"]),
        "seq" to seq,
        "dismissKey" to js(dismissKey),
    )
    val queueId = boundedIdentifier(event["queueId"])
    if (!queueId.isNullOrEmpty()) notice = notice.put("queueId", js(queueId))
    if (isFiniteNumber(event["ts"])) notice = notice.put("at", event["ts"])
    var notices = state["notices"].arr!!.add(notice)
    val interruptedCount = notices.count { isTurnInterruptedNotice(it) }
    if (interruptedCount > Limits.MAX_TURN_INTERRUPTED_NOTICES) {
        var drop = interruptedCount - Limits.MAX_TURN_INTERRUPTED_NOTICES
        notices = notices.filterKeep { item ->
            if (drop > 0 && isTurnInterruptedNotice(item)) {
                drop -= 1
                false
            } else {
                true
            }
        }
    }
    return state.put("notices", notices)
}

// events.mjs:2826
private fun foldExternalAdvancement(state: JsObj, event: JsObj): JsObj {
    val seq = event["seq"]
    if (hasNoticeSeq(state, seq)) return state
    val noticeId = boundedIdentifier(event["noticeId"])
    val notices = state["notices"].arr!!
    if (!noticeId.isNullOrEmpty() &&
        notices.any { it.obj?.get("kind").str == "external_advancement" && it.obj?.get("dismissKey").str == noticeId }
    ) {
        return state
    }
    val dismissKey = noticeId
        ?: if (seq != null) "external_advancement:seq:${jsToString(seq)}" else "external_advancement:unsequenced"
    if (isNoticeDismissed(state, dismissKey)) return state
    val notice = JsObj.of(
        "kind" to js("external_advancement"),
        "count" to event["count"],
        "seq" to seq,
        "dismissKey" to js(dismissKey),
    )
    return state.put("notices", notices.add(notice))
}

// events.mjs:2852
private fun foldNoticeDismissed(state: JsObj, event: JsObj): JsObj {
    val dismissKey = boundedIdentifier(event["dismissKey"])
    if (dismissKey.isNullOrEmpty() || isNoticeDismissed(state, dismissKey)) return state
    return applyNoticeDismissal(state, dismissKey)
}
