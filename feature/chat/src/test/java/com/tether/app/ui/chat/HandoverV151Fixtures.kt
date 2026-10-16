package com.tether.app.ui.chat

import com.tether.app.client.Handover
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonPrimitive

/** W34: the handover stages a v151 server reports, as the composer's strip, queue and keys draw them. */
object HandoverV151Fixtures {
    fun session(stage: String?, tasks: Int? = null, pending: Boolean = true): AgentSession = ComposerFixtures.session.copy(
        handoverPending = pending,
        handoverStage = stage?.let { JsonPrimitive(it) },
        handoverBackgroundTasks = tasks?.let { JsonPrimitive(it) },
    )

    fun view(session: AgentSession, folded: ChatFixtures.Folded, stopping: Boolean = false, requested: Boolean = false): Handover.StageView? =
        Handover.handoverStageView(
            session,
            hasInstructionRow = folded.projection.queuedMessages.any { it.isHandoverRequest },
            turnRunning = folded.projection.activeTurnId != null,
            requested = requested,
            stopping = stopping,
        )

    /** A shot: the lead's row, its transcript projection, and the stage view the strip, queue and keys read. */
    class Stage(val session: AgentSession, val folded: ChatFixtures.Folded, val stopping: Boolean = false) {
        val view: Handover.StageView? get() = view(session, folded, stopping)
    }

    val requestedQueued = Stage(session("requested"), HandoverUiFixtures.busyWithRequest)
    val requestedWriting = Stage(session("requested"), ComposerFixtures.busy)
    val scheduled = Stage(session("scheduled"), ComposerFixtures.busy)
    val waitingBackground = Stage(session("waiting_background", tasks = 2), ComposerFixtures.idle)
    val stopping = Stage(session("scheduled"), ComposerFixtures.busy, stopping = true)
}
