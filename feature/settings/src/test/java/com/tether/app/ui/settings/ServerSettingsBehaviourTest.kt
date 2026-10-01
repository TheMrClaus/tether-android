package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performScrollTo
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

    private fun show(b: ServerSettingsBinding, tab: SettingsTab = SettingsTab.Advanced) {
        binding = b
        state.tab = tab
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (shown) SettingsUnderTest(store.prefs, state, serverSettings = binding)
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
            "Message while busy", "Claude model fallback", "Archive on merge", "Default permission mode", "Default sandbox tier",
            "Default to isolated worktree", "Allowed folders", "Spawn extra writable roots", "Detached agent launches", "Claude CLI version")) {
            assertTrue(label, all.contains(label))
        }
        // The GitHub card is excluded (T8.4) and says so in its own section.
        tag(ServerSettingsTags.GitHub).assertExists()
        assertTrue(all.contains(AdvancedRows.GITHUB_LATER))
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

    @Test fun theCliPickerSendsSetAdvancedSettings() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(writer = writer))
        openSelect(ClaudeCliCopy.PICKER_TITLE)
        pick("Bundled (SDK)")
        compose.waitForIdle()
        assertEquals(listOf("""{"type":"set-advanced-settings","claudeCliVersion":"bundled"}"""), writer.cli.map { it.first.encode() })
        assertEquals(ORIGIN, writer.cli.single().second)
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
        // A forced secret is masked and has no Reveal.
        tag(ServerSettingsTags.masked(ServerSetting.Password)).assertExists()
        tag(ServerSettingsTags.reveal(ServerSetting.Password)).assertDoesNotExist()
        tag(ServerSettingsTags.reveal(ServerSetting.ProxyToken)).assertExists()
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

    // ---- the secrets -------------------------------------------------------------------------------

    @Test fun theSecretsAreMaskedByDefaultAndInNoSemanticsLogPreferenceOrSavedState() {
        ShadowLog.clear()
        show(ServerFixtures.binding(view = ServerFixtures.view(secretSettings())))
        tag(ServerSettingsTags.masked(ServerSetting.Password)).assertExists()
        tag(ServerSettingsTags.masked(ServerSetting.ProxyToken)).assertExists()
        tag(ServerSettingsTags.input(ServerSetting.Password)).assertDoesNotExist()
        assertTrue(texts().contains("Password, hidden"))
        assertTrue(texts().contains("Reveal Password"))
        assertNowhere(SENTINEL, TOKEN_SENTINEL)
        // Even the mask's length says nothing: it is fixed.
        assertFalse(allSemantics().contains("•".repeat(SENTINEL.length)))
    }

    @Test fun revealShowsOnlyThatSecretAndHideMasksItAgain() {
        ShadowLog.clear()
        show(ServerFixtures.binding(view = ServerFixtures.view(secretSettings())))
        tag(ServerSettingsTags.reveal(ServerSetting.Password)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(allSemantics().contains(SENTINEL))
        assertFalse(allSemantics().contains(TOKEN_SENTINEL))
        // Revealed, it is still in no log, preference or saved state.
        val saved = savedState()
        assertFalse(saved.contains(SENTINEL))
        assertFalse(store.stored().toString().contains(SENTINEL))
        assertFalse(ShadowLog.getLogs().any { "${it.tag} ${it.msg}".contains(SENTINEL) })
        tag(ServerSettingsTags.reveal(ServerSetting.Password)).performScrollTo().performClick()
        compose.waitForIdle()
        tag(ServerSettingsTags.masked(ServerSetting.Password)).assertExists()
        assertNowhere(SENTINEL, TOKEN_SENTINEL)
    }

    @Test fun aRevealedSecretCanBeChangedAndSendsOnlyItsKey() {
        val writer = RecordingWriter()
        show(ServerFixtures.binding(view = ServerFixtures.view(secretSettings()), writer = writer))
        tag(ServerSettingsTags.reveal(ServerSetting.ProxyToken)).performScrollTo().performClick()
        tag(ServerSettingsTags.input(ServerSetting.ProxyToken)).performTextReplacement("new-token")
        tag(ServerSettingsTags.input(ServerSetting.ProxyToken)).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"proxyToken":"new-token"}}""")), writer.frames())
    }

    @Test fun closingSettingsMasksTheSecretAgain() {
        show(ServerFixtures.binding(view = ServerFixtures.view(secretSettings())))
        tag(ServerSettingsTags.reveal(ServerSetting.Password)).performScrollTo().performClick()
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        tag(ServerSettingsTags.masked(ServerSetting.Password)).assertExists()
        assertNowhere(SENTINEL, TOKEN_SENTINEL)
    }

    @Test fun leavingTheTabMasksTheSecretAgain() {
        show(ServerFixtures.binding(view = ServerFixtures.view(secretSettings())))
        tag(ServerSettingsTags.reveal(ServerSetting.Password)).performScrollTo().performClick()
        compose.waitForIdle()
        state.tab = SettingsTab.General
        compose.waitForIdle()
        state.tab = SettingsTab.Advanced
        compose.waitForIdle()
        tag(ServerSettingsTags.masked(ServerSetting.Password)).assertExists()
        assertNowhere(SENTINEL, TOKEN_SENTINEL)
    }

    @Test fun anotherServerStartsMasked() {
        show(ServerFixtures.binding(view = ServerFixtures.view(secretSettings())))
        tag(ServerSettingsTags.reveal(ServerSetting.Password)).performScrollTo().performClick()
        compose.waitForIdle()
        binding = binding.copy(origin = OTHER_ORIGIN)
        compose.waitForIdle()
        tag(ServerSettingsTags.masked(ServerSetting.Password)).assertExists()
        assertFalse(allSemantics().contains(SENTINEL))
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
        val bidi = "/srv/‮evil"
        show(ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("allowedRoots" to listOf(bidi))))))
        tag(ServerSettingsTags.entry(ServerSetting.AllowedRoots, bidi)).performScrollTo()
        // The path rule writes the override out as a token; the remove key names it by the value rule.
        assertFalse(texts().any { it.contains("‮") })
        assertTrue(texts().contains("Remove /srv/\\u{202E}evil"))
    }

    /** Fake values only in the revealed golden: the fixture's value is obviously fake. */
    @Test fun theGoldenSecretIsObviouslyFake() {
        assertTrue(FAKE_PASSWORD.startsWith("FAKE-"))
    }
}

/** A rotation (saved-instance-state restore) masks a revealed secret: the reveal is never saved state. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServerSettingsRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationMasksTheSecret() {
        val restoration = StateRestorationTester(compose)
        val binding = ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(password = SENTINEL)))
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Advanced) }
            SettingsUnderTest(store.prefs, state, serverSettings = binding)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ServerSettingsTags.reveal(ServerSetting.Password), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ServerSettingsTags.input(ServerSetting.Password), useUnmergedTree = true).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        // The tab is restored (saved state), the reveal is not.
        compose.onNodeWithTag(SettingsDialogTags.panel(SettingsTab.Advanced), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ServerSettingsTags.masked(ServerSetting.Password), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ServerSettingsTags.input(ServerSetting.Password), useUnmergedTree = true).assertDoesNotExist()
    }
}
