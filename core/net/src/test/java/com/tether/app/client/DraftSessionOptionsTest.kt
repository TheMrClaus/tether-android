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

    /**
     * ta-coik.4: a mode no Mode row lists (a retired `dontAsk`, an older build's value) is shown as
     * itself, as draft-composer.tsx:153 `modeDisplay` (its select draws the raw value), never turned
     * into the provider's default (the retired ta-xki clamp, the negative control).
     */
    @Test
    fun aModeNoRowListsIsShownAsItIs() {
        val locked = options("claude", "opus", mode = "dontAsk")
        assertEquals("dontAsk", locked.mode!!.value)
        assertNull("no row is current", locked.mode!!.current)
        assertEquals("dontAsk", locked.mode!!.label)
        assertFalse(locked.modeDanger)
        assertFalse("not the retired clamp's Manual", locked.mode!!.value == "default")
        assertEquals("off", options("codex", "gpt-5", mode = "off").mode!!.value)
        assertEquals("acceptEdits", options("opencode", "oc", mode = "acceptEdits").mode!!.value)
        // Positive control: a listed mode is its row.
        assertEquals("Plan", options("claude", "opus", mode = "plan").mode!!.current!!.label)
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

    /**
     * ta-coik.4: use-draft-composer.ts:227-245 selectEffort / selectMode take the value as it is and
     * remember it; the create carries it (the server validates). The retired ta-xki rule refused them.
     */
    @Test
    fun aPickIsTakenAsItIsAsOnTheWeb() = runTest {
        val h = harness()
        assertFalse("no row picked: no provider to send it to", h.model.selectMode("plan", "claude"))
        h.model.selectProviderAndModel("claude", "opus")
        assertTrue(h.model.selectMode("dontAsk", "claude"))
        assertTrue(h.model.selectEffort("max"))
        assertEquals("dontAsk", h.mode())
        assertEquals("max", h.effort())
        val frame = h.send()
        assertEquals("dontAsk", frame.permissionMode)
        assertEquals("max", frame.reasoningEffort)
        runCurrent()
        val claude = h.store.prefsOf(A, "claude") as JsObj
        assertEquals(JsStr("dontAsk"), claude["mode"])
        assertEquals(JsStr("max"), claude["reasoningEffort"])
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

    /** ta-coik.4: a stored mode rides as lib/draft-form.ts resolveMode resolves it (it trusts the preference). */
    @Test
    fun aStoredModeRidesAsTheReducerResolvesIt() = runTest {
        val h = seeded(
            providerPrefs(
                "claude" to JsObj.of("mode" to JsStr("dontAsk"), "autoMode" to JsBool.TRUE),
                "codex" to JsObj.of("mode" to JsStr("off")),
                "opencode" to JsObj.of("mode" to JsStr("acceptEdits")),
            ),
        )
        h.model.selectProviderAndModel("claude", "opus")
        assertEquals("the retired Locked as it is, not Manual", "dontAsk", h.mode())
        assertEquals("dontAsk", h.send().permissionMode)
        h.client.createErrors.value = CreateErrorReply("no", 1, "req-1")
        h.model.onCreateError(h.client.createErrors.value!!)
        h.model.selectProvider("codex")
        assertEquals("off", h.mode())
        assertEquals("codexModePreset degrades it, as on the web", "workspace-write", h.send().sandboxPolicy)
        h.client.createErrors.value = CreateErrorReply("no", 2, "req-2")
        h.model.onCreateError(h.client.createErrors.value!!)
        h.model.selectProvider("opencode")
        assertEquals("acceptEdits", h.mode())
        assertEquals("acceptEdits", h.send().permissionMode)
    }

    @Test
    fun aStoredPreferenceForAProviderThatNoLongerExistsIsNeverUsed() = runTest {
        val h = seeded(providerPrefs("gone" to JsObj.of("mode" to JsStr("plan")), "claude" to JsObj.of("mode" to JsStr("plan"))))
        h.model.selectProviderAndModel("work", "opus")
        assertEquals("the work row's own default, not another row's choice", "bypassPermissions", h.mode())
    }

    /** Garbage preferences never crash; the frame carries what the reducer resolved, as the web's would. */
    @Test
    fun garbagePreferencesRideAsTheWebResolvesThem() = runTest {
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
            val mode = h.mode().orEmpty()
            val frame = h.send()
            assertEquals("$prefs", mode.ifEmpty { "bypassPermissions" }, frame.permissionMode)
            assertNull(frame.sandboxPolicy)
            assertNull(frame.approvalPolicy)
            assertTrue(h.model.selectMode("plan", "claude"))
            runCurrent()
            assertEquals(JsStr("plan"), (h.store.prefsOf(A, "claude") as JsObj)["mode"])
        }
        // lib/draft-form.ts normalizeId: a non-string mode is "", so Claude's cold Auto; a string is trimmed, kept.
        val number = seeded(providerPrefs("claude" to JsObj.of("mode" to JsNum(42.0))))
        number.model.selectProvider("claude")
        assertEquals("bypassPermissions", number.mode())
        val odd = seeded(providerPrefs("claude" to JsObj.of("mode" to JsStr(" plan‮"))))
        odd.model.selectProvider("claude")
        assertEquals("plan‮", odd.mode())
    }

    // --- r2 ------------------------------------------------------------------------------------------

    private fun H.push(entries: List<ProviderCatalogEntry>) {
        client.providerCatalog.value = entries
        model.refresh()
    }

    /** `work` re-extended to opencode (the operator edited the profile). */
    private val workAsOpencode = catalog.map { if (it.key == "work") ProviderCatalogEntry("work", "opencode", "ready", listOf(SessionModelOption("oc", "OC")), label = "Work", profileId = "work") else it }

    /** ta-coik.4: a pick is stored as lib/draft-form.ts mergeDraftPreferences writes it (no app-side provider field). */
    @Test
    fun aPickIsStoredAsTheWebStoresIt() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("work", "opus")
        assertTrue(h.model.selectMode("plan", "claude"))
        runCurrent()
        val row = h.store.prefsOf(A, "work") as JsObj
        assertEquals(JsStr("plan"), row["mode"])
        assertEquals(setOf("model", "mode"), row.keys)
    }

    /** ta-coik.4: a stored mode follows the row key, as on the web (lib/draft-form.ts providerPreferences[key]). */
    @Test
    fun aStoredModeFollowsTheRowKeyAsOnTheWeb() = runTest {
        // The profile now extends opencode: its stored Auto applies to the row, as the web's reducer does.
        val h = seeded(providerPrefs("work" to JsObj.of("mode" to JsStr("bypassPermissions"), "provider" to JsStr("claude"))))
        h.push(workAsOpencode)
        h.model.selectProvider("work")
        assertEquals("bypassPermissions", h.mode())
        val frame = h.send()
        assertEquals("opencode", frame.provider)
        assertEquals("bypassPermissions", frame.permissionMode)
        assertEquals("use-draft-composer.ts:311,335: opencode's Auto", "never", frame.approvalPolicy?.value)
        // Positive control: an untouched row starts on its provider's default.
        val fresh = harness()
        fresh.push(workAsOpencode)
        fresh.model.selectProvider("work")
        assertEquals("", fresh.mode())
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

    /** ta-coik.4: the Effort control lists every variant the catalog sends (draft-composer.tsx:119-125), and the frame sends the pick. */
    @Test
    fun effortsAreTheCatalogsVariantsAsTheWebListsThem() {
        val over = "é".repeat(100) + "x" // 201 bytes
        val entry = ProviderCatalogEntry(
            "claude", "claude", "ready",
            listOf(SessionModelOption("opus", "Opus", variants = listOf(ModelVariantOption("", "Empty"), ModelVariantOption(over, "Over"), ModelVariantOption("high", "High")))),
        )
        assertEquals(listOf("", over, "high"), DraftSessionOptionsModel.offeredEfforts(entry, "opus").map { it.value })
        val many = ProviderCatalogEntry("claude", "claude", "ready", listOf(SessionModelOption("opus", "Opus", variants = (1..1_000).map { ModelVariantOption("v$it", "V$it") })))
        assertEquals(1_000, DraftSessionOptionsModel.offeredEfforts(many, "opus").size)
        val picked = DraftForm.INITIAL_USER_MODIFIED.with("reasoningEffort" to JsBool.TRUE)
        assertEquals("v250", CreateFrame.build(form("claude", "opus", effort = "v250").put("cwd", JsStr("/w")), many, picked, "r").reasoningEffort)
        assertEquals(over, CreateFrame.build(form("claude", "opus", effort = over).put("cwd", JsStr("/w")), entry, picked, "r").reasoningEffort)
    }

    /** The picked model leaves the live catalog: Effort falls back to the first row's variants (draft-composer.tsx:121-123). */
    @Test
    fun aModelTheRowNoLongerListsShowsTheFirstRowsEfforts() = runTest {
        val h = harness()
        h.model.selectProviderAndModel("claude", "opus")
        h.push(catalog.map { if (it.key == "claude") it.copy(models = listOf(SessionModelOption("sonnet", "Sonnet", variants = variants), SessionModelOption("haiku", "Haiku"))) else it })
        assertEquals("the pick is kept (userModified)", "opus", (h.model.state.value.form["model"] as JsStr).value)
        assertEquals(listOf("low", "medium", "high"), DraftSessionOptionsModel.of(h.model.state.value).effort!!.options.map { it.value })
        assertTrue(h.model.selectEffort("high"))
        assertEquals("high", h.send().reasoningEffort)
    }

    /** ta-coik.4, as a property: a picked, non-empty effort is sent for every model, listed or not. */
    @Test
    fun aPickedEffortIsSentForEveryModel() {
        val picked = DraftForm.INITIAL_USER_MODIFIED.with("reasoningEffort" to JsBool.TRUE)
        for (entry in catalog) for (model in listOf("", "opus", "haiku", "gpt-5", "oc", "pi-1", "gone", "custom")) for (effort in listOf("low", "medium", "high", "max")) {
            val f = form(entry.key, model, effort).put("cwd", JsStr("/w"))
            assertEquals("${entry.key}/$model/$effort", effort, CreateFrame.build(f, entry, picked, "r").reasoningEffort)
        }
    }
}
