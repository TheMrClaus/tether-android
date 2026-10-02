package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.client.ConnectionState
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.draft.DraftComposerTags
import com.tether.app.ui.draft.DraftFixtures
import com.tether.app.ui.draft.DraftTestClient
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-3e7: components/studio-welcome.tsx on the empty Sessions stage, over the real MainShell, view
 * model and preferences. Every read after a tap waits on the model or the drawn screen (the v2
 * compose rule; the preferences are IO-fed).
 */
abstract class StudioWelcomeBase(private val width: Int, private val height: Int) {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    private val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }
    protected val client = DraftTestClient()
    protected val vm by lazy { TetherViewModel(client) }

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(width, height)
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    /** The Sessions view with nothing selected: the welcome stage. */
    protected fun launch() {
        runBlocking { prefs.setLastView("sessions") }
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        awaitTag(StudioWelcomeTags.Root)
    }

    protected fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun until(what: String, condition: () -> Boolean) = try {
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("timed out waiting for: $what", e)
    }

    protected fun awaitTag(tag: String) = until("tag $tag") { exists(tag) }

    protected fun tap(tag: String) {
        awaitTag(tag)
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    protected fun pickerShown() = rule.onAllNodesWithText("Choose a folder").fetchSemanticsNodes().isNotEmpty()

    private fun prop(tag: String, key: androidx.compose.ui.semantics.SemanticsPropertyKey<*>) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(key)

    protected fun description(tag: String) = (prop(tag, SemanticsProperties.ContentDescription) as? List<*>)?.joinToString()

    protected fun state(tag: String) = prop(tag, SemanticsProperties.StateDescription) as? String

    @Test
    fun startASessionOpensTheNewSessionSheet() {
        launch()
        assertFalse(vm.draftOpen.value)
        tap(ShellTags.StartSessionKey)
        awaitTag(DraftComposerTags.Sheet)
        assertTrue(vm.draftOpen.value)
    }

    @Test
    fun openWorkspaceOpensTheFolderPickerOnTheCurrentWorkspace() {
        launch()
        assertFalse(pickerShown())
        tap(StudioWelcomeTags.OpenWorkspace)
        until("the folder picker") { pickerShown() }
        // dashboard.tsx 1727: browseWorkspace(currentWorkspace || workspaceRoot).
        until("the picker listed a folder") { client.browsed.isNotEmpty() }
        assertEquals(DraftFixtures.ROOT, client.browsed.last())
        assertFalse("the sheet is not the picker", vm.draftOpen.value)
        rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        until("the picker closed") { !pickerShown() }
    }

    @Test
    fun bothKeysAreDisabledOfflineAndATapDoesNothing() {
        client.inner.link.value = ConnectionState.Disconnected
        launch()
        until("Start a session disabled") { runCatching { rule.onNodeWithTag(ShellTags.StartSessionKey).assertIsNotEnabled() }.isSuccess }
        rule.onNodeWithTag(StudioWelcomeTags.OpenWorkspace).assertIsNotEnabled()
        rule.onNodeWithTag(ShellTags.StartSessionKey).performClick()
        rule.onNodeWithTag(StudioWelcomeTags.OpenWorkspace).performClick()
        // A semantics click on a disabled key is refused too (an accessibility service's tap).
        runCatching { rule.onNodeWithTag(ShellTags.StartSessionKey).performSemanticsAction(SemanticsActions.OnClick) }
        runCatching { rule.onNodeWithTag(StudioWelcomeTags.OpenWorkspace).performSemanticsAction(SemanticsActions.OnClick) }
        rule.waitForIdle()
        assertFalse(vm.draftOpen.value)
        assertFalse(exists(DraftComposerTags.Sheet))
        assertFalse(pickerShown())
        assertTrue(client.browsed.isEmpty())
        // The link comes back: both keys are live again.
        client.inner.link.value = ConnectionState.Connected
        until("Start a session enabled") { runCatching { rule.onNodeWithTag(ShellTags.StartSessionKey).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(StudioWelcomeTags.OpenWorkspace).assertIsEnabled()
    }

    @Test
    fun theProvidersFooterReflectsAvailability() {
        launch()
        awaitTag(StudioWelcomeTags.provider(3))
        assertEquals("Claude", description(StudioWelcomeTags.provider(0)))
        assertEquals("Available", state(StudioWelcomeTags.provider(0)))
        assertEquals("Pi", description(StudioWelcomeTags.provider(3)))
        assertEquals("Not configured", state(StudioWelcomeTags.provider(3)))
        // A provider that becomes configured says so.
        client.providers.value = client.providers.value.map { if (it.id == "pi") it.copy(available = true) else it }
        until("Pi available") { state(StudioWelcomeTags.provider(3)) == "Available" }
    }

    @Test
    fun untrustedProviderLabelsAreDrawnThroughTheLabelRule() {
        client.providers.value = listOf(
            ProviderInfo("claude", "Cl\u202Eaude\n\u200B  Code", "C", true),
            ProviderInfo("acp", "\u2066\u200B\u2069", "A", false),
        )
        launch()
        awaitTag(StudioWelcomeTags.provider(1))
        assertEquals("Claude Code", description(StudioWelcomeTags.provider(0)))
        // A label that cleans to nothing spells out the provider's id instead of drawing nothing.
        assertEquals("acp", description(StudioWelcomeTags.provider(1)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StudioWelcomePhoneBehaviourTest : StudioWelcomeBase(412, 915)

/** The expanded shell (T4.2): the same keys and footer in the desktop layout. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class StudioWelcomeExpandedBehaviourTest : StudioWelcomeBase(1280, 800)
