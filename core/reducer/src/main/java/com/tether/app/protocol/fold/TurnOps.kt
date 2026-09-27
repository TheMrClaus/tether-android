package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.obj

// T2.1 H1: session/turn scaffolding shared by every family fold — the notice dismissal keys
// (events.mjs 215–283), the initial session and turn projections (285–490) and the turn
// update helpers (1256–1276, 1347–1369).

// events.mjs:224
fun backgroundNoticeDismissKey(kind: JsValue?, seq: JsValue?): String =
    if (seq != null) "${jsToString(kind)}:${jsToString(seq)}" else "${jsToString(kind)}:unsequenced"

// events.mjs:227
fun externalAdvancementDismissKey(fromCursor: JsValue?, toCursor: JsValue?): String =
    "external_advancement:${jsToString(fromCursor)}:${jsToString(toCursor)}"

// events.mjs:230
fun providerNoticeDismissKey(turnId: JsValue?, noticeId: JsValue?): String =
    "provider_notice:${jsToString(coalesce(turnId, JsStr("session")))}:${jsToString(noticeId)}"

// events.mjs:233
fun compactionDismissKey(turnId: JsValue?, itemId: JsValue?): String =
    "context_compacted:${jsToString(turnId)}:${jsToString(itemId)}"

// events.mjs:241
fun isNoticeDismissed(state: JsObj, dismissKey: String?): Boolean {
    if (dismissKey.isNullOrEmpty()) return false
    val dismissed = state["dismissedNotices"].arr ?: return false
    return dismissed.any { it is JsStr && it.value == dismissKey }
}

private fun hasDismissKey(list: JsValue?, dismissKey: String): Boolean =
    list.arr?.any { item -> (item.obj?.get("dismissKey") as? JsStr)?.value == dismissKey } == true

// events.mjs:251
fun projectionHasNoticeKey(state: JsObj?, dismissKey: String?): Boolean {
    if (state == null || dismissKey.isNullOrEmpty()) return false
    if (hasDismissKey(state["notices"], dismissKey)) return true
    if (hasDismissKey(state["providerNotices"], dismissKey)) return true
    for (turn in (state["turnsById"].obj ?: JsObj.EMPTY).values) {
        if (hasDismissKey(turn.obj?.get("compactions"), dismissKey)) return true
        if (hasDismissKey(turn.obj?.get("providerNotices"), dismissKey)) return true
    }
    return false
}

private fun withoutDismissKey(list: JsArr, dismissKey: String): JsArr =
    list.filterKeep { item -> !strictEquals(item.obj?.get("dismissKey"), JsStr(dismissKey)) }

// events.mjs:266
fun applyNoticeDismissal(state: JsObj, dismissKey: String): JsObj {
    val dismissedNotices = ((state["dismissedNotices"].arr ?: JsArr.EMPTY).add(JsStr(dismissKey)))
        .slice(-Limits.MAX_DISMISSED_NOTICES)
    val notices = withoutDismissKey(state["notices"].arr!!, dismissKey)
    val providerNotices = withoutDismissKey(state["providerNotices"].arr!!, dismissKey)
    val originalTurns = state["turnsById"].obj!!
    var turnsById = originalTurns
    for ((turnId, turnValue) in originalTurns) {
        val turn = turnValue.obj!!
        val compactions = turn["compactions"].arr!!
        val turnNotices = turn["providerNotices"].arr!!
        val nextCompactions = withoutDismissKey(compactions, dismissKey)
        val nextProviderNotices = withoutDismissKey(turnNotices, dismissKey)
        if (nextCompactions.size == compactions.size && nextProviderNotices.size == turnNotices.size) continue
        turnsById = turnsById.put(
            turnId,
            turn.with("compactions" to nextCompactions, "providerNotices" to nextProviderNotices),
        )
    }
    return state.with(
        "dismissedNotices" to dismissedNotices,
        "notices" to notices,
        "providerNotices" to providerNotices,
        "turnsById" to turnsById,
    )
}

// events.mjs:285 — `args` is the corpus `initial` object ({ tetherSessionId, provider, cwd, nativeSessionId? }).
fun initialSessionState(args: JsObj): JsObj = JsObj.of(
    "tetherSessionId" to args["tetherSessionId"],
    "provider" to args["provider"],
    "cwd" to args["cwd"],
    // Destructuring default: only `undefined` falls back to null.
    "nativeSessionId" to (args["nativeSessionId"] ?: JsNull),
    "cliCapabilities" to JsArr.EMPTY,
    "cliVersion" to JsNull,
    "cliInventory" to JsNull,
    "mcpHealth" to JsObj.EMPTY,
    "rateLimit" to JsNull,
    "rateLimitResume" to JsNull,
    "fastModeState" to JsNull,
    "fastModeDisabledReason" to JsNull,
    "accountAuth" to JsNull,
    "todo" to JsNull,
    "todoTasks" to JsArr.EMPTY,
    "status" to js(Limits.SESSION_STATUS.READY),
    "lastTurnOutcome" to JsNull,
    "lastError" to JsNull,
    "unattributedPermissionDenials" to JsArr.EMPTY,
    "providerNotices" to JsArr.EMPTY,
    "lastModelFallback" to JsNull,
    "notices" to JsArr.EMPTY,
    "dismissedNotices" to JsArr.EMPTY,
    "backgroundCommands" to JsArr.EMPTY,
    "backgroundTasks" to JsArr.EMPTY,
    "spawnedRuns" to JsArr.EMPTY,
    "spawnedRunKeys" to JsArr.EMPTY,
    "turnOrder" to JsArr.EMPTY,
    "turnsById" to JsObj.EMPTY,
    "activeTurnId" to JsNull,
    "queuedMessages" to JsArr.EMPTY,
)

/** Kotlin-side convenience for the common `initialSessionState({ ... })` call. */
fun initialSessionState(
    tetherSessionId: String,
    provider: String,
    cwd: String,
    nativeSessionId: String? = null,
): JsObj = initialSessionState(
    JsObj.of(
        "tetherSessionId" to js(tetherSessionId),
        "provider" to js(provider),
        "cwd" to js(cwd),
        "nativeSessionId" to (nativeSessionId?.let { js(it) } ?: JsNull),
    ),
)

// events.mjs:408
fun currentTurn(state: JsObj): JsObj? {
    val active = state["activeTurnId"]
    return if (truthy(active)) state["turnsById"].obj?.prop(active).obj else null
}

// events.mjs:426
fun lastMessageActivityAt(state: JsObj): Double? {
    val order = state["turnOrder"].arr ?: JsArr.EMPTY
    val lastTurnId = if (order.isNotEmpty()) order[order.size - 1] else null
    val startedAt = if (truthy(lastTurnId)) state["turnsById"].obj?.prop(lastTurnId).obj?.get("startedAt") else null
    return startedAt.num
}

// events.mjs:432
fun newTurnProjection(
    turnId: JsValue?,
    idempotencyKey: JsValue?,
    continuation: JsValue? = null,
    startedAt: JsValue = JsNull,
    commandRun: JsValue? = null,
): JsObj = JsObj.of(
    "turnId" to turnId,
    "idempotencyKey" to idempotencyKey.let { if (isNullish(it)) JsNull else it },
    "commandRun" to commandRun.let { if (isNullish(it)) JsNull else it },
    "startedAt" to startedAt,
    "liveTokens" to JsNull,
    "run" to JsNull,
    "runCount" to js(0),
    "activeMs" to js(0),
    "continuation" to js(truthy(continuation)),
    "status" to js("running"),
    "outcome" to JsNull,
    "blocks" to JsArr.EMPTY,
    "blocksById" to JsObj.EMPTY,
    "pendingApprovals" to JsObj.EMPTY,
    "pendingQuestions" to JsObj.EMPTY,
    "answeredQuestions" to JsArr.EMPTY,
    "permissionDenials" to JsArr.EMPTY,
    "usage" to JsNull,
    "apiRetry" to JsNull,
    "initialPlan" to JsNull,
    "plan" to JsNull,
    "diff" to JsNull,
    "modelReroutes" to JsArr.EMPTY,
    "modelFallbacks" to JsArr.EMPTY,
    "reviews" to JsArr.EMPTY,
    "compactions" to JsArr.EMPTY,
    "providerNotices" to JsArr.EMPTY,
    "warnings" to JsArr.EMPTY,
)

// events.mjs:1256
fun isOpenCurrentTurn(state: JsObj, turnId: JsValue?): Boolean =
    strictEquals(state["activeTurnId"], turnId) &&
        !strictEquals(state["turnsById"].obj?.prop(turnId).obj?.get("status"), JsStr("done"))

// events.mjs:1260 — the updater receives the active turn (never absent on a guarded path).
fun updateTurn(state: JsObj, updater: (JsObj) -> JsObj): JsObj {
    val active = state["activeTurnId"]
    if (!truthy(active)) return state
    val turnId = jsToString(active)
    val turnsById = state["turnsById"].obj!!
    return state.put("turnsById", turnsById.put(turnId, updater(turnsById[turnId].obj ?: JsObj.EMPTY)))
}

// events.mjs:1266 — propagates an updater's "no change" (same turn) as the same state.
fun updateTurnById(state: JsObj, turnId: JsValue?, updater: (JsObj) -> JsObj): JsObj {
    val turnsById = state["turnsById"].obj!!
    val turn = turnsById.prop(turnId).obj ?: return state
    val updated = updater(turn)
    if (updated === turn) return state
    return state.put("turnsById", turnsById.put(jsToString(turnId), updated))
}

// events.mjs:1347 — `build` receives the existing block (null if new).
fun upsertBlock(turn: JsObj, blockId: JsValue?, build: (JsObj?) -> JsObj): JsObj {
    val key = jsToString(blockId)
    val blocksById = turn["blocksById"].obj!!
    val existing = blocksById[key]
    val next = build(existing?.obj)
    if (existing != null) {
        return turn.put("blocksById", blocksById.put(key, next))
    }
    return turn.with(
        "blocks" to turn["blocks"].arr!!.add(blockId ?: JsNull),
        "blocksById" to blocksById.put(key, next),
    )
}

/** `turn.runCount + 1` etc. on a numeric field (absent reads as NaN in JS; never absent here). */
internal fun JsObj.numberAt(key: String): Double = (this[key] as? JsNum)?.value ?: Double.NaN
