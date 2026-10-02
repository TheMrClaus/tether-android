package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.StubClient
import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-xki (T8.1 slice 4): the draft's Effort and Mode, against components/draft-composer.tsx (887c222
 * ~113-160, ~373-467), lib/protocol.ts PERMISSION_MODE_OPTIONS / OPENCODE_MODE_OPTIONS and
 * lib/codex-mode-presets.mjs; the per-provider, per-origin preferences and their fallback; and the
 * picks through the engine riding the create.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftSessionOptionsTest {

    private companion object {
        const val A = "https://a.example:443"
        const val B = "https://b.example:443"

        val variants = listOf(ModelVariantOption("low", "Low"), ModelVariantOption("medium", "Medium"), ModelVariantOption("high", "High"))
        val catalog = listOf(
            ProviderCatalogEntry(
                "claude", "claude", "ready",
                listOf(SessionModelOption("opus", "Opus", variants = variants), SessionModelOption("haiku", "Haiku")),
                label = "Claude",
            ),
            ProviderCatalogEntry("work", "claude", "ready", listOf(SessionModelOption("opus", "Opus", variants = variants)), label = "Claude (work)", profileId = "work"),
            ProviderCatalogEntry(
                "codex", "codex", "ready",
                listOf(SessionModelOption("gpt-5", "GPT-5", variants = listOf(ModelVariantOption("high", "High")))),
                label = "Codex",
            ),
            ProviderCatalogEntry("opencode", "opencode", "ready", listOf(SessionModelOption("oc", "OC")), label = "OpenCode"),
            ProviderCatalogEntry("pi", "pi", "ready", listOf(SessionModelOption("pi-1", "Pi One", variants = variants)), label = "Pi"),
        )
    }

    // --- the vocabularies -------------------------------------------------------------------------------

    @Test
    fun eachProviderHasTheWebsModeRowsAndLabels() {
        assertEquals(
            listOf("default" to "Manual", "acceptEdits" to "Accept Edits", "plan" to "Plan", "bypassPermissions" to "Auto"),
            DraftModes.options("claude")!!.map { it.value to it.label },
        )
        assertEquals(listOf(false, false, false, true), DraftModes.options("claude")!!.map { it.danger })
        assertEquals(
            listOf("default" to "Default permissions", "auto-review" to "Auto-review", "full-access" to "Full access"),
            DraftModes.options("codex")!!.map { it.value to it.label },
        )
        assertEquals(listOf(false, false, true), DraftModes.options("codex")!!.map { it.danger })
        assertEquals(listOf("default" to "Build", "plan" to "Plan"), DraftModes.options("opencode")!!.map { it.value to it.label })
        assertEquals("Runs every tool without asking — including destructive commands", DraftModes.options("claude")!!.last().hint)
        for (other in listOf("pi", "reasonix", "acp", "dsh", "")) assertNull(other, DraftModes.options(other))
        assertTrue(DraftModes.hasAutoChip("opencode"))
        assertFalse("Claude's Auto is a Mode row", DraftModes.hasAutoChip("claude"))
        assertFalse("Codex folds Auto into its presets", DraftModes.hasAutoChip("codex"))
    }

    @Test
    fun aModeTheProviderDoesNotOfferIsItsDefault() {
        // r2 (verifier F2): the retired, more restrictive Locked is Manual, never the Auto default.
        assertEquals("default", DraftModes.normalize("claude", "dontAsk"))
        assertEquals("bypassPermissions", DraftModes.normalize("claude", "rm -rf"))
        assertEquals("bypassPermissions", DraftModes.normalize("claude", ""))
        assertEquals("acceptEdits", DraftModes.normalize("claude", "acceptEdits"))
        assertEquals("default", DraftModes.normalize("codex", "off"))
        assertEquals("default", DraftModes.normalize("codex", "bypassPermissions"))
        assertEquals("full-access", DraftModes.normalize("codex", "full-access"))
        assertEquals("", DraftModes.normalize("opencode", "acceptEdits"))
        assertEquals("bypassPermissions", DraftModes.normalize("opencode", "bypassPermissions"))
        assertEquals("plan", DraftModes.normalize("opencode", "plan"))
        assertEquals("a provider with no Mode row keeps it (its frame ignores it)", "anything", DraftModes.normalize("pi", "anything"))
        assertFalse(DraftModes.selectable("claude", "dontAsk"))
        assertFalse(DraftModes.selectable("opencode", ""))
        assertFalse(DraftModes.selectable("pi", "default"))
        assertTrue(DraftModes.selectable("opencode", "bypassPermissions"))
    }

    // --- the options model --------------------------------------------------------------------------------

    private fun form(key: String, model: String = "", effort: String = "", mode: String = ""): JsObj =
        DraftForm.INITIAL_DRAFT_FORM.with("key" to JsStr(key), "model" to JsStr(model), "reasoningEffort" to JsStr(effort), "mode" to JsStr(mode))

    private fun options(key: String, model: String = "", effort: String = "", mode: String = "") =
        DraftSessionOptionsModel.of(DraftComposerState(form = form(key, model, effort, mode), entries = catalog))

    @Test
    fun claudeOnAutoIsTheDangerRowAndNamedInWords() {
        val o = options("claude", "opus", mode = "bypassPermissions")
        assertEquals("bypassPermissions", o.mode!!.value)
        assertEquals("Auto", o.mode!!.current!!.label)
        assertTrue(o.modeDanger)
        assertEquals("Permission mode", o.modeAriaLabel)
        assertNull("Claude has no separate Auto chip", o.auto)
        assertEquals("Auto", o.elevatedLabel)
        val manual = options("claude", "opus", mode = "default")
        assertFalse(manual.modeDanger)
        assertNull(manual.elevatedLabel)
    }

    @Test
    fun codexShowsItsThreePresetsAndFullAccessIsTheDangerOne() {
        val o = options("codex", "gpt-5", mode = "full-access")
        assertEquals(listOf("default", "auto-review", "full-access"), o.mode!!.options.map { it.value })
        assertEquals("Codex mode", o.modeAriaLabel)
        assertTrue(o.modeDanger)
        assertEquals("Full access", o.elevatedLabel)
        assertNull(o.auto)
        assertFalse(options("codex", "gpt-5", mode = "auto-review").modeDanger)
    }

    @Test
    fun opencodeShowsBuildAndPlanAndTheAutoChip() {
        val cold = options("opencode", "oc", mode = "")
        assertEquals("the untouched mode shows as Build", "default", cold.mode!!.value)
        assertEquals(false, cold.auto!!.on)
        assertNull(cold.elevatedLabel)
        val auto = options("opencode", "oc", mode = "bypassPermissions")
        assertEquals("Auto on shows its select on Build (modeDisplay)", "default", auto.mode!!.value)
        assertTrue(auto.auto!!.on)
        assertFalse("Build is not a danger row", auto.modeDanger)
        assertEquals("Auto", auto.elevatedLabel)
        assertEquals("plan", options("opencode", "oc", mode = "plan").mode!!.value)
    }

    @Test
    fun otherProvidersHaveNoModeRowAndNothingPickedHasNoControls() {
        val pi = options("pi", "pi-1")
        assertNull(pi.mode)
        assertNull(pi.auto)
        assertTrue("Effort still shows from the model's variants", pi.effort != null)
        assertEquals(DraftSessionOptions.None, options(""))
        assertFalse(options("").hasSettings)
    }

    @Test
    fun effortShowsOnlyWhenTheModelHasAVariantAndReadsDefaultUntouched() {
        val opus = options("claude", "opus")
        assertEquals(listOf("low", "medium", "high"), opus.effort!!.options.map { it.value })
        assertNull("untouched: no row is current (the pill reads Default)", opus.effort!!.current)
        assertNull("a model with no variant hides Effort", options("claude", "haiku").effort)
        // issue #44: one variant is enough.
        assertEquals(listOf("high"), options("codex", "gpt-5").effort!!.options.map { it.value })
        // An untouched model ("") uses the default row, else the first row.
        assertEquals(listOf("low", "medium", "high"), options("claude", "").effort!!.options.map { it.value })
        assertEquals("high", options("claude", "opus", effort = "high").effort!!.current!!.label.lowercase())
    }

    @Test
    fun hostileVariantLabelsAreDrawnByTheLabelRule() {
        val evil = ProviderCatalogEntry(
            "claude", "claude", "ready",
            listOf(SessionModelOption("opus", "Opus", variants = listOf(ModelVariantOption("x", "‮" + "A".repeat(5_000)), ModelVariantOption("y‮", "")))),
        )
        val o = DraftSessionOptionsModel.of(DraftComposerState(form = form("claude", "opus"), entries = listOf(evil)))
        for (option in o.effort!!.options) {
            assertFalse(option.label.contains('‮'))
            assertTrue(option.label.length <= LabelText.MAX_LABEL + 10)
            assertTrue("never blank", option.label.isNotEmpty())
        }
        assertEquals("the value is kept raw for the wire", "y‮", o.effort!!.options[1].value)
    }

    // --- the engine: picks, preferences, the create -----------------------------------------------------

    private class Client : StubClient() {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val consentOrigin = MutableStateFlow<String?>(A)
        override val linkEpoch = MutableStateFlow(1L)
        override val providers = MutableStateFlow(
            listOf(ProviderInfo("claude", "Claude", "C", true), ProviderInfo("codex", "Codex", "X", true), ProviderInfo("opencode", "OpenCode", "O", true), ProviderInfo("pi", "Pi", "P", true)),
        )
        override val providerCatalog = MutableStateFlow(catalog)
        override val providerCatalogLive = MutableStateFlow(true)
        override val workspaceRoot = MutableStateFlow<String?>("/root")
        override val createdSessions = MutableStateFlow<CreatedReply?>(null)
        override val createErrors = MutableStateFlow<CreateErrorReply?>(null)
        val frames = mutableListOf<ClientMessage.Create>()

        override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
            if (expectedOrigin != consentOrigin.value) return NewSessionResult.NotLive
            frames += NewSessionGuard.resolve(request, providerCatalog.value, providers.value) ?: return NewSessionResult.NotOffered
            return NewSessionResult.Sent
        }
    }

    private class H(val client: Client, val model: DraftComposerModel, val store: DraftStore)

    private fun TestScope.harness(store: DraftStore = InMemoryDraftStore(), origin: String = A): H {
        val client = Client()
        var n = 0
        val model = DraftComposerModel(client, store, backgroundScope, currentWorkspace = { "/w" }, newRequestId = { "req-${++n}" })
        model.onOrigin(origin)
        runCurrent()
        model.refresh()
        return H(client, model, store)
    }

    private fun H.mode() = (model.state.value.form["mode"] as? JsStr)?.value
    private fun H.effort() = (model.state.value.form["reasoningEffort"] as? JsStr)?.value

    private fun H.send(): ClientMessage.Create {
        model.setText("go")
        assertEquals(DraftSubmitResult.Sent, model.submit(A))
        return client.frames.last()
    }

    private suspend fun DraftStore.prefsOf(origin: String, key: String): JsValue? =
        (readDraftPreferences(origin)["providerPreferences"] as? JsObj)?.get(key)

    @Test
    fun theDraftStartsOnTheWebsDefaultsPerProvider() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("claude", "opus")
        assertEquals("Claude starts on Auto", "bypassPermissions", h.mode())
        h.model.selectProviderAndModel("codex", "gpt-5")
        assertEquals("Codex starts on Default permissions", "default", h.mode())
        h.model.selectProviderAndModel("opencode", "oc")
        assertEquals("opencode starts untouched (Build)", "", h.mode())
        h.model.selectProviderAndModel("pi", "pi-1")
        assertEquals("", h.mode())
    }

    @Test
    fun aPickedModeAndEffortRideTheCreateAndAreRememberedForTheRowOnThisServer() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("work", "opus")
        assertTrue(h.model.selectMode("acceptEdits", "claude"))
        assertTrue(h.model.selectEffort("high"))
        val frame = h.send()
        assertEquals("acceptEdits", frame.permissionMode)
        assertEquals("high", frame.reasoningEffort)
        assertEquals("work", frame.profileId)
        assertNull(frame.sandboxPolicy)
        runCurrent()
        val stored = h.store.prefsOf(A, "work") as JsObj
        assertEquals(JsStr("acceptEdits"), stored["mode"])
        assertEquals(JsStr("high"), stored["reasoningEffort"])
        assertNull("only the row picked on", h.store.prefsOf(A, "claude"))
        assertEquals("nothing on another server", JsObj.EMPTY, h.store.readDraftPreferences(B))
    }

    @Test
    fun aRememberedChoiceSeedsTheNextDraftOnTheSameServerOnly() = runTest {
        val store = InMemoryDraftStore()
        val first = harness(store)
        first.model.selectProviderAndModel("codex", "gpt-5")
        first.model.selectMode("auto-review", "codex")
        runCurrent()
        val again = harness(store)
        again.model.selectProviderAndModel("codex", "gpt-5")
        assertEquals("auto-review", again.mode())
        assertEquals("auto_review", again.send().approvalsReviewer?.value)
        val elsewhere = harness(store, origin = B)
        elsewhere.model.selectProviderAndModel("codex", "gpt-5")
        assertEquals("server B never sees A's choice", "default", elsewhere.mode())
    }

    @Test
    fun anEffortIsSentOnlyWhenPickedInThisDraft() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("claude", "opus")
        assertEquals("", h.effort())
        assertNull("untouched: the engine's default", h.send().reasoningEffort)
    }

    @Test
    fun aPickOutsideTheOfferedSetsChangesNothing() = runTest {
        val h = harness()
        assertFalse("no row picked", h.model.selectMode("plan", "claude"))
        h.model.selectProviderAndModel("claude", "opus")
        assertFalse(h.model.selectMode("dontAsk", "claude"))
        assertFalse(h.model.selectMode("full-access", "claude"))
        assertFalse(h.model.selectMode("", "claude"))
        assertFalse(h.model.selectEffort("max"))
        assertFalse(h.model.selectEffort(""))
        assertEquals("bypassPermissions", h.mode())
        assertEquals("", h.effort())
        h.model.selectModel("haiku")
        assertFalse("haiku offers no effort", h.model.selectEffort("high"))
        h.model.selectProviderAndModel("pi", "pi-1")
        assertFalse("pi has no Mode row", h.model.selectMode("plan", "pi"))
        runCurrent()
        val claude = h.store.prefsOf(A, "claude") as? JsObj
        assertNull("nothing refused was remembered", claude?.get("mode"))
        assertNull(claude?.get("reasoningEffort"))
    }

    @Test
    fun theOpencodeAutoChipIsBuildPlusApprovalNever() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("opencode", "oc")
        assertTrue(h.model.toggleAuto("opencode"))
        assertEquals("bypassPermissions", h.mode())
        val on = h.send()
        assertEquals("bypassPermissions", on.permissionMode)
        assertEquals("never", on.approvalPolicy?.value)
        h.client.createErrors.value = CreateErrorReply("no", 1, "req-1")
        h.model.onCreateError(h.client.createErrors.value!!)
        assertTrue(h.model.toggleAuto("opencode"))
        assertEquals("off is Build", "default", h.mode())
        val off = h.send()
        assertEquals("default", off.permissionMode)
        assertNull(off.approvalPolicy)
        h.model.selectProviderAndModel("claude", "opus")
        assertFalse("no chip for Claude", h.model.toggleAuto("claude"))
    }

    // --- the preferences fallback ---------------------------------------------------------------------

    private suspend fun TestScope.seeded(prefs: JsObj): H {
        val store = InMemoryDraftStore()
        store.writeDraftPreferences(A, prefs)
        return harness(store)
    }

    private fun providerPrefs(vararg rows: Pair<String, JsValue>) = JsObj.of("providerPreferences" to JsObj.of(*rows))

    @Test
    fun aStoredModeThatNoLongerExistsFallsBackToTheDefault() = runTest {
        val h = seeded(
            providerPrefs(
                "claude" to JsObj.of("mode" to JsStr("dontAsk")),
                "codex" to JsObj.of("mode" to JsStr("off")),
                "opencode" to JsObj.of("mode" to JsStr("acceptEdits")),
            ),
        )
        h.model.selectProviderAndModel("claude", "opus")
        assertEquals("the retired Locked is Manual (r2, verifier F2)", "default", h.mode())
        assertEquals("default", h.send().permissionMode)
        h.client.createErrors.value = CreateErrorReply("no", 1, "req-1")
        h.model.onCreateError(h.client.createErrors.value!!)
        h.model.selectProvider("codex")
        assertEquals("default", h.mode())
        h.model.selectProvider("opencode")
        assertEquals("", h.mode())
    }

    @Test
    fun aStoredPreferenceForAProviderThatNoLongerExistsIsNeverUsed() = runTest {
        val h = seeded(providerPrefs("gone" to JsObj.of("mode" to JsStr("plan")), "claude" to JsObj.of("mode" to JsStr("plan"))))
        h.model.selectProviderAndModel("work", "opus")
        assertEquals("the work row's own default, not another row's choice", "bypassPermissions", h.mode())
    }

    @Test
    fun garbagePreferencesNeverProduceAFrameValueOutsideTheKnownSets() = runTest {
        val garbage: List<JsObj> = listOf(
            providerPrefs("claude" to JsObj.of("mode" to JsNum(42.0), "reasoningEffort" to JsBool.TRUE, "model" to JsArr.EMPTY, "autoMode" to JsStr("yes"))),
            providerPrefs("claude" to JsStr("junk")),
            providerPrefs("claude" to JsArr.of(listOf(JsStr("plan")))),
            JsObj.of("providerPreferences" to JsStr("junk")),
            JsObj.of("providerPreferences" to JsArr.EMPTY, "customModels" to JsNum(1.0)),
            providerPrefs("claude" to JsObj.of("mode" to JsStr(" plan‮"), "reasoningEffort" to JsStr("max"), "model" to JsStr("\u0000"))),
            providerPrefs("claude" to JsObj.of("mode" to JsStr("bypassPermissions\u0000"), "reasoningEffort" to JsStr("high "))),
        )
        for (prefs in garbage) {
            val h = seeded(prefs)
            h.model.selectProvider("claude")
            val mode = h.mode()
            assertTrue("$prefs -> $mode", mode in setOf("default", "acceptEdits", "plan", "bypassPermissions"))
            val frame = h.send()
            assertTrue("$prefs -> ${frame.permissionMode}", frame.permissionMode in setOf("default", "acceptEdits", "plan", "bypassPermissions"))
            assertNull(frame.sandboxPolicy)
            assertTrue("$prefs -> ${frame.reasoningEffort}", frame.reasoningEffort == null || frame.reasoningEffort in setOf("low", "medium", "high"))
            assertNull(frame.approvalPolicy)
            // A pick on top of the garbage is remembered without a crash, and is valid.
            assertTrue(h.model.selectMode("plan", "claude"))
            runCurrent()
            assertEquals(JsStr("plan"), (h.store.prefsOf(A, "claude") as JsObj)["mode"])
        }
    }

    // --- r2 ------------------------------------------------------------------------------------------

    private fun H.push(entries: List<ProviderCatalogEntry>) {
        client.providerCatalog.value = entries
        model.refresh()
    }

    /** `work` re-extended to opencode (the operator edited the profile). */
    private val workAsOpencode = catalog.map { if (it.key == "work") ProviderCatalogEntry("work", "opencode", "ready", listOf(SessionModelOption("oc", "OC")), label = "Work", profileId = "work") else it }

    @Test
    fun aPickRecordsTheProviderItWasMadeFor() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("work", "opus")
        assertTrue(h.model.selectMode("plan", "claude"))
        runCurrent()
        val row = h.store.prefsOf(A, "work") as JsObj
        assertEquals(JsStr("plan"), row["mode"])
        assertEquals(JsStr("claude"), row[DraftComposerModel.PREF_PROVIDER])
    }

    /** r2 (security F1): a stored Auto made for Claude never becomes opencode's Auto chip. */
    @Test
    fun aStoredModeFollowsTheProviderNotTheRowKey() = runTest {
        val stored = providerPrefs("work" to JsObj.of("mode" to JsStr("bypassPermissions"), DraftComposerModel.PREF_PROVIDER to JsStr("claude")))
        // Positive control: still Claude, the stored choice applies.
        val same = seeded(providerPrefs("work" to JsObj.of("mode" to JsStr("plan"), DraftComposerModel.PREF_PROVIDER to JsStr("claude"))))
        same.model.selectProvider("work")
        assertEquals("plan", same.mode())
        // The profile now extends opencode: its stored mode is absent, opencode's default applies.
        val h = seeded(stored)
        h.push(workAsOpencode)
        h.model.selectProvider("work")
        assertEquals("opencode's default (Build), not its Auto", "", h.mode())
        val frame = h.send()
        assertEquals("opencode", frame.provider)
        assertNull("no approvalPolicy never", frame.approvalPolicy)
        assertEquals("bypassPermissions", frame.permissionMode)
        // A record with no provider at all is absent too (none was ever written by a release).
        val legacy = seeded(providerPrefs("work" to JsObj.of("mode" to JsStr("bypassPermissions"))))
        legacy.push(workAsOpencode)
        legacy.model.selectProvider("work")
        assertEquals("", legacy.mode())
    }

    @Test
    fun aRetiredRestrictiveModeWithNoProviderStillLandsOnManual() = runTest {
        val h = seeded(providerPrefs("claude" to JsObj.of("mode" to JsStr("dontAsk"), "autoMode" to JsBool.TRUE)))
        h.model.selectProvider("claude")
        assertEquals("default", h.mode())
        assertEquals("default", h.send().permissionMode)
    }

    /** r2 (security F1): a tap on rows drawn for Claude that lands after the row became opencode. */
    @Test
    fun aTapDrawnForAnotherProviderChangesNothing() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("work", "opus")
        val drawn = DraftSessionOptionsModel.of(h.model.state.value)
        assertEquals("claude", drawn.provider)
        h.push(workAsOpencode)
        assertFalse("Claude's Auto row, tapped late", h.model.selectMode("bypassPermissions", drawn.provider))
        assertFalse(h.model.toggleAuto(drawn.provider))
        assertEquals("", h.mode())
        assertNull(h.send().approvalPolicy)
        runCurrent()
        assertNull("nothing remembered", (h.store.prefsOf(A, "work") as? JsObj)?.get("mode"))
        // Control: drawn for the provider the row is now, it is taken.
        h.client.createErrors.value = CreateErrorReply("no", 1, "req-1")
        h.model.onCreateError(h.client.createErrors.value!!)
        assertTrue(h.model.toggleAuto("opencode"))
        assertEquals("never", h.send().approvalPolicy?.value)
    }

    /** r2 (security F2): only efforts the server's bound admits, at most MAX_ITEMS of them. */
    @Test
    fun effortsAreHeldToTheServersBound() {
        val edge = "é".repeat(100) // 200 bytes
        val entry = ProviderCatalogEntry(
            "claude", "claude", "ready",
            listOf(SessionModelOption("opus", "Opus", variants = listOf(ModelVariantOption("", "Empty"), ModelVariantOption("x".repeat(201), "Huge"), ModelVariantOption(edge + "x", "Over"), ModelVariantOption(edge, "Edge"), ModelVariantOption("high", "High")))),
        )
        assertEquals(listOf(edge, "high"), DraftSessionOptionsModel.offeredEfforts(entry, "opus").map { it.value })
        val o = DraftSessionOptionsModel.of(DraftComposerState(form = form("claude", "opus"), entries = listOf(entry)))
        assertEquals(listOf(edge, "high"), o.effort!!.options.map { it.value })
        val many = ProviderCatalogEntry("claude", "claude", "ready", listOf(SessionModelOption("opus", "Opus", variants = (1..100_000).map { ModelVariantOption("v$it", "V$it") })))
        val offered = DraftSessionOptionsModel.offeredEfforts(many, "opus")
        assertEquals(LabelText.MAX_ITEMS, offered.size)
        assertEquals(LabelText.MAX_ITEMS, DraftSessionOptionsModel.of(DraftComposerState(form = form("claude", "opus"), entries = listOf(many))).effort!!.options.size)
        // The frame refuses a value past the cap and an overlong one.
        val picked = DraftForm.INITIAL_USER_MODIFIED.with("reasoningEffort" to JsBool.TRUE)
        assertNull(CreateFrame.build(form("claude", "opus", effort = "v250").put("cwd", JsStr("/w")), many, picked, "r").reasoningEffort)
        assertEquals("v200", CreateFrame.build(form("claude", "opus", effort = "v200").put("cwd", JsStr("/w")), many, picked, "r").reasoningEffort)
        assertNull(CreateFrame.build(form("claude", "opus", effort = edge + "x").put("cwd", JsStr("/w")), entry, picked, "r").reasoningEffort)
    }

    /** r2 (verifier F1): the picked model leaves the live catalog: Effort hides and nothing is dropped silently. */
    @Test
    fun anEffortThatCanBePickedIsAnEffortThatIsSent() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("claude", "opus")
        assertTrue(DraftSessionOptionsModel.of(h.model.state.value).effort != null)
        // The server drops opus from the row (haiku has no variant, so the first-row fallback would show one).
        h.push(catalog.map { if (it.key == "claude") it.copy(models = listOf(SessionModelOption("sonnet", "Sonnet", variants = variants), SessionModelOption("haiku", "Haiku"))) else it })
        assertEquals("the pick is kept (userModified)", "opus", (h.model.state.value.form["model"] as JsStr).value)
        assertNull("no Effort control for a model the row no longer lists", DraftSessionOptionsModel.of(h.model.state.value).effort)
        assertFalse(h.model.selectEffort("high"))
        assertNull(h.send().reasoningEffort)
    }

    /** r2 (verifier F1), as a property: the Effort control lists exactly what the frame would send. */
    @Test
    fun theEffortControlAndTheFrameAgreeForEveryModel() {
        val picked = DraftForm.INITIAL_USER_MODIFIED.with("reasoningEffort" to JsBool.TRUE)
        for (entry in catalog) for (model in listOf("", "opus", "haiku", "gpt-5", "oc", "pi-1", "gone", "custom")) for (effort in listOf("low", "medium", "high", "max")) {
            val f = form(entry.key, model, effort).put("cwd", JsStr("/w"))
            val shown = DraftSessionOptionsModel.of(entry, f).effort?.options.orEmpty().map { it.value }
            val sent = CreateFrame.build(f, entry, picked, "r").reasoningEffort
            assertEquals("${entry.key}/$model/$effort", effort in shown, sent == effort)
        }
    }
}
