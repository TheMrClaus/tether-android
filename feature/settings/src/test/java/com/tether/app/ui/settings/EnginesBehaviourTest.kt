package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.tether.app.client.EngineCard
import com.tether.app.client.ServerSetting
import com.tether.app.ui.settings.ServerFixtures.ORIGIN
import com.tether.app.ui.settings.ServerFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.ServerFixtures.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-dh1: the Engines tab (settings-dialog.tsx 887c222 :2107-2251) through the semantics tree: the
 * web's order and words, the switches (`headlessModes`, joined as the web joins it, the issue #86
 * rule), Scan again (`detect-engines` once, busy while in flight), Host config, and a home,
 * command or launch command written as the web's blur writes it (90fbb9f :2174, :2209, :2226):
 * Done, a focus loss or closing with an edit in the field, at once (ta-coik.5: no app-only
 * confirmation). Each write is waited for on the writer and asserted as the exact frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class EnginesBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)
    private var binding by mutableStateOf(ServerSettingsBinding.None)
    private var shown by mutableStateOf(true)
    private var settings = ServerFixtures.settingsJson()

    /** A writer whose every write the "server" applies and answers (a new frame, one more reply). */
    private fun answering() = RecordingWriter { patch ->
        settings = ServerFixtures.applied(settings, patch)
        binding = binding.copy(settings = ServerFixtures.view(settings, envForced = forced), replies = binding.replies + 1)
    }

    private var forced: Map<String, Boolean> = mapOf("stateDir" to true)

    private fun show(b: ServerSettingsBinding) {
        binding = b
        compose.setContent { if (shown) SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun showWith(writer: ServerSettingsWriter, view: com.tether.app.client.ServerSettingsView = ServerFixtures.view(settings, envForced = forced)) =
        show(ServerFixtures.binding(view = view, writer = writer))

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun textOf(t: String): String = tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun editable(s: ServerSetting): String = tag(ServerSettingsTags.input(s)).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** Flip a switch through its semantics action (the toggleable's OnClick), as TalkBack would. */
    private fun flip(node: SemanticsNodeInteraction) {
        node.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Type [value] into [s]'s field and press Done. */
    private fun typeAndDone(s: ServerSetting, value: String) {
        val field = tag(ServerSettingsTags.input(s)).performScrollTo()
        field.performTextReplacement(value)
        compose.waitForIdle()
        assertEquals("the edit reached the field", value, editable(s))
        field.performImeAction()
        compose.waitForIdle()
    }

    private fun waitForWrites(writer: RecordingWriter, n: Int) = compose.waitUntil(5_000) { writer.patches.size >= n }

    private fun frames(vararg settings: String) = settings.map { json("""{"type":"set-server-settings","settings":$it}""") }

    // ---- order and words -------------------------------------------------------------------------

    @Test fun theCardsFollowTheWebsOrderAndWords() {
        showWith(RecordingWriter())
        val all = texts()
        val order = listOf("Engines", EngineRows.CAPTION, "Scan again", "Claude Code", "Codex", "OpenCode", "Reasonix", "Pi", "DeepSeek Harness", "Claude accounts", "Custom providers", "Host config", "Share host config")
        val at = order.map { text -> all.indexOf(text).also { assertTrue("$text missing", it >= 0) } }
        assertEquals(at.sorted(), at)
        // Detection, as the card's subtitle says it.
        assertEquals("2.1.284 · host install", textOf(EngineTags.status(EngineCard.Claude)))
        assertEquals("0.159.0 · user PATH", textOf(EngineTags.status(EngineCard.Codex)))
        assertEquals("not found", textOf(EngineTags.status(EngineCard.Opencode)))
        assertEquals("1.39.4 · bundled", textOf(EngineTags.status(EngineCard.Reasonix)))
        assertEquals("installed · user PATH", textOf(EngineTags.status(EngineCard.Pi)))
        assertEquals("scanning…", textOf(EngineTags.status(EngineCard.Dsh)))
        // The home captions (issue #86: Claude's is optional), the command and launch rows.
        for (text in listOf("Optional — defaults to your real HOME", "Dedicated credential home", "Required — set a directory before enabling",
            "CLI binary name or path", "Launch command", "Detected home", "Use detected", "Enable Codex", "Codex command", "Claude Code launch command")) {
            assertTrue(text, all.contains(text))
        }
        assertTrue(all.any { it.startsWith("Full wrapper command — its last token is the claude binary, e.g. jean-claude run -- claude.") })
        assertTrue(all.any { it.startsWith("Not the same as Claude account sync above") && it.contains("TETHER_CLAUDE_HOME") })
        // The switches show headlessModes ("claude,codex").
        tag(EngineTags.switch(EngineCard.Claude)).assertIsOn()
        tag(EngineTags.switch(EngineCard.Codex)).assertIsOn()
        tag(EngineTags.switch(EngineCard.Pi)).assertIsOff()
        // The fields hold the server's values; Claude's empty home shows its detected home as the hint.
        assertEquals("codex", editable(ServerSetting.CodexCommand))
        assertEquals("/srv/homes/codex", editable(ServerSetting.CodexHome))
        assertTrue(all.contains("/home/op/.claude"))
        // ta-q6p drew Custom providers: no slot is left on this tab.
        assertEquals(0, compose.onAllNodesWithTag(SettingsTags.ComingSoon).fetchSemanticsNodes().size)
    }

    @Test fun beforeTheServerRepliesTheEnginesWait() {
        show(ServerFixtures.binding(view = null))
        tag(EngineTags.Section).assertExists()
        tag(ServerSettingsTags.Loading).assertExists()
        assertFalse(exists(EngineTags.Scan))
        assertFalse(exists(EngineTags.card(EngineCard.Claude)))
        assertFalse(exists(EngineTags.HostConfig))
    }

    @Test fun signedOutNoEngineIsDrawn() {
        show(ServerFixtures.binding(origin = null))
        assertFalse(exists(EngineTags.card(EngineCard.Codex)))
        assertFalse(exists(EngineTags.HostConfig))
    }

    // ---- the switches ------------------------------------------------------------------------------

    @Test fun aSwitchSendsTheJoinedListAndOnlyThatKey() {
        val writer = answering()
        showWith(writer)
        flip(tag(EngineTags.switch(EngineCard.Codex)))
        waitForWrites(writer, 1)
        tag(EngineTags.switch(EngineCard.Codex)).assertIsOff()
        flip(tag(EngineTags.switch(EngineCard.Claude)))
        waitForWrites(writer, 2)
        tag(EngineTags.switch(EngineCard.Claude)).assertIsOff()
        // Claude's empty home does not block turning it back on.
        flip(tag(EngineTags.switch(EngineCard.Claude)))
        waitForWrites(writer, 3)
        assertEquals(frames("""{"headlessModes":"claude"}""", """{"headlessModes":""}""", """{"headlessModes":"claude"}"""), writer.frames())
        assertTrue(writer.patches.all { it.second == ORIGIN })
    }

    @Test fun aListWithOtherModesKeepsThemAsTheWebDoes() {
        settings = ServerFixtures.settingsJson(overrides = mapOf("headlessModes" to " fake, claude ,", "piHome" to "/srv/homes/pi"))
        val writer = answering()
        showWith(writer)
        flip(tag(EngineTags.switch(EngineCard.Pi)))
        waitForWrites(writer, 1)
        assertEquals(frames("""{"headlessModes":"fake,claude,pi"}"""), writer.frames())
    }

    @Test fun issue86AMissingHomeBlocksTheOthersSwitchButNeverClaudes() {
        val writer = RecordingWriter()
        showWith(writer)
        tag(EngineTags.switch(EngineCard.Claude)).assertIsEnabled()
        val opencode = tag(EngineTags.switch(EngineCard.Opencode)).performScrollTo()
        opencode.assertIsNotEnabled()
        opencode.performClick()
        if (opencode.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) opencode.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun aHomeTheEnvironmentSetsUnblocksTheSwitch() {
        forced = mapOf("opencodeHome" to true)
        val writer = RecordingWriter()
        showWith(writer)
        tag(EngineTags.switch(EngineCard.Opencode)).assertIsEnabled()
        flip(tag(EngineTags.switch(EngineCard.Opencode)))
        waitForWrites(writer, 1)
        assertEquals(frames("""{"headlessModes":"claude,codex,opencode"}"""), writer.frames())
    }

    @Test fun envForcedValuesAreLockedAndNeverWritten() {
        forced = mapOf("headlessModes" to true, "codexCommand" to true, "codexHome" to true, "shareHostConfig" to true)
        val writer = RecordingWriter()
        showWith(writer)
        for (e in EngineCard.entries) tag(EngineTags.switch(e)).assertIsNotEnabled()
        tag(EngineTags.Scan).assertIsNotEnabled()
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).assertIsNotEnabled()
        tag(ServerSettingsTags.input(ServerSetting.CodexHome)).assertIsNotEnabled()
        // Each forced row: its lock and "Set by environment" (Codex home, Codex command, Share host config).
        assertEquals(3, compose.onAllNodesWithTag(SettingsTags.EnvLock, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(texts().count { it == ServerRowCopy.SET_BY_ENV } >= 3)
        // Taps on what is locked do nothing.
        tag(ServerSettingsTags.row(ServerSetting.ShareHostConfig)).performScrollTo().performClick()
        tag(EngineTags.switch(EngineCard.Pi)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
        assertEquals(emptyList<Any>(), writer.scans)
    }

    @Test fun shareHostConfigWritesAtOnce() {
        val writer = answering()
        showWith(writer)
        flip(tag(ServerSettingsTags.row(ServerSetting.ShareHostConfig)))
        waitForWrites(writer, 1)
        assertEquals(frames("""{"shareHostConfig":false}"""), writer.frames())
        tag(ServerSettingsTags.row(ServerSetting.ShareHostConfig)).assertIsOff()
    }

    // ---- Scan again ------------------------------------------------------------------------------

    @Test fun scanAgainSendsDetectEnginesOnceAndIsBusyUntilTheReply() {
        val writer = RecordingWriter()
        showWith(writer)
        val scan = tag(EngineTags.Scan).performScrollTo()
        scan.performClick()
        compose.waitUntil(5_000) { writer.scans.size == 1 }
        compose.waitForIdle()
        assertEquals(listOf(ORIGIN), writer.scans)
        // Busy: disabled, saying so; a second tap sends nothing.
        scan.assertIsNotEnabled()
        assertTrue(texts().contains(EngineRows.SCANNING))
        scan.performClick()
        compose.waitForIdle()
        assertEquals(1, writer.scans.size)
        // The reply (a server-settings frame, even an unchanged one) ends it.
        binding = binding.copy(replies = binding.replies + 1)
        compose.waitForIdle()
        scan.assertIsEnabled()
        assertTrue(texts().contains(EngineRows.SCAN))
        scan.performClick()
        compose.waitUntil(5_000) { writer.scans.size == 2 }
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun aDoubleTapInOneFrameScansOnce() {
        val writer = RecordingWriter()
        showWith(writer)
        val action = tag(EngineTags.Scan).performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            action()
            action()
        }
        compose.waitForIdle()
        assertEquals(listOf(ORIGIN), writer.scans)
    }

    @Test fun aScanWhoseReplyNeverComesStopsBeingBusy() {
        val writer = RecordingWriter()
        showWith(writer)
        tag(EngineTags.Scan).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { writer.scans.size == 1 }
        compose.waitForIdle()
        tag(EngineTags.Scan).assertIsNotEnabled()
        // From here the clock moves only by hand: just short of the timeout it is still busy.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(EngineRows.SCAN_TIMEOUT_MS - 1_000)
        tag(EngineTags.Scan).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(2_000)
        tag(EngineTags.Scan).assertIsEnabled()
        assertEquals(1, writer.scans.size)
    }

    // ---- what the server runs: written as the web's blur writes it (ta-coik.5) --------------------

    @Test fun aCommandIsSentOnDoneAtOnceLikeTheWeb() {
        val writer = answering()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex/bin/codex")
        waitForWrites(writer, 1)
        compose.waitForIdle()
        assertEquals(frames("""{"codexCommand":"/opt/codex/bin/codex"}"""), writer.frames())
        assertEquals(ORIGIN, writer.patches.single().second)
        assertFalse("no confirmation", texts().any { it.startsWith("Change the ") })
        // The reply refills the field with the new value.
        assertEquals("/opt/codex/bin/codex", editable(ServerSetting.CodexCommand))
    }

    @Test fun aHomeAndTheLaunchCommandWriteTheSameWay() {
        val writer = answering()
        showWith(writer)
        typeAndDone(ServerSetting.ClaudeLaunchCommand, "jean-claude run -- claude")
        waitForWrites(writer, 1)
        // An emptied home is null, as the web's `value || null`.
        typeAndDone(ServerSetting.CodexHome, "")
        waitForWrites(writer, 2)
        assertEquals(frames("""{"claudeLaunchCommand":"jean-claude run -- claude"}""", """{"codexHome":null}"""), writer.frames())
    }

    @Test fun theTrimmedValueIsSent() {
        val writer = answering()
        showWith(writer)
        typeAndDone(ServerSetting.PiCommand, "  /opt/pi/bin/pi \t")
        waitForWrites(writer, 1)
        assertEquals(frames("""{"piCommand":"/opt/pi/bin/pi"}"""), writer.frames())
    }

    @Test fun onlySpacesAroundTheServersValueSendNothing() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, " codex ")
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    /** :2209 `onBlur`: focus moving to another field writes the edit. */
    @Test fun anEditIsSentOnAFocusLossLikeTheWebsBlur() {
        val writer = RecordingWriter()
        showWith(writer)
        val field = tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performScrollTo()
        field.performClick()
        field.performTextReplacement("/tmp/next")
        tag(ServerSettingsTags.input(ServerSetting.CodexHome)).performScrollTo().performClick()
        waitForWrites(writer, 1)
        assertEquals(frames("""{"codexCommand":"/tmp/next"}"""), writer.frames())
    }

    /** As every Settings text row (ServerSettingsBehaviourTest): an edit left in the field is written when Settings closes. */
    @Test fun anEditLeftInAFieldIsSentWhenSettingsCloses() {
        val writer = RecordingWriter()
        showWith(writer)
        tag(ServerSettingsTags.input(ServerSetting.ClaudeHome)).performScrollTo().performTextReplacement("/tmp/h")
        shown = false
        compose.waitForIdle()
        assertEquals(frames("""{"claudeHome":"/tmp/h"}"""), writer.frames())
        assertEquals(ORIGIN, writer.patches.single().second)
    }

    @Test fun aValuePastTheServersLimitCannotBeTyped() {
        showWith(RecordingWriter())
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performScrollTo().performTextReplacement("a".repeat(257))
        compose.waitForIdle()
        assertEquals("codex", editable(ServerSetting.CodexCommand))
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performTextReplacement("a".repeat(256))
        compose.waitForIdle()
        assertEquals(256, editable(ServerSetting.CodexCommand).length)
    }

    /** :2194: "Use detected" writes the detected home at once. */
    @Test fun useDetectedWritesAtOnce() {
        val writer = answering()
        showWith(writer)
        tag(EngineTags.useDetected(EngineCard.Opencode)).performScrollTo().performClick()
        waitForWrites(writer, 1)
        assertEquals(frames("""{"opencodeHome":"/home/op/.config/opencode"}"""), writer.frames())
        assertFalse("no confirmation", texts().contains("Change the OpenCode home?"))
        // A home is set now: no detected-home row, and the switch can be turned on.
        compose.waitForIdle()
        assertFalse(exists(EngineTags.useDetected(EngineCard.Opencode)))
        tag(EngineTags.switch(EngineCard.Opencode)).assertIsEnabled()
    }

    /** :2191 `noHome && det?.configDir`: the web draws "Use detected" whether or not the environment sets the key. */
    @Test fun useDetectedIsShownWhenTheEnvironmentSetsTheHomeAsOnTheWeb() {
        forced = mapOf("opencodeHome" to true)
        val writer = RecordingWriter()
        showWith(writer)
        tag(EngineTags.useDetected(EngineCard.Opencode)).performScrollTo().assertExists()
        tag(EngineTags.useDetected(EngineCard.Opencode)).performClick()
        waitForWrites(writer, 1)
        assertEquals(frames("""{"opencodeHome":"/home/op/.config/opencode"}"""), writer.frames())
    }

    // ---- spoofing --------------------------------------------------------------------------------

    @Test fun aSpoofedServerValueIsNeverDrawnRawAndATypedValueIsSentExactly() {
        val writer = answering()
        val serverSpoof = "co\u202Edex"
        settings = ServerFixtures.settingsJson(overrides = mapOf("codexHome" to serverSpoof))
        showWith(writer)
        // The field never draws the server's override raw (the edit-field rule).
        assertEquals("codex", editable(ServerSetting.CodexHome))
        val typed = "/srv/\u202Egnp.exe\u200B/home"
        typeAndDone(ServerSetting.CodexHome, typed)
        waitForWrites(writer, 1)
        assertEquals(json("""{"codexHome":"$typed"}"""), writer.patches.single().first)
    }

    @Test fun aNoBreakSpaceIsSentExactly() {
        val writer = answering()
        showWith(writer)
        val typed = "/opt/x\u00A0y/claude"
        typeAndDone(ServerSetting.ClaudeCommand, typed)
        waitForWrites(writer, 1)
        assertEquals(json("""{"claudeCommand":"/opt/x\u00A0y/claude"}"""), writer.patches.single().first)
    }

    /** ta-coik.5: the send path takes an engine value like any other key (no confirmed-only path). */
    @Test fun theBindingSendsAnEngineWriteLikeAnyOther() {
        val writer = RecordingWriter()
        val b = ServerFixtures.binding(writer = writer)
        assertTrue(b.send(json("""{"codexCommand":"/opt/c"}""")))
        assertTrue(b.send(json("""{"headlessModes":"claude"}""")))
        assertEquals(listOf(json("""{"codexCommand":"/opt/c"}"""), json("""{"headlessModes":"claude"}""")), writer.patches.map { it.first })
    }
}

/**
 * ta-dh1: a real activity recreation (a configuration change: rotation, theme, locale) never sends
 * a half-typed engine value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class EnginesRecreationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun recreatingTheActivitySendsNoHalfTypedValue() {
        val writer = RecordingWriter()
        val state = SettingsDialogState(SettingsTab.Engines)
        val binding = ServerFixtures.binding(writer = writer)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
        // A command half typed, still focused when the configuration changes.
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performScrollTo().performClick()
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performTextReplacement("/tmp/ha")
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
        assertEquals(emptyList<Any>(), writer.scans)
    }
}
