package com.tether.app.client

import com.tether.app.client.SessionControlsGuardTest.Companion.codexSnapshot
import com.tether.app.client.SessionControlsGuardTest.Companion.controls
import com.tether.app.client.SessionControlsGuardTest.Companion.session
import com.tether.app.protocol.ModeOption
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.CollaborationMode
import com.tether.app.protocol.model.CollaborationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T7.2: the row derivations (chat-view.tsx:2128-2495, 2886-2955, 3704-3776 at tether 7d65611). */
class ComposerControlsModelTest {

    private val opus = SessionModelOption(
        "claude-opus-5[1m]", "Opus (1M context)",
        variants = listOf(ModelVariantOption("low", "low"), ModelVariantOption("high", "high")), supportsFastMode = true,
    )
    private val sonnet = SessionModelOption("claude-sonnet-5", "Sonnet")
    private val legacyA = SessionModelOption("claude-opus-4-1", "Opus 4.1", legacy = true)
    private val legacyB = SessionModelOption("claude-sonnet-4", "Sonnet 4", legacy = true)
    private val claudeControls = controls(listOf(opus, sonnet, legacyA, legacyB), defaultModel = opus.value, defaultReasoningEffort = "high")

    private fun derive(
        s: com.tether.app.protocol.model.AgentSession,
        c: com.tether.app.protocol.ServerMessage.SessionControls?,
        codex: ProviderControlsState<CodexSnapshot>? = null,
        pins: List<String> = emptyList(),
        opencode: ProviderControlsState<OpencodeSnapshot>? = null,
    ) = ComposerControlsModel.derive(s, c, codex, pins, opencode)

    @Test
    fun claudeShowsTheAppliedDefaultModelEffortAndManual() {
        val row = derive(session("claude"), claudeControls)
        assertTrue(row.live)
        assertEquals(opus.value, row.model!!.value)
        assertEquals("Opus (1M context)", row.model!!.label)
        // v44: the applied default effort, no synthesized "Default" row when it is known.
        assertEquals("high", row.effort!!.value)
        assertEquals(listOf("low", "high"), row.effort!!.options.map { it.value })
        assertEquals("default", row.mode!!.value)
        assertEquals(listOf("default", "acceptEdits", "plan", "bypassPermissions"), row.mode!!.options.map { it.value })
        assertEquals(listOf(false, false, false, true), row.mode!!.options.map { it.danger })
        assertEquals("Prompts before every gated tool (Bash, Write, Edit…)", row.hint)
        assertFalse(row.hintDanger)
        assertNull("Claude's Auto is a Mode row (v100), no toggle", row.auto)
        assertEquals("Permission mode", row.modeAriaLabel)
        assertEquals(FastModeControl("off", null), row.fastMode)
    }

    @Test
    fun anUnknownEffortGetsTheDefaultRowAndBypassReadsAsDanger() {
        val row = derive(session("claude").copy(permissionMode = "bypassPermissions"), controls(listOf(opus), defaultModel = opus.value))
        assertEquals("", row.effort!!.value)
        assertEquals(listOf("", "low", "high"), row.effort!!.options.map { it.value })
        assertEquals("Default", row.effort!!.label)
        assertEquals("Auto", row.mode!!.label)
        assertTrue(row.hintDanger)
    }

    @Test
    fun anUnknownStoredModeIsShownAsUnknownWithAWarningNeverAsManual() {
        // Round 2 (M1): a removed (dontAsk) or newer mode keeps its value and warns.
        for (stored in listOf("dontAsk", "someFutureMode")) {
            val row = derive(session("claude").copy(permissionMode = stored), claudeControls)
            assertEquals(stored, row.mode!!.value)
            assertEquals("Unknown mode ($stored)", row.mode!!.label)
            assertTrue(row.mode!!.current!!.danger)
            assertTrue("shown, never selectable", row.mode!!.current!!.disabled)
            assertEquals(ComposerControlsModel.UNKNOWN_MODE_HINT, row.hint)
            assertTrue(row.hintDanger)
            assertTrue(row.unknownMode)
        }
        // An opencode agent the static fallback (no controls reply yet) does not list.
        val run = derive(session("opencode").copy(permissionMode = "review"), null)
        assertEquals("Unknown mode (review)", run.mode!!.label)
        assertTrue(run.unknownMode)
        // An opencode approval policy that is neither null nor "never".
        val policy = derive(session("opencode", engine = OPENCODE_V2).copy(approvalPolicy = "on-request"), null)
        assertEquals("Unknown approval policy (on-request)", policy.mode!!.label)
        assertTrue(policy.hintDanger)
        assertTrue(policy.unknownMode)
        // Known values stay as they were.
        assertFalse(derive(session("claude").copy(permissionMode = "plan"), claudeControls).unknownMode)
        assertFalse(derive(session("opencode", engine = OPENCODE_V2).copy(approvalPolicy = "never"), null).unknownMode)
    }

    @Test
    fun opencodeAgentsShowTheirValueWhenTheLabelCouldMislead() {
        assertEquals("Build", ComposerControlsModel.opencodeAgentLabel("default", "Build"))
        assertEquals("Plan", ComposerControlsModel.opencodeAgentLabel("plan", "Plan"))
        assertEquals("Plan (planx)", ComposerControlsModel.opencodeAgentLabel("planx", "Plan"))
        assertEquals("Build (yolo)", ComposerControlsModel.opencodeAgentLabel("yolo", "Build"))
        // Round 4 (P3): case-sensitive — only an exact label/value match stays bare.
        assertEquals("Review (review)", ComposerControlsModel.opencodeAgentLabel("review", "Review"))
        assertEquals("Review (REVIEW)", ComposerControlsModel.opencodeAgentLabel("REVIEW", "Review"))
        assertEquals("review", ComposerControlsModel.opencodeAgentLabel("review", "review"))
        assertTrue(ComposerControlsModel.opencodeAgentLabel("review", "Review") != ComposerControlsModel.opencodeAgentLabel("REVIEW", "Review"))
        assertEquals("Unknown approval policy (on\\u{200B}request)", derive(session("opencode", engine = OPENCODE_V2).copy(approvalPolicy = "on\u200Brequest"), null).mode!!.label)
        assertEquals("Reviewer (review)", ComposerControlsModel.opencodeAgentLabel("review", "Reviewer"))
        assertEquals("review", ComposerControlsModel.opencodeAgentLabel("review", ""))
        // Round 3 (N-M1): the built-in check is on the raw value; hidden characters are spelled out.
        assertEquals("Plan (PLAN)", ComposerControlsModel.opencodeAgentLabel("PLAN", "Plan"))
        assertEquals("Plan (plan\\u{200B})", ComposerControlsModel.opencodeAgentLabel("plan\u200B", "Plan"))
        assertEquals("Custom agent (plan\\u{200B})", ComposerControlsModel.opencodeAgentLabel("plan\u200B", ""))
        assertEquals("Plan (Plan)", ComposerControlsModel.opencodeAgentLabel("Plan", "Plan"))
        assertEquals("Custom agent (PLAN)", ComposerControlsModel.opencodeAgentLabel("PLAN", ""))
        assertEquals("plan", ComposerControlsModel.opencodeAgentLabel("plan", ""))
        assertEquals("Review (review\\u{200B})", ComposerControlsModel.opencodeAgentLabel("review\u200B", "Review"))
        val longValue = "plan" + "\u200B".repeat(200)
        assertTrue(ComposerControlsModel.opencodeAgentLabel(longValue, "Plan").contains("\\u{200B}"))
        val row = derive(
            session("opencode"),
            controls(modes = listOf(ModeOption("default", "Build", ""), ModeOption("planx", "Plan", "Plans"))),
        )
        val planx = row.mode!!.options.single { it.value == "planx" }
        assertEquals("Plan (planx)", planx.label)
        assertTrue("absent danger on a custom agent reads as danger", planx.danger)
        assertFalse(row.mode!!.options.single { it.value == "default" }.danger)
    }

    @Test
    fun legacyModelsGroupUnlessPinned() {
        val row = derive(session("claude"), claudeControls, pins = listOf(legacyB.value))
        assertEquals(listOf(opus.value, sonnet.value, legacyB.value), row.model!!.options.map { it.value })
        assertEquals(listOf(legacyA.value), row.model!!.legacy.map { it.value })
        assertEquals(ComposerControlsModel.LEGACY_NOTE, row.model!!.legacyNote)
        // The label of the selected row is found in the legacy group too.
        assertEquals("Opus 4.1", derive(session("claude").copy(model = legacyA.value), claudeControls).model!!.label)
    }

    @Test
    fun legacyRowsAreMarkedPinnedOrPinnableAsTheWebMarksThem() {
        // ta-coik.55: lib/model-picker.mjs 90fbb9f :509-510 — pinned legacy rows `pinned`, the group's
        // rows `pinnable`, advertised rows neither (so they get no pin key).
        val row = derive(session("claude"), claudeControls, pins = listOf(legacyB.value))
        val (pinned, plain) = row.model!!.options.partition { it.pinned }
        assertEquals(listOf(legacyB.value), pinned.map { it.value })
        assertTrue(plain.none { it.pinned || it.pinnable })
        assertTrue(row.model!!.legacy.all { it.pinnable && !it.pinned })
        // A pin set naming an advertised model leaves that row unmarked (only legacy rows are pinnable).
        assertTrue(derive(session("claude"), claudeControls, pins = listOf(opus.value)).model!!.options.none { it.pinned || it.pinnable })
    }

    @Test
    fun fastModeFollowsTheSelectedModelAndTheSessionsReport() {
        assertNull(derive(session("claude").copy(model = sonnet.value), claudeControls).fastMode)
        assertEquals(
            FastModeControl("cooldown", "network_error"),
            derive(session("claude").copy(fastModeState = "cooldown", fastModeDisabledReason = "network_error"), claudeControls).fastMode,
        )
    }

    @Test
    fun opencodeServeV2HasTheAutoToggle() {
        val serve = session("opencode", engine = OPENCODE_V2).copy(approvalPolicy = "never")
        val row = derive(serve, controls(modes = listOf(ModeOption("default", "Build", "b"), ModeOption("plan", "Plan", "p"))))
        assertEquals(AutoToggle(true, "Auto-approves permission requests that are not explicitly denied"), row.auto)
        assertEquals("default", row.mode!!.value) // Auto is not a Mode row for opencode
        assertEquals(row.auto!!.hint, row.hint)
        assertTrue(row.hintDanger)
        assertEquals("Mode", row.modeAriaLabel)
        assertNull("opencode run-v1 has no toggle", derive(session("opencode"), null).auto)
    }

    @Test
    fun codexV2UsesItsCatalogsWithPlaceholdersBeforeTheyArrive() {
        val codex = session("codex", engine = CODEX_V2)
        val loading = derive(codex, null, ProviderControlsState(null, true, null))
        assertEquals(listOf("Loading…"), loading.model!!.options.map { it.label })
        assertFalse(loading.model!!.enabled)
        assertFalse(loading.mode!!.enabled)
        val row = derive(codex.copy(model = "gpt-5.5-mini"), null, ProviderControlsState(codexSnapshot(), false, null))
        assertEquals("gpt-5.5-mini", row.model!!.value)
        assertEquals("low", row.effort!!.value) // the selected model's default effort
        assertEquals(listOf("low", "xhigh"), row.effort!!.options.map { it.value })
        assertEquals("default", row.mode!!.value) // no applied mode: Default, not the first row (Plan)
        assertEquals(AutoToggle(false, "Runs everything without asking (never prompts)"), row.auto)
        assertEquals("", row.hint)
        val planned = derive(codex.copy(collaborationMode = CollaborationMode("plan", CollaborationSettings(null, null))), null, ProviderControlsState(codexSnapshot(), false, null))
        assertEquals("plan", planned.mode!!.value)
    }

    @Test
    fun legacyCodexAndReadOnlySessionsShowWhatWasRestored() {
        val legacy = derive(session("codex").copy(model = "gpt-5", reasoningEffort = "high"), null)
        assertFalse(legacy.live)
        assertTrue(legacy.legacyCodexHint)
        assertEquals(RestoredSettings("gpt-5", "high", null), legacy.restored)
        assertNull(legacy.model)
        val readOnly = derive(session("claude").copy(readOnly = true, permissionMode = "plan"), claudeControls)
        assertFalse(readOnly.live)
        assertFalse(readOnly.legacyCodexHint)
        assertEquals(RestoredSettings(null, null, "plan"), readOnly.restored)
        assertNull(derive(session("claude").copy(readOnly = true), claudeControls).restored)
    }

    @Test
    fun rawServerValuesNeverReachALabel() {
        // Round 3 (F1): the verifier's repro — an override character and 10k characters.
        val evil = "\u202E" + "x".repeat(10_000)
        val row = derive(session("claude").copy(model = evil), claudeControls)
        assertEquals(evil, row.model!!.value) // the value itself is untouched
        assertTrue(row.model!!.label.length <= LabelText.MAX_LABEL)
        assertFalse(row.model!!.label.contains('\u202E'))
        val effort = derive(session("claude").copy(reasoningEffort = evil), claudeControls).effort!!
        assertTrue(effort.label.length <= LabelText.MAX_LABEL && !effort.label.contains('\u202E'))
        val restored = derive(session("claude").copy(readOnly = true, model = evil, reasoningEffort = evil, permissionMode = evil), claudeControls).restored!!
        for (v in listOf(restored.model!!, restored.effort!!, restored.mode!!)) {
            assertTrue(v.length <= LabelText.MAX_LABEL)
            assertFalse(v.contains('\u202E'))
        }
        val unknown = derive(session("claude").copy(permissionMode = "plan\u200B"), claudeControls)
        assertEquals("Unknown mode (plan\\u{200B})", unknown.mode!!.label)
    }

    @Test
    fun opencodePermissionModeAutoWithoutThePolicyIsUnknown() {
        // Round 3 (N-L1): Auto for opencode is approvalPolicy "never", never a bare permissionMode.
        val row = derive(session("opencode", engine = OPENCODE_V2).copy(permissionMode = "bypassPermissions"), null)
        assertEquals("Unknown mode (bypassPermissions)", row.mode!!.label)
        assertTrue(row.unknownMode)
        assertTrue(row.hintDanger)
        assertFalse(derive(session("opencode", engine = OPENCODE_V2).copy(permissionMode = "bypassPermissions", approvalPolicy = "never"), null).unknownMode)
    }

    @Test
    fun theStaticOpencodeRowsTakeTheSameDangerRule() {
        // Round 3 (N-L3): before the controls reply, a snapshot flagging plan styles the fallback row.
        val flagged = OpencodeSnapshot.parse(
            SessionControlsGuardTest.json("""{"revision":"r","models":{"status":"ready","items":[]},"modes":{"status":"ready","items":[{"value":"plan","label":"Plan","hint":"","danger":true}]}}"""),
        )
        val row = derive(session("opencode", engine = OPENCODE_V2), null, opencode = ProviderControlsState(flagged, false, null))
        assertTrue(row.mode!!.options.single { it.value == "plan" }.danger)
        assertFalse(row.mode!!.options.single { it.value == "default" }.danger)
        assertFalse(derive(session("opencode"), null).mode!!.options.single { it.value == "plan" }.danger)
    }

    @Test
    fun dshHasOnlyAModelSelect() {
        val row = derive(session("dsh"), controls(listOf(SessionModelOption("dsh-1", "DSH"))))
        assertNotNull(row.model)
        assertNull(row.effort)
        assertNull(row.mode)
        assertNull(row.auto)
    }

    /** T6.6 (chat-view.tsx:2477-2482, 4331): Auto-continue rides the live row of Claude and Codex v2 only. */
    @Test
    fun autoContinueIsOnTheLiveRowOfClaudeAndCodexOnly() {
        assertEquals(AutoContinueControl(false), derive(session("claude"), claudeControls).autoContinue)
        assertEquals(AutoContinueControl(true), derive(session("claude").copy(autoContinueOnLimit = true), claudeControls).autoContinue)
        assertEquals(AutoContinueControl(false), derive(session("codex", CODEX_V2), null).autoContinue)
        // A legacy Codex thread has no live row; a read-only session none either.
        assertNull(derive(session("codex"), null).autoContinue)
        assertNull(derive(session("claude").copy(readOnly = true), claudeControls).autoContinue)
        for (other in listOf("opencode", "reasonix", "pi", "dsh")) assertNull(other, derive(session(other), null).autoContinue)
    }
}
