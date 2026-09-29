package com.tether.app.client

import com.tether.app.protocol.ModeOption
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.2: the pure checks the client runs under its lock before a session control reaches the wire,
 * and the exact frames it builds (lib/protocol-validate.mjs validateCodexControlAction /
 * validateOpencodeServeControlAction shapes at tether 7d65611).
 */
class SessionControlsGuardTest {

    private val claude = session("claude")

    private val claudeControls = controls(
        models = listOf(
            SessionModelOption("claude-opus-5[1m]", "Opus (1M context)", variants = listOf(ModelVariantOption("low", "low"), ModelVariantOption("high", "high")), supportsFastMode = true),
            SessionModelOption("claude-sonnet-5", "Sonnet"),
            SessionModelOption("claude-opus-4-1", "Opus 4.1", legacy = true),
        ),
        defaultModel = "claude-opus-5[1m]",
    )

    private fun check(s: AgentSession, c: ServerMessage.SessionControls?, control: SessionControl, codex: CodexSnapshot? = null, opencode: OpencodeSnapshot? = null) =
        SessionControlsGuard.check(s, c, codex, opencode, control)

    @Test
    fun claudeModesAreTheWebsFourAndAutoNeedsTheConfirmation() {
        for (mode in listOf("default", "acceptEdits", "plan")) assertNull(mode, check(claude, claudeControls, SessionControl.Mode(mode)))
        // v100: Locked (dontAsk) is no longer offered; anything else is refused.
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Mode("dontAsk")))
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Mode("BYPASSPERMISSIONS")))
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Mode("")))
        assertEquals(ControlResult.NeedsConfirmation, check(claude, claudeControls, SessionControl.Mode("bypassPermissions")))
        assertNull(check(claude, claudeControls, SessionControl.Mode("bypassPermissions", confirmed = true)))
        // The mode list does not depend on the controls reply for Claude.
        assertNull(check(claude, null, SessionControl.Mode("plan")))
    }

    @Test
    fun reasonixYoloAndPiAutoNeedTheConfirmationToo() {
        assertEquals(ControlResult.NeedsConfirmation, check(session("reasonix"), null, SessionControl.Mode("bypassPermissions")))
        assertEquals(ControlResult.NotOffered, check(session("reasonix"), null, SessionControl.Mode("acceptEdits")))
        assertEquals(ControlResult.NeedsConfirmation, check(session("pi"), null, SessionControl.Mode("bypassPermissions")))
        assertNull(check(session("pi"), null, SessionControl.Mode("acceptEdits")))
    }

    @Test
    fun opencodeModesAreTheDiscoveredAgentsAndAutoOnlyOnServeV2() {
        val discovered = controls(modes = listOf(ModeOption("default", "Build", ""), ModeOption("review", "Review", ""), ModeOption("yolo", "YOLO", "", danger = true)))
        val run = session("opencode")
        assertNull(check(run, discovered, SessionControl.Mode("review")))
        assertEquals(ControlResult.NotOffered, check(run, discovered, SessionControl.Mode("plan")))
        assertEquals(ControlResult.NeedsConfirmation, check(run, discovered, SessionControl.Mode("yolo")))
        // Without the reply, the static Build / Plan fallback.
        assertNull(check(run, null, SessionControl.Mode("plan")))
        // Auto (bypassPermissions) is the serve-v2 toggle only; run-v1 has no toggle.
        assertEquals(ControlResult.NotOffered, check(run, null, SessionControl.Mode("bypassPermissions", confirmed = true)))
        val serve = session("opencode", engine = OPENCODE_V2)
        assertEquals(ControlResult.NeedsConfirmation, check(serve, null, SessionControl.Mode("bypassPermissions")))
        assertNull(check(serve, null, SessionControl.Mode("bypassPermissions", confirmed = true)))
    }

    @Test
    fun codexAndUnknownProvidersNeverSendSetModeOrSetModel() {
        val codex = session("codex", engine = CODEX_V2)
        assertEquals(ControlResult.NotOffered, check(codex, null, SessionControl.Mode("default")))
        assertEquals(ControlResult.NotOffered, check(codex, claudeControls, SessionControl.Model("claude-sonnet-5")))
        assertEquals(ControlResult.NotOffered, check(session("fake"), claudeControls, SessionControl.Model("claude-sonnet-5")))
    }

    @Test
    fun aModelIsSentOnlyWhenListedOrTypedAsAPlausibleId() {
        assertNull(check(claude, claudeControls, SessionControl.Model("claude-sonnet-5")))
        assertNull(check(claude, claudeControls, SessionControl.Model("claude-opus-4-1"))) // a legacy row is still offered
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Model("claude-haiku-9")))
        assertEquals(ControlResult.NotOffered, check(claude, null, SessionControl.Model("claude-sonnet-5")))
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Model(LEGACY_GROUP_VALUE)))
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Model(LEGACY_GROUP_VALUE, typed = true)))
        // /model <id> passthrough (chat-view.tsx:2976).
        assertNull(check(claude, null, SessionControl.Model("claude-haiku-9", typed = true)))
        for (bad in listOf("fix the bug", "a", "-leading", "x".repeat(65), "", "model;rm", "déjà")) {
            assertEquals(bad, ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Model(bad, typed = true)))
        }
    }

    @Test
    fun effortIsOneOfTheActiveModelsVariantsOrTheDefault() {
        assertNull(check(claude, claudeControls, SessionControl.Effort("high")))
        assertNull(check(claude, claudeControls, SessionControl.Effort("")))
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Effort("max")))
        // The active model is the operator's pin: Sonnet reports no levels.
        assertEquals(ControlResult.NotOffered, check(claude.copy(model = "claude-sonnet-5"), claudeControls, SessionControl.Effort("high")))
        assertEquals(ControlResult.NotOffered, check(session("dsh"), claudeControls, SessionControl.Effort("high")))
    }

    @Test
    fun fastModeOnlyForClaudeOnAModelThatSupportsIt() {
        assertNull(check(claude, claudeControls, SessionControl.FastMode(true)))
        assertNull(check(claude, claudeControls, SessionControl.FastMode(false)))
        assertEquals(ControlResult.NotOffered, check(claude.copy(model = "claude-sonnet-5"), claudeControls, SessionControl.FastMode(true)))
        assertEquals(ControlResult.NotOffered, check(session("opencode"), claudeControls, SessionControl.FastMode(true)))
        assertEquals(ControlResult.NotOffered, check(claude, null, SessionControl.FastMode(true)))
    }

    @Test
    fun codexActionsAreCheckedAgainstTheSnapshot() {
        val codex = session("codex", engine = CODEX_V2)
        val snap = codexSnapshot()
        fun c(control: SessionControl, s: CodexSnapshot? = snap) = check(codex, null, control, codex = s)
        assertNull(c(SessionControl.CodexModelSelection("gpt-5.5", "high")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexModelSelection("gpt-5.5", "xhigh"))) // not THIS model's effort
        assertNull(c(SessionControl.CodexModelSelection("gpt-5.5-mini", "xhigh")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexModelSelection("gpt-9", "high")))
        assertNull(c(SessionControl.CodexCollaboration("plan")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexCollaboration("nope")))
        assertNull(c(SessionControl.CodexSkill("skill-a", false)))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexSkill("skill-z", true)))
        assertNull(c(SessionControl.CodexCompaction))
        assertEquals(ControlResult.NeedsConfirmation, c(SessionControl.CodexAutoApprove(true)))
        assertNull(c(SessionControl.CodexAutoApprove(true, confirmed = true)))
        assertNull(c(SessionControl.CodexAutoApprove(false)))
        // No snapshot (none on this socket) = nothing to bind an action to.
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexCompaction, null))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexAutoApprove(false), null))
        // A legacy Codex thread has no control channel.
        assertEquals(ControlResult.NotOffered, check(session("codex"), null, SessionControl.CodexCompaction, codex = snap))
        // Actions the snapshot reports unavailable.
        val offline = CodexSnapshot.parse(codexRaw(review = "unavailable", compaction = "unsupported"))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexCompaction, offline))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexReview(ReviewTarget.UncommittedChanges, "inline"), offline))
    }

    @Test
    fun reviewTargetsTakeTheServersShapeOnly() {
        val codex = session("codex", engine = CODEX_V2)
        val snap = codexSnapshot()
        fun r(target: ReviewTarget, delivery: String = "inline") = check(codex, null, SessionControl.CodexReview(target, delivery), codex = snap)
        assertNull(r(ReviewTarget.UncommittedChanges))
        assertNull(r(ReviewTarget.UncommittedChanges, "detached"))
        assertEquals(ControlResult.NotOffered, r(ReviewTarget.UncommittedChanges, "elsewhere"))
        assertNull(r(ReviewTarget.BaseBranch("main")))
        assertEquals(ControlResult.NotOffered, r(ReviewTarget.BaseBranch("")))
        assertEquals(ControlResult.NotOffered, r(ReviewTarget.BaseBranch("b".repeat(201))))
        assertNull(r(ReviewTarget.Commit("abc1234")))
        assertNull(r(ReviewTarget.Commit("ABCDEF0123456789")))
        assertEquals(ControlResult.NotOffered, r(ReviewTarget.Commit("abc123")))
        assertEquals(ControlResult.NotOffered, r(ReviewTarget.Commit("xyz1234")))
        assertNull(r(ReviewTarget.Custom("Check the error paths")))
        assertEquals(ControlResult.NotOffered, r(ReviewTarget.Custom("x".repeat(4097))))
    }

    @Test
    fun opencodeActionsAreCheckedAgainstTheSnapshot() {
        val serve = session("opencode", engine = OPENCODE_V2)
        val snap = opencodeSnapshot()
        fun c(control: SessionControl) = check(serve, null, control, opencode = snap)
        assertNull(c(SessionControl.OpencodeModelSelection("openai/gpt-5", null)))
        assertNull(c(SessionControl.OpencodeModelSelection("openai/gpt-5", "high")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.OpencodeModelSelection("openai/gpt-5", "max")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.OpencodeModelSelection("anthropic/x", null)))
        assertNull(c(SessionControl.OpencodeMode("plan")))
        assertEquals(ControlResult.NeedsConfirmation, c(SessionControl.OpencodeMode("yolo")))
        assertNull(c(SessionControl.OpencodeMode("yolo", confirmed = true)))
        assertEquals(ControlResult.NotOffered, c(SessionControl.OpencodeMode("unknown")))
        assertEquals(ControlResult.NotOffered, check(session("opencode"), null, SessionControl.OpencodeMode("plan"), opencode = snap))
    }

    @Test
    fun framesAreExactlyTheValidatorsShapes() {
        val snap = codexSnapshot()
        fun frame(control: SessionControl) = Json.parseToJsonElement(SessionControlsGuard.frame("s1", control, snap, opencodeSnapshot(), "op-1").encode()).jsonObject
        assertEquals(json("""{"type":"set-mode","sessionId":"s1","permissionMode":"plan"}"""), frame(SessionControl.Mode("plan")))
        assertEquals(json("""{"type":"set-model","sessionId":"s1","model":"claude-sonnet-5"}"""), frame(SessionControl.Model("claude-sonnet-5")))
        assertEquals(json("""{"type":"set-reasoning-effort","sessionId":"s1","reasoningEffort":""}"""), frame(SessionControl.Effort("")))
        assertEquals(json("""{"type":"set-fast-mode","sessionId":"s1","enabled":true}"""), frame(SessionControl.FastMode(true)))
        val envelope = """"revision":"catalog-3","operatorAction":true,"operatorActionId":"op-1""""
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"set-model-selection","modelId":"gpt-5.5","reasoningEffortId":"high",$envelope}}"""),
            frame(SessionControl.CodexModelSelection("gpt-5.5", "high")),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"set-approval-policy","approvalPolicy":null,$envelope}}"""),
            frame(SessionControl.CodexAutoApprove(false)),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"set-approval-policy","approvalPolicy":"never",$envelope}}"""),
            frame(SessionControl.CodexAutoApprove(true, confirmed = true)),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"start-review","target":{"type":"commit","sha":"abc1234","title":null},"delivery":"detached",$envelope}}"""),
            frame(SessionControl.CodexReview(ReviewTarget.Commit("abc1234"), "detached")),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"start-compaction",$envelope}}"""),
            frame(SessionControl.CodexCompaction),
        )
        assertEquals(
            json("""{"type":"opencode-control-action","sessionId":"s1","action":{"type":"set-model-selection","modelId":"openai/gpt-5","variantId":null,"revision":"oc-1","operatorAction":true,"operatorActionId":"op-1"}}"""),
            frame(SessionControl.OpencodeModelSelection("openai/gpt-5", null)),
        )
        assertEquals(
            json("""{"type":"opencode-control-action","sessionId":"s1","action":{"type":"set-mode","mode":"plan","revision":"oc-1","operatorAction":true,"operatorActionId":"op-1"}}"""),
            frame(SessionControl.OpencodeMode("plan")),
        )
    }

    @Test
    fun looksLikeModelIdMatchesTheWeb() {
        assertTrue(looksLikeModelId("claude-opus-5[1m]".replace("[1m]", "")))
        assertTrue(looksLikeModelId("gpt-5.5:latest"))
        assertTrue(looksLikeModelId("ab"))
        assertFalse(looksLikeModelId("claude-opus-5[1m]"))
        assertFalse(looksLikeModelId("two words"))
    }

    @Test
    fun snapshotsParseTolerantly() {
        assertNull(CodexSnapshot.parse(json("""{"models":{"status":"ready","items":[]}}""")))
        assertNull(CodexSnapshot.parse(json("""{"revision":""}""")))
        val sparse = CodexSnapshot.parse(json("""{"revision":"r","models":{"status":"weird","items":[{"name":"no id"},{"id":"m","reasoningEfforts":[{"id":"low"},{"x":1}]}]}}"""))!!
        assertEquals("unavailable", sparse.models.status)
        assertEquals(listOf("m"), sparse.models.items.map { it.id })
        assertEquals(listOf("low"), sparse.models.items.single().reasoningEfforts.map { it.id })
        assertEquals("unavailable", sparse.reviewStatus)
        assertEquals("unavailable", sparse.skills.status)
        assertNull(OpencodeSnapshot.parse(null))
    }

    companion object {
        fun session(provider: String, engine: String? = null) = AgentSession(
            id = "s1", provider = provider, engineGeneration = engine, name = "n", cwd = "/w", status = "ready", startedAt = 1, updatedAt = 1,
        )

        fun controls(
            models: List<SessionModelOption> = emptyList(),
            defaultModel: String? = null,
            modes: List<ModeOption>? = null,
            defaultReasoningEffort: String? = null,
        ) = ServerMessage.SessionControls("s1", models, emptyList(), null, defaultModel = defaultModel, modes = modes, defaultReasoningEffort = defaultReasoningEffort)

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        fun codexRaw(review: String = "ready", compaction: String = "ready"): JsonObject = json(
            """
            {"revision":"catalog-3",
             "models":{"status":"ready","items":[
               {"id":"gpt-5.5","name":"GPT-5.5","description":"Frontier","defaultReasoningEffort":"medium",
                "reasoningEfforts":[{"id":"low","description":"Fast"},{"id":"medium","description":"Balanced"},{"id":"high","description":"Deep"}]},
               {"id":"gpt-5.5-mini","name":"GPT-5.5 mini","description":"","defaultReasoningEffort":"low",
                "reasoningEfforts":[{"id":"low","description":""},{"id":"xhigh","description":""}]}]},
             "collaborationModes":{"status":"ready","items":[
               {"id":"plan","name":"Plan","mode":"plan","model":null,"reasoningEffort":null},
               {"id":"default","name":"Default","mode":"default","model":null,"reasoningEffort":null}]},
             "skills":{"status":"ready","items":[{"id":"skill-a","name":"Release notes","description":"Drafts notes","scope":"repo","enabled":true}]},
             "hooks":{"status":"ready","items":[{"id":"h1","name":"Format","event":"PostToolUse","handler":"command","source":"repo","trust":"trusted","enabled":true,"managed":false}]},
             "apps":{"status":"unsupported","items":[]},
             "mcpServers":{"status":"ready","items":[{"id":"m1","name":"github","status":"needs-auth","statusLabel":"Needs auth","toolCount":12}]},
             "rateLimits":{"status":"ready","items":[{"id":"codex","name":"Codex","status":"available","statusLabel":"Available",
               "primary":{"usedPercent":42.25,"windowDurationMins":300,"resetsAt":1790000000},"secondary":null,"credits":{"hasCredits":true,"unlimited":false}}]},
             "actions":{"review":"$review","compaction":"$compaction"}}
            """.trimIndent(),
        )

        fun codexSnapshot(): CodexSnapshot = CodexSnapshot.parse(codexRaw())!!

        fun opencodeRaw(): JsonObject = json(
            """
            {"revision":"oc-1",
             "models":{"status":"ready","items":[
               {"value":"openai/gpt-5","displayName":"GPT-5","providerLabel":"openai","variants":[{"value":"high","label":"high"},{"value":"low","label":"low"}]},
               {"value":"deepseek/deepseek-v4","displayName":"DeepSeek V4","providerLabel":"deepseek"}]},
             "modes":{"status":"ready","items":[
               {"value":"default","label":"Build","hint":"Edits and runs tools"},
               {"value":"plan","label":"Plan","hint":"Plans only"},
               {"value":"yolo","label":"YOLO","hint":"Everything, no asking","danger":true}]}}
            """.trimIndent(),
        )

        fun opencodeSnapshot(): OpencodeSnapshot = OpencodeSnapshot.parse(opencodeRaw())!!
    }
}
