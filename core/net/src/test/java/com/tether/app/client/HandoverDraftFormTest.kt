package com.tether.app.client

import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-74gu: the handover composer's form rules against the WEB. Every expected JSON below is the output of the web's own
 * lib/draft-form.ts at tether 62ceca46 (buildHandoverSuccessor / seedFromSource run under node with these inputs), so
 * key presence, an explicit null and the absent keys are the web's, not a paraphrase. The one deliberate difference is
 * `toolApproval` for Reasonix: Android has no Approvals control, so it omits the key and the server inherits the
 * source's (lib/fresh-create.mjs:182); the web's literal output carries `"toolApproval":"ask"`.
 */
class HandoverDraftFormTest {
    private val models = listOf(
        SessionModelOption(value = "default", displayName = "Default"),
        SessionModelOption(
            value = "opus", displayName = "Opus", resolvedModel = "claude-opus-4",
            variants = listOf(ModelVariantOption("low", "Low"), ModelVariantOption("high", "High")),
        ),
        SessionModelOption(value = "sonnet", displayName = "Sonnet"),
    )

    private fun entry(provider: String, profileId: String? = null) =
        ProviderCatalogEntry(profileId ?: provider, provider, "ready", models, defaultModel = null, profileId = profileId)

    private fun form(key: String, model: String = "", effort: String = "", mode: String = "") =
        DraftForm.INITIAL_DRAFT_FORM.put("key", JsStr(key)).put("model", JsStr(model)).put("reasoningEffort", JsStr(effort))
            .put("mode", JsStr(mode)).put("cwd", JsStr("/w"))

    private fun json(s: String): JsonObject = TetherJson.parseToJsonElement(s).jsonObject

    private fun check(
        expected: String,
        entry: ProviderCatalogEntry,
        form: com.tether.app.protocol.tree.JsObj,
        seedModel: String = "",
        seedEffort: String = "",
        current: HandoverDraftForm.CodexCurrent? = null,
        name: String = "",
        edited: Boolean = false,
    ) {
        val built = HandoverDraftForm.buildSuccessor(form, entry, seedModel, seedEffort, current, name, edited)
        assertEquals(json(expected), built)
    }

    // ---- buildHandoverSuccessor, JSON-equal to the web ----
    @Test fun claudeUnchangedNamed() = check(
        """{"provider":"claude","cwd":"/w","model":"opus","reasoningEffort":"high","permissionMode":"plan","name":"Billing 2"}""",
        entry("claude"), form("claude", "opus", "high", "plan"), "opus", "high", name = " Billing 2 ", edited = true,
    )

    @Test fun claudeClearedModelAndEffortOnAProfileRowSendDefault() = check(
        """{"provider":"claude","cwd":"/w","profileId":"work","model":"default","reasoningEffort":"default","permissionMode":"bypassPermissions"}""",
        entry("claude", "work"), form("work"), "opus", "high", name = "x", edited = false,
    )

    @Test fun claudeChangedAndAWhitespaceNameIsNotSent() = check(
        """{"provider":"claude","cwd":"/w","model":"sonnet","reasoningEffort":"low","permissionMode":"bypassPermissions"}""",
        entry("claude"), form("claude", "sonnet", "low", "bypassPermissions"), "opus", "high", name = "   ", edited = true,
    )

    @Test fun claudeEmptySeedAndEmptyFormOmitsModelAndEffort() = check(
        """{"provider":"claude","cwd":"/w","permissionMode":"default"}""",
        entry("claude"), form("claude", mode = "default"),
    )

    @Test fun codexPresetAutoReview() = check(
        """{"provider":"codex","cwd":"/w","permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","approvalPolicy":null,"approvalsReviewer":"auto_review"}""",
        entry("codex"), form("codex", mode = "auto-review"),
    )

    @Test fun codexPresetFullAccessSendsAnExplicitNullReviewer() = check(
        """{"provider":"codex","cwd":"/w","model":"opus","permissionMode":"bypassPermissions","sandboxPolicy":"off","approvalPolicy":"never","approvalsReviewer":null}""",
        entry("codex"), form("codex", "opus", mode = "full-access"), "opus",
    )

    @Test fun codexCustomWithAGuardianReviewerLeavesTheReviewerToInherit() = check(
        """{"provider":"codex","cwd":"/w","permissionMode":"bypassPermissions","sandboxPolicy":"read-only","approvalPolicy":null}""",
        entry("codex"), form("codex", mode = "current"),
        current = HandoverDraftForm.CodexCurrent("read-only", null, "guardian_subagent", "S"),
    )

    @Test fun codexCustomWithNoSandboxOmitsItAndKeepsApprovalAndNullReviewer() = check(
        """{"provider":"codex","cwd":"/w","permissionMode":"bypassPermissions","approvalPolicy":"never","approvalsReviewer":null}""",
        entry("codex"), form("codex", mode = "current"),
        current = HandoverDraftForm.CodexCurrent(null, "never", null, "S"),
    )

    @Test fun codexCustomUnsandboxedWithAutoReview() = check(
        """{"provider":"codex","cwd":"/w","permissionMode":"bypassPermissions","sandboxPolicy":"off","approvalPolicy":null,"approvalsReviewer":"auto_review"}""",
        entry("codex"), form("codex", mode = "current"),
        current = HandoverDraftForm.CodexCurrent("off", null, "auto_review", "S"),
    )

    @Test fun aPresetPickReplacesTheHeldCurrentTriple() = check(
        """{"provider":"codex","cwd":"/w","permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","approvalPolicy":null,"approvalsReviewer":null}""",
        entry("codex"), form("codex", mode = "default"),
        current = HandoverDraftForm.CodexCurrent("off", null, null, "S"),
    )

    @Test fun opencodeAutoIsApprovalPolicyNever() = check(
        """{"provider":"opencode","cwd":"/w","model":"sonnet","permissionMode":"bypassPermissions","approvalPolicy":"never"}""",
        entry("opencode"), form("opencode", "sonnet", mode = "bypassPermissions"), "sonnet",
    )

    @Test fun opencodeBuildIsAnExplicitNullApprovalPolicy() = check(
        """{"provider":"opencode","cwd":"/w","permissionMode":"default","approvalPolicy":null}""",
        entry("opencode"), form("opencode", mode = "default"),
    )

    @Test fun reasonixSendsDefaultModeAndOmitsToolApproval() = check(
        // web: {"provider":"reasonix","cwd":"/w","permissionMode":"default","toolApproval":"ask","name":"n"}
        """{"provider":"reasonix","cwd":"/w","permissionMode":"default","name":"n"}""",
        entry("reasonix"), form("reasonix"), name = "n", edited = true,
    )

    @Test fun piKeepsTheWireShapeBypassPermissions() = check(
        """{"provider":"pi","cwd":"/w","model":"sonnet","permissionMode":"bypassPermissions"}""",
        entry("pi"), form("pi", "sonnet"),
    )

    @Test fun noPromptNoWorktreeAndAnAbsentKeyStaysAbsent() {
        val built = HandoverDraftForm.buildSuccessor(form("claude", "opus", "high", "plan"), entry("claude"), "opus", "high", null, "", false)
        for (k in listOf("prompt", "worktree", "useWorktree", "name", "profileId", "toolApproval", "sandboxPolicy", "approvalPolicy")) {
            assertFalse("$k must be absent", built.containsKey(k))
        }
    }

    @Test fun anExplicitNullIsAKeyNotAnAbsence() {
        val built = HandoverDraftForm.buildSuccessor(form("opencode", mode = "default"), entry("opencode"), "", "", null, "", false)
        assertTrue(built["approvalPolicy"] is JsonNull)
    }

    // ---- seedFromSource, JSON-equal to the web ----
    private fun src(provider: String = "claude", block: (AgentSession) -> AgentSession = { it }) =
        block(AgentSession(id = "src", provider = provider, name = "Src", cwd = "/src", status = "idle", startedAt = 1, updatedAt = 1))

    private fun seed(entry: ProviderCatalogEntry, session: AgentSession) = HandoverDraftForm.seedFromSource(entry, session)

    private fun assertSeed(s: HandoverDraftForm.Seed, key: String, model: String, effort: String, mode: String, extra: String? = null, current: HandoverDraftForm.CodexCurrent? = null) {
        assertEquals(HandoverDraftForm.Seed(key, model, effort, mode, "/src", current, extra), s)
    }

    @Test fun seedClaude() = assertSeed(
        seed(entry("claude"), src { it.copy(model = "opus", reasoningEffort = "high", permissionMode = "plan") }),
        "claude", "opus", "high", "plan",
    )

    @Test fun seedClaudeResolvesTheCanonicalModelAndDefaultsTheMode() = assertSeed(
        seed(entry("claude"), src { it.copy(model = "claude-opus-4", reasoningEffort = "low") }),
        "claude", "opus", "low", "bypassPermissions",
    )

    @Test fun seedClaudeKeepsAnUnlistedModelAsExtraAndNeverPinsAnEffort() = assertSeed(
        seed(entry("claude"), src { it.copy(model = "weird-model-9", reasoningEffort = "high", permissionMode = "acceptEdits") }),
        "claude", "weird-model-9", "", "acceptEdits", extra = "weird-model-9",
    )

    @Test fun seedCodexMatchingAPresetSelectsIt() {
        fun codex(block: (AgentSession) -> AgentSession) = seed(entry("codex"), src("codex", block))
        assertSeed(codex { it.copy(sandboxPolicy = "workspace-write") }, "codex", "", "", "default")
        assertSeed(codex { it.copy(sandboxPolicy = "off", approvalPolicy = "never") }, "codex", "", "", "full-access")
        assertSeed(codex { it.copy(sandboxPolicy = "workspace-write", approvalsReviewer = JsonPrimitive("auto_review")) }, "codex", "", "", "auto-review")
    }

    @Test fun seedCodexMatchingNoPresetKeepsItsRawTriple() {
        assertSeed(
            seed(entry("codex"), src("codex") { it.copy(sandboxPolicy = "read-only", approvalPolicy = "never", approvalsReviewer = JsonPrimitive("auto_review")) }),
            "codex", "", "", "current", current = HandoverDraftForm.CodexCurrent("read-only", "never", "auto_review", "Src"),
        )
        assertSeed(
            seed(entry("codex"), src("codex")),
            "codex", "", "", "current", current = HandoverDraftForm.CodexCurrent(null, null, null, "Src"),
        )
    }

    @Test fun seedOpencodeAutoAndBuild() {
        assertSeed(seed(entry("opencode"), src("opencode") { it.copy(approvalPolicy = "never", permissionMode = "default") }), "opencode", "", "", "bypassPermissions")
        assertSeed(seed(entry("opencode"), src("opencode") { it.copy(permissionMode = "default") }), "opencode", "", "", "default")
    }

    @Test fun seedReasonixAndPi() {
        assertSeed(seed(entry("reasonix"), src("reasonix") { it.copy(permissionMode = "default", model = "sonnet") }), "reasonix", "sonnet", "", "default")
        assertSeed(seed(entry("pi"), src("pi") { it.copy(permissionMode = "bypassPermissions", model = "sonnet") }), "pi", "sonnet", "", "")
    }

    // ---- the Codex "Current (custom)" row ----
    @Test fun currentCustomOptionWordsAndDanger() {
        val danger = HandoverDraftForm.codexCurrentOption(HandoverDraftForm.CodexCurrent("off", null, "auto_review", "Src"))
        assertEquals("current", danger.value)
        assertEquals("Current (custom)", danger.label)
        assertEquals("Kept from Src: no sandbox, Codex's default approvals, the auto-reviewer reviews. Pick a mode to replace it.", danger.hint)
        assertTrue(danger.danger)
        val calm = HandoverDraftForm.codexCurrentOption(HandoverDraftForm.CodexCurrent("read-only", "never", "guardian_subagent", "Src"))
        assertEquals("Kept from Src: read-only sandbox, never asks, the guardian subagent reviews. Pick a mode to replace it.", calm.hint)
        assertFalse(calm.danger)
        assertEquals(
            "Kept from S: its sandbox, Codex's default approvals, you review. Pick a mode to replace it.",
            HandoverDraftForm.codexCurrentOption(HandoverDraftForm.CodexCurrent(null, null, null, "S")).hint,
        )
        assertEquals(
            "Kept from S: workspace-write sandbox, asks on request, you review. Pick a mode to replace it.",
            HandoverDraftForm.codexCurrentOption(HandoverDraftForm.CodexCurrent("workspace-write", "on-request", "user", "S")).hint,
        )
    }

    @Test fun permissionModeForMatchesTheWeb() {
        assertEquals("bypassPermissions", HandoverDraftForm.permissionModeFor("claude", ""))
        assertEquals("plan", HandoverDraftForm.permissionModeFor("opencode", "plan"))
        assertEquals("default", HandoverDraftForm.permissionModeFor("reasonix", "anything"))
        assertEquals("bypassPermissions", HandoverDraftForm.permissionModeFor("codex", "full-access"))
    }
}
