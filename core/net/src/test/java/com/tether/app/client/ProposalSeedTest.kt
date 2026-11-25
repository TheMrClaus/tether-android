package com.tether.app.client

import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** v175: where the proposal card's rows start, and the settings a Start carries. */
class ProposalSeedTest {
    private val entries = listOf(
        ProviderCatalogEntry("claude", "claude", "ready", listOf(SessionModelOption(value = "opus", displayName = "Opus"), SessionModelOption(value = "sonnet", displayName = "Sonnet"))),
        ProviderCatalogEntry("codex", "codex", "ready", listOf(SessionModelOption(value = "gpt", displayName = "Gpt"))),
    )
    private val session = AgentSession(
        id = "s1", provider = "claude", name = "Billing", cwd = "/work/a", status = "idle", startedAt = 1, updatedAt = 1,
        model = "opus", reasoningEffort = null, permissionMode = "plan",
    )
    private fun s(vararg pairs: Pair<String, com.tether.app.protocol.tree.JsValue>) = JsObj.of(*pairs)

    @Test
    fun anEmptySuggestionStartsOnTheProposingSession() {
        val r = ProposalSeed.of(JsObj.EMPTY, entries, session, JsObj.EMPTY)!!
        assertEquals("claude", (r.form["key"] as JsStr).value)
        assertEquals("opus", (r.form["model"] as JsStr).value)
        assertEquals("/work/a", (r.form["cwd"] as JsStr).value)
        assertEquals(JsBool.FALSE, r.form["useWorktree"])
        // The operator's own default mode, not the proposing session's.
        assertEquals("bypassPermissions", (r.form["mode"] as JsStr).value)
    }

    @Test
    fun aSuggestedValueWinsAndTheOperatorsRememberedModeFillsTheRest() {
        val prefs = s("providerPreferences" to s("claude" to s("mode" to JsStr("acceptEdits"))))
        val r = ProposalSeed.of(s("model" to JsStr("sonnet"), "cwd" to JsStr("/other"), "useWorktree" to JsBool.TRUE), entries, session, prefs)!!
        assertEquals("sonnet", (r.form["model"] as JsStr).value)
        assertEquals("/other", (r.form["cwd"] as JsStr).value)
        assertEquals(JsBool.TRUE, r.form["useWorktree"])
        assertEquals("acceptEdits", (r.form["mode"] as JsStr).value)
    }

    @Test
    fun anotherHarnessDoesNotInheritTheSessionsModel() {
        val r = ProposalSeed.of(s("provider" to JsStr("codex")), entries, session, JsObj.EMPTY)!!
        assertEquals("codex", (r.form["key"] as JsStr).value)
        assertEquals("", (r.form["model"] as JsStr).value)
    }

    @Test
    fun aHarnessThatIsNotOnThisServerSeedsNothing() {
        assertNull(ProposalSeed.of(s("provider" to JsStr("opencode")), entries, session, JsObj.EMPTY))
    }

    @Test
    fun everySeededFieldCarriesItsModifiedFlagSoStartSendsThem() {
        val r = ProposalSeed.of(JsObj.EMPTY, entries, session, JsObj.EMPTY)!!
        val entry = entries[0]
        val settings = Proposals.startSettings(r.form, entry, r.modified)
        assertEquals("claude", settings["provider"].toString().trim('"'))
        assertEquals("opus", settings["model"].toString().trim('"'))
        assertEquals("/work/a", settings["cwd"].toString().trim('"'))
        assertFalse(settings.containsKey("prompt") || settings.containsKey("text") || settings.containsKey("name") || settings.containsKey("requestId") || settings.containsKey("type"))
    }

    @Test
    fun aCodexTripleNoPresetReproducesIsKeptAsTheCurrentRowAndSentAsIs() {
        val r = ProposalSeed.of(s("provider" to JsStr("codex"), "sandboxPolicy" to JsStr("read-only")), entries, session, JsObj.EMPTY)!!
        assertEquals(HandoverDraftForm.CODEX_CURRENT_MODE, (r.form["mode"] as JsStr).value)
        val current = r.codexCurrent!!
        assertEquals("read-only", current.sandboxPolicy)
        val settings = Proposals.startSettings(r.form, entries[1], r.modified, current)
        assertEquals("\"read-only\"", settings["sandboxPolicy"].toString())
        assertEquals("null", settings["approvalPolicy"].toString())
    }

    @Test
    fun aMatchingCodexPresetNeedsNoCurrentRow() {
        val r = ProposalSeed.of(s("provider" to JsStr("codex"), "sandboxPolicy" to JsStr("off"), "approvalPolicy" to JsStr("never")), entries, session, JsObj.EMPTY)!!
        assertNull(r.codexCurrent)
        assertEquals("full-access", (r.form["mode"] as JsStr).value)
    }
}
