package com.tether.app.client

import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.helpers.CodexModePresets
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-8cv: the `create` frame, key for key, against the web's submit (hooks/use-draft-composer.ts
 * 887c222 ~313-347), over provider × mode × profile × worktree × model/effort. Each expected frame
 * is the web's object literal evaluated by hand for that row. ta-93qs: Claude's create carries no
 * `sandboxPolicy` at all, exactly as tether 6e38663's composer (it names no tier for Claude and the
 * server's one rule decides), asserted on its own below.
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

    /**
     * ta-xki: every row lists two models: `opus` with two reasoning-effort variants and `gpt-5` with
     * one (an effort rides only when the selected model offers it).
     */
    private val models = listOf(
        SessionModelOption("opus", "Opus", variants = listOf(ModelVariantOption("low", "Low"), ModelVariantOption("high", "High"))),
        SessionModelOption("gpt-5", "GPT-5", variants = listOf(ModelVariantOption("high", "High"))),
    )

    private fun entry(provider: String, profileId: String? = null) =
        ProviderCatalogEntry(profileId ?: provider, provider, "ready", models, profileId = profileId)

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
        Row("claude cold (Auto)", entry("claude"), form(), expected = frame("claude", """"permissionMode":"bypassPermissions",""")),
        Row("claude default", entry("claude"), form("mode" to "default"), expected = frame("claude", """"permissionMode":"default",""")),
        Row("claude acceptEdits", entry("claude"), form("mode" to "acceptEdits"), expected = frame("claude", """"permissionMode":"acceptEdits",""")),
        Row("claude plan", entry("claude"), form("mode" to "plan"), expected = frame("claude", """"permissionMode":"plan",""")),
        Row("claude bypass explicit", entry("claude"), form("mode" to "bypassPermissions"), expected = frame("claude", """"permissionMode":"bypassPermissions",""")),
        Row(
            "claude on a profile",
            entry("claude", profileId = "work"),
            form("mode" to "default"),
            expected = frame("claude", """"permissionMode":"default","profileId":"work","""),
        ),
        Row(
            "claude model + effort picked in this draft",
            entry("claude"),
            form("model" to "opus", "reasoningEffort" to "high"),
            picked("model", "reasoningEffort"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","model":"opus","reasoningEffort":"high","""),
        ),
        Row(
            "claude model shown but not picked (display only)",
            entry("claude"),
            form("model" to "opus", "reasoningEffort" to "high"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","""),
        ),
        Row(
            "claude picked but empty (engine default)",
            entry("claude"),
            form(),
            picked("model", "reasoningEffort"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","""),
        ),
        Row(
            "claude effort picked, model not",
            entry("claude"),
            form("model" to "opus", "reasoningEffort" to "low"),
            picked("reasoningEffort"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","reasoningEffort":"low","""),
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
                """"permissionMode":"bypassPermissions","worktree":{"mode":"branch-off","slug":"x","branch":"feat/x","baseRef":"origin/main"},""",
                useWorktree = true,
            ),
        ),
        Row(
            "worktree branch-off, nothing filled (server decides)",
            entry("claude"),
            form("useWorktree" to true),
            expected = frame("claude", """"permissionMode":"bypassPermissions","worktree":{"mode":"branch-off"},""", useWorktree = true),
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
            expected = frame("claude", """"permissionMode":"bypassPermissions",""", useWorktree = true),
        ),
        Row(
            "worktree checkout-branch",
            entry("claude"),
            form("useWorktree" to true, "worktreeMode" to "checkout-branch", "worktreeBranch" to "main"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","worktree":{"mode":"checkout-branch","branch":"main"},""", useWorktree = true),
        ),
        Row(
            "worktree checkout-branch without a branch: no block",
            entry("claude"),
            form("useWorktree" to true, "worktreeMode" to "checkout-branch"),
            expected = frame("claude", """"permissionMode":"bypassPermissions",""", useWorktree = true),
        ),
        Row(
            "worktree fields filled but isolation off",
            entry("claude"),
            form("worktreeBranch" to "feat/x", "worktreeSlug" to "x"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","""),
        ),
        // --- ta-xki: Mode x Effort x profile, as the composer picks them ----------------------------------
        Row(
            "claude Accept Edits + effort high on a profile",
            entry("claude", profileId = "work"),
            form("mode" to "acceptEdits", "model" to "opus", "reasoningEffort" to "high"),
            picked("mode", "reasoningEffort"),
            expected = frame("claude", """"permissionMode":"acceptEdits","profileId":"work","reasoningEffort":"high","""),
        ),
        Row(
            "claude Plan + model and effort low",
            entry("claude"),
            form("mode" to "plan", "model" to "opus", "reasoningEffort" to "low"),
            picked("mode", "model", "reasoningEffort"),
            expected = frame("claude", """"permissionMode":"plan","model":"opus","reasoningEffort":"low","""),
        ),
        Row(
            "claude Auto with an untouched effort (the engine's default)",
            entry("claude"),
            form("mode" to "bypassPermissions", "model" to "opus"),
            picked("mode", "model"),
            expected = frame("claude", """"permissionMode":"bypassPermissions","model":"opus","""),
        ),
        Row(
            "codex auto-review + effort high",
            entry("codex"),
            form("mode" to "auto-review", "model" to "gpt-5", "reasoningEffort" to "high"),
            picked("mode", "model", "reasoningEffort"),
            expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","approvalsReviewer":"auto_review","model":"gpt-5","reasoningEffort":"high","""),
        ),
        Row(
            "codex full-access on a profile + effort",
            entry("codex", profileId = "codex-work"),
            form("mode" to "full-access", "model" to "opus", "reasoningEffort" to "low"),
            picked("reasoningEffort"),
            expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"off","approvalPolicy":"never","profileId":"codex-work","reasoningEffort":"low","""),
        ),
        Row(
            "opencode Build (default) + effort",
            entry("opencode"),
            form("mode" to "default", "model" to "gpt-5", "reasoningEffort" to "high"),
            picked("mode", "model", "reasoningEffort"),
            expected = frame("opencode", """"permissionMode":"default","model":"gpt-5","reasoningEffort":"high","""),
        ),
        Row(
            "opencode Auto chip on a profile",
            entry("opencode", profileId = "oc"),
            form("mode" to "bypassPermissions"),
            picked("mode"),
            expected = frame("opencode", """"permissionMode":"bypassPermissions","approvalPolicy":"never","profileId":"oc","""),
        ),
        Row(
            "pi with an effort (no Mode row)",
            entry("pi"),
            form("mode" to "acceptEdits", "model" to "opus", "reasoningEffort" to "high"),
            picked("reasoningEffort"),
            expected = frame("pi", """"permissionMode":"bypassPermissions","reasoningEffort":"high","""),
        ),
    )

    /**
     * ta-xki: the rows where the app's frame differs from the web's ON PURPOSE (a stale, tampered or
     * hand-built form): a mode the provider does not offer becomes its default, an effort the selected
     * model does not offer is dropped. [web] is what the web would send; [expected] the app's frame.
     */
    private data class Clamp(val name: String, val entry: ProviderCatalogEntry, val form: JsObj, val modified: JsObj, val web: String, val expected: String)

    private val clamps = listOf(
        Clamp(
            // r2 (verifier F2, owner-delegated): a retired mode more restrictive than the default is Manual.
            "claude retired dontAsk (Locked): Manual, never Auto",
            entry("claude"), form("mode" to "dontAsk"), DraftForm.INITIAL_USER_MODIFIED,
            web = frame("claude", """"permissionMode":"dontAsk","""),
            expected = frame("claude", """"permissionMode":"default","""),
        ),
        Clamp(
            "claude garbage mode",
            entry("claude", profileId = "work"), form("mode" to "rm -rf /"), DraftForm.INITIAL_USER_MODIFIED,
            web = frame("claude", """"permissionMode":"rm -rf /","profileId":"work","""),
            expected = frame("claude", """"permissionMode":"bypassPermissions","profileId":"work","""),
        ),
        Clamp(
            "claude codex preset name",
            entry("claude"), form("mode" to "full-access"), DraftForm.INITIAL_USER_MODIFIED,
            web = frame("claude", """"permissionMode":"full-access","""),
            expected = frame("claude", """"permissionMode":"bypassPermissions","""),
        ),
        Clamp(
            "opencode acceptEdits (not an opencode row): Build, no approval policy",
            entry("opencode"), form("mode" to "acceptEdits"), DraftForm.INITIAL_USER_MODIFIED,
            web = frame("opencode", """"permissionMode":"acceptEdits","""),
            expected = frame("opencode", """"permissionMode":"bypassPermissions","""),
        ),
        Clamp(
            "opencode garbage mode",
            entry("opencode"), form("mode" to "yolo"), DraftForm.INITIAL_USER_MODIFIED,
            web = frame("opencode", """"permissionMode":"yolo","""),
            expected = frame("opencode", """"permissionMode":"bypassPermissions","""),
        ),
        Clamp(
            "an effort the model does not offer is dropped",
            entry("claude"), form("mode" to "plan", "model" to "gpt-5", "reasoningEffort" to "low"), picked("model", "reasoningEffort"),
            web = frame("claude", """"permissionMode":"plan","model":"gpt-5","reasoningEffort":"low","""),
            expected = frame("claude", """"permissionMode":"plan","model":"gpt-5","""),
        ),
        Clamp(
            "an effort for a hand-added model id (no variants) is dropped",
            entry("codex"), form("mode" to "default", "model" to "my-custom", "reasoningEffort" to "high"), picked("model", "reasoningEffort"),
            web = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","model":"my-custom","reasoningEffort":"high","""),
            expected = frame("codex", """"permissionMode":"bypassPermissions","sandboxPolicy":"workspace-write","model":"my-custom","""),
        ),
        Clamp(
            "r2 (security F2): an effort past the server's 200-byte bound is never sent",
            ProviderCatalogEntry("claude", "claude", "ready", listOf(SessionModelOption("opus", "Opus", variants = listOf(ModelVariantOption("x".repeat(201), "Huge"), ModelVariantOption("high", "High"))))),
            form("model" to "opus", "reasoningEffort" to "x".repeat(201)), picked("reasoningEffort"),
            web = frame("claude", """"permissionMode":"bypassPermissions","reasoningEffort":"${"x".repeat(201)}","""),
            expected = frame("claude", """"permissionMode":"bypassPermissions","""),
        ),
        Clamp(
            "an effort on a row with no models is dropped",
            ProviderCatalogEntry("claude", "claude", "ready", emptyList()), form("reasoningEffort" to "high"), picked("reasoningEffort"),
            web = frame("claude", """"permissionMode":"bypassPermissions","reasoningEffort":"high","""),
            expected = frame("claude", """"permissionMode":"bypassPermissions","""),
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
     * ta-93qs (negative control for the retired divergence): the app sends what the web sends, so a
     * Claude create names no sandbox tier at all, for any mode, profile or worktree; the server decides.
     */
    @Test
    fun claudeCreateCarriesNoSandboxPolicyKey() {
        val claudeRows = rows.filter { it.entry.provider == "claude" }
        assertTrue(claudeRows.isNotEmpty())
        for (row in claudeRows) {
            val built = CreateFrame.build(row.form, row.entry, row.modified, "r")
            assertNull(row.name, built.sandboxPolicy)
            assertFalse(row.name, "sandboxPolicy" in built.toJsonObject())
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

    /** ta-xki: the clamps, each against the web's frame (they differ) and the app's (exactly). */
    @Test
    fun aModeOrEffortOutsideTheKnownSetsNeverReachesTheFrame() {
        for (c in clamps) {
            val built = CreateFrame.build(c.form, c.entry, c.modified, "r").toJsonObject()
            assertEquals(c.name, Json.parseToJsonElement(c.expected).jsonObject, built)
            // Negative control: the web's own frame for the row is different, so the clamp is live.
            assertNotEquals(c.name + ": the web's frame", Json.parseToJsonElement(c.web).jsonObject, built)
            assertEquals(c.name + ": the oracle agrees on the web side", Json.parseToJsonElement(c.web).jsonObject, webFrame(c.entry, c.form, c.modified))
        }
    }

    // --- ta-xki: the whole cross-product against a port of the web's object literal ------------------------

    private val providers = listOf("claude", "codex", "opencode", "pi", "reasonix", "acp")
    private val modes = listOf("", "default", "acceptEdits", "plan", "bypassPermissions", "auto-review", "full-access", "dontAsk", "off", "bogus")
    private val modelEfforts = listOf("" to "", "opus" to "", "opus" to "high", "opus" to "low", "gpt-5" to "high", "gpt-5" to "low", "custom-id" to "high", "" to "high")
    private val pickedSets = listOf(emptyList(), listOf("mode"), listOf("model"), listOf("reasoningEffort"), listOf("model", "reasoningEffort", "mode"))
    private val profiles = listOf(null, "work")

    /**
     * hooks/use-draft-composer.ts 887c222 ~313-347, ported key for key with no app rule in it: the
     * oracle the app is checked against. (Worktree is covered by the literal rows above.)
     */
    private fun webFrame(entry: ProviderCatalogEntry, form: JsObj, modified: JsObj): JsonObject {
        fun f(k: String) = (form[k] as? JsStr)?.value ?: ""
        fun m(k: String) = modified[k] == JsBool.TRUE
        val provider = entry.provider
        val isClaude = provider == "claude"
        val isCodex = provider == "codex"
        val isOpencode = provider == "opencode"
        val preset = CodexModePresets.codexModePreset(JsStr(f("mode")))
        val autoMode = !isCodex && f("mode") == "bypassPermissions"
        return buildJsonObject {
            put("type", "create")
            put("provider", provider)
            put("cwd", f("cwd"))
            put("requestId", "r")
            put("permissionMode", if (isClaude || isOpencode) f("mode").ifEmpty { "bypassPermissions" } else "bypassPermissions")
            // tether 6e38663 (ta-zzl): only Codex names a tier; Claude's is the server's one rule.
            if (isCodex) put("sandboxPolicy", (preset["sandboxPolicy"] as JsStr).value)
            if (isCodex) {
                (preset["approvalPolicy"] as? JsStr)?.let { put("approvalPolicy", it.value) }
            } else if (autoMode && isOpencode) {
                put("approvalPolicy", "never")
            }
            if (isCodex && preset["approvalsReviewer"] == JsStr("auto_review")) put("approvalsReviewer", "auto_review")
            put("useWorktree", form["useWorktree"] == JsBool.TRUE)
            entry.profileId?.let { put("profileId", it) }
            if (m("model") && f("model").isNotEmpty()) put("model", f("model"))
            if (m("reasoningEffort") && f("reasoningEffort").isNotEmpty()) put("reasoningEffort", f("reasoningEffort"))
        }
    }

    /** The values each key may ever take on the app's wire, per provider (the known sets). */
    private fun assertWithinKnownSets(name: String, provider: String, frame: JsonObject, entry: ProviderCatalogEntry, model: String) {
        fun v(k: String): String? = (frame[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val permission = v("permissionMode")
        when (provider) {
            "claude" -> assertTrue("$name: $permission", permission in setOf("default", "acceptEdits", "plan", "bypassPermissions"))
            "opencode" -> assertTrue("$name: $permission", permission in setOf("default", "plan", "bypassPermissions"))
            else -> assertEquals(name, "bypassPermissions", permission)
        }
        when (provider) {
            "codex" -> assertTrue(name, v("sandboxPolicy") in setOf("workspace-write", "off"))
            else -> assertEquals(name, null, v("sandboxPolicy"))
        }
        assertTrue(name, v("approvalPolicy") in setOf(null, "never"))
        if (v("approvalPolicy") != null) assertTrue(name, provider == "codex" || provider == "opencode")
        assertTrue(name, v("approvalsReviewer") in setOf(null, "auto_review"))
        if (v("approvalsReviewer") != null) assertEquals(name, "codex", provider)
        v("reasoningEffort")?.let { e -> assertTrue("$name: $e", DraftSessionOptionsModel.offeredEfforts(entry, model).any { it.value == e }) }
    }

    @Test
    fun theCrossProductMatchesTheWebForEveryOfferedValueAndStaysInsideTheKnownSets() {
        var exact = 0
        var clamped = 0
        for (provider in providers) for (mode in modes) for ((model, effort) in modelEfforts) for (picks in pickedSets) for (profile in profiles) {
            val e = entry(provider, profileId = profile)
            val f = form("mode" to mode, "model" to model, "reasoningEffort" to effort)
            val mod = picked(*picks.toTypedArray())
            val name = "$provider/$mode/$model/$effort/$picks/$profile"
            val built = CreateFrame.build(f, e, mod, "r").toJsonObject()
            assertWithinKnownSets(name, provider, built, e, model)
            val known = DraftModes.known(provider)
            val modeOffered = known == null || mode in known
            val effortOffered = effort.isEmpty() || !mod.flag("reasoningEffort") || DraftSessionOptionsModel.offeredEfforts(e, model).any { it.value == effort }
            // The web's frame for the same row (no app-only change: ta-93qs retired the last one).
            val web = webFrame(e, f, mod)
            if (modeOffered && effortOffered) {
                assertEquals("$name: keys", web.keys, built.keys)
                assertEquals(name, web, built)
                exact++
            } else {
                // Off the known sets the app's frame is the web's frame for the fallback values.
                val fallback = f.put("mode", JsStr(DraftModes.normalize(provider, mode)))
                    .put("reasoningEffort", JsStr(effort.takeIf { effortOffered }.orEmpty()))
                val expected = webFrame(e, fallback, mod)
                assertEquals("$name (fallback)", expected, built)
                clamped++
            }
        }
        // 6 providers x 10 modes x 8 model/effort x 5 pick sets x 2 profiles.
        assertEquals(4800, exact + clamped)
        assertTrue("both halves are exercised: $exact exact, $clamped clamped", exact > 1000 && clamped > 500)
    }

    private fun JsObj.flag(key: String) = this[key] == JsBool.TRUE
}
