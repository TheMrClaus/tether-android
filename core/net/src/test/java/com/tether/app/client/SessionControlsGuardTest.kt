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
    fun claudeModesAreTheWebsFourAndAutoIsSentLikeTheWeb() {
        for (mode in listOf("default", "acceptEdits", "plan")) assertNull(mode, check(claude, claudeControls, SessionControl.Mode(mode)))
        // v100: Locked (dontAsk) is no longer offered; anything else is refused.
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Mode("dontAsk")))
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Mode("BYPASSPERMISSIONS")))
        assertEquals(ControlResult.NotOffered, check(claude, claudeControls, SessionControl.Mode("")))
        // ta-coik.7: Auto passes on the first tap, as the web's chooseMode (chat-view.tsx:2500-2510).
        assertNull(check(claude, claudeControls, SessionControl.Mode("bypassPermissions")))
        // The mode list does not depend on the controls reply for Claude.
        assertNull(check(claude, null, SessionControl.Mode("plan")))
    }

    @Test
    fun reasonixYoloAndPiAutoAreSentLikeTheWebToo() {
        assertNull(check(session("reasonix"), null, SessionControl.Mode("bypassPermissions")))
        assertEquals(ControlResult.NotOffered, check(session("reasonix"), null, SessionControl.Mode("acceptEdits")))
        assertNull(check(session("pi"), null, SessionControl.Mode("bypassPermissions")))
        assertNull(check(session("pi"), null, SessionControl.Mode("acceptEdits")))
    }

    @Test
    fun opencodeModesAreTheDiscoveredAgentsAndAutoOnlyOnServeV2() {
        val discovered = controls(modes = listOf(ModeOption("default", "Build", ""), ModeOption("review", "Review", ""), ModeOption("yolo", "YOLO", "", danger = true)))
        val run = session("opencode")
        // ta-coik.7: a non-built-in or danger agent is sent like any other listed agent.
        assertNull(check(run, discovered, SessionControl.Mode("review")))
        assertEquals(ControlResult.NotOffered, check(run, discovered, SessionControl.Mode("plan")))
        assertNull(check(run, discovered, SessionControl.Mode("yolo")))
        // Without the reply, the static Build / Plan fallback.
        assertNull(check(run, null, SessionControl.Mode("plan")))
        // Auto (bypassPermissions) is the serve-v2 toggle only; run-v1 has no toggle.
        assertEquals(ControlResult.NotOffered, check(run, null, SessionControl.Mode("bypassPermissions")))
        val serve = session("opencode", engine = OPENCODE_V2)
        assertNull(check(serve, null, SessionControl.Mode("bypassPermissions")))
    }

    @Test
    fun anOpencodeAgentCallingItselfPlanWithNoDangerFlagIsMarkedButSentOnBothPaths() {
        val serve = session("opencode", engine = OPENCODE_V2)
        val sneaky = controls(modes = listOf(ModeOption("default", "Build", ""), ModeOption("planx", "Plan", "Plans only (really: everything)")))
        val snap = OpencodeSnapshot.parse(json("""{"revision":"oc-1","models":{"status":"ready","items":[]},"modes":{"status":"ready","items":[{"value":"planx","label":"Plan","hint":"Plans only"}]}}"""))!!
        // The composer row / sheet path (set-mode) and the panel path (opencode-control-action).
        // ta-coik.7: drawn in --warning (styling), but sent on the tap on both paths, as on the web.
        assertTrue(ComposerControlsModel.opencodeAgentMarkedDanger("planx", sneaky, snap))
        assertNull(check(serve, sneaky, SessionControl.Mode("planx"), opencode = snap))
        assertNull(check(serve, sneaky, SessionControl.OpencodeMode("planx", "oc-1"), opencode = snap))
        assertFalse(ComposerControlsModel.opencodeAgentMarkedDanger("default", sneaky, snap))
        assertNull(check(serve, sneaky, SessionControl.Mode("default"), opencode = snap))
        val flagged = OpencodeSnapshot.parse(json("""{"revision":"oc-1","models":{"status":"ready","items":[]},"modes":{"status":"ready","items":[{"value":"plan","label":"Plan","hint":"","danger":true}]}}"""))!!
        assertNull(check(serve, controls(modes = listOf(ModeOption("plan", "Plan", "", danger = false))), SessionControl.Mode("plan"), opencode = flagged))
        assertNull(check(serve, null, SessionControl.OpencodeMode("plan", "oc-1"), opencode = flagged))
        // Either source flagging it is enough to mark it; an explicit false on a custom agent is honoured.
        assertFalse(ComposerControlsModel.opencodeAgentMarkedDanger("review", controls(modes = listOf(ModeOption("review", "Review", "", danger = false))), null))
        assertEquals(true, ComposerControlsModel.opencodeAgentDanger("plan", controls(modes = listOf(ModeOption("plan", "Plan", "", danger = false))), flagged))
    }

    @Test
    fun aProviderActionDrawnFromAnOlderCatalogIsNotSent() {
        val codex = session("codex", engine = CODEX_V2)
        val snap = codexSnapshot()
        assertNull(check(codex, null, SessionControl.CodexCompaction("catalog-3"), codex = snap))
        assertEquals(ControlResult.NotOffered, check(codex, null, SessionControl.CodexCompaction("catalog-2"), codex = snap))
        assertEquals(ControlResult.NotOffered, check(codex, null, SessionControl.CodexModelSelection("gpt-5.5", "high", "catalog-4"), codex = snap))
        val serve = session("opencode", engine = OPENCODE_V2)
        assertEquals(ControlResult.NotOffered, check(serve, null, SessionControl.OpencodeMode("plan", "oc-0"), opencode = opencodeSnapshot()))
        // The frame carries the control's own revision.
        val frame = Json.parseToJsonElement(SessionControlsGuard.frame("s1", SessionControl.CodexCompaction("catalog-3"), "op").encode()).jsonObject
        assertEquals("catalog-3", frame["action"]!!.jsonObject["revision"]!!.toString().trim('"'))
    }

    @Test
    fun aTypedModelIdIsPinnedOnEveryEngineButCodexAsTheWebDoes() {
        // Round 3 (F2): chat-view.tsx:3015-3024 at 7d65611.
        for (p in listOf("claude", "opencode", "reasonix", "pi", "dsh")) {
            assertNull(p, check(session(p), null, SessionControl.Model("model-9", typed = true)))
            assertEquals(p, ControlResult.NotOffered, check(session(p), null, SessionControl.Model("two words", typed = true)))
        }
        assertEquals(ControlResult.NotOffered, check(session("codex", engine = CODEX_V2), null, SessionControl.Model("gpt-9", typed = true)))
        assertEquals(ControlResult.NotOffered, check(session("codex"), null, SessionControl.Model("gpt-9", typed = true)))
        assertEquals(ControlResult.NotOffered, check(session("fake"), null, SessionControl.Model("gpt-9", typed = true)))
        assertFalse(typedModelAllowed("codex"))
        assertTrue(typedModelAllowed("opencode"))
    }

    @Test
    fun baseBranchesFollowGitRefNameRules() {
        for (ok in listOf("main", "release/2026.09", "feature/T7.2-x", "v1.0")) assertTrue(ok, SessionControlsGuard.validBranchName(ok))
        for (bad in listOf("-main", "--upload-pack=x", "a b", "a\tb", "a\nb", "a..b", "a~1", "a^", "a:b", "a?", "a*", "a[b", "a\\b", "@", "a@{1}", "/a", "a/", "a//b", "a.", ".a", "a/.b", "a.lock", "a/b.lock", "\u0001x", "")) {
            assertFalse(bad, SessionControlsGuard.validBranchName(bad))
        }
        val codex = session("codex", engine = CODEX_V2)
        assertEquals(ControlResult.NotOffered, check(codex, null, SessionControl.CodexReview(ReviewTarget.BaseBranch("-x"), "inline", "catalog-3"), codex = codexSnapshot()))
    }

    @Test
    fun visibleValueSpellsOutWhatWouldBeInvisible() {
        assertEquals("plan", LabelText.visibleValue("plan"))
        assertEquals("plan\\u{200B}", LabelText.visibleValue("plan\u200B"))
        assertEquals("\\u{202E}evil", LabelText.visibleValue("\u202Eevil"))
        assertEquals("a b", LabelText.visibleValue("a b"))
        assertEquals("a\\u{0009}b\\u{000A}", LabelText.visibleValue("a\tb\n"))
        assertEquals("  x", LabelText.visibleValue("  x")) // nothing collapsed or trimmed
        val long = LabelText.visibleValue("\u200B".repeat(1000))
        assertEquals(LabelText.MAX_LABEL, long.length)
        assertTrue(long, Regex("…#[0-9a-f]{6}$").containsMatchIn(long))
        // Round 4 (P3): distinct values never display alike — a literal backslash escape, a shared
        // 80-character prefix, a difference only in case.
        assertEquals("\\\\u{200B}", LabelText.visibleValue("\\u{200B}"))
        assertTrue(LabelText.visibleValue("\\u{200B}") != LabelText.visibleValue("\u200B"))
        val a = "agent-" + "x".repeat(100) + "-one"
        val b = "agent-" + "x".repeat(100) + "-two"
        assertTrue(LabelText.visibleValue(a) != LabelText.visibleValue(b))
        assertEquals(LabelText.MAX_LABEL, LabelText.visibleValue(a).length)
        assertEquals("the tag is stable", LabelText.visibleValue(a), LabelText.visibleValue(a))
        // I-c: a huge input is not walked past the bound.
        assertEquals(LabelText.MAX_LABEL, LabelText.label("y".repeat(5_000_000)).length)
    }

    @Test
    fun serverTextIsCleanedAndCatalogsAreBounded() {
        assertEquals("Opus nimda", LabelText.label("Opus\u202E nimda\u202C"))
        assertEquals("a b c", LabelText.label("a\n\n b\u200B\t c"))
        assertEquals("safe", LabelText.label("\u2066safe\u2069\u200E"))
        assertEquals("", LabelText.label("\u3164\u2800\uFE0F"))
        val long = LabelText.label("x".repeat(500))
        assertEquals(LabelText.MAX_LABEL, long.length)
        assertTrue(long.endsWith("…"))
        val many = (0 until 500).joinToString(",") { """{"id":"s$it","name":"S$it","description":"","scope":"repo","enabled":true}""" }
        val snap = CodexSnapshot.parse(json("""{"revision":"r","skills":{"status":"ready","items":[$many]}}"""))!!
        assertEquals(LabelText.MAX_ITEMS, snap.skills.items.size)
        val bidi = OpencodeSnapshot.parse(json("""{"revision":"r","modes":{"status":"ready","items":[{"value":"x","label":"\u202Eyolo","hint":"line1\nline2"}]},"models":{"status":"error","items":[],"error":"${"e".repeat(900)}"}}"""))!!
        assertEquals("yolo", bidi.modes.items.single().label)
        assertEquals("line1 line2", bidi.modes.items.single().hint)
        assertEquals(LabelText.MAX_ERROR, bidi.models.error!!.length)
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
        assertNull(c(SessionControl.CodexModelSelection("gpt-5.5", "high", "catalog-3")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexModelSelection("gpt-5.5", "xhigh", "catalog-3"))) // not THIS model's effort
        assertNull(c(SessionControl.CodexModelSelection("gpt-5.5-mini", "xhigh", "catalog-3")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexModelSelection("gpt-9", "high", "catalog-3")))
        assertNull(c(SessionControl.CodexCollaboration("plan", "catalog-3")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexCollaboration("nope", "catalog-3")))
        assertNull(c(SessionControl.CodexSkill("skill-a", false, "catalog-3")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexSkill("skill-z", true, "catalog-3")))
        assertNull(c(SessionControl.CodexCompaction("catalog-3")))
        assertNull(c(SessionControl.CodexAutoApprove(true, "catalog-3")))
        assertNull(c(SessionControl.CodexAutoApprove(false, "catalog-3")))
        // No snapshot (none on this socket) = nothing to bind an action to.
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexCompaction("catalog-3"), null))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexAutoApprove(false, "catalog-3"), null))
        // A legacy Codex thread has no control channel.
        assertEquals(ControlResult.NotOffered, check(session("codex"), null, SessionControl.CodexCompaction("catalog-3"), codex = snap))
        // Actions the snapshot reports unavailable.
        val offline = CodexSnapshot.parse(codexRaw(review = "unavailable", compaction = "unsupported"))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexCompaction("catalog-3"), offline))
        assertEquals(ControlResult.NotOffered, c(SessionControl.CodexReview(ReviewTarget.UncommittedChanges, "inline", "catalog-3"), offline))
    }

    @Test
    fun reviewTargetsTakeTheServersShapeOnly() {
        val codex = session("codex", engine = CODEX_V2)
        val snap = codexSnapshot()
        fun r(target: ReviewTarget, delivery: String = "inline") = check(codex, null, SessionControl.CodexReview(target, delivery, "catalog-3"), codex = snap)
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
        assertNull(c(SessionControl.OpencodeModelSelection("openai/gpt-5", null, "oc-1")))
        assertNull(c(SessionControl.OpencodeModelSelection("openai/gpt-5", "high", "oc-1")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.OpencodeModelSelection("openai/gpt-5", "max", "oc-1")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.OpencodeModelSelection("anthropic/x", null, "oc-1")))
        assertNull(c(SessionControl.OpencodeMode("plan", "oc-1")))
        assertNull(c(SessionControl.OpencodeMode("yolo", "oc-1")))
        assertEquals(ControlResult.NotOffered, c(SessionControl.OpencodeMode("unknown", "oc-1")))
        assertEquals(ControlResult.NotOffered, check(session("opencode"), null, SessionControl.OpencodeMode("plan", "oc-1"), opencode = snap))
    }

    @Test
    fun framesAreExactlyTheValidatorsShapes() {
        val snap = codexSnapshot()
        fun frame(control: SessionControl) = Json.parseToJsonElement(SessionControlsGuard.frame("s1", control, "op-1").encode()).jsonObject
        assertEquals(json("""{"type":"set-mode","sessionId":"s1","permissionMode":"plan"}"""), frame(SessionControl.Mode("plan")))
        assertEquals(json("""{"type":"set-model","sessionId":"s1","model":"claude-sonnet-5"}"""), frame(SessionControl.Model("claude-sonnet-5")))
        assertEquals(json("""{"type":"set-reasoning-effort","sessionId":"s1","reasoningEffort":""}"""), frame(SessionControl.Effort("")))
        assertEquals(json("""{"type":"set-fast-mode","sessionId":"s1","enabled":true}"""), frame(SessionControl.FastMode(true)))
        // ta-coik.7: the most permissive switches, exactly the web's frames (use-tether.ts:1693-1695 set-mode,
        // :1726-1728 set-auto-continue-on-limit; chat-view.tsx:2488-2497 set-approval-policy below).
        assertEquals(json("""{"type":"set-mode","sessionId":"s1","permissionMode":"bypassPermissions"}"""), frame(SessionControl.Mode("bypassPermissions")))
        assertEquals(json("""{"type":"set-auto-continue-on-limit","sessionId":"s1","enabled":true}"""), frame(SessionControl.AutoContinueOnLimit(true)))
        assertEquals(json("""{"type":"set-auto-continue-on-limit","sessionId":"s1","enabled":false}"""), frame(SessionControl.AutoContinueOnLimit(false)))
        val envelope = """"revision":"catalog-3","operatorAction":true,"operatorActionId":"op-1""""
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"set-model-selection","modelId":"gpt-5.5","reasoningEffortId":"high",$envelope}}"""),
            frame(SessionControl.CodexModelSelection("gpt-5.5", "high", "catalog-3")),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"set-approval-policy","approvalPolicy":null,$envelope}}"""),
            frame(SessionControl.CodexAutoApprove(false, "catalog-3")),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"set-approval-policy","approvalPolicy":"never",$envelope}}"""),
            frame(SessionControl.CodexAutoApprove(true, "catalog-3")),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"start-review","target":{"type":"commit","sha":"abc1234","title":null},"delivery":"detached",$envelope}}"""),
            frame(SessionControl.CodexReview(ReviewTarget.Commit("abc1234"), "detached", "catalog-3")),
        )
        assertEquals(
            json("""{"type":"codex-control-action","sessionId":"s1","action":{"type":"start-compaction",$envelope}}"""),
            frame(SessionControl.CodexCompaction("catalog-3")),
        )
        assertEquals(
            json("""{"type":"opencode-control-action","sessionId":"s1","action":{"type":"set-model-selection","modelId":"openai/gpt-5","variantId":null,"revision":"oc-1","operatorAction":true,"operatorActionId":"op-1"}}"""),
            frame(SessionControl.OpencodeModelSelection("openai/gpt-5", null, "oc-1")),
        )
        assertEquals(
            json("""{"type":"opencode-control-action","sessionId":"s1","action":{"type":"set-mode","mode":"plan","revision":"oc-1","operatorAction":true,"operatorActionId":"op-1"}}"""),
            frame(SessionControl.OpencodeMode("plan", "oc-1")),
        )
        // opencode-serve-controls.tsx:132-139: a danger agent's frame is the same shape.
        assertEquals(
            json("""{"type":"opencode-control-action","sessionId":"s1","action":{"type":"set-mode","mode":"yolo","revision":"oc-1","operatorAction":true,"operatorActionId":"op-1"}}"""),
            frame(SessionControl.OpencodeMode("yolo", "oc-1")),
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
