package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.ClaudeAccountRemoved
import com.tether.app.client.ClaudeLoginCode
import com.tether.app.client.ClaudeLoginLink
import com.tether.app.client.ClaudeLoginState
import com.tether.app.client.ClaudeLoginStatus
import com.tether.app.client.ClaudeSyncMode
import com.tether.app.client.ClaudeSyncSaved
import com.tether.app.client.SecurityResult
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.settings.AccountsFixtures.ORIGIN
import com.tether.app.ui.settings.AccountsFixtures.OTHER_ORIGIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * ta-7rh: the Claude accounts section's CHANGES (settings-dialog.tsx 887c222 :1380-1867, server
 * 90fbb9f), on a phone and on an expanded window: each sends exactly the web's call for the server
 * drawn from, one at a time; Remove and Log out are asked first and send what the confirmation shows;
 * the login runs start → poll → link → code → success, ends on Cancel, and keeps the code only until it
 * is handed over; a server without #236 (its owner-sign-in 403) is said once and nothing breaks.
 */
abstract class ClaudeAccountsChangesBehaviourBase(private val layout: TetherLayoutClass) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    protected val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)
    private val fast = LoginPollPace(first = 20, next = 20, afterFailure = 20, limitMs = 60_000)
    private val link = ClaudeLoginLink.parse("https://claude.ai/oauth/authorize?code=true&client_id=FAKE&state=FAKE-STATE")!!

    private fun binding(reads: FakeAccounts, actions: FakeAccountActions, opener: LoginLinkOpener = LoginLinkOpener.None, origin: String = ORIGIN) =
        ClaudeAccountsBinding(reads, origin, AccountsFixtures.TIME, actions = actions, opener = opener, pace = fast)

    private fun show(binding: ClaudeAccountsBinding, armMs: Long = 0L) {
        compose.setContent {
            CompositionLocalProvider(LocalConfirmArmMs provides armMs) {
                SettingsUnderTest(store.prefs, state, layout = layout, claudeAccounts = binding)
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        waitFor("Claude Code (work)")
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun tap(t: String) {
        // A dialog's keys (the confirmation) sit in no scrolling body.
        if (t == ClaudeAccountsTags.ConfirmGo || t == ClaudeAccountsTags.ConfirmCancel) tag(t).performClick() else tag(t).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun everything(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodesWithTag(ClaudeAccountsTags.Section, useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        compose.onAllNodesWithTag(ClaudeAccountsTags.ConfirmSheet, useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) { everything().any { it.contains(text) } }
    private fun waitForCall(actions: FakeAccountActions, name: String) = compose.waitUntil(5_000) { actions.pending(name) }
    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private val removedKept = ClaudeAccountRemoved(removed = true, credentialsDeleted = false)

    // ---- Add and Rename -----------------------------------------------------------------------------

    @Test fun addSendsTheTrimmedNicknameForThisServerAndReadsTheListAgain() {
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        tap(ClaudeAccountsTags.Add)
        tag(ClaudeAccountsTags.AddField).performTextReplacement("  work2 ")
        tap(ClaudeAccountsTags.AddSubmit)
        waitForCall(actions, "add")
        assertEquals("add($ORIGIN, work2)", actions.calls.single().toString())
        actions.answer("add", SecurityResult.Ok(Unit, ORIGIN, null))
        compose.waitUntil(5_000) { reads.calls.count { it == "list" } == 2 }
        compose.waitUntil(5_000) { !exists(ClaudeAccountsTags.AddField) }
        tag(ClaudeAccountsTags.Add).assertIsEnabled()
    }

    @Test fun aBlankNicknameSendsNothingAndCancelClosesTheRow() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.Add)
        tag(ClaudeAccountsTags.AddField).performTextReplacement("   ")
        tag(ClaudeAccountsTags.AddSubmit).assertIsNotEnabled()
        compose.runOnUiThread { tag(ClaudeAccountsTags.AddSubmit).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!.invoke() }
        compose.waitForIdle()
        assertTrue(actions.calls.isEmpty())
        tap(ClaudeAccountsTags.AddCancel)
        tag(ClaudeAccountsTags.AddField).assertDoesNotExist()
    }

    /** A refusal of the server's own (a 400 sentence) is shown by the label rule, under the row. */
    @Test fun aRefusedAddSaysWhy() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.Add)
        tag(ClaudeAccountsTags.AddField).performTextReplacement("work")
        tap(ClaudeAccountsTags.AddSubmit)
        waitForCall(actions, "add")
        actions.answer("add", SecurityResult.Refused(400, "An account nickname must be 64 characters or fewer.\u202E", ORIGIN))
        waitFor("An account nickname must be 64 characters or fewer.")
        assertFalse(everything().any { it.contains('\u202E') })
        tag(ClaudeAccountsTags.AddField).assertExists()
    }

    @Test fun renameStartsFromTheNicknameAndSendsTheNewOne() {
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        // The host default has nothing to rename (settings-dialog.tsx:1688): no key at all.
        tag(ClaudeAccountsTags.rename("claude-default")).assertDoesNotExist()
        tap(ClaudeAccountsTags.rename("claude-work"))
        assertEquals("work", tag(ClaudeAccountsTags.renameField("claude-work")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        tag(ClaudeAccountsTags.renameField("claude-work")).performTextReplacement("job")
        tap(ClaudeAccountsTags.renameSave("claude-work"))
        waitForCall(actions, "rename")
        assertEquals("rename($ORIGIN, claude-work=job)", actions.calls.single().toString())
        actions.answer("rename", SecurityResult.Ok(Unit, ORIGIN, null))
        compose.waitUntil(5_000) { !exists(ClaudeAccountsTags.renameField("claude-work")) }
        compose.waitUntil(5_000) { reads.calls.count { it == "list" } == 2 }
    }

    /** One change at a time: two taps in one frame send ONE call (the key's busy is set in the tap's frame). */
    @Test fun twoTapsInOneFrameSendOnce() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.Add)
        tag(ClaudeAccountsTags.AddField).performTextReplacement("work2")
        compose.waitForIdle()
        val click = tag(ClaudeAccountsTags.AddSubmit).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
        assertEquals(1, actions.calls.count { it.name == "add" })
        // And while it is in flight every other change rests.
        tag(ClaudeAccountsTags.login("claude-work")).assertIsNotEnabled()
        tag(ClaudeAccountsTags.remove("claude-work")).assertIsNotEnabled()
    }

    // ---- Remove and Log out: asked first --------------------------------------------------------------

    @Test fun removeIsAskedFirstAndSendsWhatTheConfirmationShows() {
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        tap(ClaudeAccountsTags.deleteCredentials("claude-work"))
        assertEquals(ToggleableState.On, tag(ClaudeAccountsTags.deleteCredentials("claude-work")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        tap(ClaudeAccountsTags.remove("claude-work"))
        tag(ClaudeAccountsTags.ConfirmSheet).assertExists()
        assertTrue(everything().contains(ClaudeAccountsCopy.REMOVE_ALSO_LOGIN))
        assertTrue(everything().contains(ClaudeAccountsCopy.removeBody("Claude Code (work)")))
        assertTrue("nothing before the confirmation", actions.calls.isEmpty())
        // Cancel sends nothing.
        tap(ClaudeAccountsTags.ConfirmCancel)
        tag(ClaudeAccountsTags.ConfirmSheet).assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())
        // Asked again and confirmed: exactly the shown account and choice.
        tap(ClaudeAccountsTags.remove("claude-work"))
        tap(ClaudeAccountsTags.ConfirmGo)
        waitForCall(actions, "remove")
        assertEquals("remove($ORIGIN, claude-work deleteCredentials=true)", actions.calls.single().toString())
        actions.answer("remove", SecurityResult.Ok(removedKept, ORIGIN, null))
        // The server kept the login it does not own: said, not hidden.
        waitFor(ClaudeAccountsCopy.removedKeptLogin("Claude Code (work)"))
        compose.waitUntil(5_000) { reads.calls.count { it == "list" } == 2 }
    }

    @Test fun removeWithoutTheBoxKeepsTheLoginAndSaysSo() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.remove("claude-fresh"))
        assertTrue(everything().contains(ClaudeAccountsCopy.REMOVE_KEEPS_LOGIN))
        tap(ClaudeAccountsTags.ConfirmGo)
        waitForCall(actions, "remove")
        assertEquals("remove($ORIGIN, claude-fresh deleteCredentials=false)", actions.calls.single().toString())
        actions.answer("remove", SecurityResult.Ok(ClaudeAccountRemoved(removed = true, credentialsDeleted = false), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.removed("Claude Code (fresh)"))
    }

    @Test fun theHostDefaultsRemovalSaysItsLoginIsNeverDeleted() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.remove("claude-default"))
        assertTrue(everything().contains(ClaudeAccountsCopy.REMOVE_HOST_DEFAULT))
        tap(ClaudeAccountsTags.ConfirmGo)
        waitForCall(actions, "remove")
        actions.answer("remove", SecurityResult.Ok(ClaudeAccountRemoved(removed = false, credentialsDeleted = false), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.notRemoved("Claude Code (default)"))
    }

    // The confirm key's beat (armed only after CONFIRM_ARM_MS, re-armed for a replaced confirmation)
    // is ArmedConfirmKeyTest's, on a hand-driven clock.

    @Test fun logOutIsAskedFirstThenTheStatusIsCheckedAgain() {
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        tap(ClaudeAccountsTags.logout("claude-work"))
        assertTrue(everything().contains(ClaudeAccountsCopy.logoutBody("Claude Code (work)")))
        assertTrue(actions.calls.isEmpty())
        tap(ClaudeAccountsTags.ConfirmGo)
        waitForCall(actions, "logout")
        assertEquals("logout($ORIGIN, claude-work)", actions.calls.single().toString())
        actions.answer("logout", SecurityResult.Ok(Unit, ORIGIN, null))
        compose.waitUntil(5_000) { "status:claude-work" in reads.calls }
    }

    // ---- Log in -------------------------------------------------------------------------------------

    @Test fun theLoginRunsFromTheLinkToTheCodeToSignedIn() {
        ShadowLog.clear()
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        val opener = RecordingOpener()
        show(binding(reads, actions, opener))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        tag(ClaudeAccountsTags.login("claude-work")).assertIsNotEnabled()
        waitFor(ClaudeAccountsCopy.LOGIN_STARTING)
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.PendingUrl, null, false, null), ORIGIN, null))
        waitForCall(actions, "pollLogin")
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.openCaption("claude.ai"))
        waitFor(ClaudeAccountsCopy.LOGIN_PASTE)
        tap(ClaudeAccountsTags.loginOpen("claude-work"))
        assertEquals(listOf(link), opener.opened.toList())
        // The code, typed, then handed over: the field is emptied the moment the server took it.
        tag(ClaudeAccountsTags.code("claude-work")).performTextReplacement("FAKE-CODE-777")
        tap(ClaudeAccountsTags.codeSubmit("claude-work"))
        waitForCall(actions, "submitCode")
        val sent = actions.calls.single { it.name == "submitCode" }
        assertEquals(ORIGIN, sent.origin)
        assertEquals(ClaudeLoginCode("FAKE-CODE-777"), sent.code)
        actions.answer("submitCode", SecurityResult.Ok(Unit, ORIGIN, null))
        compose.waitUntil(5_000) { tag(ClaudeAccountsTags.code("claude-work")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty() }
        // The poll goes on until the server says it is done; then the panel goes and the status is read.
        waitForCall(actions, "pollLogin")
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.Success, null, false, null), ORIGIN, null))
        compose.waitUntil(5_000) { !exists(ClaudeAccountsTags.loginPanel("claude-work")) }
        compose.waitUntil(5_000) { "status:claude-work" in reads.calls }
        tag(ClaudeAccountsTags.login("claude-work")).assertIsEnabled()
        // The code was never logged, nor kept in the dialog's saved state.
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains("FAKE-CODE-777") == true })
        val saved = with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }.toString()
        assertFalse(saved.contains("FAKE-CODE-777"))
        assertFalse(store.stored().toString().contains("FAKE-CODE-777"))
    }

    @Test fun cancelEndsThePollAndTellsTheServer() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        waitForCall(actions, "pollLogin")
        tag(ClaudeAccountsTags.code("claude-work")).performTextReplacement("FAKE-HALF")
        tap(ClaudeAccountsTags.loginCancel("claude-work"))
        compose.waitUntil(5_000) { actions.calls.any { it.name == "cancelLogin" } }
        assertEquals("cancelLogin($ORIGIN, claude-work)", actions.calls.last { it.name == "cancelLogin" }.toString())
        tag(ClaudeAccountsTags.loginPanel("claude-work")).assertDoesNotExist()
        // The in-flight poll's answer lands on nothing, and no new poll starts.
        val polls = actions.calls.count { it.name == "pollLogin" }
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(polls, actions.calls.count { it.name == "pollLogin" })
        tag(ClaudeAccountsTags.loginPanel("claude-work")).assertDoesNotExist()
        // A fresh login starts with an empty code field.
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.code("claude-work")) }
        assertEquals("", tag(ClaudeAccountsTags.code("claude-work")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun aFailedLoginSaysWhyAndHidesTheCodeField() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.PendingUrl, null, false, null), ORIGIN, null))
        waitForCall(actions, "pollLogin")
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.Error, null, false, "Claude login did not complete (exit code 1)."), ORIGIN, null))
        waitFor("Claude login did not complete (exit code 1).")
        tag(ClaudeAccountsTags.code("claude-work")).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("no poll after an error", 1, actions.calls.count { it.name == "pollLogin" })
    }

    @Test fun aLinkThatIsNotPlainHttpsIsNotOffered() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions, RecordingOpener()))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, null, linkRefused = true, error = null), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.LINK_REFUSED)
        tag(ClaudeAccountsTags.loginOpen("claude-work")).assertDoesNotExist()
    }

    @Test fun aLoginAlreadyRunningElsewhereIsSaid() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Refused(409, com.tether.app.client.ClaudeAccountActionCopy.ALREADY_IN_PROGRESS, ORIGIN))
        waitFor(com.tether.app.client.ClaudeAccountActionCopy.ALREADY_IN_PROGRESS)
        assertFalse(actions.calls.any { it.name == "pollLogin" })
    }

    // ---- a server without #236 ----------------------------------------------------------------------

    @Test fun aServerWithoutTheOwnerGradeChangeIsSaidOnceAndNothingBreaks() {
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        tag(ClaudeAccountsTags.OwnerNeeded).assertDoesNotExist()
        tap(ClaudeAccountsTags.Add)
        tag(ClaudeAccountsTags.AddField).performTextReplacement("work2")
        tap(ClaudeAccountsTags.AddSubmit)
        waitForCall(actions, "add")
        actions.answer("add", SecurityResult.OwnerSignInNeeded(ORIGIN))
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.OwnerNeeded) }
        assertTrue(everything().contains(ClaudeAccountsCopy.OWNER_NEEDED))
        // Every change rests; reading still works.
        for (id in listOf("claude-default", "claude-work", "claude-fresh")) {
            tag(ClaudeAccountsTags.login(id)).assertIsNotEnabled()
            tag(ClaudeAccountsTags.logout(id)).assertIsNotEnabled()
            tag(ClaudeAccountsTags.remove(id)).assertIsNotEnabled()
            tag(ClaudeAccountsTags.check(id)).assertIsEnabled()
        }
        tag(ClaudeAccountsTags.SyncNow).assertIsNotEnabled()
        tap(ClaudeAccountsTags.check("claude-work"))
        waitFor("Logged in — work@example.com")
        assertEquals(listOf("add"), actions.names())
        // Try again offers the changes again; the next change asks the server again.
        tap(ClaudeAccountsTags.TryAgain)
        tag(ClaudeAccountsTags.OwnerNeeded).assertDoesNotExist()
        tag(ClaudeAccountsTags.login("claude-work")).assertIsEnabled()
    }

    @Test fun anOwnerRefusalOfTheLoginDropsItsPanel() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.OwnerSignInNeeded(ORIGIN))
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.OwnerNeeded) }
        tag(ClaudeAccountsTags.loginPanel("claude-work")).assertDoesNotExist()
        assertFalse(actions.calls.any { it.name == "pollLogin" })
    }

    // ---- the server the section was drawn from --------------------------------------------------------

    /** A change's answer about another server is dropped; a new server starts from nothing (login, code and all). */
    @Test fun aNewServerDropsTheLoginAndTheCode() {
        val actions = FakeAccountActions()
        val first = binding(FakeAccounts(), actions)
        val second = binding(FakeAccounts(origin = OTHER_ORIGIN), actions, origin = OTHER_ORIGIN)
        var current by mutableStateOf(first)
        compose.setContent {
            CompositionLocalProvider(LocalConfirmArmMs provides 0L) { SettingsUnderTest(store.prefs, state, layout = layout, claudeAccounts = current) }
        }
        compose.waitUntil(5_000) { state.draft != null }
        waitFor("Claude Code (work)")
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        waitForCall(actions, "pollLogin")
        tag(ClaudeAccountsTags.code("claude-work")).performTextReplacement("FAKE-OLD-SERVER")
        current = second
        compose.waitUntil(5_000) { everything().any { it.contains("Claude Code (work)") } && !exists(ClaudeAccountsTags.loginPanel("claude-work")) }
        // The old poll's answer lands on nothing; nothing is sent for the old server any more.
        val before = actions.calls.size
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(before, actions.calls.size)
        assertFalse(everything().any { it.contains("FAKE-OLD-SERVER") })
        // A change now goes to the new server only.
        tap(ClaudeAccountsTags.logout("claude-work"))
        tap(ClaudeAccountsTags.ConfirmGo)
        waitForCall(actions, "logout")
        assertEquals(OTHER_ORIGIN, actions.calls.last().origin)
    }

    @Test fun anAnswerAboutAnotherServerIsDropped() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), OTHER_ORIGIN, null))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertFalse(everything().contains(ClaudeAccountsCopy.openCaption("claude.ai")))
        assertFalse(actions.calls.any { it.name == "pollLogin" })
    }

    @Test fun signedOutOffersNoChange() {
        val actions = FakeAccountActions()
        compose.setContent { SettingsUnderTest(store.prefs, state, layout = layout, claudeAccounts = ClaudeAccountsBinding(FakeAccounts(), null, actions = actions)) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitUntil(5_000) { everything().any { it.contains("Signed out") } }
        tag(ClaudeAccountsTags.Add).assertIsNotEnabled()
        assertTrue(actions.calls.isEmpty())
    }

    // ---- Sync -----------------------------------------------------------------------------------------

    @Test fun aSyncSettingIsSavedWholeAndTheAnswerShown() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        waitFor("Sync across accounts")
        tap(ClaudeAccountsTags.syncCategory("hooks"))
        waitForCall(actions, "saveSync")
        val expected = AccountsFixtures.SYNC.config.copy(categories = AccountsFixtures.SYNC.config.categories.copy(hooks = true))
        assertEquals("saveSync($ORIGIN, $expected)", actions.calls.single().toString())
        // Shown at once (the web's optimistic value) …
        assertEquals(ToggleableState.On, tag(ClaudeAccountsTags.syncCategory("hooks")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        actions.answer("saveSync", SecurityResult.Ok(ClaudeSyncSaved(expected, com.tether.app.client.ClaudeSyncResult(1790000000000, "ok", changed = 4, upToDate = 0, error = null)), ORIGIN, null))
        waitFor("4 updated, 0 already current.")
    }

    @Test fun aRefusedSyncSaveGoesBackAndSaysWhy() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        waitFor("Sync across accounts")
        tag(ClaudeAccountsTags.SyncMode).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Sync everything").performClick()
        waitForCall(actions, "saveSync")
        assertTrue(actions.calls.single().arg!!.contains("mode=${ClaudeSyncMode.All}"))
        // Shown at once: "everything" has no category rows.
        compose.waitUntil(5_000) { !exists(ClaudeAccountsTags.syncCategory("hooks")) }
        actions.answer("saveSync", SecurityResult.Refused(400, "Invalid sync config.", ORIGIN))
        waitFor("Invalid sync config.")
        // … and a failure puts back what the server had ("selected": its category rows are back).
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.syncCategory("hooks")) }
        assertEquals(ToggleableState.Off, tag(ClaudeAccountsTags.syncCategory("hooks")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
    }

    @Test fun syncNowRunsAPassAndShowsIt() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        waitFor("Sync across accounts")
        tap(ClaudeAccountsTags.SyncNow)
        waitForCall(actions, "runSync")
        assertEquals("runSync($ORIGIN, null)", actions.calls.single().toString())
        waitFor(ClaudeAccountsCopy.SYNCING)
        actions.answer("runSync", SecurityResult.Ok(ClaudeSyncSaved(AccountsFixtures.SYNC.config, com.tether.app.client.ClaudeSyncResult(1790000000000, "ok", changed = 0, upToDate = 3, error = null)), ORIGIN, null))
        waitFor("0 updated, 3 already current.")
    }

    /** Nothing in the section is ever the client's raw state: a seed-free dispose leaves no code anywhere. */
    @Test fun closingTheTabDropsThePastedCode() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        tag(ClaudeAccountsTags.code("claude-work")).performTextReplacement("FAKE-LEFT-BEHIND")
        compose.runOnUiThread { state.tab = SettingsTab.General }
        compose.waitForIdle()
        compose.runOnUiThread { state.tab = SettingsTab.Engines }
        compose.waitUntil(5_000) { everything().any { it.contains("Claude Code (work)") } }
        assertFalse(everything().any { it.contains("FAKE-LEFT-BEHIND") })
        tag(ClaudeAccountsTags.loginPanel("claude-work")).assertDoesNotExist()
        assertNull(actions.calls.firstOrNull { it.name == "submitCode" })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ClaudeAccountsChangesPhoneTest : ClaudeAccountsChangesBehaviourBase(TetherLayoutClass.Phone)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ClaudeAccountsChangesExpandedTest : ClaudeAccountsChangesBehaviourBase(TetherLayoutClass.Expanded)
