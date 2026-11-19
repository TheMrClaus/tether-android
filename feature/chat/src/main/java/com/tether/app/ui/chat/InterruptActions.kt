package com.tether.app.ui.chat

import com.tether.app.client.InterruptResult
import com.tether.app.client.TetherClient

/**
 * The two interrupt lambdas ChatScreen hands the Composer (plain Stop, and the Stop panel's box commit with
 * `stopBackground = true`). Built outside the ChatScreen body on purpose: two lambda-bearing `remember` blocks in that
 * ~550-line composable doubled the Kotlin K2 data-flow analysis (2^26 -> 2^27 Implications) and OOMed the CI compile.
 */
internal class InterruptActions(
    val interrupt: (String) -> InterruptResult,
    val interruptStopBackground: (String) -> InterruptResult,
    /** v165: "Interrupt and send" on ONE waiting row: the row's queue id, then the turn the button was drawn for. */
    val interruptRow: (String, String) -> InterruptResult,
    /** v165: Steer on ONE waiting row (its queue id). */
    val steerRow: (String) -> Unit,
)

internal fun interruptActionsFor(client: TetherClient, sessionId: String?, drawnFor: String?): InterruptActions =
    InterruptActions(
        interrupt = { turnId ->
            if (sessionId != null) client.interrupt(sessionId, drawnFor, turnId) else InterruptResult.Locked
        },
        interruptStopBackground = { turnId ->
            if (sessionId != null) client.interrupt(sessionId, drawnFor, turnId, stopBackground = true) else InterruptResult.Locked
        },
        interruptRow = { queueId, turnId ->
            if (sessionId != null) client.interrupt(sessionId, drawnFor, turnId, stopBackground = false, queueId = queueId) else InterruptResult.Locked
        },
        steerRow = { queueId -> if (sessionId != null) client.queueSteer(sessionId, queueId) },
    )

/** v165: the session's `queueSteerMode` ("auto" or "hold") out of the last `server-settings` frame; null = none seen (automatic). */
internal fun queueSteerModeOf(frame: com.tether.app.protocol.ServerMessage.ServerSettings?): String? =
    (frame?.settings?.get("queueSteerMode") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
