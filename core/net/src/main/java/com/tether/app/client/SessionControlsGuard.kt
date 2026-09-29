package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T7.2: what became of one operator session-control tap. Only [Sent] means a frame went on the wire;
 * every other value means nothing was transmitted and nothing is held for later.
 */
enum class ControlResult {
    Sent,

    /** No live, handshaken socket (or the frame could not be handed to it). */
    NotConnected,

    /** Connected, but the session is not confirmed live on this connection (or the row was drawn for another server). */
    NotLive,

    /** The session is read-only or handed off, or not listed at all (fail closed). */
    Locked,

    /** The value is not one the server (or, for the static vocabularies, the web) offers this session now. */
    NotOffered,

    /** A change to the most permissive posture that did not come through the confirmation. */
    NeedsConfirmation,
}

/** One operator choice on the composer row, the session sheet or a provider-controls panel. */
sealed interface SessionControl {
    /** `set-mode`: a permission / agent mode from the provider's vocabulary. */
    data class Mode(val value: String, val confirmed: Boolean = false) : SessionControl

    /** `set-model`: a listed row, or ([typed]) a `/model <id>` argument that passes [looksLikeModelId]. */
    data class Model(val value: String, val typed: Boolean = false) : SessionControl

    /** `set-reasoning-effort`: one of the active model's variants, or "" (the model's default). */
    data class Effort(val value: String) : SessionControl

    /** `set-fast-mode` (v95, Claude, a model that reports supportsFastMode). */
    data class FastMode(val enabled: Boolean) : SessionControl

    /**
     * A provider action (Codex / opencode-serve): bound to the [revision] of the catalog snapshot the
     * row or panel was drawn from. The client refuses it when the snapshot has moved on (round 2, L1)
     * and sends exactly this revision.
     */
    sealed interface ProviderAction : SessionControl {
        val revision: String
    }

    /** Codex `set-model-selection`: a catalog model and one of ITS efforts. */
    data class CodexModelSelection(val modelId: String, val effortId: String, override val revision: String) : ProviderAction

    /** Codex `set-collaboration-mode`: a catalog item id. */
    data class CodexCollaboration(val collaborationId: String, override val revision: String) : ProviderAction

    /** Codex `set-approval-policy`: Auto approve on ("never") or off (null). On needs [confirmed]. */
    data class CodexAutoApprove(val enabled: Boolean, override val revision: String, val confirmed: Boolean = false) : ProviderAction

    /** Codex `set-skill-enabled`: a catalog skill. */
    data class CodexSkill(val skillId: String, val enabled: Boolean, override val revision: String) : ProviderAction

    /** Codex `start-review`. */
    data class CodexReview(val target: ReviewTarget, val delivery: String, override val revision: String) : ProviderAction

    /** Codex `start-compaction`. */
    data class CodexCompaction(override val revision: String) : ProviderAction

    /** opencode-serve `set-model-selection`: a catalog model and null or one of ITS variants. */
    data class OpencodeModelSelection(val modelId: String, val variantId: String?, override val revision: String) : ProviderAction

    /** opencode-serve `set-mode`: a catalog agent. A possibly-permissive agent needs [confirmed]. */
    data class OpencodeMode(val mode: String, override val revision: String, val confirmed: Boolean = false) : ProviderAction
}

/** [this] re-issued as confirmed by the operator (the confirmation dialog), or null when it has no such flag. */
fun SessionControl.confirmedCopy(): SessionControl? = when (this) {
    is SessionControl.Mode -> copy(confirmed = true)
    is SessionControl.CodexAutoApprove -> copy(confirmed = true)
    is SessionControl.OpencodeMode -> copy(confirmed = true)
    else -> null
}

/** codex-control-action `start-review` targets (validateCodexReviewTarget). */
sealed interface ReviewTarget {
    data object UncommittedChanges : ReviewTarget
    data class BaseBranch(val branch: String) : ReviewTarget
    data class Commit(val sha: String) : ReviewTarget
    data class Custom(val instructions: String) : ReviewTarget
}

/**
 * T7.2 security semantics for the session controls, as pure checks. Run by RealTetherClient under its
 * lock, after the link / liveness / lock checks, against the session's CURRENT state: the session row,
 * the last `session-controls` reply, and the provider-control snapshot received on this socket. A
 * value is sent only when it is exactly one the state offers (the web's static vocabularies for
 * Claude / reasonix / pi modes), a typed `/model` id only when it passes [looksLikeModelId], a review
 * target only in the shape the server validates, and a change to the most permissive posture only
 * with the confirmation flag the confirmation dialog sets.
 */
object SessionControlsGuard {

    /** Null when [control] may be sent for [session]; otherwise why not. */
    fun check(
        session: AgentSession,
        controls: ServerMessage.SessionControls?,
        codex: CodexSnapshot?,
        opencode: OpencodeSnapshot?,
        control: SessionControl,
    ): ControlResult? {
        val offered = ComposerControlsModel.derive(session, controls, codex?.let { ProviderControlsState(it, false, null) }, emptyList())
        val provider = session.provider
        if (control is SessionControl.ProviderAction) {
            val current = when (control) {
                is SessionControl.OpencodeModelSelection, is SessionControl.OpencodeMode -> opencode?.revision
                else -> codex?.revision
            }
            // Drawn from a catalog that has since been replaced (or from none on this socket).
            if (current == null || current != control.revision) return ControlResult.NotOffered
        }
        return when (control) {
            is SessionControl.Mode -> {
                if (provider !in MODE_PROVIDERS) return ControlResult.NotOffered
                val options = ComposerControlsModel.modeOptions(session, controls, opencode)
                val option = options.firstOrNull { it.value == control.value }
                val auto = control.value == ModeVocabulary.AUTO && offered.auto != null
                if (option == null && !auto) return ControlResult.NotOffered
                // Round 2 (M2): an opencode agent is confirmed unless it is a built-in or BOTH
                // sources that list it leave it undangerous; the static vocabularies by their flag.
                val escalates = if (provider == "opencode") {
                    ComposerControlsModel.opencodeAgentNeedsConfirmation(control.value, controls, opencode)
                } else {
                    option?.danger == true || control.value == ModeVocabulary.AUTO
                }
                if (escalates && !control.confirmed) ControlResult.NeedsConfirmation else null
            }
            is SessionControl.Model -> {
                if (provider !in MODEL_PROVIDERS) return ControlResult.NotOffered
                if (control.value == LEGACY_GROUP_VALUE) return ControlResult.NotOffered
                if (control.typed) {
                    // chat-view.tsx:3015-3024: every engine with a model select but Codex pins a
                    // plausible typed id (Codex's model is one paired catalog selection).
                    if (typedModelAllowed(provider) && looksLikeModelId(control.value)) null else ControlResult.NotOffered
                } else {
                    if (controls?.models.orEmpty().any { it.value == control.value }) null else ControlResult.NotOffered
                }
            }
            is SessionControl.Effort -> {
                if (provider !in EFFORT_PROVIDERS) return ControlResult.NotOffered
                if (control.value.isEmpty()) return null
                val variants = ComposerControlsModel.activeModel(session, controls)?.variants.orEmpty()
                if (variants.any { it.value == control.value }) null else ControlResult.NotOffered
            }
            is SessionControl.FastMode ->
                if (provider == "claude" && ComposerControlsModel.activeModel(session, controls)?.supportsFastMode == true) null else ControlResult.NotOffered
            is SessionControl.CodexModelSelection -> {
                val snap = codexV2(session, codex) ?: return ControlResult.NotOffered
                if (!snap.models.ready) return ControlResult.NotOffered
                val model = snap.models.items.firstOrNull { it.id == control.modelId } ?: return ControlResult.NotOffered
                if (model.reasoningEfforts.none { it.id == control.effortId }) return ControlResult.NotOffered
                if (!bounded(control.modelId, 200) || !bounded(control.effortId, 200)) ControlResult.NotOffered else null
            }
            is SessionControl.CodexCollaboration -> {
                val snap = codexV2(session, codex) ?: return ControlResult.NotOffered
                if (!snap.collaborationModes.ready) return ControlResult.NotOffered
                if (snap.collaborationModes.items.any { it.id == control.collaborationId } && bounded(control.collaborationId, 200)) null else ControlResult.NotOffered
            }
            is SessionControl.CodexAutoApprove -> {
                codexV2(session, codex) ?: return ControlResult.NotOffered
                if (control.enabled && !control.confirmed) ControlResult.NeedsConfirmation else null
            }
            is SessionControl.CodexSkill -> {
                val snap = codexV2(session, codex) ?: return ControlResult.NotOffered
                if (!snap.skills.ready) return ControlResult.NotOffered
                if (snap.skills.items.any { it.id == control.skillId } && bounded(control.skillId, 200)) null else ControlResult.NotOffered
            }
            is SessionControl.CodexReview -> {
                val snap = codexV2(session, codex) ?: return ControlResult.NotOffered
                if (snap.reviewStatus != "ready") return ControlResult.NotOffered
                if (control.delivery != "inline" && control.delivery != "detached") return ControlResult.NotOffered
                if (validReviewTarget(control.target)) null else ControlResult.NotOffered
            }
            is SessionControl.CodexCompaction -> {
                val snap = codexV2(session, codex) ?: return ControlResult.NotOffered
                if (snap.compactionStatus == "ready") null else ControlResult.NotOffered
            }
            is SessionControl.OpencodeModelSelection -> {
                val snap = opencodeV2(session, opencode) ?: return ControlResult.NotOffered
                if (!snap.models.ready) return ControlResult.NotOffered
                val model = snap.models.items.firstOrNull { it.value == control.modelId } ?: return ControlResult.NotOffered
                if (!bounded(control.modelId, 200)) return ControlResult.NotOffered
                val variant = control.variantId ?: return null
                if (model.variants.orEmpty().any { it.value == variant } && bounded(variant, 200)) null else ControlResult.NotOffered
            }
            is SessionControl.OpencodeMode -> {
                val snap = opencodeV2(session, opencode) ?: return ControlResult.NotOffered
                if (!snap.modes.ready) return ControlResult.NotOffered
                snap.modes.items.firstOrNull { it.value == control.mode } ?: return ControlResult.NotOffered
                if (!bounded(control.mode, 200)) return ControlResult.NotOffered
                val escalates = ComposerControlsModel.opencodeAgentNeedsConfirmation(control.mode, controls, snap)
                if (escalates && !control.confirmed) ControlResult.NeedsConfirmation else null
            }
        }
    }

    /**
     * The frame for a [check]ed control. Provider actions carry the explicit operator envelope
     * (protocol-validate.mjs validateCodexControlAction): the revision of the snapshot the value was
     * checked against, `operatorAction: true`, and a fresh [operatorActionId].
     */
    fun frame(sessionId: String, control: SessionControl, operatorActionId: String): ClientMessage = when (control) {
        is SessionControl.Mode -> ClientMessage.SetMode(sessionId, control.value)
        is SessionControl.Model -> ClientMessage.SetModel(sessionId, control.value)
        is SessionControl.Effort -> ClientMessage.SetReasoningEffort(sessionId, control.value)
        is SessionControl.FastMode -> ClientMessage.SetFastMode(sessionId, control.enabled)
        is SessionControl.CodexModelSelection -> codexAction(sessionId, control.revision, operatorActionId, "set-model-selection") {
            put("modelId", control.modelId)
            put("reasoningEffortId", control.effortId)
        }
        is SessionControl.CodexCollaboration -> codexAction(sessionId, control.revision, operatorActionId, "set-collaboration-mode") {
            put("collaborationId", control.collaborationId)
        }
        is SessionControl.CodexAutoApprove -> codexAction(sessionId, control.revision, operatorActionId, "set-approval-policy") {
            if (control.enabled) put("approvalPolicy", "never") else put("approvalPolicy", JsonNull)
        }
        is SessionControl.CodexSkill -> codexAction(sessionId, control.revision, operatorActionId, "set-skill-enabled") {
            put("skillId", control.skillId)
            put("enabled", control.enabled)
        }
        is SessionControl.CodexReview -> codexAction(sessionId, control.revision, operatorActionId, "start-review") {
            put("target", reviewTargetJson(control.target))
            put("delivery", control.delivery)
        }
        is SessionControl.CodexCompaction -> codexAction(sessionId, control.revision, operatorActionId, "start-compaction") {}
        is SessionControl.OpencodeModelSelection -> ClientMessage.OpencodeControlAction(
            sessionId,
            envelope(control.revision, operatorActionId, "set-model-selection") {
                put("modelId", control.modelId)
                if (control.variantId != null) put("variantId", control.variantId) else put("variantId", JsonNull)
            },
        )
        is SessionControl.OpencodeMode -> ClientMessage.OpencodeControlAction(
            sessionId,
            envelope(control.revision, operatorActionId, "set-mode") { put("mode", control.mode) },
        )
    }

    /** True for controls that travel as `codex-control-action` (their state shows busy until the result). */
    fun isCodexAction(control: SessionControl): Boolean = control is SessionControl.ProviderAction && !isOpencodeAction(control)

    fun isOpencodeAction(control: SessionControl): Boolean =
        control is SessionControl.OpencodeModelSelection || control is SessionControl.OpencodeMode

    /** validateCodexReviewTarget: branch 1-200 units, a 7-64 hex sha (title null), instructions 1-4096. */
    fun validReviewTarget(target: ReviewTarget): Boolean = when (target) {
        ReviewTarget.UncommittedChanges -> true
        is ReviewTarget.BaseBranch -> bounded(target.branch, 200) && validBranchName(target.branch)
        is ReviewTarget.Commit -> SHA.matches(target.sha)
        is ReviewTarget.Custom -> bounded(target.instructions, 4096)
    }

    /**
     * I3: a base branch the way `git check-ref-format --branch` accepts one: no leading "-" (never
     * read as an option), no whitespace, control or `~^:?*[\\` characters, no "..", "@{" or "//", not
     * "@", no leading/trailing "/" or ".", no component starting with "." or ending in ".lock".
     */
    fun validBranchName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("-") || name == "@") return false
        if (name.any { it.isWhitespace() || it.isISOControl() || it == '\u007f' || it in "~^:?*[\\" }) return false
        if (name.contains("..") || name.contains("@{") || name.contains("//")) return false
        if (name.startsWith("/") || name.endsWith("/") || name.endsWith(".")) return false
        return name.split('/').none { it.startsWith(".") || it.endsWith(".lock") }
    }

    private fun reviewTargetJson(target: ReviewTarget): JsonObject = buildJsonObject {
        when (target) {
            ReviewTarget.UncommittedChanges -> put("type", "uncommittedChanges")
            is ReviewTarget.BaseBranch -> { put("type", "baseBranch"); put("branch", target.branch) }
            is ReviewTarget.Commit -> { put("type", "commit"); put("sha", target.sha); put("title", JsonNull) }
            is ReviewTarget.Custom -> { put("type", "custom"); put("instructions", target.instructions) }
        }
    }

    private fun codexAction(
        sessionId: String,
        revision: String,
        operatorActionId: String,
        type: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): ClientMessage = ClientMessage.CodexControlAction(sessionId, envelope(revision, operatorActionId, type, fields))

    private fun envelope(revision: String, operatorActionId: String, type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", type)
        fields()
        put("revision", revision)
        put("operatorAction", true)
        put("operatorActionId", operatorActionId)
    }

    private fun codexV2(session: AgentSession, codex: CodexSnapshot?): CodexSnapshot? =
        if (session.provider == "codex" && session.engineGeneration == CODEX_V2) codex else null

    private fun opencodeV2(session: AgentSession, opencode: OpencodeSnapshot?): OpencodeSnapshot? =
        if (session.provider == "opencode" && session.engineGeneration == OPENCODE_V2) opencode else null

    private fun bounded(value: String, max: Int): Boolean = value.isNotEmpty() && value.length <= max

    private val SHA = Regex("^[0-9a-f]{7,64}$", RegexOption.IGNORE_CASE)

    private val MODE_PROVIDERS = setOf("claude", "opencode", "reasonix", "pi")
    private val MODEL_PROVIDERS = setOf("claude", "opencode", "reasonix", "pi", "dsh")
    private val EFFORT_PROVIDERS = setOf("claude", "opencode", "reasonix", "pi")
}

/**
 * chat-view.tsx:2976 looksLikeModelId: text that could be a model id or alias (the CLI is the real
 * validator). Screens out spaces and prose punctuation so a typo'd command is never pinned.
 */
fun looksLikeModelId(arg: String): Boolean = MODEL_ID.matches(arg)

private val MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{1,63}$")

/** Round 3 (F2): the providers whose composer pins a typed `/model <id>` — the same set as the UI. */
fun typedModelAllowed(provider: String): Boolean = provider != "codex" && provider in setOf("claude", "opencode", "reasonix", "pi", "dsh")

/** lib/model-picker.mjs LEGACY_GROUP_VALUE: the group row's sentinel, never a model id. */
const val LEGACY_GROUP_VALUE = "__legacy_models__"

const val CODEX_V2 = "codex-app-server-v2"
const val OPENCODE_V2 = "opencode-serve-v2"

internal fun List<SessionModelOption>?.orEmpty(): List<SessionModelOption> = this ?: emptyList()
