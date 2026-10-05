package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * ta-m7ef (tether PR #241, PROTOCOL 143 r3): the end-session half of the consent port: lib/protocol.ts
 * TeardownPreview / WORKTREE_*_SKIPPED_COPY and lib/draft-form.ts teardownConsentStep, stopsSessionsNotice,
 * teardownCheckoutMayHaveChanged (tether 1bf4a465). The same rules as SetupConsent.kt: untrusted text,
 * fail closed, nothing cut.
 */

private const val MAX_STOPS = 20

/** lib/protocol.ts WORKTREE_SETUP_SKIPPED_COPY, verbatim. */
object WorktreeSkipCopy {
    val SETUP: Map<String, String> = mapOf(
        "consent-missing" to "This project's setup and teardown did not run: the create carried no approval for them (an older client, a schedule or an API call). Create the session again from the composer to review and run them.",
        "consent-mismatch" to "This project's setup and teardown did not run: what the checkout declares is not what was approved — the branch or pull request may have moved after it was checked. Create the session again to review the current commands.",
        "consent-not-owner" to "This project's setup and teardown did not run: only an owner sign-in can approve a project's setup.",
    )

    /** lib/protocol.ts WORKTREE_TEARDOWN_SKIPPED_COPY, verbatim. */
    val TEARDOWN: Map<String, String> = mapOf(
        "consent-missing" to "This project's teardown did not run: the archive carried no approval for it (an automatic archive, an API call, an older client, or a session ended with its parent).",
        "declined" to "This project's teardown did not run: it was declined when the session was archived.",
        "consent-mismatch" to "This project's teardown did not run: what was approved is not what would have run.",
        "consent-not-owner" to "This project's teardown did not run: only an owner sign-in can approve it.",
        "checkout-tampered" to "This project's teardown did not run: the checkout's .git file no longer points at the git directory Tether created for it, so nothing in it is trusted.",
        "checkout-changed" to "This project's teardown did not run: the checkout changed after the teardown was approved (or Tether could not check it).",
        "processes-alive" to "This project's teardown did not run: Tether could not prove that every process the session started had stopped.",
        "sessions-changed" to "This project's teardown did not run: the other sessions it would have stopped were not the ones shown when it was approved.",
    )

    /** inspector.tsx / worktree-services-card.tsx: the sentence for a skipped setup (an unknown reason reads as skipped). */
    fun setup(reason: String): String = SETUP[reason] ?: "This project's setup and teardown did not run."

    /** The sentence for a skipped teardown (an unknown reason reads as skipped). */
    fun teardown(reason: String): String = TEARDOWN[reason] ?: "This project's teardown did not run."
}

/** The other live sessions an archive would stop (TeardownPreview.stopsSessions). */
data class StoppedSession(val sessionId: String, val name: String?)

/** lib/protocol.ts TeardownPreview: what archiving a session would run as teardown, and the consent to send. */
data class TeardownPreview(
    val sessionId: String,
    val commands: List<String>?,
    val commit: String?,
    val worktreePath: String?,
    /** `checkoutIntact === true` is the only good reading. */
    val checkoutIntact: Boolean?,
    /** Null = unknown: treated as changed. */
    val checkoutChanged: Boolean?,
    val hiddenCharacters: Boolean,
    val digest: String?,
    val consent: String?,
    val error: String?,
    val stopsSessions: List<StoppedSession>,
) {
    override fun toString(): String = "TeardownPreview(commands=${commands?.size}, stops=${stopsSessions.size}, error=${error != null})"

    companion object {
        fun parse(o: JsonObject): TeardownPreview = TeardownPreview(
            sessionId = o.text("sessionId", 256).orEmpty(),
            commands = o.commandList("commands"),
            commit = o.text("commit", 128),
            worktreePath = o.text("worktreePath", 4096),
            checkoutIntact = o.flag("checkoutIntact"),
            checkoutChanged = o.flag("checkoutChanged"),
            hiddenCharacters = o.flag("hiddenCharacters") == true,
            digest = o.text("digest", 128),
            consent = o.text("consent", 128),
            error = o.text("error", 2048),
            stopsSessions = ((o["stopsSessions"] as? JsonArray) ?: JsonArray(emptyList())).asSequence()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { entry -> entry.text("sessionId", 256)?.let { StoppedSession(it, entry.text("name", 256)) } }
                .take(MAX_STOPS)
                .toList(),
        )
    }
}

/** The answer to one `archive-inspect` (an `archive-preview` frame), stamped with the socket it came on. */
data class ArchivePreviewReply(
    val sessionId: String,
    /** Null: the session has no worktree (a present JSON null), or [malformed]. */
    val preview: TeardownPreview?,
    val malformed: Boolean,
    val requestId: String?,
    val linkEpoch: Long,
)

/** draft-form.ts TeardownConsentStep. */
sealed interface TeardownConsentStep {
    /** Nothing would run: end now (`teardownConsent` "none"). */
    data class End(val teardownConsent: String = SETUP_CONSENT_NONE) : TeardownConsentStep

    /** Show the teardown commands and the warnings; end with [TeardownApproval.consent] only if approved. */
    data class Confirm(val preview: TeardownApproval) : TeardownConsentStep

    /** The preview failed or cannot be trusted: offer only to end WITHOUT the teardown, saying why. */
    data class Without(val message: String) : TeardownConsentStep
}

/** A validated [TeardownPreview] ready to confirm. */
data class TeardownApproval(
    val commit: String?,
    val commands: List<String>,
    val checkoutChanged: Boolean?,
    val hiddenCharacters: Boolean,
    val stopsSessionsNotice: String?,
    val consent: String,
) {
    /** `teardownCheckoutMayHaveChanged`: only an explicit false drops the warning. */
    val mayHaveChanged: Boolean get() = checkoutChanged != false

    override fun toString(): String = "TeardownApproval(commands=${commands.size})"
}

object TeardownSteps {
    /** lib/draft-form.ts stopsSessionsNotice: the sentence naming the OTHER live sessions an end would stop, else null. */
    fun stopsSessionsNotice(stops: List<StoppedSession>): String? {
        if (stops.isEmpty()) return null
        val names = stops.map { entry -> entry.name?.trim()?.takeIf { it.isNotEmpty() } ?: entry.sessionId }
        val who = if (stops.size == 1) "another session" else "${stops.size} other sessions"
        return "Ending this session also stops $who that can write its checkout: ${names.joinToString(", ")}."
    }

    /**
     * lib/draft-form.ts teardownConsentStep for the answer to one `archive-inspect`: [reply] null preview
     * and not malformed = no worktree = end at once.
     */
    fun teardown(reply: ArchivePreviewReply): TeardownConsentStep {
        if (reply.malformed) return TeardownConsentStep.Without(TEARDOWN_CHECK_FAILED_COPY)
        val p = reply.preview ?: return TeardownConsentStep.End()
        if (!p.error.isNullOrEmpty()) return TeardownConsentStep.Without(p.error)
        if (p.consent == SETUP_CONSENT_NONE) {
            if (SetupConsentSteps.nonEmpty(p.commands)) return TeardownConsentStep.Without(TEARDOWN_CHECK_FAILED_COPY)
            // r7 (D): nothing to run, but other sessions would be stopped: never silently.
            stopsSessionsNotice(p.stopsSessions)?.let { return TeardownConsentStep.Without(it) }
            return TeardownConsentStep.End()
        }
        if (p.checkoutIntact != true) return TeardownConsentStep.Without(TEARDOWN_TAMPERED_COPY)
        val consent = p.consent
        val commands = p.commands
        if (consent == null || !SETUP_DIGEST_RE.matches(consent) || consent != p.digest || commands == null || commands.isEmpty()) {
            return TeardownConsentStep.Without(TEARDOWN_CHECK_FAILED_COPY)
        }
        return TeardownConsentStep.Confirm(
            TeardownApproval(p.commit, commands, p.checkoutChanged, p.hiddenCharacters, stopsSessionsNotice(p.stopsSessions), consent),
        )
    }
}
