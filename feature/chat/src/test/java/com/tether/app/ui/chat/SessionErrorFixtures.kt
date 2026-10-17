package com.tether.app.ui.chat

import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.js

/**
 * W50: hand-built session-error trees. The app's pre-R1 fold never fills `sessionErrors` (only `lastError`),
 * so the v152 snapshot's list is written into the folded tree here, the way a snapshot carries it.
 */
internal object SessionErrorFixtures {
    /** One `sessionErrors` entry; a null [seq] / [ts] is the schema's own nullable (omitted -> JSON null). */
    data class Err(val message: String, val ts: Double?, val seq: Long? = null)

    fun with(base: ChatFixtures.Folded, errors: List<Err>, lastError: String? = null): ChatFixtures.Folded {
        var tree = base.tree.put(
            "sessionErrors",
            JsArr.of(errors.map { e ->
                JsObj.of("seq" to (e.seq?.let { js(it.toInt()) } ?: JsNull), "ts" to (e.ts?.let { js(it) } ?: JsNull), "message" to js(e.message))
            }),
        )
        if (lastError != null) tree = tree.put("lastError", js(lastError))
        return ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree)
    }

    /** Two finished turns: t1 starts 01:01 UTC, t2 02:01 UTC. */
    val twoTurns: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *ChatFixtures.turn("t1", "First prompt", "First reply", ChatFixtures.T_IDLE),
            *ChatFixtures.turn("t2", "Second prompt", "Second reply", ChatFixtures.T_MARKDOWN),
        )
    }

    /** No turns at all: a session whose only content is its errors. */
    val noTurns: ChatFixtures.Folded by lazy { ChatFixtures.fold() }

    /** The golden clock: 23:00 UTC on the turns' day (1970-01-01), so an error from the day before wears a date prefix. */
    const val NOW = 82_800_000L

    /** Three errors interleaved with the turns: one the day before (date prefix, before t1), one after t1, one after t2. */
    val interleaved: ChatFixtures.Folded by lazy {
        with(
            twoTurns,
            listOf(
                Err("Engine exited: rate limited by the provider.", -600_000.0, seq = 3),
                Err("The Claude CLI could not start: command not found (claude)", ChatFixtures.T_IDLE + 30_000.0, seq = 9),
                Err("Login has expired. Sign in again to continue.", ChatFixtures.T_MARKDOWN + 45_000.0, seq = 12),
            ),
        )
    }

    val errorOnly: ChatFixtures.Folded by lazy {
        with(noTurns, listOf(Err("The Claude CLI could not start: command not found (claude)", ChatFixtures.T_IDLE.toDouble(), seq = 1)))
    }

    /** Describes the rows that matter to an ordering: one per turn (its prompt), each error, each command, Load earlier. */
    fun order(items: List<ChatItem>): List<String> = items.mapNotNull {
        when (it) {
            is ChatItem.LoadEarlier -> "LE"
            is ChatItem.Block -> if (it.block.kind == com.tether.app.protocol.model.Vocab.BLOCK_USER_MESSAGE) it.turnId else null
            is ChatItem.SessionError -> "E:${it.text}"
            is ChatItem.BgCommand -> "BG:${it.command.commandId}"
            else -> null
        }
    }
}
