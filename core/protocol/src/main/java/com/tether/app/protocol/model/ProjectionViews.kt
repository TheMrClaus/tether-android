package com.tether.app.protocol.model

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * T2.1D: zero-copy typed read-only views over the v128 projection tree (docs/parity/T2.1_PLAN.md
 * §1 option b). Each view is a value class over the [JsObj] the fold produced — no decoding, no
 * allocation beyond the wrapper — so a screen reads the tree directly and every untouched subtree
 * keeps its identity (Compose skips it; `compose-stability.conf` marks `protocol.tree.*` stable).
 *
 * Reads follow the JS field types exactly: a key that is absent (JS undefined), JS null, or of
 * another type reads as Kotlin null — the raw value is always there via [JsObj] for the cases a
 * view does not model. T5/T6 screens migrate onto these from the legacy [SessionProjection]
 * (see [LegacyProjectionAdapter]); fields are added here as those screens need them.
 */
@JvmInline
value class SessionView(val obj: JsObj) {
    val tetherSessionId: String? get() = obj.string("tetherSessionId")
    val provider: String? get() = obj.string("provider")
    val cwd: String? get() = obj.string("cwd")
    val nativeSessionId: String? get() = obj.string("nativeSessionId")
    val status: String? get() = obj.string("status")
    val lastTurnOutcome: String? get() = obj.string("lastTurnOutcome")
    val lastError: String? get() = obj.string("lastError")
    val activeTurnId: String? get() = obj.string("activeTurnId")

    /** `turnOrder` (turn ids, oldest first). */
    val turnOrder: JsArr get() = obj.array("turnOrder")
    val turnCount: Int get() = turnOrder.size

    fun turn(turnId: String): TurnView? = (obj["turnsById"] as? JsObj)?.get(turnId)?.let { (it as? JsObj)?.let(::TurnView) }

    /** The turn at [index] of `turnOrder`. */
    fun turnAt(index: Int): TurnView? = (turnOrder.getOrNull(index) as? JsStr)?.let { turn(it.value) }

    /** `currentTurn(state)` (events.mjs:408): the turn `activeTurnId` points at. */
    val activeTurn: TurnView? get() = activeTurnId?.let { turn(it) }

    val queuedMessages: List<QueuedMessageView> get() = obj.array("queuedMessages").objects(::QueuedMessageView)

    /**
     * v130 (S13.1-C): the last 50 queueIds that LEFT the queue (withdrawn or flushed), oldest
     * first — acceptance evidence for pending-input reconciliation. Null when the key is absent (a
     * pre-v130 server) or not an array; non-string entries are skipped.
     */
    val removedQueueIds: List<String>? get() = (obj["removedQueueIds"] as? JsArr)?.mapNotNull { (it as? JsStr)?.value }
    val notices: JsArr get() = obj.array("notices")
    val providerNotices: JsArr get() = obj.array("providerNotices")
    val mcpHealth: JsObj get() = obj["mcpHealth"] as? JsObj ?: JsObj.EMPTY
    val todo: JsObj? get() = obj["todo"] as? JsObj
    val cliInventory: JsObj? get() = obj["cliInventory"] as? JsObj
    val rateLimit: JsObj? get() = obj["rateLimit"] as? JsObj
    val rateLimitResume: JsObj? get() = obj["rateLimitResume"] as? JsObj
}

@JvmInline
value class TurnView(val obj: JsObj) {
    val turnId: String? get() = obj.string("turnId")
    val idempotencyKey: String? get() = obj.string("idempotencyKey")
    val status: String? get() = obj.string("status")
    val outcome: String? get() = obj.string("outcome")
    val continuation: Boolean get() = obj.boolean("continuation") == true
    val startedAt: Double? get() = obj.number("startedAt")
    val liveTokens: Double? get() = obj.number("liveTokens")
    val activeMs: Double? get() = obj.number("activeMs")
    val runCount: Double? get() = obj.number("runCount")
    val error: String? get() = obj.string("error")
    val run: JsObj? get() = obj["run"] as? JsObj
    val usage: JsObj? get() = obj["usage"] as? JsObj

    /** `blocks` (block ids in display order). */
    val blockIds: JsArr get() = obj.array("blocks")
    val blockCount: Int get() = blockIds.size

    fun block(blockId: String): BlockView? = (obj["blocksById"] as? JsObj)?.get(blockId)?.let { (it as? JsObj)?.let(::BlockView) }

    /** The block at [index] of `blocks`. */
    fun blockAt(index: Int): BlockView? = (blockIds.getOrNull(index) as? JsStr)?.let { block(it.value) }

    val pendingApprovals: List<ApprovalView> get() = (obj["pendingApprovals"] as? JsObj).values(::ApprovalView)
    val pendingQuestions: List<QuestionView> get() = (obj["pendingQuestions"] as? JsObj).values(::QuestionView)
}

@JvmInline
value class BlockView(val obj: JsObj) {
    val blockId: String? get() = obj.string("blockId")

    /** `user_message` | `message` | `thinking` | `tool` (verbatim; unknown kinds pass through). */
    val kind: String? get() = obj.string("kind")
    val text: String? get() = obj.string("text")
    val name: String? get() = obj.string("name")

    /** Tool input / output: arbitrary JS values, raw. */
    val input: JsValue? get() = obj["input"]
    val output: JsValue? get() = obj["output"]
    val isError: Boolean? get() = obj.boolean("isError")
    val done: Boolean? get() = obj.boolean("done")

    /** Present only while a tool is live; removed by `tool_end`. */
    val elapsedSeconds: Double? get() = obj.number("elapsedSeconds")

    /** Only ever literal `true`. */
    val aborted: Boolean get() = obj.boolean("aborted") == true
    val attachments: JsArr? get() = obj["attachments"] as? JsArr
    val subagent: JsObj? get() = obj["subagent"] as? JsObj
}

@JvmInline
value class ApprovalView(val obj: JsObj) {
    val requestId: String? get() = obj.string("requestId")
    val toolId: String? get() = obj.string("toolId")
    val name: String? get() = obj.string("name")
    val input: JsValue? get() = obj["input"]
    val choices: JsArr? get() = obj["choices"] as? JsArr
    val metadata: JsObj? get() = obj["metadata"] as? JsObj

    /** v131: the journal-stamped `ts` of the request event (epoch ms); null on an unstamped fold / pre-v131. */
    val createdAt: Double? get() = obj.number("createdAt")
}

@JvmInline
value class QuestionView(val obj: JsObj) {
    val requestId: String? get() = obj.string("requestId")
    val toolId: String? get() = obj.string("toolId")
    val questions: JsArr get() = obj.array("questions")

    /** v131: as [ApprovalView.createdAt]. */
    val createdAt: Double? get() = obj.number("createdAt")
}

@JvmInline
value class QueuedMessageView(val obj: JsObj) {
    val queueId: String? get() = obj.string("queueId")
    val text: String? get() = obj.string("text")

    /** v133: [QueuedMessage.originKind] — absent = "user", unknown / non-string = null. */
    val origin: String? get() = QueuedOrigin.of(obj.string("origin"), absent = !obj.containsKey("origin"))

    /** v133: the operator's own queued message (lib/queued-message.mjs operatorQueuedMessages). */
    val isOperatorMessage: Boolean get() = origin == QueuedOrigin.USER

    /** v133: a system notice's kind ("spawn" | "command" | "continuation"; unknown kept as-is). */
    val noticeKind: String? get() = obj.string("noticeKind")
}

private fun JsObj.string(key: String): String? = (this[key] as? JsStr)?.value
private fun JsObj.number(key: String): Double? = (this[key] as? JsNum)?.value
private fun JsObj.boolean(key: String): Boolean? = (this[key] as? JsBool)?.value
private fun JsObj.array(key: String): JsArr = this[key] as? JsArr ?: JsArr.EMPTY

private inline fun <V> JsArr.objects(wrap: (JsObj) -> V): List<V> = mapNotNull { (it as? JsObj)?.let(wrap) }
private inline fun <V> JsObj?.values(wrap: (JsObj) -> V): List<V> = this?.values?.mapNotNull { (it as? JsObj)?.let(wrap) } ?: emptyList()
