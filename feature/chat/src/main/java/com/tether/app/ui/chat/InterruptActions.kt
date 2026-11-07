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
)

internal fun interruptActionsFor(client: TetherClient, sessionId: String?, drawnFor: String?): InterruptActions =
    InterruptActions(
        interrupt = { turnId ->
            if (sessionId != null) client.interrupt(sessionId, drawnFor, turnId) else InterruptResult.Locked
        },
        interruptStopBackground = { turnId ->
            if (sessionId != null) client.interrupt(sessionId, drawnFor, turnId, stopBackground = true) else InterruptResult.Locked
        },
    )
