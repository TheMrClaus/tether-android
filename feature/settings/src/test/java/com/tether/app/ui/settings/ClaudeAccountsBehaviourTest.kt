package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.ClaudeAccountsResult
import com.tether.app.client.FilesAuthority
import com.tether.app.client.HttpClaudeAccountActions
import com.tether.app.client.HttpClaudeAccounts
import com.tether.app.ui.settings.AccountsFixtures.ORIGIN
import com.tether.app.ui.settings.AccountsFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.AccountsFixtures.RAW_SENTINEL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * ta-9q2 / ta-ebc: the Engines tab's Claude accounts section (settings-dialog.tsx 887c222
 * :1380-1867): what it reads and when, what it shows, the answers bound to the shown server, and
 * `plan.raw` nowhere. ta-7rh: the changes are offered (their behaviour: ClaudeAccountsChangesBehaviourTest),
 * and over the real client only their fixed routes leave.
 *
 * ta-b72: the v2 rule (a StandardTestDispatcher for the composition). Under the v1 rule the
 * section's effects ran on an unconfined dispatcher, so an answer the real reader finished on an
 * OkHttp/IO thread resumed THERE and wrote Compose state off the main thread, racing the test
 * thread's layout ("performMeasureAndLayout called during measure layout"). Now each answer is
 * dispatched back to the test thread, as AndroidUiDispatcher resumes it on main in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ClaudeAccountsBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)

    private fun show(binding: ClaudeAccountsBinding) {
        compose.setContent { SettingsUnderTest(store.prefs, state, claudeAccounts = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun everything(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { out += it }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) { everything().any { it.contains(text) } }

    @Test fun openingTheTabReadsTheListAndTheSyncStateOnceAndNothingElse() {
        val fake = FakeAccounts()
        show(fake.binding())
        waitFor("Claude Code (work)")
        compose.waitUntil(5_000) { "sync" in fake.calls }
        compose.waitForIdle()
        assertEquals(listOf("list", "sync"), fake.calls.toList())
        // The section sits between the engines and Custom providers, as on the web (ta-dh1 drew the
        // engines and Host config, ta-q6p the profiles).
        compose.onNodeWithTag(ProfileTags.Section).assertExists()
    }

    @Test fun eachAccountShowsItsLabelPlanOrganizationStatusAndPath() {
        show(FakeAccounts().binding())
        waitFor("Claude Code (work)")
        val all = everything()
        for (text in listOf("Claude Code (default)", "Claude Code (work)", "Claude Code (fresh)", "Team Premium 5x", "Team Standard", "Brainrocket", "Pre-existing")) {
            assertTrue(text, all.contains(text))
        }
        assertEquals(3, all.count { it == "Status unknown" })
        tag(ClaudeAccountsTags.plan("claude-default")).assertExists()
        tag(ClaudeAccountsTags.plan("claude-fresh")).assertDoesNotExist()
        tag(ClaudeAccountsTags.organization("claude-default")).assertExists()
        tag(ClaudeAccountsTags.organization("claude-work")).assertDoesNotExist()
        // The CLAUDE_CONFIG_DIR, only in the Sign-in row and only when the server says there is one.
        assertTrue(all.contains("/srv/tether/state/claude-accounts/claude-work"))
        assertTrue(all.contains(ClaudeAccountsPresentation.NO_HOME))
        assertEquals(1, all.count { it == "/srv/tether/.claude" })
        // The sync state (ta-7rh: as its controls: the mode and primary said with their values, the categories ticked).
        for (text in listOf("Sync across accounts", "Sync across accounts: Sync selected categories", "Primary account for sync: Claude Code (default)", "Last synced at 10:13:20 — 2 updated, 1 already current.")) {
            assertTrue(text, all.contains(text))
        }
        for ((key, on) in listOf("plugins" to true, "skills" to true, "mcp" to true, "hooks" to false)) {
            val state = tag(ClaudeAccountsTags.syncCategory(key)).fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
            assertEquals(key, if (on) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off, state)
        }
    }

    /** #231: the payload's `raw` holds the sentinel; it is in no text, label, tag, log line or stored byte. */
    @Test fun theRawSentinelIsNowhere() {
        ShadowLog.clear()
        show(FakeAccounts().binding())
        waitFor("Team Premium 5x")
        compose.onNodeWithTag(ClaudeAccountsTags.check("claude-work"), useUnmergedTree = true).performScrollTo().performClick()
        waitFor("Logged in — work@example.com")
        val drawn = everything()
        for (leak in listOf(RAW_SENTINEL, "team_tier_1", "default_raven", "default_claude_max_5x", "claude_team")) {
            assertFalse(leak, drawn.any { it.contains(leak) })
            assertFalse(leak, ShadowLog.getLogs().any { it.msg?.contains(leak) == true || it.tag?.contains(leak) == true })
            assertFalse(leak, store.stored().toString().contains(leak))
            assertFalse(leak, (with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }).toString().contains(leak))
        }
    }

    /**
     * ta-7rh (owner 2026-10-02, tether #236: the app has the web's permissions): the changes are
     * offered, with no owner note until a server refuses one; the host default still has no Rename.
     */
    @Test fun theChangeControlsAreOfferedWithNoOwnerNote() {
        show(FakeAccounts().binding().copy(actions = FakeAccountActions()))
        waitFor("Claude Code (work)")
        tag(ClaudeAccountsTags.OwnerNeeded).assertDoesNotExist()
        for (id in listOf("claude-default", "claude-work", "claude-fresh")) {
            tag(ClaudeAccountsTags.login(id)).assertIsEnabled()
            tag(ClaudeAccountsTags.logout(id)).assertIsEnabled()
            tag(ClaudeAccountsTags.remove(id)).assertIsEnabled()
            tag(ClaudeAccountsTags.check(id)).assertIsEnabled()
        }
        tag(ClaudeAccountsTags.rename("claude-work")).assertIsEnabled()
        tag(ClaudeAccountsTags.rename("claude-default")).assertDoesNotExist()
        tag(ClaudeAccountsTags.Add).assertIsEnabled()
        tag(ClaudeAccountsTags.SyncNow).assertIsEnabled()
    }

    /** An account whose id cannot be put in a path is listed, but nothing may change it. */
    @Test fun anAccountThatCannotBeNamedOffersNoChange() {
        val json = """{"accounts":[{"id":"Bad/Id","label":"Odd"},{"id":"claude-work","label":"Claude Code (work)"}]}"""
        show(FakeAccounts(lists = listOf(ClaudeAccountsResult.Ok(AccountsFixtures.decode(json), ORIGIN))).binding().copy(actions = FakeAccountActions()))
        waitFor("Odd")
        for (key in listOf(ClaudeAccountsTags.login("Bad/Id"), ClaudeAccountsTags.logout("Bad/Id"), ClaudeAccountsTags.remove("Bad/Id"), ClaudeAccountsTags.rename("Bad/Id"))) {
            tag(key).assertIsNotEnabled()
        }
        tag(ClaudeAccountsTags.login("claude-work")).assertIsEnabled()
    }

    /**
     * Over the real reader and the real changes on a fake server (tether 90fbb9f shapes): Add, Log out
     * (sent at once, as on the web), Remove (the web's two taps) and Sync now each send exactly their
     * fixed route, by their own method, with the credential and the web's body, and nothing else leaves.
     * r2: the Sync now result lands while the post-Remove list re-read may still be in flight, and is
     * kept (the fold is of the state as it is when an answer lands): this used to flake.
     */
    @Test fun overTheRealClientOnlyTheFixedRoutesAreSent() {
        val server = MockWebServer()
        val arrived = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived += "${request.method} ${request.path}"
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when (request.method to request.path) {
                    "GET" to "/api/claude-accounts" -> json.setBody(AccountsFixtures.LIST_JSON)
                    "GET" to "/api/claude-accounts/sync" -> json.setBody(AccountsFixtures.SYNC_JSON)
                    "GET" to "/api/claude-accounts/claude-work/status" -> json.setBody("""{"ok":true,"id":"claude-work","loggedIn":false,"authMethod":"none"}""")
                    "POST" to "/api/claude-accounts" -> json.setResponseCode(201).setBody("""{"profile":{"id":"claude-home","extends":"claude","label":"Claude Code (home)"}}""")
                    "POST" to "/api/claude-accounts/claude-work/logout" -> json.setBody("""{"ok":true,"id":"claude-work","loggedOut":true}""")
                    "DELETE" to "/api/claude-accounts/claude-fresh" -> json.setBody("""{"ok":true,"removed":true,"credentialsDeleted":false}""")
                    "POST" to "/api/claude-accounts/sync/run" -> json.setBody("""{"config":{"mode":"selected","categories":{"plugins":true,"skills":true,"hooks":false,"mcp":true},"primaryAccountId":"claude-default"},"result":{"ranAt":1790000000000,"status":"ok","entries":[]}}""")
                    else -> json.setResponseCode(404).setBody("""{"error":"unexpected"}""")
                }
            }
        }
        server.start()
        try {
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            val authority = { FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") } }
            val origin = "http://${server.hostName}:${server.port}"
            val binding = ClaudeAccountsBinding(HttpClaudeAccounts(http, authority = authority), origin, AccountsFixtures.TIME, actions = HttpClaudeAccountActions(http, authority = authority))
            compose.setContent {
                SettingsUnderTest(store.prefs, state, claudeAccounts = binding)
            }
            compose.waitUntil(5_000) { state.draft != null }
            waitFor("Claude Code (work)")
            waitFor("Sync across accounts")
            tag(ClaudeAccountsTags.Add).performScrollTo().performClick()
            compose.onNodeWithTag(ClaudeAccountsTags.AddField, useUnmergedTree = true).performTextReplacement("home")
            tag(ClaudeAccountsTags.AddSubmit).performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(ClaudeAccountsTags.AddField, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            // Log out goes at once (no confirmation, as on the web); its status re-read follows it.
            tag(ClaudeAccountsTags.logout("claude-work")).performScrollTo().performClick()
            compose.waitUntil(5_000) { "GET /api/claude-accounts/claude-work/status" in arrived }
            // Remove: the first tap arms it ("Confirm remove"), the second sends it.
            tag(ClaudeAccountsTags.remove("claude-fresh")).performScrollTo().performClick()
            waitFor(ClaudeAccountsCopy.CONFIRM_REMOVE)
            assertFalse("one tap sends nothing", arrived.any { it.startsWith("DELETE") })
            tag(ClaudeAccountsTags.remove("claude-fresh")).performScrollTo().performClick()
            waitFor("Removed Claude Code (fresh).")
            compose.waitUntil(5_000) { compose.isDrawnEnabled(ClaudeAccountsTags.SyncNow) }
            tag(ClaudeAccountsTags.SyncNow).performScrollTo().performClick()
            waitFor("0 updated, 0 already current.")
            compose.waitForIdle()
            val seen = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
            val writes = seen.filter { it.method != "GET" }.map { Triple(it.method, it.path, it.body.readUtf8()) }
            assertEquals(
                listOf(
                    Triple("POST", "/api/claude-accounts", """{"nickname":"home"}"""),
                    Triple("POST", "/api/claude-accounts/claude-work/logout", "{}"),
                    Triple("DELETE", "/api/claude-accounts/claude-fresh", """{"deleteCredentials":false}"""),
                    Triple("POST", "/api/claude-accounts/sync/run", "{}"),
                ),
                writes,
            )
            for (req in seen) {
                assertEquals(req.path, "Bearer tthr_test", req.getHeader("Authorization"))
                if (req.method == "GET") {
                    assertTrue(req.path, req.path in setOf("/api/claude-accounts", "/api/claude-accounts/sync", "/api/claude-accounts/claude-work/status"))
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun checkReadsThatAccountsStatus() {
        val fake = FakeAccounts()
        show(fake.binding())
        waitFor("Claude Code (work)")
        tag(ClaudeAccountsTags.check("claude-work")).performScrollTo().performClick()
        waitFor("Logged in — work@example.com")
        tag(ClaudeAccountsTags.check("claude-fresh")).performScrollTo().performClick()
        waitFor("Status unknown — No such Claude account.")
        assertEquals(listOf("status:claude-work", "status:claude-fresh"), fake.calls.filter { it.startsWith("status") })
        // The others are untouched.
        assertTrue(everything().contains("Status unknown"))
    }

    /** r2: two taps on Check in one frame send ONE status read (each runs `claude auth status` on the server). */
    @Test fun twoTapsInOneFrameAskOnce() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeAccounts(statusGate = { gate.await() })
        show(fake.binding())
        waitFor("Claude Code (work)")
        val check = tag(ClaudeAccountsTags.check("claude-work")).performScrollTo()
        val click = check.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Both taps before any recomposition: the key is still drawn enabled for the second.
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
        assertEquals(1, fake.calls.count { it == "status:claude-work" })
        // A third tap while the first is in flight sends nothing either.
        compose.runOnUiThread { click() }
        compose.waitForIdle()
        assertEquals(1, fake.calls.count { it == "status:claude-work" })
        // Once the answer lands, Check asks again.
        gate.complete(Unit)
        waitFor("Logged in — work@example.com")
        tag(ClaudeAccountsTags.check("claude-work")).performClick()
        compose.waitUntil(5_000) { fake.calls.count { it == "status:claude-work" } == 2 }
    }

    /**
     * r3, the verifier's probe (ScratchVerifyTa9q2UiTest.repeatedCheckTaps): after a check has
     * settled, with a source that answers AT ONCE (its gate open), two OnClick invocations
     * back-to-back on the UI thread. The first read can finish inside the first tap, so the status
     * guard alone lets the second through; the per-id guard holds it until the next frame.
     */
    @Test fun twoTapsInOneFrameAskOnceEvenWhenTheAnswerIsInstant() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeAccounts(statusGate = { gate.await() })
        show(fake.binding())
        waitFor("Claude Code (work)")
        val check = tag(ClaudeAccountsTags.check("claude-work"))
        check.performScrollTo().performClick()
        compose.waitForIdle()
        gate.complete(Unit)
        waitFor("Logged in — work@example.com")
        val before = fake.calls.count { it == "status:claude-work" }
        assertEquals(1, before)
        compose.runOnUiThread {
            val node = check.fetchSemanticsNode()
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        }
        compose.waitForIdle()
        assertEquals("same-frame extra calls", 1, fake.calls.count { it == "status:claude-work" } - before)
        // The guard is per frame, not a lock-out: a later tap asks again.
        waitFor("Logged in — work@example.com")
        check.performClick()
        compose.waitUntil(5_000) { fake.calls.count { it == "status:claude-work" } == before + 2 }
    }

    /** r2: two accounts whose titles could pass for each other each show their profile id, by the one-line rule. */
    @Test fun lookAlikeAccountsShowTheirIds() {
        val json = """{"accounts":[
            {"id":"claude-work","label":"Work"},
            {"id":"claude-work-2","label":"W\u043Erk"},
            {"id":"claude-home","label":"Home"}]}"""
        show(FakeAccounts(lists = listOf(ClaudeAccountsResult.Ok(AccountsFixtures.decode(json), ORIGIN))).binding())
        waitFor("Home")
        for (id in listOf("claude-work", "claude-work-2")) {
            val node = tag(ClaudeAccountsTags.id(id)).fetchSemanticsNode()
            val drawn = node.config[SemanticsProperties.Text].joinToString("") { it.text }
            // Breakable anywhere, but the whole id, end included.
            assertEquals(id, drawn.replace("\u2060\u200B", ""))
            assertEquals("profile $id", node.config[SemanticsProperties.ContentDescription].single())
        }
        tag(ClaudeAccountsTags.id("claude-home")).assertDoesNotExist()
    }

    @Test fun whileLoadingItSaysSo() {
        show(FakeAccounts(hangList = true).binding())
        tag(ClaudeAccountsTags.Loading).assertExists()
        assertTrue(everything().contains(ClaudeAccountsPresentation.LOADING))
        // r3: the web's "Add Claude account" sits outside the list's conditional (settings-dialog.tsx:1838-1862).
        tag(ClaudeAccountsTags.Add).assertIsEnabled()
    }

    /**
     * r3 (web parity): Add works while the list is still loading and after the list failed, as the
     * web's (settings-dialog.tsx:1838-1862); signed out it is not offered (the control).
     */
    @Test fun addIsOfferedWhileTheListLoads() = addWithoutAList(FakeAccounts(hangList = true), ClaudeAccountsPresentation.LOADING)

    @Test fun addIsOfferedAfterTheListFailed() =
        addWithoutAList(FakeAccounts(lists = listOf(ClaudeAccountsResult.Unavailable(500, ORIGIN))), ClaudeAccountsPresentation.LIST_ERROR)

    private fun addWithoutAList(reads: FakeAccounts, shown: String) {
        val actions = FakeAccountActions(auto = { if (it == "add") com.tether.app.client.SecurityResult.Ok(Unit, ORIGIN, null) else null })
        show(reads.binding().copy(actions = actions))
        waitFor(shown)
        tag(ClaudeAccountsTags.Add).performScrollTo().performClick()
        compose.onNodeWithTag(ClaudeAccountsTags.AddField, useUnmergedTree = true).performTextReplacement("home")
        tag(ClaudeAccountsTags.AddSubmit).performScrollTo().performClick()
        compose.waitUntil(5_000) { actions.calls.any { it.name == "add" } }
        assertEquals("add($ORIGIN, home)", actions.calls.single().toString())
    }

    // The control (signed out, no Add) is ClaudeAccountsChangesBehaviourTest.signedOutOffersNoChange.

    @Test fun aFailureShowsTheStatusRowAndRetryReadsAgain() {
        val fake = FakeAccounts(lists = listOf(ClaudeAccountsResult.Unavailable(500, ORIGIN), ClaudeAccountsResult.Ok(AccountsFixtures.LIST, ORIGIN)))
        show(fake.binding())
        waitFor(ClaudeAccountsPresentation.LIST_ERROR)
        tag(ClaudeAccountsTags.Notice).assertExists()
        tag(ClaudeAccountsTags.Retry).performScrollTo().performClick()
        waitFor("Claude Code (work)")
        tag(ClaudeAccountsTags.Notice).assertDoesNotExist()
        assertEquals(2, fake.calls.count { it == "list" })
    }

    @Test fun aSignInGatewayIsNamed() {
        show(FakeAccounts(lists = listOf(ClaudeAccountsResult.Blocked(302, ORIGIN))).binding())
        waitFor(ClaudeAccountsPresentation.BLOCKED)
        assertTrue(everything().any { it.contains(ClaudeAccountsPresentation.BLOCKED_DETAIL) })
        tag(ClaudeAccountsTags.Retry).assertIsEnabled()
    }

    @Test fun anEmptyListDrawsNoCardAndNoSync() {
        val fake = FakeAccounts(lists = listOf(ClaudeAccountsResult.Ok(emptyList(), ORIGIN)))
        show(fake.binding())
        compose.waitUntil(5_000) { "list" in fake.calls }
        compose.waitForIdle()
        tag(ClaudeAccountsTags.Loading).assertDoesNotExist()
        tag(ClaudeAccountsTags.Notice).assertDoesNotExist()
        tag(ClaudeAccountsTags.Sync).assertDoesNotExist()
        tag(ClaudeAccountsTags.Add).assertExists()
        assertEquals(listOf("list"), fake.calls.toList())
    }

    /** The goldens' seam: a seed is the first frame and nothing is read on opening; a seed for another server is ignored. */
    @Test fun aSeedIsShownAtOnceAndOnlyForItsServer() {
        val seeded = ClaudeAccountsState(origin = ORIGIN, accounts = AccountsFixtures.LIST.take(1))
        val fake = FakeAccounts()
        show(ClaudeAccountsBinding(fake, ORIGIN, AccountsFixtures.TIME, initial = seeded))
        assertTrue(everything().contains("Claude Code (default)"))
        assertFalse(everything().contains("Claude Code (work)"))
        assertTrue(fake.calls.isEmpty())
        // Check still asks.
        tag(ClaudeAccountsTags.check("claude-default")).performScrollTo().performClick()
        compose.waitUntil(5_000) { "status:claude-default" in fake.calls }
    }

    @Test fun aSeedForAnotherServerIsIgnored() {
        val fake = FakeAccounts()
        val foreign = ClaudeAccountsState(origin = OTHER_ORIGIN, accounts = AccountsFixtures.decode("""{"accounts":[{"id":"claude-x","label":"Foreign"}]}"""))
        show(ClaudeAccountsBinding(fake, ORIGIN, AccountsFixtures.TIME, initial = foreign))
        waitFor("Claude Code (work)")
        assertFalse(everything().contains("Foreign"))
        assertEquals("list", fake.calls.first())
    }

    @Test fun signedOutAsksNothing() {
        val fake = FakeAccounts()
        show(ClaudeAccountsBinding(fake, null))
        waitFor("Signed out")
        assertTrue(fake.calls.isEmpty())
    }

    /** settings-dialog.tsx:1420-1424: a first paint from the offline snapshot is read again once, 4 s later. */
    @Test fun anOfflinePlanIsReadAgainOnce() {
        val offline = AccountsFixtures.decode(AccountsFixtures.LIST_JSON.replace("\"source\":\"profile\"", "\"source\":\"credentials\""))
        val fake = FakeAccounts(lists = listOf(ClaudeAccountsResult.Ok(offline, ORIGIN)))
        compose.mainClock.autoAdvance = false
        compose.setContent { SettingsUnderTest(store.prefs, state, claudeAccounts = fake.binding()) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(5_000) { fake.calls.count { it == "list" } == 1 }
        compose.mainClock.advanceTimeBy(ClaudeAccountsModel.PLAN_RETRY_MS + 500)
        compose.waitUntil(5_000) { fake.calls.count { it == "list" } == 2 }
        compose.mainClock.advanceTimeBy(ClaudeAccountsModel.PLAN_RETRY_MS * 3)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("never a poll", 2, fake.calls.count { it == "list" })
    }

    /** A new server starts from nothing; an answer that arrives for the previous one is dropped. */
    @Test fun aReplyForThePreviousServerIsDropped() {
        val gate = CompletableDeferred<Unit>()
        val first = FakeAccounts(origin = ORIGIN, listGate = { gate.await() })
        val second = FakeAccounts(
            origin = OTHER_ORIGIN,
            lists = listOf(ClaudeAccountsResult.Ok(AccountsFixtures.LIST.take(1), OTHER_ORIGIN)),
        )
        var binding by mutableStateOf(first.binding())
        compose.setContent { SettingsUnderTest(store.prefs, state, claudeAccounts = binding) }
        compose.waitUntil(5_000) { "list" in first.calls }
        binding = second.binding()
        waitFor("Claude Code (default)")
        // The first server's list lands late: it must not replace the second's.
        gate.complete(Unit)
        compose.waitForIdle()
        val all = everything()
        assertFalse(all.contains("Claude Code (work)"))
        assertTrue(all.contains("Claude Code (default)"))
    }

    /** Server text by the text rules: the label rule for names, the path rule for a CLAUDE_CONFIG_DIR. */
    @Test fun serverTextIsDrawnSafely() {
        val json = """{"accounts":[{"id":"claude-x","label":"Claude Code (\u202Ekrow)","configDir":"/srv/\u202Eevil\u2066dir","hasConfigDir":true,"plan":{"label":"Max\u200B 20x","organizationName":"Acme\u202E Corp","source":"profile","raw":{}}}]}"""
        show(FakeAccounts(lists = listOf(ClaudeAccountsResult.Ok(AccountsFixtures.decode(json), ORIGIN))).binding())
        waitFor("Claude Code (krow)")
        val all = everything()
        assertTrue(all.contains("Max 20x"))
        assertTrue(all.contains("Acme Corp"))
        // The path's controls are drawn as visible tokens, never applied.
        val path = all.single { it.contains("evil") }
        assertTrue(path, path.contains("U+202E") && path.contains("U+2066"))
        assertFalse(path, path.contains('\u202E') || path.contains('\u2066'))
        assertFalse(all.any { it.contains('\u202E') })
    }
}
