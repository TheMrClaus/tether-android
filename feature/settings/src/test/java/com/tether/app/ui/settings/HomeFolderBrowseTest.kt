package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.client.EngineCard
import com.tether.app.protocol.model.DirectoryEntry
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.ui.FolderPickerTags
import com.tether.app.ui.settings.ProfileFixtures.WORK
import com.tether.app.ui.settings.ProfileFixtures.frame
import com.tether.app.ui.settings.ProfileFixtures.gemini
import com.tether.app.ui.settings.ProfileFixtures.zai
import com.tether.app.ui.settings.ServerFixtures.json
import kotlinx.coroutines.flow.MutableStateFlow
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
 * T8.2: Settings' home "Browse folders" (settings-dialog.tsx 90fbb9f :740-750, :2178-2188,
 * :2457-2480): an engine card's and a custom provider's key lists the home (else the detected one,
 * else the server's default), opens the shared folder picker ("Choose a home directory", with
 * "Create a new folder"), and the folder chosen fills that home, written at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HomeFolderBrowseTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)
    private val directories = MutableStateFlow<DirectoryListing?>(null)
    private val browsed = mutableListOf<String?>()
    private val created = mutableListOf<Pair<String, String>>()
    private val picker = HomeFolderPicker(directories, { browsed += it }, { cwd, name -> created += cwd to name })

    private fun show(serverSettings: ServerSettingsBinding = ServerSettingsBinding.None, providers: ProvidersBinding = ProvidersBinding.None) {
        compose.setContent {
            CompositionLocalProvider(LocalHomeFolderPicker provides picker) {
                SettingsUnderTest(store.prefs, state, serverSettings = serverSettings, providers = providers)
                HomeFolderPickerHost(picker)
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pickerShown() = compose.onAllNodesWithText(HomeFolderCopy.PICKER_TITLE).fetchSemanticsNodes().isNotEmpty()

    private fun tap(t: String) {
        tag(t).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun description(t: String) = tag(t).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun listing(current: String) = DirectoryListing(current, parent = "/srv", entries = listOf(DirectoryEntry("nested", "$current/nested")))

    @Test fun anEngineHomeIsBrowsedAndThePickedFolderWrittenAtOnce() {
        val writer = RecordingWriter()
        show(serverSettings = ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(), envForced = mapOf("stateDir" to true)), writer = writer))
        val browse = HomeFolderTags.engine(EngineCard.Codex.id)
        assertEquals("Browse for Codex home", description(browse))
        tag(browse).performScrollTo()
        tap(browse)
        // :2182: from the home the server holds.
        assertEquals(listOf<String?>("/srv/homes/codex"), browsed)
        assertTrue("the picker opened", pickerShown())
        assertTrue("Create a new folder is offered", exists(FolderPickerTags.CreateToggle))
        directories.value = listing("/srv/homes/codex-2")
        compose.waitForIdle()
        tap(FolderPickerTags.Use)
        compose.waitUntil(5_000) { writer.patches.isNotEmpty() }
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"codexHome":"/srv/homes/codex-2"}}""")), writer.frames())
        compose.waitUntil(5_000) { !pickerShown() }
    }

    @Test fun anEmptyEngineHomeBrowsesTheDetectedOne() {
        show(serverSettings = ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(), envForced = mapOf("stateDir" to true)), writer = RecordingWriter()))
        val browse = HomeFolderTags.engine(EngineCard.Claude.id)
        tag(browse).performScrollTo()
        tap(browse)
        // :2182 `home || det?.configDir`: Claude's home is empty, its detected one is listed.
        assertEquals(listOf<String?>("/home/op/.claude"), browsed)
        tap(FolderPickerTags.Cancel)
        compose.waitUntil(5_000) { !pickerShown() }
    }

    @Test fun anEngineHomeTheEnvironmentSetsHasNoBrowseKey() {
        show(serverSettings = ServerFixtures.binding(view = ServerFixtures.view(ServerFixtures.settingsJson(), envForced = mapOf("codexHome" to true)), writer = RecordingWriter()))
        assertFalse(exists(HomeFolderTags.engine(EngineCard.Codex.id)))
        assertTrue(exists(HomeFolderTags.engine(EngineCard.Claude.id)))
    }

    @Test fun aProfileHomeIsBrowsedAndThePickedFolderWrittenAtOnce() {
        val writer = RecordingProvidersWriter(newest = { ProfileFixtures.list() })
        show(providers = ProvidersBinding(ProfileFixtures.list(), ProfileFixtures.ORIGIN, writer))
        val browse = HomeFolderTags.profile("gemini")
        tag(browse).performScrollTo()
        tap(browse)
        assertEquals(listOf<String?>("/srv/homes/gemini"), browsed)
        assertTrue(pickerShown())
        // "Create a new folder" in the folder being browsed.
        directories.value = listing("/srv/homes")
        compose.waitForIdle()
        tap(FolderPickerTags.Use)
        compose.waitUntil(5_000) { writer.writes.isNotEmpty() }
        assertEquals(frame(gemini().replace("/srv/homes/gemini", "/srv/homes"), WORK, zai()), writer.frames().single())
        compose.waitUntil(5_000) { !pickerShown() }
    }

    @Test fun theHomePickerCreatesAFolderWhereItIsBrowsing() {
        show(providers = ProvidersBinding(ProfileFixtures.list(), ProfileFixtures.ORIGIN, RecordingProvidersWriter(newest = { ProfileFixtures.list() })))
        tag(HomeFolderTags.profile("zai")).performScrollTo()
        tap(HomeFolderTags.profile("zai"))
        // zai has no home: the server's default folder (`undefined`).
        assertEquals(listOf<String?>(null), browsed)
        directories.value = listing("/srv/homes")
        compose.waitForIdle()
        tap(FolderPickerTags.CreateToggle)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(FolderPickerTags.NewFolderName)), useUnmergedTree = true).performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(" zai ")) }
        compose.waitForIdle()
        tap(FolderPickerTags.Create)
        assertEquals(listOf("/srv/homes" to "zai"), created)
        assertTrue("the picker stays open for the new listing", pickerShown())
    }
}
