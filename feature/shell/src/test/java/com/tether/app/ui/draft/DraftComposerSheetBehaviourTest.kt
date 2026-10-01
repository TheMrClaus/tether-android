package com.tether.app.ui.draft

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.client.READINESS_MODELS_LOADING
import com.tether.app.client.READINESS_NEED_CWD
import com.tether.app.client.READINESS_NEED_PROMPT
import com.tether.app.client.READINESS_NEED_PROVIDER
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.MainShell
import com.tether.app.ui.NEW_SESSION_ROW_TAG
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.components.FixedKeyboardInset
import com.tether.app.ui.components.KeyboardInset
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.overview.OverviewTags
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.shell.ShellTags
import com.tether.app.ui.sidebar.SidebarTags
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-abm (T8.1 slice 2): the new-session sheet over the real shell, view model and draft engine,
 * with a client that resolves the create as the real one does. Every tap goes through a semantics
 * action; every read after one waits on the model or the drawn screen (never a single read: the
 * ta-b72 lesson), under the v2 compose rule (the preferences are IO-fed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DraftComposerSheetBehaviourTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    private val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }
    private val client = DraftTestClient()
    private val vm by lazy { TetherViewModel(client) }
    private val composer get() = vm.draftComposer

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(412, 915)
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    private fun launch(restorer: StateRestorationTester? = null, keyboard: KeyboardInset? = null) {
        val content: @Composable () -> Unit = {
            TetherTheme {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    if (keyboard != null) CompositionLocalProvider(LocalKeyboardInset provides keyboard) { MainShell(vm, prefs) } else MainShell(vm, prefs)
                }
            }
        }
        if (restorer != null) restorer.setContent(content) else rule.setContent(content)
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for [condition], letting the main looper run what the view model posted to it on each
     * poll (Robolectric pauses that looper; the engine's first-turn wait resumes there).
     */
    private fun until(what: String = "condition", condition: () -> Boolean) = try {
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("timed out waiting for: $what", e)
    }

    private fun awaitTag(tag: String) = until("tag $tag") { exists(tag) }

    private fun awaitGone(tag: String) = until("no tag $tag") { !exists(tag) }

    private fun tap(tag: String) {
        awaitTag(tag)
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun type(text: String) {
        awaitTag(DraftComposerTags.Input)
        rule.onNodeWithTag(DraftComposerTags.Input).performTextReplacement(text)
        until("the draft holds the typed text") { composer.state.value.text == text }
    }

    private fun shownText(tag: String): String? = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        ?.let { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }

    private fun textOf(tag: String): String = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun formKey() = (composer.state.value.form["key"] as? JsStr)?.value.orEmpty()

    private fun formCwd() = (composer.state.value.form["cwd"] as? JsStr)?.value.orEmpty()

    private fun selected(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun openSheet() {
        launch()
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
    }

    /** Pick [key], type [text] and Send (each step waited on). */
    private fun compose(key: String, text: String) {
        tap(NEW_SESSION_ROW_TAG + key)
        until("the draft picked $key") { formKey() == key }
        type(text)
        until("Send enabled") { runCatching { rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).assertIsEnabled() }.isSuccess }
    }

    @Test
    fun everyNewSessionKeyRaisesTheSheetAndCloseKeepsTheDraft() {
        launch()
        // Fresh install: the Overview, whose New session key raises the sheet.
        tap(OverviewTags.NewSession)
        awaitTag(DraftComposerTags.Sheet)
        until("a fresh catalog was asked for") { client.catalogRequests >= 1 }
        type("kept across the close")
        tap(DraftComposerTags.Close)
        awaitGone(DraftComposerTags.Sheet)
        assertFalse(vm.draftOpen.value)
        tap(OverviewTags.NewSession)
        awaitTag(DraftComposerTags.Sheet)
        until("the text came back") { textOf(DraftComposerTags.Input) == "kept across the close" }
    }

    @Test
    fun theEmptyStageAndTheDrawerRaiseTheSheetToo() {
        runBlocking { prefs.setLastView("sessions") }
        launch()
        // Sessions' empty stage: "Start first session".
        tap(ShellTags.StartSessionKey)
        awaitTag(DraftComposerTags.Sheet)
        tap(DraftComposerTags.Close)
        awaitGone(DraftComposerTags.Sheet)
        // The drawer's New session key (the drawer closes as the sheet rises).
        tap(ShellTags.MenuKey)
        tap(SidebarTags.NewSession)
        awaitTag(DraftComposerTags.Sheet)
        until("the drawer closed") { !exists(ShellTags.DrawerBackdrop) }
    }

    @Test
    fun everyTapReachesTheModel() {
        client.directories.value = DirectoryListing(current = "/srv/other", parent = "/srv", entries = emptyList())
        runBlocking { prefs.updatePreferences { it.copy(pinnedProjects = listOf("/srv/ws/app"), defaultWorkspace = "/srv/ws/docs") } }
        openSheet()
        // A provider row picks that row (checked, announced selected).
        tap(NEW_SESSION_ROW_TAG + "personal")
        until("personal picked") { formKey() == "personal" }
        until("the row is drawn selected") { selected(NEW_SESSION_ROW_TAG + "personal") }
        assertFalse(selected(NEW_SESSION_ROW_TAG + "work"))
        // The folder chip's quick picks (pinned → default → current → root).
        until("the root seeded the folder") { formCwd() == DraftFixtures.ROOT }
        tap(DraftComposerTags.WorkspaceChip)
        awaitTag(DraftComposerTags.WorkspacePopover)
        until("the pinned and default picks are listed") { exists(DraftComposerTags.quickPick("/srv/ws/app")) && exists(DraftComposerTags.quickPick("/srv/ws/docs")) }
        tap(DraftComposerTags.quickPick("/srv/ws/app"))
        until("the folder is the pick") { formCwd() == "/srv/ws/app" }
        awaitGone(DraftComposerTags.WorkspacePopover)
        // Browse for another folder: the interim folder picker, on the draft's folder.
        tap(DraftComposerTags.WorkspaceChip)
        tap(DraftComposerTags.Browse)
        until("it browsed from the draft's folder") { client.browsed.lastOrNull() == "/srv/ws/app" }
        until("the folder picker is up") { rule.onAllNodesWithText("Choose a working folder").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Use this folder")[0].performSemanticsAction(SemanticsActions.OnClick)
        until("the browsed folder is the draft's") { formCwd() == "/srv/other" }
        // The paperclip opens the T7.4 sheet.
        tap(DraftComposerTags.Attach)
        until("the attachment sheet is up") { rule.onAllNodesWithText("Add attachment").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("nothing was sent by any of it", client.creates.isEmpty())
    }

    @Test
    fun sendCreatesTheSessionAndSendsTheFirstMessage() {
        openSheet()
        compose("work", "  Summarize the README.  ")
        tap(DraftComposerTags.Send)
        until("one create went out") { client.creates.size == 1 }
        val create = client.creates.single()
        assertEquals("claude", create.provider)
        assertEquals("work", create.profileId)
        assertEquals(DraftFixtures.ROOT, create.cwd)
        assertEquals("workspace-write", create.sandboxPolicy)
        assertTrue(create.requestId!!.isNotEmpty())
        assertTrue("nothing is sent before created", client.firstSends.isEmpty())
        client.answer("new-work")
        until("the first message went to the new session") { client.firstSends.toList() == listOf("new-work" to "Summarize the README.") }
        until("the new session is selected") { vm.selectedSessionId.value == "new-work" }
        until("the sheet closed") { !vm.draftOpen.value }
        until("the draft started over") { composer.state.value.text.isEmpty() }
        awaitGone(DraftComposerTags.Sheet)
        assertEquals(1, client.creates.size)
    }

    @Test
    fun readinessBlocksSendWithTheWebsReasonInOrder() {
        client.workspaceRoot.value = null
        openSheet()
        fun reason(expected: String) {
            until("readiness says \"$expected\"") { shownText(DraftComposerTags.Readiness) == expected }
            rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).assertIsNotEnabled()
        }
        reason(READINESS_NEED_PROMPT)
        type("Hello")
        reason(READINESS_NEED_PROVIDER)
        tap(NEW_SESSION_ROW_TAG + "opencode")
        reason(READINESS_MODELS_LOADING)
        tap(NEW_SESSION_ROW_TAG + "claude")
        reason(READINESS_NEED_CWD)
        // A disabled Send sends nothing, however it is reached.
        rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(client.creates.isEmpty())
        // The server's root arrives: ready.
        rule.runOnUiThread { client.workspaceRoot.value = DraftFixtures.ROOT }
        awaitGone(DraftComposerTags.Readiness)
        until("Send enabled") { runCatching { rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).assertIsEnabled() }.isSuccess }
        // An unavailable row cannot be picked.
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "pi", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun attachmentsAreCarriedToTheFirstTurn() {
        openSheet()
        val a = Attachment(name = "a.png", mediaType = "image/png", data = "iVBORw0KGgo=")
        val b = Attachment(name = "notes.txt", mediaType = "text/plain", data = "aGVsbG8=")
        rule.runOnUiThread { composer.setStagedAttachments(listOf(StagedAttachment(1, a, 8), StagedAttachment(2, b, 5))) }
        awaitTag(DraftComposerTags.Attachments)
        until("both chips are drawn") { rule.onAllNodesWithTag("staged-attachment", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        // Remove one by its chip's key.
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Remove notes.txt"), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        until("one is left") { composer.state.value.staged.map { it.attachment.name } == listOf("a.png") }
        // Attachments alone are a first turn: only a provider is needed.
        tap(NEW_SESSION_ROW_TAG + "claude")
        until("Send enabled") { runCatching { rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).assertIsEnabled() }.isSuccess }
        tap(DraftComposerTags.Send)
        until("the create went out") { client.creates.size == 1 }
        client.answer("new-pic")
        until("the attachments went with the first turn") { client.attachmentSends.size == 1 }
        val (session, text, sent) = client.attachmentSends.single()
        assertEquals("new-pic", session)
        assertEquals("", text)
        assertEquals(listOf(a), sent)
        assertTrue("never through the text path", client.firstSends.isEmpty())
        until("the draft let them go") { composer.state.value.staged.isEmpty() }
    }

    @Test
    fun theLaunchingStageShowsWhileTheCreateIsInFlight() {
        openSheet()
        compose("work", "Open the launch stage")
        tap(DraftComposerTags.Send)
        until("the create went out") { client.creates.size == 1 }
        awaitGone(DraftComposerTags.Sheet)
        awaitTag(DraftComposerTags.Launching)
        until("it names the provider") { rule.onAllNodesWithText("Opening the Claude (work) session…").fetchSemanticsNodes().isNotEmpty() }
        until("the operator's message is the first bubble") { rule.onAllNodesWithText("Open the launch stage").fetchSemanticsNodes().isNotEmpty() }
        client.answer("new-launch")
        awaitGone(DraftComposerTags.Launching)
        until("the new session is selected") { vm.selectedSessionId.value == "new-launch" }
    }

    @Test
    fun aServerRefusalBringsTheSheetBackUnlockedWithTheText() {
        openSheet()
        compose("claude", "Keep this prompt")
        tap(DraftComposerTags.Send)
        until("the create went out") { client.creates.size == 1 }
        awaitTag(DraftComposerTags.Launching)
        client.refuse("Skipping tool approvals needs a browser sign-in, not a paired device.")
        awaitTag(DraftComposerTags.Sheet)
        awaitGone(DraftComposerTags.Launching)
        until("the server's words are shown") { shownText(DraftComposerTags.Error) == "Skipping tool approvals needs a browser sign-in, not a paired device." }
        until("the text is kept") { textOf(DraftComposerTags.Input) == "Keep this prompt" }
        until("Send enabled") { runCatching { rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).assertIsEnabled() }.isSuccess }
        assertEquals("claude", formKey())
        assertEquals("never resent on its own", 1, client.creates.size)
        assertTrue(client.firstSends.isEmpty())
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun aRecreationKeepsTheOpenSheetAndTheDraftAndASessionSwitchKeepsTheDraft() {
        val restorer = StateRestorationTester(rule)
        launch(restorer)
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        compose("personal", "Survive a rotation")
        restorer.emulateSavedInstanceStateRestore()
        awaitTag(DraftComposerTags.Sheet)
        until("the text is back") { textOf(DraftComposerTags.Input) == "Survive a rotation" }
        until("the pick is back") { selected(NEW_SESSION_ROW_TAG + "personal") }
        // Selecting a session closes the sheet; the draft stays for the next opening.
        val other = com.tether.app.protocol.model.AgentSession(id = "s-other", provider = "claude", name = "other", cwd = "/w", status = "ready", startedAt = 1, updatedAt = 1)
        rule.runOnUiThread {
            client.sessions.value = listOf(other)
            vm.selectSession("s-other")
        }
        awaitGone(DraftComposerTags.Sheet)
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        until("the text is still there") { textOf(DraftComposerTags.Input) == "Survive a rotation" }
        assertEquals("personal", formKey())
        assertEquals(DraftFixtures.ROOT, formCwd())
    }

    @Test
    fun aServerSwitchDropsTheDraft() {
        openSheet()
        compose("work", "Only for this server")
        val staged = Attachment(name = "a.png", mediaType = "image/png", data = "iVBORw0KGgo=")
        rule.runOnUiThread { composer.setStagedAttachments(listOf(StagedAttachment(1, staged, 8))) }
        until("staged") { composer.state.value.staged.size == 1 }
        rule.runOnUiThread { client.server.value = "https://other.test" }
        until("the draft is dropped") { composer.state.value.text.isEmpty() && composer.state.value.staged.isEmpty() && formKey().isEmpty() }
        awaitGone(DraftComposerTags.Sheet)
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        until("it opens empty") { textOf(DraftComposerTags.Input).isEmpty() }
        assertFalse(selected(NEW_SESSION_ROW_TAG + "work"))
        assertTrue(client.creates.isEmpty())
    }

    @Test
    fun theKeyboardShrinksTheTextBox() {
        launch(keyboard = FixedKeyboardInset(320.dp))
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Input)
        val open = rule.onNodeWithTag(DraftComposerTags.Input).getBoundsInRoot()
        assertTrue("the text box is 2.75rem while the keyboard is up: ${open.height}", open.height < 60.dp)
    }

    @Test
    fun withoutAKeyboardTheTextBoxHasItsWritingRoom() {
        launch(keyboard = FixedKeyboardInset(0.dp))
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Input)
        val closed = rule.onNodeWithTag(DraftComposerTags.Input).getBoundsInRoot()
        assertTrue("6rem on a phone: ${closed.height}", closed.height >= 95.dp)
    }
}
