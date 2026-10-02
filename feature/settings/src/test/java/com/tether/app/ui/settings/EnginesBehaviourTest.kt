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
import androidx.test.espresso.Espresso
import com.tether.app.client.EngineCard
import com.tether.app.client.ServerSetting
import com.tether.app.ui.settings.ServerFixtures.ORIGIN
import com.tether.app.ui.settings.ServerFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.ServerFixtures.json
import com.tether.app.ui.text.SafeText
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
 * rule), Scan again (`detect-engines` once, busy while in flight), Host config, and the owner's
 * rule for what the server runs: a home, command or launch command is sent ONLY by its
 * confirmation, which shows the current and the new value visibly; dismissing it, an env lock
 * landing meanwhile, a focus loss, a close or a tab change sends nothing. Each write is waited for
 * on the writer and asserted as the exact frame.
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

    /** Type [value] into [s]'s field and press Done (the only way an engine value asks to be sent). */
    private fun typeAndDone(s: ServerSetting, value: String) {
        val field = tag(ServerSettingsTags.input(s)).performScrollTo()
        field.performTextReplacement(value)
        compose.waitForIdle()
        assertEquals("the edit reached the field", value, editable(s))
        field.performImeAction()
        compose.waitForIdle()
    }

    /**
     * r2: hand a state write made outside an input event (a semantics action, a runOnUiThread
     * block) to the recomposer now. In this Robolectric harness such a write is otherwise not
     * applied until something else triggers a frame (a real tap always is).
     */
    private fun flushWrites() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

    /** r2: let the confirmation's key arm (it ignores taps for [CONFIRM_ARM_MS] after it appears). */
    private fun arm() {
        compose.mainClock.advanceTimeBy(CONFIRM_ARM_MS + 50)
        compose.waitForIdle()
    }

    /** Tap Change once it is armed. */
    private fun confirm() {
        arm()
        tag(EngineTags.Confirm).performClick()
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

    // ---- what the server runs: confirmed, never sent otherwise -------------------------------------

    @Test fun aCommandIsSentOnlyAfterItsConfirmation() {
        val writer = answering()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex/bin/codex")
        assertEquals("Done only asks", emptyList<Any>(), writer.patches)
        tag(EngineTags.ConfirmSheet).assertExists()
        assertTrue(texts().contains("Change the Codex command?"))
        assertEquals("codex", SafeText.original(textOf(EngineTags.ConfirmNow)))
        assertEquals("/opt/codex/bin/codex", SafeText.original(textOf(EngineTags.ConfirmNew)))
        assertFalse(exists(EngineTags.ConfirmTrimmed))
        confirm()
        waitForWrites(writer, 1)
        compose.waitForIdle()
        assertEquals(frames("""{"codexCommand":"/opt/codex/bin/codex"}"""), writer.frames())
        assertEquals(ORIGIN, writer.patches.single().second)
        assertFalse(exists(EngineTags.ConfirmSheet))
        // The reply refills the field with the new value; its caption is back.
        assertEquals("/opt/codex/bin/codex", editable(ServerSetting.CodexCommand))
        assertFalse(exists(EngineTags.unsaved(ServerSetting.CodexCommand)))
    }

    @Test fun aHomeAndTheLaunchCommandAreConfirmedToo() {
        val writer = answering()
        showWith(writer)
        typeAndDone(ServerSetting.ClaudeLaunchCommand, "jean-claude run -- claude")
        assertTrue(texts().contains("Change the Claude Code launch command?"))
        assertEquals("Empty — no wrapper", textOf(EngineTags.ConfirmNow))
        confirm()
        waitForWrites(writer, 1)
        // An emptied home is null, as the web's `value || null`.
        typeAndDone(ServerSetting.CodexHome, "")
        assertEquals("/srv/homes/codex", SafeText.original(textOf(EngineTags.ConfirmNow)))
        assertEquals("Empty — not set", textOf(EngineTags.ConfirmNew))
        confirm()
        waitForWrites(writer, 2)
        assertEquals(frames("""{"claudeLaunchCommand":"jean-claude run -- claude"}""", """{"codexHome":null}"""), writer.frames())
    }

    @Test fun theConfirmationShowsTheTrimmedValueThatIsSent() {
        val writer = answering()
        showWith(writer)
        typeAndDone(ServerSetting.PiCommand, "  /opt/pi/bin/pi \t")
        assertEquals("/opt/pi/bin/pi", SafeText.original(textOf(EngineTags.ConfirmNew)))
        tag(EngineTags.ConfirmTrimmed).assertExists()
        assertTrue(texts().contains(EngineRows.TRIMMED))
        confirm()
        waitForWrites(writer, 1)
        assertEquals(frames("""{"piCommand":"/opt/pi/bin/pi"}"""), writer.frames())
    }

    @Test fun onlySpacesAroundTheServersValueAskNothing() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, " codex ")
        assertFalse(exists(EngineTags.ConfirmSheet))
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun cancelBackAndATapOutsideSendNothing() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/tmp/other")
        tag(EngineTags.Cancel).performClick()
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        // The edit stays in the field, marked as not saved.
        assertEquals("/tmp/other", editable(ServerSetting.CodexCommand))
        tag(EngineTags.unsaved(ServerSetting.CodexCommand)).assertExists()
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performImeAction()
        compose.waitForIdle()
        tag(EngineTags.ConfirmSheet).assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performImeAction()
        compose.waitForIdle()
        tag(EngineTags.ConfirmSheet).assertExists()
        // A tap on the scrim, outside the confirmation's card.
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(4f, 4f)) }
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        shown = false
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun aDoubleTapOnChangeSendsOnce() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/tmp/other")
        arm()
        val action = tag(EngineTags.Confirm).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            action()
            action()
            flushWrites()
        }
        waitForWrites(writer, 1)
        compose.waitForIdle()
        assertEquals(1, writer.patches.size)
    }

    @Test fun anEnvLockLandingMidConfirmationSendsNothing() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.ClaudeCommand, "/tmp/claude")
        tag(EngineTags.ConfirmSheet).assertExists()
        binding = binding.copy(settings = ServerFixtures.view(settings, envForced = mapOf("claudeCommand" to true)))
        compose.waitForIdle()
        if (exists(EngineTags.Confirm)) {
            confirm()
            compose.waitForIdle()
        }
        assertFalse(exists(EngineTags.ConfirmSheet))
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun theWriteIsBuiltFromTheLatestReply() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        // Another device sets the same value meanwhile: confirming sends nothing more.
        binding = binding.copy(settings = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("codexCommand" to "/opt/codex"))), replies = 1)
        compose.waitForIdle()
        assertEquals("/opt/codex", SafeText.original(textOf(EngineTags.ConfirmNow)))
        confirm()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun theConfirmationGoesWithTheFrameOrTheServer() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/tmp/a")
        binding = binding.copy(settings = null)
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        binding = ServerFixtures.binding(writer = writer)
        compose.waitForIdle()
        typeAndDone(ServerSetting.CodexCommand, "/tmp/b")
        tag(EngineTags.ConfirmSheet).assertExists()
        binding = ServerFixtures.binding(origin = OTHER_ORIGIN, writer = writer)
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        // The other server's field starts from its own value, nothing half typed.
        assertEquals("codex", editable(ServerSetting.CodexCommand))
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun anEditIsNeverSentOnAFocusLossATabChangeOrAClose() {
        val writer = RecordingWriter()
        showWith(writer)
        val field = tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performScrollTo()
        field.performClick()
        field.performTextReplacement("/tmp/half")
        // Focus moves to another field.
        tag(ServerSettingsTags.input(ServerSetting.CodexHome)).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        // Another tab and back: the edit is gone, nothing sent.
        state.tab = SettingsTab.General
        compose.waitForIdle()
        state.tab = SettingsTab.Engines
        compose.waitForIdle()
        assertEquals("codex", editable(ServerSetting.CodexCommand))
        tag(ServerSettingsTags.input(ServerSetting.ClaudeHome)).performScrollTo().performTextReplacement("/tmp/h")
        shown = false
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
        assertEquals(emptyList<Any>(), writer.scans)
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

    @Test fun useDetectedIsConfirmedLikeATypedHome() {
        val writer = answering()
        showWith(writer)
        tag(EngineTags.useDetected(EngineCard.Opencode)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
        assertTrue(texts().contains("Change the OpenCode home?"))
        assertEquals("/home/op/.config/opencode", SafeText.original(textOf(EngineTags.ConfirmNew)))
        confirm()
        waitForWrites(writer, 1)
        assertEquals(frames("""{"opencodeHome":"/home/op/.config/opencode"}"""), writer.frames())
        // A home is set now: no detected-home row, and the switch can be turned on.
        compose.waitForIdle()
        assertFalse(exists(EngineTags.useDetected(EngineCard.Opencode)))
        tag(EngineTags.switch(EngineCard.Opencode)).assertIsEnabled()
    }

    // ---- spoofing --------------------------------------------------------------------------------

    @Test fun aSpoofedPathIsShownVisiblyInTheConfirmationAndSentExactly() {
        val writer = answering()
        val serverSpoof = "co\u202Edex"
        settings = ServerFixtures.settingsJson(overrides = mapOf("codexHome" to serverSpoof))
        showWith(writer)
        // The field never draws the server's override raw (the edit-field rule).
        assertEquals("codex", editable(ServerSetting.CodexHome))
        val typed = "/srv/\u202Egnp.exe\u200B/home"
        typeAndDone(ServerSetting.CodexHome, typed)
        val now = textOf(EngineTags.ConfirmNow)
        val next = textOf(EngineTags.ConfirmNew)
        for (shownText in listOf(now, next)) {
            assertFalse("a raw override is drawn", shownText.contains('\u202E'))
            assertTrue("the override is a visible token", shownText.contains("⟨U+202E⟩"))
        }
        assertTrue("the zero-width space is a visible token", next.contains("⟨U+200B⟩"))
        // Every remaining U+200B is an inserted break opportunity (after the token mark), never content.
        next.indices.filter { next[it] == '\u200B' }.forEach { assertEquals(SafeText.MARK, next[it - 1]) }
        // What is confirmed is what is sent.
        assertEquals(serverSpoof, SafeText.original(now))
        assertEquals(typed, SafeText.original(next))
        confirm()
        waitForWrites(writer, 1)
        assertEquals(json("""{"codexHome":"$typed"}"""), writer.patches.single().first)
    }


    // ---- r2 ----------------------------------------------------------------------------------------

    /** r2 (F2): a tap on Change the moment the confirmation appears (a double tap on Done) sends nothing. */
    @Test fun aTapBeforeTheConfirmationArmsSendsNothing() {
        val writer = answering()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(CONFIRM_ARM_MS - 150)
        tag(EngineTags.Confirm).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { flushWrites() }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(emptyList<Any>(), writer.patches)
        tag(EngineTags.ConfirmSheet).assertExists()
        // Armed now: the same tap sends.
        compose.mainClock.advanceTimeBy(200)
        tag(EngineTags.Confirm).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { flushWrites() }
        compose.mainClock.autoAdvance = true
        waitForWrites(writer, 1)
        assertEquals(frames("""{"codexCommand":"/opt/codex"}"""), writer.frames())
    }

    /** r2 (verifier 1): an env lock set in the same frame as the tap on Change sends nothing. */
    @Test fun anEnvLockInTheSameFrameAsTheTapSendsNothing() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/tmp/x")
        arm()
        val action = tag(EngineTags.Confirm).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            binding = binding.copy(settings = ServerFixtures.view(settings, envForced = mapOf("codexCommand" to true)))
            action()
            flushWrites()
        }
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        assertEquals(emptyList<Any>(), writer.patches)
    }

    /** r2: the write is built from the client's NEWEST frame, even when the composed one is behind it. */
    @Test fun theWriteReadsTheClientsNewestFrame() {
        val writer = RecordingWriter()
        val newest = ServerFixtures.view(settings, envForced = mapOf("codexCommand" to true))
        show(ServerFixtures.binding(view = ServerFixtures.view(settings), writer = writer).copy(fresh = { newest }))
        typeAndDone(ServerSetting.CodexCommand, "/tmp/x")
        confirm()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    /**
     * ta-q9l: the client dropped its frame (a sign-out, an auth-required state, a server switch)
     * between the tap on Change and the send, and the screen has not caught up (the composed frame
     * is still there): nothing is sent; the write is never built from the on-screen frame.
     */
    @Test fun aFrameDroppedBetweenConfirmAndSendSendsNothing() {
        val writer = RecordingWriter()
        val live = ServerFixtures.view(settings)
        var clientFrame: com.tether.app.client.ServerSettingsView? = live
        show(ServerFixtures.binding(view = live, writer = writer).copy(fresh = { clientFrame }))
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        arm()
        val action = tag(EngineTags.Confirm).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            clientFrame = null
            action()
            flushWrites()
        }
        compose.waitForIdle()
        assertTrue("the screen still draws the old frame", binding.settings != null)
        assertFalse(exists(EngineTags.ConfirmSheet))
        assertEquals(emptyList<Any>(), writer.patches)
    }

    /** ta-q9l: the positive control: the same tap with the client's frame live sends the confirmed value once. */
    @Test fun aLiveClientFrameAtConfirmSends() {
        val writer = RecordingWriter()
        val live = ServerFixtures.view(settings)
        show(ServerFixtures.binding(view = live, writer = writer).copy(fresh = { live }))
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        arm()
        val action = tag(EngineTags.Confirm).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            action()
            flushWrites()
        }
        waitForWrites(writer, 1)
        assertEquals(frames("""{"codexCommand":"/opt/codex"}"""), writer.frames())
        assertEquals(1, writer.confirmedWrites.size)
    }

    /** ta-q9l: the binding's newest frame is the client's, never the composed one once the client has none. */
    @Test fun latestSettingsNeverFallsBackToTheComposedFrame() {
        val composed = ServerFixtures.view(settings)
        val newer = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("codexCommand" to "/opt/newer")))
        val b = ServerFixtures.binding(view = composed)
        assertEquals("no hook: the composed frame", composed, b.latestSettings())
        assertEquals("the client's newer frame", newer, b.copy(fresh = { newer }).latestSettings())
        assertEquals("the client dropped it: none", null, b.copy(fresh = { null }).latestSettings())
        assertEquals("no origin: none", null, b.copy(origin = null, fresh = { newer }).latestSettings())
    }

    /**
     * ta-q9l: the server's value changes while the confirmation is open (another client wrote it):
     * the confirmation shows the new "Now" and its key re-arms, the whole window again.
     */
    @Test fun aNowReplacedWhileTheConfirmationIsOpenReArmsTheKey() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        arm()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            binding = binding.copy(settings = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("codexCommand" to "/opt/other")), envForced = forced))
            flushWrites()
        }
        compose.mainClock.advanceTimeBy(32)
        assertEquals("/opt/other", SafeText.original(textOf(EngineTags.ConfirmNow)))
        tag(EngineTags.Confirm).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { flushWrites() }
        compose.mainClock.advanceTimeBy(CONFIRM_ARM_MS - 150)
        tag(EngineTags.Confirm).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { flushWrites() }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(emptyList<Any>(), writer.patches)
        tag(EngineTags.ConfirmSheet).assertExists()
        // The window has run again: the tap sends.
        compose.mainClock.advanceTimeBy(200)
        tag(EngineTags.Confirm).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { flushWrites() }
        compose.mainClock.autoAdvance = true
        waitForWrites(writer, 1)
        assertEquals(frames("""{"codexCommand":"/opt/codex"}"""), writer.frames())
    }

    /**
     * ta-q9l r2 (security F1): the server's value changes in the same frame as the tap on Change
     * (the tap runs the armed key's lambda of the frame before, so the key's re-arm cannot stop it):
     * the write is built against the "Now" that confirmation showed, and the newest frame's differs,
     * so nothing is sent.
     */
    @Test fun aNowChangedInTheSameFrameAsTheTapSendsNothing() {
        val writer = RecordingWriter()
        showWith(writer)
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        assertEquals("codex", SafeText.original(textOf(EngineTags.ConfirmNow)))
        arm()
        val action = tag(EngineTags.Confirm).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            binding = binding.copy(settings = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("codexCommand" to "/opt/other")), envForced = forced))
            action()
            flushWrites()
        }
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        assertEquals(emptyList<Any>(), writer.patches)
    }

    /**
     * ta-q9l r2 (security F1): the client's newest frame holds another "Now" than the screen (the
     * screen has not caught up) when Change is tapped: nothing is sent.
     */
    @Test fun aNowChangedInTheClientsNewestFrameBeforeTheSendSendsNothing() {
        val writer = RecordingWriter()
        val live = ServerFixtures.view(settings)
        var clientFrame: com.tether.app.client.ServerSettingsView? = live
        show(ServerFixtures.binding(view = live, writer = writer).copy(fresh = { clientFrame }))
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        arm()
        clientFrame = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("codexCommand" to "/opt/other")))
        tag(EngineTags.Confirm).performClick()
        compose.waitForIdle()
        assertFalse(exists(EngineTags.ConfirmSheet))
        assertEquals(emptyList<Any>(), writer.patches)
    }

    /**
     * ta-q9l r2: the positive control: the newest frame changed between arm and send, but not this
     * key's "Now" (another key moved): the confirmed value is sent, once.
     */
    @Test fun anotherKeyChangedBeforeTheSendStillSends() {
        val writer = RecordingWriter()
        val live = ServerFixtures.view(settings)
        var clientFrame: com.tether.app.client.ServerSettingsView? = live
        show(ServerFixtures.binding(view = live, writer = writer).copy(fresh = { clientFrame }))
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        arm()
        clientFrame = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("claudeCommand" to "/opt/claude")))
        tag(EngineTags.Confirm).performClick()
        waitForWrites(writer, 1)
        assertEquals(frames("""{"codexCommand":"/opt/codex"}"""), writer.frames())
        assertEquals(1, writer.confirmedWrites.size)
    }

    /** r2 (F1): a no-break space is a visible token in the confirmation, and the value is sent exactly. */
    @Test fun aNoBreakSpaceIsShownAsATokenAndSentExactly() {
        val writer = answering()
        showWith(writer)
        val typed = "/opt/x\u00A0y/claude"
        typeAndDone(ServerSetting.ClaudeCommand, typed)
        val next = textOf(EngineTags.ConfirmNew)
        assertTrue(next, next.contains("⟨U+00A0⟩"))
        assertFalse(next.contains('\u00A0'))
        assertEquals(typed, SafeText.original(next))
        confirm()
        waitForWrites(writer, 1)
        assertEquals(json("""{"claudeCommand":"/opt/x\u00A0y/claude"}"""), writer.patches.single().first)
        assertTrue(texts().none { it == "Spaces at the start and end were removed." })
    }

    /** r2: the new value is drawn above the current one. */
    @Test fun theNewValueComesFirst() {
        showWith(RecordingWriter())
        typeAndDone(ServerSetting.CodexCommand, "/opt/codex")
        val newTop = tag(EngineTags.ConfirmNew).fetchSemanticsNode().positionInRoot.y
        val nowTop = tag(EngineTags.ConfirmNow).fetchSemanticsNode().positionInRoot.y
        assertTrue("Change to above Now", newTop < nowTop)
    }

    /** r2 (verifier 3): spaces typed around an empty value raise no "Not saved" hint (Done would do nothing). */
    @Test fun whitespaceOnlyTypingRaisesNoHint() {
        showWith(RecordingWriter())
        tag(ServerSettingsTags.input(ServerSetting.ClaudeLaunchCommand)).performScrollTo().performTextReplacement("   ")
        compose.waitForIdle()
        assertEquals("   ", editable(ServerSetting.ClaudeLaunchCommand))
        assertFalse(exists(EngineTags.unsaved(ServerSetting.ClaudeLaunchCommand)))
        tag(ServerSettingsTags.input(ServerSetting.ClaudeLaunchCommand)).performTextReplacement(" w ")
        compose.waitForIdle()
        tag(EngineTags.unsaved(ServerSetting.ClaudeLaunchCommand)).assertExists()
    }

    /** r2 (F3): the send path refuses an unconfirmed patch naming what the server runs, whichever builder made it. */
    @OptIn(com.tether.app.client.EngineConfirmationOnly::class)
    @Test fun theBindingRefusesAnUnconfirmedEngineWrite() {
        val writer = RecordingWriter()
        val b = ServerFixtures.binding(writer = writer)
        for (key in com.tether.app.client.ServerSettingsPatch.runsKeys) {
            assertFalse(key, b.send(json("""{"$key":"/tmp/evil"}""")))
            assertFalse(key, b.send(json("""{"host":"h","$key":null}""")))
        }
        assertTrue(b.send(json("""{"headlessModes":"claude"}""")))
        assertTrue(b.sendConfirmed(com.tether.app.client.ServerSettingsPatch.engineValue(ServerFixtures.view(), ServerSetting.CodexCommand, "/opt/c", expectedNow = ServerFixtures.view().text(ServerSetting.CodexCommand))))
        assertEquals(listOf(json("""{"headlessModes":"claude"}"""), json("""{"codexCommand":"/opt/c"}""")), writer.patches.map { it.first })
        assertEquals(1, writer.confirmedWrites.size)
    }
}

/**
 * ta-dh1: a real activity recreation (a configuration change: rotation, theme, locale) never sends
 * a half-typed engine value, nor one waiting in its confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class EnginesRecreationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun recreatingTheActivitySendsNoHalfTypedOrUnconfirmedValue() {
        val writer = RecordingWriter()
        val state = SettingsDialogState(SettingsTab.Engines)
        val binding = ServerFixtures.binding(writer = writer)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
        // A launch command typed and Done: its confirmation is open, not confirmed.
        tag(ServerSettingsTags.input(ServerSetting.ClaudeLaunchCommand)).performScrollTo().performTextReplacement("wrapper -- claude")
        tag(ServerSettingsTags.input(ServerSetting.ClaudeLaunchCommand)).performImeAction()
        compose.waitForIdle()
        tag(EngineTags.ConfirmSheet).assertExists()
        tag(EngineTags.Cancel).performClick()
        compose.waitForIdle()
        // A command half typed, still focused when the configuration changes.
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performScrollTo().performClick()
        tag(ServerSettingsTags.input(ServerSetting.CodexCommand)).performTextReplacement("/tmp/ha")
        tag(ServerSettingsTags.input(ServerSetting.ClaudeLaunchCommand)).performImeAction()
        compose.waitForIdle()
        tag(EngineTags.ConfirmSheet).assertExists()
        assertEquals(emptyList<Any>(), writer.patches)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
        assertEquals(emptyList<Any>(), writer.scans)
    }
}
