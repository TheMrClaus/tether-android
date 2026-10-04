package com.tether.app.ui.settings

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
 * drawn from, each key busy on its own as the web's; Log out goes at once and Remove is the web's two
 * taps within 4 s (r2: no app-only confirmation); the login runs start → poll → link → code → success,
 * ends on Cancel, its deadline or an error (the server then told), and keeps the code only until it
 * is handed over; a server without #236 (its owner-sign-in 403) is said and nothing breaks; answers
 * fold into the state as it is when they land (r2).
 */
abstract class ClaudeAccountsChangesBehaviourBase(private val layout: TetherLayoutClass) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    protected val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)
    private val fast = LoginPollPace(first = 20, next = 20, afterFailure = 20)
    private val link = ClaudeLoginLink.parse("https://claude.ai/oauth/authorize?code=true&client_id=FAKE&state=FAKE-STATE")!!

    private fun binding(reads: FakeAccounts, actions: FakeAccountActions, opener: LoginLinkOpener = LoginLinkOpener.None, origin: String = ORIGIN) =
        ClaudeAccountsBinding(reads, origin, AccountsFixtures.TIME, actions = actions, opener = opener, pace = fast)

    private fun show(binding: ClaudeAccountsBinding, wait: String = "Claude Code (work)") {
        compose.setContent {
            SettingsUnderTest(store.prefs, state, layout = layout, claudeAccounts = binding)
        }
        compose.waitUntil(5_000) { state.draft != null }
        waitFor(wait)
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun tap(t: String) {
        tag(t).performScrollTo().performClick()
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

    /**
     * Each key has its own busy, as each of the web's rows (addBusy, logoutBusyId, …): two taps in one
     * frame send ONE call (set in the tap's frame), and the other rows stay usable meanwhile.
     */
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
        tag(ClaudeAccountsTags.AddSubmit).assertIsNotEnabled()
        waitFor(ClaudeAccountsCopy.ADDING)
        // The web keeps every other row usable while an Add is in flight.
        tag(ClaudeAccountsTags.login("claude-work")).assertIsEnabled()
        tag(ClaudeAccountsTags.remove("claude-work")).assertIsEnabled()
        tag(ClaudeAccountsTags.logout("claude-work")).assertIsEnabled()
        // So is the same tap twice on Log out: one call.
        val logout = tag(ClaudeAccountsTags.logout("claude-work")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            logout()
            logout()
        }
        compose.waitForIdle()
        assertEquals(1, actions.calls.count { it.name == "logout" })
        tag(ClaudeAccountsTags.logout("claude-work")).assertIsNotEnabled()
        waitFor(ClaudeAccountsCopy.LOGGING_OUT)
    }

    // ---- Remove (the web's two taps) and Log out (at once) ---------------------------------------------

    /** Every text and description drawn on (or in) the key, so its label and its spoken name are both read. */
    private fun removeKeyText(id: String): String {
        val out = StringBuilder()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out.append(it.text).append('|') }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out.append(it).append('|') }
            n.children.forEach(::walk)
        }
        walk(tag(ClaudeAccountsTags.remove(id)).fetchSemanticsNode())
        return out.toString()
    }

    /**
     * settings-dialog.tsx `armRemove` (:1509-1519): the first tap arms the key ("Confirm remove") and
     * sends nothing; the second, within CLAUDE_ACCOUNT_REMOVE_ARM_MS (4 s), removes with "Also delete
     * stored login" as it is THEN (`performRemove` reads deleteCredsById at that moment). No dialog.
     */
    @Test fun removeIsTheWebsTwoTapsAndSendsTheBoxAsItIsOnTheSecond() {
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        tap(ClaudeAccountsTags.remove("claude-work"))
        assertTrue("one tap sends nothing", actions.calls.isEmpty())
        compose.waitUntil(5_000) { removeKeyText("claude-work").contains(ClaudeAccountsCopy.CONFIRM_REMOVE) }
        // The box is ticked after arming: the second tap sends it ticked, as the web.
        tap(ClaudeAccountsTags.deleteCredentials("claude-work"))
        assertEquals(ToggleableState.On, tag(ClaudeAccountsTags.deleteCredentials("claude-work")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        tap(ClaudeAccountsTags.remove("claude-work"))
        waitForCall(actions, "remove")
        assertEquals("remove($ORIGIN, claude-work deleteCredentials=true)", actions.calls.single().toString())
        waitFor(ClaudeAccountsCopy.REMOVING)
        actions.answer("remove", SecurityResult.Ok(removedKept, ORIGIN, null))
        // The server kept the login it does not own: said, not hidden.
        waitFor(ClaudeAccountsCopy.removedKeptLogin("Claude Code (work)"))
        compose.waitUntil(5_000) { reads.calls.count { it == "list" } == 2 }
    }

    /** The arm lapses after 4 s (the web's timer): a tap after that arms again and sends nothing. */
    @Test fun anArmedRemoveLapsesAfterFourSeconds() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.remove("claude-fresh"))
        compose.waitUntil(5_000) { removeKeyText("claude-fresh").contains(ClaudeAccountsCopy.CONFIRM_REMOVE) }
        compose.mainClock.advanceTimeBy(ClaudeAccountsCopy.REMOVE_ARM_MS + 200)
        compose.waitUntil(5_000) { !removeKeyText("claude-fresh").contains(ClaudeAccountsCopy.CONFIRM_REMOVE) }
        tap(ClaudeAccountsTags.remove("claude-fresh"))
        assertTrue("a tap after the arm lapsed only arms again", actions.calls.isEmpty())
        // Positive control: tapped again inside the new window, it goes.
        tap(ClaudeAccountsTags.remove("claude-fresh"))
        waitForCall(actions, "remove")
        assertEquals("remove($ORIGIN, claude-fresh deleteCredentials=false)", actions.calls.single().toString())
        actions.answer("remove", SecurityResult.Ok(ClaudeAccountRemoved(removed = true, credentialsDeleted = false), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.removed("Claude Code (fresh)"))
    }

    /** One account armed at a time (the web's single armedRemoveId): arming another moves the arm. */
    @Test fun armingAnotherAccountMovesTheArm() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.remove("claude-work"))
        tap(ClaudeAccountsTags.remove("claude-fresh"))
        assertTrue(actions.calls.isEmpty())
        compose.waitUntil(5_000) { removeKeyText("claude-fresh").contains(ClaudeAccountsCopy.CONFIRM_REMOVE) }
        assertFalse(removeKeyText("claude-work").contains(ClaudeAccountsCopy.CONFIRM_REMOVE))
        // claude-work is no longer armed: its tap arms it again, it removes nothing.
        tap(ClaudeAccountsTags.remove("claude-work"))
        assertTrue(actions.calls.isEmpty())
        tap(ClaudeAccountsTags.remove("claude-work"))
        waitForCall(actions, "remove")
        assertEquals("remove($ORIGIN, claude-work deleteCredentials=false)", actions.calls.single().toString())
    }

    @Test fun theHostDefaultsRemovalSaysItsLoginIsNeverDeleted() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.remove("claude-default"))
        tap(ClaudeAccountsTags.remove("claude-default"))
        waitForCall(actions, "remove")
        actions.answer("remove", SecurityResult.Ok(ClaudeAccountRemoved(removed = false, credentialsDeleted = false), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.notRemoved("Claude Code (default)"))
    }

    /** settings-dialog.tsx `doLogout` (:1541-1555): sent on the tap, nothing asked first; then the status is read again. */
    @Test fun logOutIsSentAtOnceThenTheStatusIsCheckedAgain() {
        val reads = FakeAccounts()
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        assertTrue("positive control: nothing before the tap", actions.calls.isEmpty())
        tap(ClaudeAccountsTags.logout("claude-work"))
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

    @Test fun aLinkThatIsNotAWebAddressIsNotOffered() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions, RecordingOpener()))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, null, linkRefused = true, error = null), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.LINK_REFUSED)
        tag(ClaudeAccountsTags.loginOpen("claude-work")).assertDoesNotExist()
    }

    /** ta-coik.17: a link of another scheme is offered and opened as the web's `<a href>` is (settings-dialog.tsx :1763-1768), with the web's caption; no web-console pointer anywhere. */
    @Test fun aLinkOfAnotherSchemeIsOfferedAndOpenedLikeTheWebs() {
        val actions = FakeAccountActions()
        val opener = RecordingOpener()
        show(binding(FakeAccounts(), actions, opener))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        val app = ClaudeLoginLink.parse("claude://oauth/callback?x=1")!!
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, app, false, null), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.OPEN_CAPTION_WEB)
        tap(ClaudeAccountsTags.loginOpen("claude-work"))
        assertEquals(listOf(app), opener.opened.toList())
        assertFalse(everything().any { it.contains("web console") })
        tag(ClaudeAccountsTags.loginLinkRefused("claude-work")).assertDoesNotExist()
    }

    /** The intent a phone's browser starts: browsable; an `intent:` link parsed as Chrome parses it, with no named component, selector or URI grant. */
    @Test fun theOpenerStartsWhatAPhonesBrowserStarts() {
        val web = LoginLinkOpener.intentFor(link)!!
        assertEquals(android.content.Intent.ACTION_VIEW, web.action)
        assertTrue(web.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
        assertEquals(link.url, web.dataString)
        val market = LoginLinkOpener.intentFor(ClaudeLoginLink.parse("market://details?id=x")!!)!!
        assertEquals("market://details?id=x", market.dataString)
        assertTrue(market.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
        val intent = LoginLinkOpener.intentFor(ClaudeLoginLink.parse("intent://claude.ai/x#Intent;scheme=https;component=com.example/.Secret;launchFlags=0x3;end")!!)!!
        assertEquals("https://claude.ai/x", intent.dataString)
        assertTrue(intent.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
        assertNull(intent.component)
        assertNull(intent.selector)
        assertEquals(0, intent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    /**
     * r3 (security F1), ta-coik.18 r2: an `intent:` link whose data is a `file:` or `content:` address
     * (Chrome's refusals) is refused, and opening it crashes nothing; other data schemes are not refused.
     */
    @Test fun anIntentLinkToAFileOrContentAddressOpensNothing() {
        for (scheme in listOf("data", "blob", "filesystem", "javascript")) {
            assertEquals(scheme, "$scheme://x/y", LoginLinkOpener.intentFor(ClaudeLoginLink.parse("intent://x/y#Intent;scheme=$scheme;end")!!)?.dataString)
        }
        for (scheme in listOf("file", "content", "FILE", "Content")) {
            val l = ClaudeLoginLink.parse("intent://x/y#Intent;scheme=$scheme;end")!!
            assertNull(scheme, LoginLinkOpener.intentFor(l))
            assertFalse(scheme, LoginLinkOpener.browser(androidx.test.core.app.ApplicationProvider.getApplicationContext()).open(l))
        }
        // An exception the platform throws at the start (a file:// URI exposed) is caught: false, no crash.
        val throwing = object : android.content.ContextWrapper(androidx.test.core.app.ApplicationProvider.getApplicationContext()) {
            override fun startActivity(intent: android.content.Intent?) = throw android.os.FileUriExposedException("file:///sdcard/x exposed")
        }
        assertFalse(LoginLinkOpener.browser(throwing).open(link))
    }

    /**
     * ta-coik.18 r2: the Open key's `intent:` link goes through the shared Chrome rule: no selector,
     * and with no app for it and no fallback, the store page of the package it names.
     */
    @Test fun anIntentLinkNoAppTakesOpensItsPackagesStorePage() {
        val started = mutableListOf<android.content.Intent>()
        val phone = object : android.content.ContextWrapper(appContext()) {
            override fun startActivity(intent: android.content.Intent) {
                if (intent.scheme != "market") throw android.content.ActivityNotFoundException()
                started += intent
            }
        }
        // parseUri keeps a selector only when the link names no package.
        val sel = ClaudeLoginLink.parse("intent://oauth/x#Intent;action=OPEN;SEL;action=android.intent.action.VIEW;scheme=https;component=com.example.other/.Sel;end")!!
        assertNull(LoginLinkOpener.intentFor(sel)!!.selector)
        val l = ClaudeLoginLink.parse("intent://oauth/x#Intent;scheme=claude;package=com.example.claude;end")!!
        assertTrue(LoginLinkOpener.browser(phone).open(l))
        val market = started.single()
        assertEquals("com.example.claude", android.net.Uri.parse(market.dataString).getQueryParameter("id"))
        assertEquals("on the Play Store app, as Chrome", com.tether.app.client.ChromeIntents.PLAY_STORE_PACKAGE, market.`package`)
    }

    /** ta-coik.18 r4 (ta-qap9): a plain login link to this app's own non-exported activity is not handed on, as Chrome. */
    @Test fun aPlainLinkToOurOwnNonExportedActivityIsNotHandedOn() {
        run {
            val pm = org.robolectric.Shadows.shadowOf(appContext().packageManager)
            val inner = android.content.ComponentName(appContext().packageName, "com.tether.app.Inner")
            val info = pm.addActivityIfNotPresent(inner)
            info.exported = false
            pm.addOrUpdateActivity(info)
            pm.addIntentFilterForActivity(inner, android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addDataScheme("tether-inner")
            })
        }
        val started = mutableListOf<android.content.Intent>()
        val phone = object : android.content.ContextWrapper(appContext()) {
            override fun startActivity(intent: android.content.Intent) { started += intent }
        }
        assertFalse(LoginLinkOpener.browser(phone).open(ClaudeLoginLink.parse("tether-inner://x")!!))
        assertEquals(emptyList<android.content.Intent>(), started)
        assertTrue("another scheme still goes out", LoginLinkOpener.browser(phone).open(ClaudeLoginLink.parse("gh://x")!!))
    }

    /** ta-coik.18 r4: a refused `intent:` login link (file data) opens its web fallback in the browser, as Chrome. */
    @Test fun aRefusedIntentLinkOpensItsWebFallback() {
        val started = mutableListOf<android.content.Intent>()
        val phone = object : android.content.ContextWrapper(appContext()) {
            override fun startActivity(intent: android.content.Intent) { started += intent }
        }
        val l = ClaudeLoginLink.parse("intent://x/y#Intent;scheme=file;S.browser_fallback_url=https%3A%2F%2Fclaude.ai%2Foauth;end")!!
        assertTrue(LoginLinkOpener.browser(phone).open(l))
        assertEquals("https://claude.ai/oauth", started.single().dataString)
        assertTrue(started.single().hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
    }

    /** ta-coik.18 r5 (ta-kn42 P3-2): the web fallback goes out through openView too: never to this app's own non-exported activity. */
    @Test fun aWebFallbackToOurOwnNonExportedActivityIsNotHandedOn() {
        val pm = org.robolectric.Shadows.shadowOf(appContext().packageManager)
        val inner = android.content.ComponentName(appContext().packageName, "com.tether.app.Inner")
        val info = pm.addActivityIfNotPresent(inner)
        info.exported = false
        pm.addOrUpdateActivity(info)
        pm.addIntentFilterForActivity(inner, android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
            addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
            addDataAuthority("inner.example.test", null)
        })
        val started = mutableListOf<android.content.Intent>()
        val phone = object : android.content.ContextWrapper(appContext()) {
            override fun startActivity(intent: android.content.Intent) { started += intent }
        }
        val l = ClaudeLoginLink.parse("intent://x/y#Intent;scheme=file;S.browser_fallback_url=https%3A%2F%2Finner.example.test%2Fx;end")!!
        assertFalse(LoginLinkOpener.browser(phone).open(l))
        assertEquals(emptyList<android.content.Intent>(), started)
    }

    /** r3 (security F2): a parsed `intent:` link keeps only Chrome's ALLOWED_INTENT_FLAGS: CLEAR_TASK and the grants are stripped. */
    @Test fun anIntentLinksFlagsAreLimitedToChromesAllowedFlags() {
        val clearTask = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        val intent = LoginLinkOpener.intentFor(ClaudeLoginLink.parse("intent://claude.ai/x#Intent;scheme=https;launchFlags=0x${Integer.toHexString(clearTask)};end")!!)!!
        assertEquals(0, intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(0, intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.flags)
        assertEquals(0, intent.flags and LoginLinkOpener.ALLOWED_INTENT_FLAGS.inv())
    }

    /**
     * r4 (security N1): an `intent:` link Intent.parseUri rejects with something other than a
     * URISyntaxException (a launchFlags or typed extra that is not a number) is a link that does not
     * parse, like any other: no intent, the opener opens nothing, and the Open tap shows the same note
     * as for a link that does not parse at all. Before r4 every Open tap on it crashed the app.
     */
    @Test fun anIntentLinkParseUriThrowsOnOpensNothingLikeAnyUnparsableLink() {
        val parse = { raw: String -> android.content.Intent.parseUri(raw, android.content.Intent.URI_INTENT_SCHEME) }
        // The baseline: a link that does not parse the way r3 already handled (URISyntaxException).
        assertTrue(runCatching { parse(UNPARSABLE) }.exceptionOrNull() is java.net.URISyntaxException)
        val throwing = listOf(
            BAD_FLAGS,
            "intent:#Intent;launchFlags=0x80000000;end",
            BAD_EXTRA,
            "intent://claude.ai/x#Intent;scheme=https;b.k=x;end",
            "intent://claude.ai/x#Intent;scheme=https;f.k=x;end",
        )
        for (raw in throwing) {
            val thrown = runCatching { parse(raw) }.exceptionOrNull()
            assertTrue("$raw: $thrown", thrown != null && thrown !is java.net.URISyntaxException)
        }
        for (raw in listOf(UNPARSABLE) + throwing) {
            val l = ClaudeLoginLink.parse(raw)!!
            assertFalse(raw, l.web)
            assertNull(raw, LoginLinkOpener.intentFor(l))
            assertFalse(raw, LoginLinkOpener.browser(appContext()).open(l))
        }
    }

    /** r4 (security N1): the Open tap on `launchFlags=zz`, through the real opener: no crash, the note any unparsable link gets. */
    @Test fun anOpenTapOnAnIntentLinkWithABadLaunchFlagsNumberCrashesNothing() = openTapSaysUnopened(BAD_FLAGS)

    /** r4 (security N1): the Open tap on an `i.k=x` extra, through the real opener: no crash, the note any unparsable link gets. */
    @Test fun anOpenTapOnAnIntentLinkWithABadTypedExtraCrashesNothing() = openTapSaysUnopened(BAD_EXTRA)

    /** The baseline the two above match: a link parseUri refuses with a URISyntaxException. */
    @Test fun anOpenTapOnAnIntentLinkThatDoesNotParseSaysSo() = openTapSaysUnopened(UNPARSABLE)

    private fun appContext() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun openTapSaysUnopened(raw: String) {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions, LoginLinkOpener.browser(appContext())))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, ClaudeLoginLink.parse(raw)!!, false, null), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.OPEN_CAPTION_WEB)
        tap(ClaudeAccountsTags.loginOpen("claude-work"))
        waitFor(ClaudeAccountsCopy.LINK_UNOPENED_APP)
        tag(ClaudeAccountsTags.loginOpen("claude-work") + ":unopened").assertExists()
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

    /**
     * The refusal is said (as the web shows a change's error) and nothing is disabled because of it:
     * reads go on, and the next change is simply sent and clears the note (the web clears a change's
     * error when it starts). No app-only "Try again" gate.
     */
    @Test fun aServerWithoutTheOwnerGradeChangeIsSaidAndNothingBreaks() {
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
        for (id in listOf("claude-default", "claude-work", "claude-fresh")) {
            tag(ClaudeAccountsTags.login(id)).assertIsEnabled()
            tag(ClaudeAccountsTags.logout(id)).assertIsEnabled()
            tag(ClaudeAccountsTags.remove(id)).assertIsEnabled()
            tag(ClaudeAccountsTags.check(id)).assertIsEnabled()
        }
        tag(ClaudeAccountsTags.SyncNow).assertIsEnabled()
        tap(ClaudeAccountsTags.check("claude-work"))
        waitFor("Logged in — work@example.com")
        assertEquals(listOf("add"), actions.names())
        // The next change is sent as it is on the web, and the note goes while it runs.
        tap(ClaudeAccountsTags.SyncNow)
        waitForCall(actions, "runSync")
        tag(ClaudeAccountsTags.OwnerNeeded).assertDoesNotExist()
        actions.answer("runSync", SecurityResult.OwnerSignInNeeded(ORIGIN))
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.OwnerNeeded) }
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
            SettingsUnderTest(store.prefs, state, layout = layout, claudeAccounts = current)
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

    /**
     * r3 (security F2): two category taps in one frame (no redraw between them) both land: the second
     * is built on the config as the first left it, not on the one drawn, so it never undoes the first.
     */
    @Test fun twoCategoryTapsInOneFrameBothLand() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        waitFor("Sync across accounts")
        compose.waitForIdle()
        val hooks = tag(ClaudeAccountsTags.syncCategory("hooks")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val skills = tag(ClaudeAccountsTags.syncCategory("skills")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            hooks()
            skills()
        }
        compose.waitUntil(5_000) { actions.calls.count { it.name == "saveSync" } == 2 }
        val c = AccountsFixtures.SYNC.config
        val saves = actions.calls.filter { it.name == "saveSync" }.map { it.arg }
        assertEquals("the first", c.copy(categories = c.categories.copy(hooks = true)).toString(), saves[0])
        assertEquals("the second keeps the first", c.copy(categories = c.categories.copy(hooks = true, skills = false)).toString(), saves[1])
        compose.waitForIdle()
        assertEquals(ToggleableState.On, tag(ClaudeAccountsTags.syncCategory("hooks")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        assertEquals(ToggleableState.Off, tag(ClaudeAccountsTags.syncCategory("skills")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
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

    // ---- r2: answers fold into the state as it is when they land (verifier P2) ---------------------------

    private fun hooksOn(): ClaudeSyncSaved {
        val c = AccountsFixtures.SYNC.config
        return ClaudeSyncSaved(c.copy(categories = c.categories.copy(hooks = true)), AccountsFixtures.SYNC.lastResult)
    }

    private fun hooks(): ToggleableState = tag(ClaudeAccountsTags.syncCategory("hooks")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState]

    /** Add, then hold the list re-read it triggers; return the gate that releases it. */
    private fun addWithTheReReadHeld(reads: () -> Int, actions: FakeAccountActions) {
        tap(ClaudeAccountsTags.Add)
        tag(ClaudeAccountsTags.AddField).performTextReplacement("x")
        tap(ClaudeAccountsTags.AddSubmit)
        waitForCall(actions, "add")
        actions.answer("add", SecurityResult.Ok(Unit, ORIGIN, null))
        compose.waitUntil(5_000) { reads() == 2 }
        compose.waitForIdle()
    }

    @Test fun aSyncChangeSurvivesAListReReadThatWasInFlightAndTheNextSaveCarriesIt() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lists = 0
        val reads = FakeAccounts(listGate = { if (++lists == 2) gate.await() })
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        waitFor("Sync across accounts")
        addWithTheReReadHeld({ lists }, actions)
        tap(ClaudeAccountsTags.syncCategory("hooks"))
        waitForCall(actions, "saveSync")
        actions.answer("saveSync", SecurityResult.Ok(hooksOn(), ORIGIN, null))
        compose.waitUntil(5_000) { !actions.pending("saveSync") && compose.isDrawnEnabled(ClaudeAccountsTags.syncCategory("skills")) }
        assertEquals("positive control: the save turned Hooks on", ToggleableState.On, hooks())
        // The re-read that was in flight lands now: it must not put the old sync state back.
        gate.complete(Unit)
        compose.waitUntil(5_000) { "list" in reads.calls && lists == 2 }
        compose.waitForIdle()
        compose.waitForIdle()
        assertEquals(ToggleableState.On, hooks())
        tap(ClaudeAccountsTags.syncCategory("skills"))
        compose.waitUntil(5_000) { actions.calls.count { it.name == "saveSync" } == 2 }
        val second = actions.calls.filter { it.name == "saveSync" }[1].arg!!
        assertTrue("the next save keeps Hooks on: $second", second.contains("hooks=true") && second.contains("skills=false"))
    }

    @Test fun aSyncNowResultSurvivesAListReReadThatWasInFlight() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lists = 0
        val reads = FakeAccounts(listGate = { if (++lists == 2) gate.await() })
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        waitFor("Sync across accounts")
        addWithTheReReadHeld({ lists }, actions)
        tap(ClaudeAccountsTags.SyncNow)
        waitForCall(actions, "runSync")
        actions.answer("runSync", SecurityResult.Ok(ClaudeSyncSaved(AccountsFixtures.SYNC.config, com.tether.app.client.ClaudeSyncResult(1790000000000, "ok", changed = 0, upToDate = 0, error = null)), ORIGIN, null))
        waitFor("0 updated, 0 already current.")
        gate.complete(Unit)
        compose.waitForIdle()
        compose.waitForIdle()
        assertTrue(everything().any { it.contains("0 updated, 0 already current.") })
        assertFalse(everything().any { it.contains("2 updated, 1 already current.") })
    }

    /** The status fold too: a status answer that lands after a sync change keeps the change. */
    @Test fun aStatusAnswerFoldsIntoTheStateAsItIsWhenItLands() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reads = FakeAccounts(statusGate = { gate.await() })
        val actions = FakeAccountActions()
        show(binding(reads, actions))
        waitFor("Sync across accounts")
        tap(ClaudeAccountsTags.check("claude-work"))
        compose.waitUntil(5_000) { "status:claude-work" in reads.calls }
        tap(ClaudeAccountsTags.syncCategory("hooks"))
        waitForCall(actions, "saveSync")
        actions.answer("saveSync", SecurityResult.Ok(hooksOn(), ORIGIN, null))
        compose.waitUntil(5_000) { !actions.pending("saveSync") }
        compose.waitForIdle()
        gate.complete(Unit)
        waitFor("Logged in — work@example.com")
        assertEquals(ToggleableState.On, hooks())
    }

    // ---- r3: the poll as the web's: no limit, no cancel but Cancel (owner rule) ----------------------------

    /**
     * settings-dialog.tsx `pollLogin` (:1561-1581): a refusal (a 4xx with no status) is read as
     * "pending-url" and polled again; the panel stays, the code field stays, nothing is cancelled.
     * The control: the next answer, awaiting the code, is taken as usual.
     */
    @Test fun aRefusedPollIsPolledAgainAsOnTheWeb() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        waitForCall(actions, "pollLogin")
        actions.answer("pollLogin", SecurityResult.Refused(429, "Too many requests.", ORIGIN))
        compose.waitUntil(5_000) { actions.calls.count { it.name == "pollLogin" } == 2 }
        waitFor(ClaudeAccountsCopy.LOGIN_WAITING)
        tag(ClaudeAccountsTags.code("claude-work")).assertExists()
        tag(ClaudeAccountsTags.loginOpen("claude-work")).assertExists()
        assertFalse(actions.calls.any { it.name == "cancelLogin" })
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.LOGIN_PASTE)
        assertFalse(actions.calls.any { it.name == "cancelLogin" })
    }

    /** The server's error ends the panel (the web's `status === "error"`), and nothing is cancelled: the web sends nothing then. */
    @Test fun aLoginThatEndsInAnErrorSendsNothing() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.PendingUrl, null, false, null), ORIGIN, null))
        waitForCall(actions, "pollLogin")
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.Error, null, false, "Claude login did not complete (exit code 1)."), ORIGIN, null))
        waitFor("Claude login did not complete (exit code 1).")
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertFalse(actions.calls.any { it.name == "cancelLogin" })
        // The control: Cancel is the one thing that tells the server.
        tap(ClaudeAccountsTags.loginCancel("claude-work"))
        compose.waitUntil(5_000) { actions.calls.any { it.name == "cancelLogin" } }
    }

    @Test fun aLoginTheServerNoLongerRunsIsNotCancelledAgain() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.PendingUrl, null, false, null), ORIGIN, null))
        waitForCall(actions, "pollLogin")
        actions.answer("pollLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.Idle, null, false, null), ORIGIN, null))
        waitFor(ClaudeAccountsCopy.LOGIN_GONE)
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertFalse(actions.calls.any { it.name == "cancelLogin" })
    }

    // ---- r2: the login host (security P3-1) ------------------------------------------------------------

    @Test fun aLongHostKeepsItsEndAndAHostThatIsNotAnthropicsIsSaid() {
        val evil = ClaudeLoginLink.parse("https://claude.ai.oauth." + ("a".repeat(40) + ".").repeat(2) + "evil.example/oauth/authorize")!!
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions, RecordingOpener()))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, evil, false, null), ORIGIN, null))
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.loginNotAnthropic("claude-work")) }
        assertTrue(everything().contains(ClaudeAccountsCopy.NOT_ANTHROPIC))
        val caption = everything().single { it.startsWith("Complete sign-in in your browser") }
        assertTrue(caption, caption.contains(".evil.example)"))
        // Still a warning, never a refusal: Open is offered.
        tag(ClaudeAccountsTags.loginOpen("claude-work")).assertIsEnabled()
    }

    @Test fun anAnthropicHostHasNoWarning() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.loginOpen("claude-work")) }
        tag(ClaudeAccountsTags.loginNotAnthropic("claude-work")).assertDoesNotExist()
    }

    // ---- r2: the profile id of a look-alike, on its card and its armed Remove (security P4-4) -------------

    /**
     * Two titles that could pass for each other ("Work" and Cyrillic "W\u043Erk"): each card shows its
     * profile id, and the armed Remove names the profile it will remove. A title nothing else could
     * pass for (the control) has neither.
     */
    @Test fun aLookAlikesArmedRemoveNamesItsProfileId() {
        val json = """{"accounts":[{"id":"claude-work","label":"Work"},{"id":"claude-work-2","label":"W\u043Erk"},{"id":"claude-home","label":"Home"}]}"""
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(lists = listOf(com.tether.app.client.ClaudeAccountsResult.Ok(AccountsFixtures.decode(json), ORIGIN))), actions), wait = "Home")
        tag(ClaudeAccountsTags.id("claude-work")).assertExists()
        tag(ClaudeAccountsTags.id("claude-work-2")).assertExists()
        tag(ClaudeAccountsTags.id("claude-home")).assertDoesNotExist()
        tap(ClaudeAccountsTags.remove("claude-work-2"))
        compose.waitUntil(5_000) { removeKeyText("claude-work-2").contains(ClaudeAccountsCopy.CONFIRM_REMOVE) }
        assertTrue(removeKeyText("claude-work-2"), removeKeyText("claude-work-2").contains("profile claude-work-2"))
        // The control: the unique title's armed key names no profile.
        tap(ClaudeAccountsTags.remove("claude-home"))
        compose.waitUntil(5_000) { removeKeyText("claude-home").contains(ClaudeAccountsCopy.CONFIRM_REMOVE) }
        assertFalse(removeKeyText("claude-home"), removeKeyText("claude-home").contains("profile"))
        assertTrue(actions.calls.isEmpty())
    }

    // ---- r2: the code field is the web's plain text field (security P4-2, decided by the web) ---------------

    /**
     * settings-dialog.tsx:1776-1784: `<input type="text">`, no autocomplete or copy guard. The owner's
     * standing rule: no app-only guard, so the code field is no password field and copy is not taken
     * away. What does guard the code stays: it lives only in memory and goes once handed over.
     */
    @Test fun theCodeFieldIsTheWebsPlainTextField() {
        val actions = FakeAccountActions()
        show(binding(FakeAccounts(), actions))
        tap(ClaudeAccountsTags.login("claude-work"))
        waitForCall(actions, "startLogin")
        actions.answer("startLogin", SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.code("claude-work")) }
        tag(ClaudeAccountsTags.code("claude-work")).performTextReplacement("FAKE-PLAIN-CODE")
        val node = tag(ClaudeAccountsTags.code("claude-work")).fetchSemanticsNode()
        // Positive control: the field took the paste, drawn as typed (as the web's).
        assertEquals("FAKE-PLAIN-CODE", node.config[SemanticsProperties.EditableText].text)
        assertFalse(node.config.contains(SemanticsProperties.Password))
        val copy = compose.onAllNodesWithTag(ClaudeAccountsTags.code("claude-work"), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { listOf(it) + generateSequence(it.parent) { p -> p.parent }.toList() }
            .firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.CopyText) }
        assertTrue("no copy guard on the code field", copy == null || copy.label != NoCopyGuardCopy.ACTION_LABEL)
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

    private companion object {
        /** r4 (security N1): parseUri throws NumberFormatException for these, not URISyntaxException. */
        const val BAD_FLAGS = "intent:#Intent;launchFlags=zz;end"
        const val BAD_EXTRA = "intent://claude.ai/x#Intent;scheme=https;i.k=x;end"

        /** parseUri throws URISyntaxException ("unknown EXTRA type"): the unparsable link r3 already handled. */
        const val UNPARSABLE = "intent://claude.ai/x#Intent;scheme=https;x.k=v;end"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ClaudeAccountsChangesPhoneTest : ClaudeAccountsChangesBehaviourBase(TetherLayoutClass.Phone)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ClaudeAccountsChangesExpandedTest : ClaudeAccountsChangesBehaviourBase(TetherLayoutClass.Expanded)
