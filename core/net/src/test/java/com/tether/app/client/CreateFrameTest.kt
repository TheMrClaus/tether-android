package com.tether.app.client

import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ta-8cv: the `create` frame, key for key, against the web's submit (hooks/use-draft-composer.ts
 * 887c222 ~313-347), over provider × mode × profile × worktree × model/effort. Each expected frame
 * is the web's object literal evaluated by hand for that row; the ONE place the app differs on
 * purpose is Claude's `sandboxPolicy` ("workspace-write" where the web sends "off", owner decision
 * 2026-10-02), asserted on its own below.
 */
class CreateFrameTest {

    private data class Row(
        val name: String,
        val entry: ProviderCatalogEntry,
        val form: JsObj,
        val modified: JsObj = DraftForm.INITIAL_USER_MODIFIED,
        /** The web's frame for this row, as JSON (requestId "r"). */
        val expected: String,
    )

    private fun entry(provider: String, profileId: String? = null) =
        ProviderCatalogEntry(profileId ?: provider, provider, "ready", emptyList(), profileId = profileId)

    private fun form(vararg fields: Pair<String, Any>): JsObj =
        DraftForm.INITIAL_DRAFT_FORM.put("cwd", JsStr("/w")).with(
            *fields.map { (k, v) -> k to (if (v is Boolean) JsBool.of(v) else JsStr(v as String)) }.toTypedArray(),
        )

    private fun picked(vararg keys: String): JsObj = DraftForm.INITIAL_USER_MODIFIED.with(*keys.map { it to JsBool.TRUE }.toTypedArray())

    /** The fields every frame carries (type, provider, cwd, requestId, useWorktree) around [rest]. */
    private fun frame(provider: String, rest: String, useWorktree: Boolean = false): String =
        """{"type":"create","provider":"$provider","cwd":"/w","requestId":"r",$rest"useWorktree":$useWorktree}"""

    private val rows = listOf(
        // --- Claude: permissionMode = form.mode || bypassPermissions; sandbox explicit ------------------
        Row("claude cold (Auto)", entry("claude"), form(), expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write",""")),
        Row("claude default", entry("claude"), form("mode" to "default"), expected = frame("claude", """"permissionMode":"default","sandboxPolicy":"workspace-write",""")),
        Row("claude acceptEdits", entry("claude"), form("mode" to "acceptEdits"), expected = frame("claude", """"permissionMode":"acceptEdits","sandboxPolicy":"workspace-write",""")),
        Row("claude plan", entry("claude"), form("mode" to "plan"), expected = frame("claude", """"permissionMode":"plan","sandboxPolicy":"workspace-write",""")),
        Row("claude bypass explicit", entry("claude"), form("mode" to "bypassPermissions"), expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write",""")),
        Row(
            "claude on a profile",
            entry("claude", profileId = "work"),
            form("mode" to "default"),
            expected = frame("claude", """"permissionMode":"default","sandboxPolicy":"workspace-write","profileId":"work","""),
        ),
        Row(
            "claude model + effort picked in this draft",
            entry("claude"),
            form("model" to "opus", "reasoningEffort" to "high"),
            picked("model", "reasoningEffort"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","model":"opus","reasoningEffort":"high","""),
        ),
        Row(
            "claude model shown but not picked (display only)",
            entry("claude"),
            form("model" to "opus", "reasoningEffort" to "high"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","""),
        ),
        Row(
            "claude picked but empty (engine default)",
            entry("claude"),
            form(),
            picked("model", "reasoningEffort"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","""),
        ),
        Row(
            "claude effort picked, model not",
            entry("claude"),
            form("model" to "opus", "reasoningEffort" to "low"),
            picked("reasoningEffort"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","reasoningEffort":"low","""),
        ),
        // --- Codex: the Mode preset gives sandbox / approvalPolicy / approvalsReviewer --------------------
        Row("codex default", entry("codex"), form("mode" to "default"), expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write",""")),
        Row(
            "codex auto-review",
            entry("codex"),
            form("mode" to "auto-review"),
            expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","approvalsReviewer":"auto_review","""),
        ),
        Row(
            "codex full-access",
            entry("codex"),
            form("mode" to "full-access"),
            expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"off","approvalPolicy":"never","""),
        ),
        Row("codex unknown mode falls back to default", entry("codex"), form("mode" to "bogus"), expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write",""")),
        Row("codex no mode", entry("codex"), form(), expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write",""")),
        Row(
            "codex on a profile with a picked model",
            entry("codex", profileId = "codex-work"),
            form("mode" to "full-access", "model" to "gpt-5"),
            picked("model"),
            expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"off","approvalPolicy":"never","profileId":"codex-work","model":"gpt-5","""),
        ),
        // --- opencode: the real mode; Build (bypassPermissions) also sends approvalPolicy never -----------
        Row("opencode cold", entry("opencode"), form(), expected = frame("opencode", """"permissionMode":"bypassPermissions",""")),
        Row("opencode build (Auto)", entry("opencode"), form("mode" to "bypassPermissions"), expected = frame("opencode", """"permissionMode":"bypassPermissions","approvalPolicy":"never",""")),
        Row("opencode plan", entry("opencode"), form("mode" to "plan"), expected = frame("opencode", """"permissionMode":"plan",""")),
        // --- every other engine: bypassPermissions for wire symmetry, no sandbox, the mode ignored --------
        Row("pi", entry("pi"), form("mode" to "plan"), expected = frame("pi", """"permissionMode":"bypassPermissions",""")),
        Row("reasonix", entry("reasonix"), form(), expected = frame("reasonix", """"permissionMode":"bypassPermissions",""")),
        Row("dsh", entry("dsh"), form("mode" to "default"), expected = frame("dsh", """"permissionMode":"bypassPermissions",""")),
        Row(
            "acp profile",
            entry("acp", profileId = "gemini-acp"),
            form("mode" to "bypassPermissions"),
            expected = frame("acp", """"permissionMode":"bypassPermissions","profileId":"gemini-acp","""),
        ),
        // --- v98 worktree: only the fields filled in ride along ------------------------------------------
        Row(
            "worktree branch-off, all fields",
            entry("claude"),
            form("useWorktree" to true, "worktreeBaseRef" to " origin/main ", "worktreeBranch" to "feat/x", "worktreeSlug" to "x"),
            expected = frame(
                "claude",
                """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","worktree":{"mode":"branch-off","slug":"x","branch":"feat/x","baseRef":"origin/main"},""",
                useWorktree = true,
            ),
        ),
        Row(
            "worktree branch-off, nothing filled (server decides)",
            entry("claude"),
            form("useWorktree" to true),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","worktree":{"mode":"branch-off"},""", useWorktree = true),
        ),
        Row(
            "worktree checkout-pr",
            entry("codex"),
            form("mode" to "default", "useWorktree" to true, "worktreeMode" to "checkout-pr", "worktreePr" to " 42 ", "worktreeBranch" to "pr-42", "worktreeBaseRef" to "ignored"),
            expected = frame(
                "codex",
                """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","worktree":{"mode":"checkout-pr","prNumber":42,"branch":"pr-42"},""",
                useWorktree = true,
            ),
        ),
        Row(
            "worktree checkout-pr unparseable: no block",
            entry("claude"),
            form("useWorktree" to true, "worktreeMode" to "checkout-pr", "worktreePr" to "abc"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write",""", useWorktree = true),
        ),
        Row(
            "worktree checkout-branch",
            entry("claude"),
            form("useWorktree" to true, "worktreeMode" to "checkout-branch", "worktreeBranch" to "main"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","worktree":{"mode":"checkout-branch","branch":"main"},""", useWorktree = true),
        ),
        Row(
            "worktree checkout-branch without a branch: no block",
            entry("claude"),
            form("useWorktree" to true, "worktreeMode" to "checkout-branch"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write",""", useWorktree = true),
        ),
        Row(
            "worktree fields filled but isolation off",
            entry("claude"),
            form("worktreeBranch" to "feat/x", "worktreeSlug" to "x"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","""),
        ),
    )

    @Test
    fun everyRowMatchesTheWebsFrameKeyForKey() {
        for (row in rows) {
            val built: JsonObject = CreateFrame.build(row.form, row.entry, row.modified, "r").toJsonObject()
            val expected = Json.parseToJsonElement(row.expected).jsonObject
            assertEquals(row.name + ": keys", expected.keys, built.keys)
            assertEquals(row.name, expected, built)
        }
    }

    /**
     * The one divergence, on its own (negative control): the web's Claude frame says "off"; the app's
     * never does, for any mode, profile or worktree.
     */
    @Test
    fun claudeIsAlwaysSandboxedExplicitlyNeverOff() {
        assertEquals("workspace-write", CreateFrame.CLAUDE_SANDBOX_POLICY)
        for (row in rows.filter { it.entry.provider == "claude" }) {
            val built = CreateFrame.build(row.form, row.entry, row.modified, "r")
            assertEquals(row.name, "workspace-write", built.sandboxPolicy)
            assertNotEquals(row.name, "off", built.sandboxPolicy)
        }
    }

    /** The token is the caller's, one per attempt; the builder never invents one. */
    @Test
    fun theRequestIdIsTheSubmitsOwn() {
        assertEquals("abc", CreateFrame.build(form(), entry("claude"), DraftForm.INITIAL_USER_MODIFIED, "abc").requestId)
        assertEquals("def", CreateFrame.build(form(), entry("claude"), DraftForm.INITIAL_USER_MODIFIED, "def").requestId)
    }

    /** The draft form's own cold start (lib/draft-form.ts defaultModeFor) gives the web's cold frames. */
    @Test
    fun theDraftStartsOnTheWebsDefaults() {
        assertEquals("bypassPermissions", DraftForm.defaultModeFor(JsStr("claude")))
        assertEquals("default", DraftForm.defaultModeFor(JsStr("codex")))
        assertEquals("", DraftForm.defaultModeFor(JsStr("pi")))
        val claude = CreateFrame.build(form("mode" to DraftForm.defaultModeFor(JsStr("claude"))), entry("claude"), DraftForm.INITIAL_USER_MODIFIED, "r")
        assertEquals("bypassPermissions", claude.permissionMode)
        val codex = CreateFrame.build(form("mode" to DraftForm.defaultModeFor(JsStr("codex"))), entry("codex"), DraftForm.INITIAL_USER_MODIFIED, "r")
        assertEquals("workspace-write", codex.sandboxPolicy)
        assertEquals(null, codex.approvalPolicy)
        val pi = CreateFrame.build(form("mode" to DraftForm.defaultModeFor(JsStr("pi"))), entry("pi"), DraftForm.INITIAL_USER_MODIFIED, "r")
        assertEquals("bypassPermissions", pi.permissionMode)
        assertEquals(null, pi.sandboxPolicy)
    }
}
