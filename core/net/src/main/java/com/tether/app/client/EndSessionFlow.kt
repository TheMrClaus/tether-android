package com.tether.app.client

import com.tether.app.protocol.model.WorktreeInfo
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ta-m7ef (tether PR #241, PROTOCOL 143 r3): ending (kill = archive) an isolated session runs its worktree
 * teardown on the host only on the owner's FRESH approval. A port of hooks/use-end-session.ts
 * (1bf4a465) with components/setup-commands.tsx TeardownConfirmDialog as the confirmation
 * ([EndConfirmation]):
 *
 *  - a session with no worktree (or one already removed) is killed at once, with no consent field;
 *  - otherwise `archive-inspect` asks the server what would run, and the end waits for the answer
 *    ([TeardownSteps.teardown]): at once with `teardownConsent: "none"` when nothing would run,
 *    else the confirmation, which ends the session with exactly the consent the preview reported
 *    ([runTeardown]), or with "none" ([endWithoutTeardown]); [cancel] ends nothing;
 *  - one end at a time (a second tap while one is pending is ignored);
 *  - a refusal of the inspect (an `error` with its requestId, or a validator error with none) turns the
 *    pending end into a confirmation that only offers to end without the teardown;
 *  - a link that is not connected drops the pending end.
 *
 * The confirmation is bound to the server the End was drawn for ([expectedOrigin]); the transport refuses
 * a send for another. Thread-safe: replies arrive on the socket's thread, taps on the main thread. No send
 * is made while the flow's own lock is held (the transport takes the client's lock).
 */
class EndSessionFlow(
    private val transport: EndTransport,
    /** The listed session's worktree (null: no such session, or no worktree). */
    private val worktreeOf: (sessionId: String) -> WorktreeInfo?,
    private val nameOf: (sessionId: String) -> String? = { null },
    /** The latest `error` frame's seq on the live link (see [SetupConsentSteps.errorEndsCheck]). */
    private val latestErrorSeq: () -> Long = { 0L },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The sends the flow needs; [RealTetherClient] implements them under its lock. */
    interface EndTransport {
        /** `archive-inspect`; false when it could not be sent (the link is not live, or the server changed). */
        fun inspect(sessionId: String, requestId: String, expectedOrigin: String?): Boolean

        /** `kill`, with [teardownConsent] when non-null (omitted otherwise). */
        fun kill(sessionId: String, teardownConsent: String?, expectedOrigin: String?)
    }

    private enum class Phase { Checking, Confirm }

    private class Pending(
        val phase: Phase,
        val sessionId: String,
        val sessionName: String,
        val requestId: String,
        val errorSeq: Long,
        val origin: String?,
        val approval: TeardownApproval?,
        val message: String?,
    ) {
        fun confirm(approval: TeardownApproval?, message: String?) = Pending(Phase.Confirm, sessionId, sessionName, requestId, errorSeq, origin, approval, message)
    }

    private val lock = Any()
    private var pending: Pending? = null
    private val _confirmation = MutableStateFlow<EndConfirmation?>(null)

    /** The teardown confirmation to draw, or null (nothing pending, or still checking). */
    val confirmation: StateFlow<EndConfirmation?> = _confirmation.asStateFlow()

    /** True while an end is checking or waiting on its confirmation. */
    val busy: Boolean get() = synchronized(lock) { pending != null }

    private fun set(next: Pending?) {
        pending = next
        _confirmation.value = next?.takeIf { it.phase == Phase.Confirm }?.let {
            EndConfirmation(it.sessionId, it.sessionName, it.approval, it.message)
        }
    }

    /** use-end-session.ts endSession. */
    fun end(sessionId: String, expectedOrigin: String?) {
        val worktree = worktreeOf(sessionId)
        if (worktree == null || worktree.status == "removed") {
            if (synchronized(lock) { pending != null }) return
            transport.kill(sessionId, null, expectedOrigin)
            return
        }
        val requestId = newRequestId()
        val check = Pending(Phase.Checking, sessionId, nameOf(sessionId).orEmpty(), requestId, latestErrorSeq(), expectedOrigin, null, null)
        synchronized(lock) {
            if (pending != null) return
            set(check)
        }
        if (!transport.inspect(sessionId, requestId, expectedOrigin)) {
            synchronized(lock) { if (pending === check) set(null) }
        }
    }

    /** The answer to an `archive-inspect`: taken only when it echoes the pending check's own requestId. */
    fun onPreview(reply: ArchivePreviewReply) {
        val step: TeardownConsentStep
        val check: Pending
        synchronized(lock) {
            check = pending ?: return
            if (check.phase != Phase.Checking || reply.requestId != check.requestId) return
            step = TeardownSteps.teardown(reply)
            when (step) {
                is TeardownConsentStep.End -> set(null)
                is TeardownConsentStep.Confirm -> set(check.confirm(step.preview, null))
                is TeardownConsentStep.Without -> set(check.confirm(null, step.message))
            }
        }
        if (step is TeardownConsentStep.End) transport.kill(check.sessionId, step.teardownConsent, check.origin)
    }

    /** A refusal of the inspect ends the check: the owner may still end without the teardown. */
    fun onError(error: CreateErrorReply) {
        synchronized(lock) {
            val check = pending ?: return
            if (!SetupConsentSteps.errorEndsCheck(check.phase == Phase.Checking, check.requestId, check.errorSeq, error)) return
            set(check.confirm(null, error.message))
        }
    }

    /** A link that is not connected drops the pending end. */
    fun onLink(connected: Boolean) {
        if (connected) return
        synchronized(lock) { if (pending != null) set(null) }
    }

    /** The owner approved the listed teardown: end with exactly that consent. */
    fun runTeardown() {
        val check = synchronized(lock) {
            val c = pending?.takeIf { it.phase == Phase.Confirm && it.approval != null } ?: return
            set(null)
            c
        }
        transport.kill(check.sessionId, check.approval!!.consent, check.origin)
    }

    /** End it without running the teardown. */
    fun endWithoutTeardown() {
        val check = synchronized(lock) {
            val c = pending?.takeIf { it.phase == Phase.Confirm } ?: return
            set(null)
            c
        }
        transport.kill(check.sessionId, SETUP_CONSENT_NONE, check.origin)
    }

    fun cancel() {
        synchronized(lock) { if (pending != null) set(null) }
    }
}

/** What the teardown confirmation shows: [approval] (the commands), or [message] (why nothing can run). */
data class EndConfirmation(
    val sessionId: String,
    val sessionName: String,
    val approval: TeardownApproval?,
    val message: String?,
)
