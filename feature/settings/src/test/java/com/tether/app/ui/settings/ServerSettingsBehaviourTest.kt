package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.ServerSetting
import com.tether.app.ui.settings.ServerFixtures.FAKE_PASSWORD
import com.tether.app.ui.settings.ServerFixtures.ORIGIN
import com.tether.app.ui.settings.ServerFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.ServerFixtures.SENTINEL
import com.tether.app.ui.settings.ServerFixtures.TOKEN_SENTINEL
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
import org.robolectric.shadows.ShadowLog

/**
 * ta-t7l: the Advanced and Metadata tabs (settings-dialog.tsx 887c222 :2257-2449) through the
 * semantics tree: the web's order and words, each write as the exact frame (only the changed key),
 * env locks, the restart banner from the server's reply, writes bound to their server, and the
 * two plaintext secrets: masked by default, and the sentinel in no semantics, log, preference,
 * saver or saved-state value while masked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServerSettingsBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private var binding by mutableStateOf(ServerSettingsBinding.None)
    private var shown by mutableStateOf(true)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })

    private fun secretSettings() = ServerFixtures.settingsJson(password = SENTINEL, proxyToken = TOKEN_SENTINEL)

    private fun show(b: ServerSettingsBinding, tab: SettingsTab = SettingsTab.Advanced, menus: MenuSpies? = null) {
        binding = b
        state.tab = tab
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                WithMenuSpies(menus) {
                    if (shown) SettingsUnderTest(store.prefs, state, serverSettings = binding)
                }
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    /** A select's trigger is named by its row (tether-select's `ariaLabel`); tapping it opens the menu. */
    private fun openSelect(label: String) {
        compose.onNodeWithContentDescription(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** An open menu's option, named by its label (tether-select rows carry it as their description). */
    private fun pick(label: String) {
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitForIdle()
    }

    /** EVERY semantics property of every node in every root (text, editable AND raw input text, labels, actions…). */
    private fun allSemantics(): String {
        val out = StringBuilder()
        fun walk(node: SemanticsNode) {
            for ((key, value) in node.config) out.append(key.name).append('=').append(value).append('\n')
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.toString()
    }

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

    private fun savedState(): String = registry.performSave().toString() +
        with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }.toString()

    private fun assertNowhere(vararg leaks: String) {
        val semantics = allSemantics()
        val saved = savedState()
        val logs = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val prefs = store.stored().toString()
        for (leak in leaks) {
            assertFalse("semantics holds $leak", semantics.contains(leak))
            assertFalse("saved state holds $leak", saved.contains(leak))
            assertFalse("a log line holds $leak", logs.contains(leak))
            assertFalse("the preference store holds $leak", prefs.contains(leak))
        }
    }

    // ---- order and words -------------------------------------------------------------------------

    @Test fun advancedDrawsTheWebsSectionsInOrder() {
        show(ServerFixtures.binding())
        val all = texts()
        val sections = listOf("Network", "Authentication", "Storage", "GitHub connection", "Session lifecycle", "Session defaults", "Claude CLI")
        val at = sections.map { title -> all.indexOf(title).also { assertTrue("$title missing", it >= 0) } }
        assertEquals(at.sorted(), at)
        for (label in listOf("Host", "Port", "Password", "Proxy token", "State directory", "Workspace root", "Persistent mode", "Task telemetry",
            "Max warm sessions", "Max concurrent turns", "Idle eviction (ms)", "Background hard cap (ms)", "Sweep interval (ms)", "Shutdown drain (ms)",
            "Message while busy", "Claude model fallback", "Auto-archive idle sessions (days)", "Archive on merge", "Default permission mode", "Default sandbox tier",
            "Default to isolated worktree", "Allowed folders", "Spawn extra writable roots", "Detached agent launches", "Claude CLI version")) {
            assertTrue(label, all.contains(label))
        }
        // ta-coik.21: the GitHub connection is drawn in its place (its rows: GitHubConnectionBehaviourTest).
        tag(ServerSettingsTags.GitHub).assertExists()
        assertTrue(all.contains("2 allowed folders"))
        assertTrue(all.contains("Auto resolves to 2.1.225 (newest installed)"))
    }

    @Test fun beforeTheServerRepliesItSaysItIsLoadingAndTheCliPickerIsDisabled() {
        show(ServerFixtures.binding(view = null, advanced = null))
        tag(ServerSettingsTags.Loading).assertExists()
        tag(ServerSettingsTags.row(ServerSetting.Host)).assertDoesNotExist()
        tag(ServerSettingsTags.CliPicker).performScrollTo()
        compose.onNodeWithContentDescription(ClaudeCliCopy.PICKER_TITLE).assertIsNotEnabled()
        assertTrue(texts().contains("Applies to sessions started after the change"))
    }

    // ---- writes: exactly the changed key ----------------------------------------------------------

    @Test fun eachRowSendsOnlyItsOwnKeyAsTheWebDoes() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        // Text: Done commits; the focus loss it causes does not send it twice.
        tag(ServerSettingsTags.input(ServerSetting.Host)).performTextReplacement("127.0.0.1")
        tag(ServerSettingsTags.input(ServerSetting.Host)).performImeAction()
        // Number.
        tag(ServerSettingsTags.input(ServerSetting.Port)).performTextReplacement("4174")
        tag(ServerSettingsTags.input(ServerSetting.Port)).performImeAction()
        // Toggle.
        tag(ServerSettingsTags.row(ServerSetting.ClaudePersistent)).performScrollTo().performClick()
        // Roots: remove one, add one.
        tag(ServerSettingsTags.remove(ServerSetting.AllowedRoots, "/srv/scratch")).performScrollTo().performClick()
        tag(ServerSettingsTags.input(ServerSetting.SpawnExtraWritableRoots)).performScrollTo().performTextReplacement(" /srv/out ")
        tag(ServerSettingsTags.add(ServerSetting.SpawnExtraWritableRoots)).performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                json("""{"type":"set-server-settings","settings":{"host":"127.0.0.1"}}"""),
                json("""{"type":"set-server-settings","settings":{"port":4174}}"""),
                json("""{"type":"set-server-settings","settings":{"claudePersistent":false}}"""),
                json("""{"type":"set-server-settings","settings":{"allowedRoots":["/srv/work"]}}"""),
                json("""{"type":"set-server-settings","settings":{"spawnExtraWritableRoots":["/srv/out"]}}"""),
            ),
            writer.frames(),
        )
        assertTrue(writer.patches.all { it.second == ORIGIN })
    }

    @Test fun aSelectSendsTheChosenOptionAndTheEmptyOptionAsNull() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("defaultSandboxPolicy" to "read-only"))), writer = writer))
        openSelect("Message while busy")
        pick("End of turn")
        openSelect("Default sandbox tier")
        pick("Provider default")
        compose.waitForIdle()
        assertEquals(
            listOf(
                json("""{"type":"set-server-settings","settings":{"messageInterruptMode":"end"}}"""),
                json("""{"type":"set-server-settings","settings":{"defaultSandboxPolicy":null}}"""),
            ),
            writer.frames(),
        )
    }

    /**
     * ta-coik.4: the field takes what a browser's number input takes and commits `Number(text)`
     * (settings-dialog.tsx:181-187): "6e4" is 60000 on the wire. Negative control: a character a
     * number input does not take (a space, Arabic-Indic digits) is refused and nothing is sent.
     */
    @Test fun aNumberFieldCommitsAsTheBrowsersNumberInput() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        val port = tag(ServerSettingsTags.input(ServerSetting.Port))
        for (bad in listOf("4 173", "١٢")) {
            port.performTextReplacement(bad)
            port.performImeAction()
            compose.waitForIdle()
            assertTrue(bad, allSemantics().contains("EditableText=4173"))
        }
        assertEquals(emptyList<Any>(), writer.patches)
        port.performTextReplacement("6e4")
        port.performImeAction()
        compose.waitForIdle()
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"port":60000}}""")), writer.frames())
    }

    /**
     * tether #244 part D (settings-dialog.tsx 29537e0 :2351): the Auto-archive idle sessions row is a number
     * row after the model fallback and before Archive on merge, off (0) by default; it saves through the same
     * server-settings path as every other row, as the number the field holds (no app-only range check: the
     * server validates 0-3650), and an emptied field is null as the web writes it.
     */
    @Test fun autoArchiveIdleDaysIsAnOffByDefaultNumberRowThatSavesThroughTheServerSettingsPath() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        val all = texts()
        val label = all.indexOf("Auto-archive idle sessions (days)")
        assertTrue(label >= 0)
        assertTrue(all.indexOf("Claude model fallback") < label && label < all.indexOf("Archive on merge"))
        assertTrue(all.contains("Archive sessions idle longer than this (0 = off)"))
        val field = tag(ServerSettingsTags.input(ServerSetting.AutoArchiveIdleDays)).performScrollTo()
        assertTrue("off by default: the field holds 0", allSemantics().contains("EditableText=0"))
        field.performTextReplacement("14")
        field.performImeAction()
        compose.waitForIdle()
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"autoArchiveIdleDays":14}}""")), writer.frames())
        assertTrue(writer.patches.all { it.second == ORIGIN })
    }

    @Test fun autoArchiveIdleDaysShowsTheServersValueAndEmptyingItWritesNull() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("autoArchiveIdleDays" to 30))), writer = writer))
        val field = tag(ServerSettingsTags.input(ServerSetting.AutoArchiveIdleDays)).performScrollTo()
        assertTrue(allSemantics().contains("EditableText=30"))
        field.performTextReplacement("")
        field.performImeAction()
        compose.waitForIdle()
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"autoArchiveIdleDays":null}}""")), writer.frames())
    }

    @Test fun autoArchiveIdleDaysSetByTheEnvironmentIsLockedAndSendsNothing() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(), envForced = mapOf("autoArchiveIdleDays" to true)), writer = writer))
        tag(ServerSettingsTags.input(ServerSetting.AutoArchiveIdleDays)).performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    /** r2: without a server (signed out) no settings are drawn, even with a frame in hand. */
    @Test fun signedOutNoSettingsAreDrawn() {
        show(ServerFixtures.binding(view = ServerFixtures.view(secretSettings()), origin = null))
        tag(ServerSettingsTags.Loading).assertExists()
        tag(ServerSettingsTags.row(ServerSetting.Host)).assertDoesNotExist()
        tag(ServerSettingsTags.row(ServerSetting.Password)).assertDoesNotExist()
        state.tab = SettingsTab.Metadata
        compose.waitForIdle()
        tag(ServerSettingsTags.row(ServerSetting.MetadataGenerationEnabled)).assertDoesNotExist()
    }

    /** ta-coik.65: the dialog's window is not FLAG_SECURE (the web does not block screenshots of Settings). */
    @Test fun theDialogWindowIsNotSecure() {
        assertEquals(androidx.compose.ui.window.SecureFlagPolicy.Inherit, SettingsDialogProperties.securePolicy)
    }

    @Test fun anUneditedFieldSendsNothingWhenItLosesFocusOrLeaves() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        tag(ServerSettingsTags.input(ServerSetting.Host)).performImeAction()
        shown = false
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun anEditLeftInAFieldIsSentWhenTheDialogClosesLikeTheWebsBlur() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        tag(ServerSettingsTags.input(ServerSetting.WorkspaceRoot)).performTextReplacement("")
        shown = false
        compose.waitForIdle()
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"workspaceRoot":null}}""")), writer.frames())
    }

    /** settings-dialog.tsx 90fbb9f :2437 `onSetCliVersion`: a pick is sent at once (ta-coik.5: no app-only confirmation). */
    @Test fun theCliPickerSendsSetAdvancedSettingsAtOnceLikeTheWeb() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        openSelect(ClaudeCliCopy.PICKER_TITLE)
        pick("2.1.220")
        assertEquals(listOf("""{"type":"set-advanced-settings","claudeCliVersion":"2.1.220"}"""), writer.cli.map { it.first.encode() })
        assertEquals(ORIGIN, writer.cli.single().second)
        assertFalse("no confirmation", texts().contains("Switch the Claude CLI?"))
        assertFalse("no confirmation", texts().contains("Switch CLI"))
    }

    @Test fun switchingBackToAutoSendsNull() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(advanced = ServerFixtures.ADVANCED.copy(claudeCliVersion = "2.1.220", effectiveSource = "picker", effectiveVersion = "2.1.220"), writer = writer))
        openSelect(ClaudeCliCopy.PICKER_TITLE)
        pick("Auto — newest installed")
        assertEquals(listOf("""{"type":"set-advanced-settings","claudeCliVersion":null}"""), writer.cli.map { it.first.encode() })
    }

    // ---- env locks ---------------------------------------------------------------------------------

    @Test fun anEnvForcedValueIsLockedAndNeverWritten() {
        val writer = RecordingWriter()
        val forced = mapOf("host" to true, "password" to true, "claudePersistent" to true, "allowedRoots" to true, "messageInterruptMode" to true, "stateDir" to true)
        show(ServerFixtures.binding(view = ServerFixtures.view(envForced = forced), advanced = ServerFixtures.ADVANCED_FORCED, writer = writer))
        // Each forced row: the lock (named "Set by environment") and the caption that says it.
        assertEquals(forced.size, compose.onAllNodesWithTag(SettingsTags.EnvLock, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2 * forced.size, texts().count { it == ServerRowCopy.SET_BY_ENV })
        tag(ServerSettingsTags.input(ServerSetting.Host)).assertIsNotEnabled()
        tag(ServerSettingsTags.input(ServerSetting.StateDir)).assertIsNotEnabled()
        // A forced secret has no key at all (the web hides them), the token (not forced) does.
        tag(ServerSecretTags.opener(ServerSetting.Password)).assertDoesNotExist()
        tag(ServerSecretTags.opener(ServerSetting.ProxyToken)).assertExists()
        // A forced toggle does nothing; a forced list has no remove or add.
        tag(ServerSettingsTags.row(ServerSetting.ClaudePersistent)).performScrollTo().performClick()
        tag(ServerSettingsTags.remove(ServerSetting.AllowedRoots, "/srv/work")).assertDoesNotExist()
        tag(ServerSettingsTags.add(ServerSetting.AllowedRoots)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Message while busy").assertIsNotEnabled()
        // The CLI forced by env: its path, no picker.
        tag(ServerSettingsTags.CliForced).performScrollTo()
        tag(ServerSettingsTags.CliPicker).assertDoesNotExist()
        assertTrue(allSemantics().contains("Forced by env — /opt/claude/bin/claude"))
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    // ---- the restart banner ----------------------------------------------------------------------

    @Test fun theRestartBannerFollowsTheServersReply() {
        // The server answers a restart-required write with restartRequired: true (server.mjs 4694).
        val writer = RecordingWriter { patch ->
            binding = binding.copy(settings = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("claudePersistent" to false)), restartRequired = "claudePersistent" in patch))
        }
        show(ServerFixtures.binding(writer = writer))
        tag(SettingsDialogTags.RestartBanner).assertDoesNotExist()
        tag(ServerSettingsTags.row(ServerSetting.ClaudePersistent)).performScrollTo().performClick()
        compose.waitForIdle()
        tag(SettingsDialogTags.RestartBanner).assertExists()
        assertTrue(texts().contains(RESTART_REQUIRED))
    }

    // ---- origin binding ----------------------------------------------------------------------------

    @Test fun aServerSwitchDropsTheTabAndAnEditGoesOnlyToItsOwnServer() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        tag(ServerSettingsTags.input(ServerSetting.Host)).performTextReplacement("10.0.0.9")
        // Another server: the edit left in the field is handed back bound to the FIRST server (the
        // client delivers it only on that server's socket), and the new tab starts from its value.
        binding = ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("host" to "192.168.1.2"))), origin = OTHER_ORIGIN, writer = writer)
        compose.waitForIdle()
        assertEquals(listOf(json("""{"host":"10.0.0.9"}""") to ORIGIN), writer.patches)
        assertTrue(allSemantics().contains("192.168.1.2"))
        tag(ServerSettingsTags.input(ServerSetting.Port)).performTextReplacement("1")
        tag(ServerSettingsTags.input(ServerSetting.Port)).performImeAction()
        assertEquals(json("""{"port":1}""") to OTHER_ORIGIN, writer.patches.last())
    }

    @Test fun signedOutNothingIsSent() {
        val b = ServerFixtures.binding(origin = null, writer = RecordingWriter())
        assertFalse(b.send(json("""{"host":"x"}""")))
        assertEquals(emptyList<Any>(), (b.writer as RecordingWriter).patches)
    }

    // ---- the secrets: write-only since v144 (ServerSecretsBehaviourTest); the no-copy guard stays for the Nodes and Profiles fields ----

    /** The masked field's text menu offers neither Copy nor Cut (paste and select-all stay). */
    @Test fun theMaskedFieldsMenuOffersNoCopyOrCut() {
        val seen = mutableListOf<List<Boolean>>()
        val platform = object : androidx.compose.ui.platform.TextToolbar {
            override val status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
            override fun hide() = Unit
            override fun showMenu(
                rect: androidx.compose.ui.geometry.Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) {
                seen += listOf(onCopyRequested != null, onPasteRequested != null, onCutRequested != null, onSelectAllRequested != null)
            }
        }
        val guarded = NoCopyToolbar(platform)
        guarded.showMenu(androidx.compose.ui.geometry.Rect.Zero, {}, {}, {}, {})
        assertEquals(listOf(listOf(false, true, false, true)), seen)
    }

    // ---- Metadata --------------------------------------------------------------------------------

    @Test fun metadataOffersTheFallbackListAndSendsTheFlatKey() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer), tab = SettingsTab.Metadata)
        val all = texts()
        for (text in listOf("Metadata generation", "Enable metadata generation", "Provider selection", "Mode", "Provider / model",
            "Tried first; the fallback list still runs if this one fails")) {
            assertTrue(text, all.contains(text))
        }
        openSelect(MetadataRows.PROVIDER)
        pick("ollama / (default)")
        tag(ServerSettingsTags.row(ServerSetting.MetadataGenerationEnabled)).performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                json("""{"type":"set-server-settings","settings":{"metadataGenerationProvider":"ollama"}}"""),
                json("""{"type":"set-server-settings","settings":{"metadataGenerationEnabled":false}}"""),
            ),
            writer.frames(),
        )
    }

    @Test fun aHandEnteredProviderShowsAsCustomAndAnUnreadableListFallsBackToText() {
        show(ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("metadataGenerationProvider" to "groq:llama")))), tab = SettingsTab.Metadata)
        // Offered as a row of its own, so the hand-entered value is visible and reachable.
        openSelect(MetadataRows.PROVIDER)
        pick("Custom: groq:llama")
        binding = ServerFixtures.binding(
            view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("metadataGenerationProviders" to "{not json", "metadataGenerationMode" to "automatic"))),
            writer = RecordingWriter(),
        )
        compose.waitForIdle()
        assertTrue(texts().contains("Only used when mode is Manual"))
        tag(ServerSettingsTags.input(ServerSetting.MetadataGenerationProvider)).performTextReplacement("openai:gpt-4o")
        tag(ServerSettingsTags.input(ServerSetting.MetadataGenerationProvider)).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"metadataGenerationProvider":"openai:gpt-4o"}}""")), (binding.writer as RecordingWriter).frames())
    }

    @Test fun serverTextIsDrawnSafely() {
        val bidi = "/srv/\u202Eevil"
        show(ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("allowedRoots" to listOf(bidi))))))
        tag(ServerSettingsTags.entry(ServerSetting.AllowedRoots, bidi)).performScrollTo()
        // The path rule writes the override out as a token; the remove key names it by the value rule.
        assertFalse(texts().any { it.contains("\u202E") })
        assertTrue(texts().contains("Remove /srv/\\u{202E}evil"))
    }

    /** Fake values only in the revealed golden: the fixture's value is obviously fake. */
    @Test fun theGoldenSecretIsObviouslyFake() {
        assertTrue(FAKE_PASSWORD.startsWith("FAKE-"))
    }
}

/** A lifecycle owner the test moves by hand. */
class TestOwner : androidx.lifecycle.LifecycleOwner {
    val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
}

/**
 * r2: a real activity recreation (a configuration change: rotation, theme, locale) never commits a
 * half-typed edit: neither a secret nor a number. A real close still commits a non-secret
 * row (ServerSettingsBehaviourTest.anEditLeftInAFieldIsSentWhenTheDialogClosesLikeTheWebsBlur).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServerSettingsRecreationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private fun recreateWithHalfTyped(setting: ServerSetting, typed: String) {
        val writer = RecordingWriter()
        val state = SettingsDialogState(SettingsTab.Advanced)
        val binding = ServerFixtures.binding(view = ServerFixtures.view(), writer = writer)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
        // Half typed and still focused when the configuration changes.
        tag(ServerSettingsTags.input(setting)).performScrollTo().performClick()
        tag(ServerSettingsTags.input(setting)).performTextReplacement(typed)
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.patches)
    }

    @Test fun recreatingTheActivitySendsNoHalfTypedNumber() = recreateWithHalfTyped(ServerSetting.Port, "41")
}

/**
 * ta-coik.20: a rotation keeps a server setting half typed (a plain one through the saved state) and
 * never writes a half-typed secret into it. (A real recreation commits nothing; see the recreation tests
 * above: this restore harness disposes the screen without a configuration change, so it is not asserted here.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServerSettingsTypedStateRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationKeepsAHalfTypedPort() {
        val restoration = StateRestorationTester(compose)
        val binding = ServerFixtures.binding(view = ServerFixtures.view())
        var saved: String? = null
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Advanced) }
            SettingsUnderTest(store.prefs, state, serverSettings = binding)
            val registry = androidx.compose.runtime.saveable.LocalSaveableStateRegistry.current
            androidx.compose.runtime.SideEffect { saved = registry?.performSave()?.toString() }
        }
        compose.waitForIdle()
        fun typed(setting: ServerSetting) = compose.onNodeWithTag(ServerSettingsTags.input(setting), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        compose.onNodeWithTag(ServerSettingsTags.input(ServerSetting.Port), useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag(ServerSettingsTags.input(ServerSetting.Port), useUnmergedTree = true).performTextReplacement("41")
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("41", typed(ServerSetting.Port))
        assertFalse("the saved state holds a secret", saved.orEmpty().contains(SENTINEL))
        assertTrue("the port is in the saved state (the check can see strings)", saved.orEmpty().contains("41"))
    }
}
