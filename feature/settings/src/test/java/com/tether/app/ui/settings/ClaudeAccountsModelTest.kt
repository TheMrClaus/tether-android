package com.tether.app.ui.settings

import com.tether.app.client.ClaudeAccount
import com.tether.app.client.ClaudeAccountPlan
import com.tether.app.client.ClaudeAccountPlanSource
import com.tether.app.client.ClaudeAccountStatus
import com.tether.app.client.ClaudeAccountsResult
import com.tether.app.client.ClaudeSyncCategories
import com.tether.app.client.ClaudeSyncConfig
import com.tether.app.client.ClaudeSyncMode
import com.tether.app.client.ClaudeSyncResult
import com.tether.app.client.ClaudeAccountsSync
import com.tether.app.ui.settings.AccountsFixtures.ORIGIN
import com.tether.app.ui.settings.AccountsFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.AccountsFixtures.RAW_SENTINEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-9q2: ClaudeAccountsSection's state and words (settings-dialog.tsx 887c222 :1229-1867), pure:
 * the origin binding, the status copy, the sync section's rules and summary, and the text rules on
 * every server string.
 */
class ClaudeAccountsModelTest {
    private val list = AccountsFixtures.LIST
    private val time = AccountsFixtures.TIME
    private fun loaded() = ClaudeAccountsModel.foldList(ClaudeAccountsState(), ClaudeAccountsResult.Ok(list, ORIGIN), ORIGIN)

    private fun account(id: String = "claude-a", label: String = "A", syncEligible: Boolean = true, plan: ClaudeAccountPlan? = null, dir: String? = null) =
        ClaudeAccount(id, label, true, dir, dir != null, true, false, syncEligible, plan)

    @Test fun theListIsTakenOnlyFromTheShownServer() {
        val state = loaded()
        assertEquals(ORIGIN, state.origin)
        assertEquals(list, state.accounts)
        // Another server's answer is dropped outright, even into an empty state.
        assertSame(state, ClaudeAccountsModel.foldList(state, ClaudeAccountsResult.Ok(emptyList(), OTHER_ORIGIN), ORIGIN))
        assertEquals(ClaudeAccountsState(), ClaudeAccountsModel.foldList(ClaudeAccountsState(), ClaudeAccountsResult.Ok(list, OTHER_ORIGIN), ORIGIN))
        // No server shown: nothing is taken.
        assertSame(state, ClaudeAccountsModel.foldList(state, ClaudeAccountsResult.Ok(emptyList(), ORIGIN), null))
        // The shown server changed: its first answer starts from nothing (no statuses or sync carried over).
        val checked = ClaudeAccountsModel.foldStatus(ClaudeAccountsModel.checking(state, "claude-work"), "claude-work", ClaudeAccountsResult.Ok(AccountsFixtures.LOGGED_IN, ORIGIN), ORIGIN)
        val moved = ClaudeAccountsModel.foldList(checked.copy(sync = AccountsFixtures.SYNC), ClaudeAccountsResult.Ok(list.take(1), OTHER_ORIGIN), OTHER_ORIGIN)
        assertEquals(ClaudeAccountsState(origin = OTHER_ORIGIN, accounts = list.take(1)), moved)
    }

    @Test fun aFailureShowsInPlaceOfTheListAndARefusalDropsIt() {
        val state = loaded()
        val failed = ClaudeAccountsModel.foldList(state, ClaudeAccountsResult.Unavailable(500, ORIGIN), ORIGIN)
        assertEquals(AccountsFault.Unavailable(500), failed.listFault)
        assertEquals(list, failed.accounts)
        // Retry that succeeds clears it.
        assertNull(ClaudeAccountsModel.foldList(failed, ClaudeAccountsResult.Ok(list, ORIGIN), ORIGIN).listFault)
        for (refusal in listOf(ClaudeAccountsResult.SignedOut(ORIGIN), ClaudeAccountsResult.Forbidden(ORIGIN))) {
            val dropped = ClaudeAccountsModel.foldList(state, refusal, ORIGIN)
            assertNull(dropped.accounts)
            assertTrue(dropped.listFault != null)
        }
        // No origin on the answer (signed out before asking, local network blocked): it still counts.
        assertEquals(AccountsFault.LocalNetworkBlocked, ClaudeAccountsModel.foldList(state, ClaudeAccountsResult.LocalNetworkBlocked, ORIGIN).listFault)
    }

    @Test fun aStatusIsTakenOnlyForAnAccountOfTheShownServer() {
        val state = loaded()
        val checking = ClaudeAccountsModel.checking(state, "claude-work")
        assertEquals(AccountStatusState.Checking, checking.statuses["claude-work"])
        // An id that is not listed, or not checkable, never shows as checking.
        assertSame(state, ClaudeAccountsModel.checking(state, "claude-gone"))
        val odd = ClaudeAccountsModel.foldList(ClaudeAccountsState(), ClaudeAccountsResult.Ok(listOf(account(id = "../x")), ORIGIN), ORIGIN)
        assertSame(odd, ClaudeAccountsModel.checking(odd, "../x"))
        // Another server's answer, or an answer once the shown server moved on, is dropped.
        assertSame(checking, ClaudeAccountsModel.foldStatus(checking, "claude-work", ClaudeAccountsResult.Ok(AccountsFixtures.LOGGED_IN, OTHER_ORIGIN), ORIGIN))
        assertSame(checking, ClaudeAccountsModel.foldStatus(checking, "claude-work", ClaudeAccountsResult.Ok(AccountsFixtures.LOGGED_IN, ORIGIN), OTHER_ORIGIN))
        val known = ClaudeAccountsModel.foldStatus(checking, "claude-work", ClaudeAccountsResult.Ok(AccountsFixtures.LOGGED_IN, ORIGIN), ORIGIN)
        assertEquals(AccountStatusState.Known(AccountsFixtures.LOGGED_IN), known.statuses["claude-work"])
        val refused = ClaudeAccountsModel.foldStatus(known, "claude-fresh", ClaudeAccountsResult.Refused(404, "No such Claude account.", ORIGIN), ORIGIN)
        assertEquals(AccountStatusState.Failed(AccountsFault.Refused("No such Claude account.")), refused.statuses["claude-fresh"])
    }

    /** settings-dialog.tsx `statusCopy`. */
    @Test fun theStatusReadsAsTheWebSaysIt() {
        val copy = ClaudeAccountsPresentation::statusCopy
        assertEquals("Status unknown", copy(null))
        assertEquals("Checking…", copy(AccountStatusState.Checking))
        assertEquals("Logged in — work@example.com", copy(AccountStatusState.Known(AccountsFixtures.LOGGED_IN)))
        assertEquals("Logged in", copy(AccountStatusState.Known(ClaudeAccountStatus(true, "claude.ai", null, null))))
        assertEquals("Not logged in", copy(AccountStatusState.Known(ClaudeAccountStatus(false, "none", null, null))))
        assertEquals("Status unknown — unrecognized-status-output", copy(AccountStatusState.Known(ClaudeAccountStatus(false, null, null, "unrecognized-status-output"))))
        assertEquals("Status unknown — No such Claude account.", copy(AccountStatusState.Failed(AccountsFault.Refused("No such Claude account."))))
        assertEquals("Status unknown — Status check failed.", copy(AccountStatusState.Failed(AccountsFault.Unavailable(500))))
        assertEquals("Status unknown — Status check failed.", copy(AccountStatusState.Failed(AccountsFault.Unavailable(null))))
        assertEquals("Status unknown — Blocked by a sign-in page", copy(AccountStatusState.Failed(AccountsFault.Blocked(302))))
        assertEquals("Status unknown — Signed out", copy(AccountStatusState.Failed(AccountsFault.SignedOut)))
    }

    @Test fun theViewHasTheWebsStates() {
        val loading = ClaudeAccountsPresentation.view(ClaudeAccountsState(), time)
        assertTrue(loading.loading)
        assertNull(loading.notice)
        val error = ClaudeAccountsPresentation.view(loaded().copy(listFault = AccountsFault.Unavailable(500)), time)
        assertEquals(ClaudeAccountsPresentation.Notice(false, "Could not load Claude accounts."), error.notice)
        assertTrue(error.cards.isEmpty())
        val refused = ClaudeAccountsPresentation.view(loaded().copy(listFault = AccountsFault.Refused("Disk full.")), time)
        assertEquals("Disk full.", refused.notice!!.text)
        val blocked = ClaudeAccountsPresentation.view(loaded().copy(listFault = AccountsFault.Blocked(302)), time)
        assertEquals(ClaudeAccountsPresentation.Notice(true, ClaudeAccountsPresentation.BLOCKED, ClaudeAccountsPresentation.BLOCKED_DETAIL), blocked.notice)
        val signedOut = ClaudeAccountsPresentation.view(ClaudeAccountsModel.signedOut(), time)
        assertFalse(signedOut.notice!!.blocked)
        // Empty: no card, no sync (the web draws nothing between the intro and Add).
        val empty = ClaudeAccountsPresentation.view(ClaudeAccountsModel.foldList(ClaudeAccountsState(), ClaudeAccountsResult.Ok(emptyList(), ORIGIN), ORIGIN), time)
        assertEquals(ClaudeAccountsPresentation.View(null, false, emptyList(), null), empty)
    }

    @Test fun aCardShowsTheLabelPlanOrganizationAndPathAsTheWebDoes() {
        val cards = ClaudeAccountsPresentation.view(loaded(), time).cards
        assertEquals(listOf("Claude Code (default)", "Claude Code (work)", "Claude Code (fresh)"), cards.map { it.title })
        assertEquals(listOf("Team Premium 5x", "Team Standard", null), cards.map { it.planTag })
        assertEquals(listOf("Brainrocket", null, null), cards.map { it.organization })
        assertEquals(listOf(true, false, false), cards.map { it.preExisting })
        // The synthetic host default has nowhere to persist a rename (settings-dialog.tsx:1688).
        assertEquals(listOf(false, true, true), cards.map { it.canRename })
        // The path only when `hasConfigDir` (settings-dialog.tsx:1735).
        assertEquals(listOf("/srv/tether/.claude", "/srv/tether/state/claude-accounts/claude-work", null), cards.map { it.configDir })
        assertTrue(cards.all { it.canCheck })
        assertEquals(cards.map { "Status unknown" }, cards.map { it.status })
        val hidden = ClaudeAccountsPresentation.card(account(dir = "/srv/x").copy(hasConfigDir = false), null)
        assertNull(hidden.configDir)
        // An id outside the registry's shape is listed but cannot be checked.
        assertFalse(ClaudeAccountsPresentation.card(account(id = "../devices"), null).canCheck)
        // Being checked: no second Check.
        assertFalse(ClaudeAccountsPresentation.card(account(), AccountStatusState.Checking).canCheck)
    }

    /** Server text by the label rule: no bidi control, no invisible character, one line, bounded. */
    @Test fun serverTextIsDrawnByTheLabelRule() {
        val rlo = "\u202E"
        val spoofed = account(
            label = "Claude Code (${rlo}krow)\n\u200B",
            plan = ClaudeAccountPlan("Max${rlo} 20x\u2066", "Acme\u200F\nInc", ClaudeAccountPlanSource.Profile),
        )
        val card = ClaudeAccountsPresentation.card(spoofed, AccountStatusState.Known(ClaudeAccountStatus(true, null, "a@b.c$rlo", null)))
        assertEquals("Claude Code (krow)", card.title)
        assertEquals("Max 20x", card.planTag)
        assertEquals("Acme Inc", card.organization)
        assertEquals("Logged in — a@b.c", card.status)
        // A label made only of invisible characters is never drawn as nothing: it is spelled out;
        // an empty or blank one gives way to the id.
        assertEquals("\\u{200B}\\u{202E}", ClaudeAccountsPresentation.card(account(label = "\u200B$rlo"), null).title)
        assertEquals("claude-a", ClaudeAccountsPresentation.card(account(label = "  "), null).title)
        assertEquals("claude-a", ClaudeAccountsPresentation.card(account(label = ""), null).title)
        // A blank plan label or organization draws no tag.
        val blank = ClaudeAccountsPresentation.card(account(plan = ClaudeAccountPlan("\u200B", "\u2066", ClaudeAccountPlanSource.Profile)), null)
        assertNull(blank.planTag)
        assertNull(blank.organization)
        // The bound.
        assertTrue(ClaudeAccountsPresentation.card(account(label = "x".repeat(500)), null).title.length <= 80)
        assertEquals(
            "Status unknown — boom",
            ClaudeAccountsPresentation.statusCopy(AccountStatusState.Failed(AccountsFault.Refused("boom$rlo\n"))),
        )
    }

    /** #231: nothing on this side can hold `raw`: every derived value of the sentinel payload is clean. */
    @Test fun theRawSentinelReachesNoState() {
        val state = loaded()
        val view = ClaudeAccountsPresentation.view(state.copy(sync = AccountsFixtures.SYNC), time)
        for (text in listOf(list.toString(), state.toString(), view.toString())) {
            assertFalse(text.contains(RAW_SENTINEL))
            assertFalse(text.contains("team_tier_1"))
            assertFalse(text.contains("default_raven"))
        }
    }

    @Test fun thePlanIsReadAgainOnlyWhileAnOfflineSnapshotShows() {
        assertFalse(ClaudeAccountsModel.wantsPlanRetry(null))
        assertFalse(ClaudeAccountsModel.wantsPlanRetry(list))
        assertTrue(ClaudeAccountsModel.wantsPlanRetry(list + account(plan = ClaudeAccountPlan("Team", null, ClaudeAccountPlanSource.Credentials))))
        assertFalse(ClaudeAccountsModel.wantsPlanRetry(listOf(account(plan = ClaudeAccountPlan("Team", null, ClaudeAccountPlanSource.Unknown)))))
    }

    /** settings-dialog.tsx:1864 and :1309: two accounts to mount it, two sync-eligible ones and a config to show it. */
    @Test fun theSyncSectionShowsOnlyWithTwoEligibleAccountsAndItsConfig() {
        val sync = AccountsFixtures.SYNC
        assertFalse(ClaudeAccountsModel.wantsSync(ClaudeAccountsState()))
        assertTrue(ClaudeAccountsModel.wantsSync(loaded()))
        assertFalse(ClaudeAccountsModel.wantsSync(loaded().copy(listFault = AccountsFault.Unavailable(500))))
        assertNull(ClaudeAccountsPresentation.sync(list, null, time))
        assertNull(ClaudeAccountsPresentation.sync(list.take(1), sync, time))
        assertNull(ClaudeAccountsPresentation.sync(list.mapIndexed { i, a -> a.copy(syncEligible = i == 0) }, sync, time))
        val view = ClaudeAccountsPresentation.sync(list, sync, time)!!
        assertEquals(
            listOf(
                ClaudeAccountsPresentation.Value("Sync across accounts", "Share plugins, skills, MCP servers, and hooks between your Claude accounts", "Sync selected categories"),
                ClaudeAccountsPresentation.Value("Categories", "Only these are kept in sync", "Plugins, Skills, MCP servers"),
                ClaudeAccountsPresentation.Value("Primary account", "Its plugins/skills/hooks/MCP servers are what the others receive", "Claude Code (default)"),
            ),
            view.rows,
        )
        assertNull(view.warning)
        assertEquals("Last synced at 10:13:20 — 2 updated, 1 already current.", view.summary)
        assertFalse(view.canRun)
        // A sync answer is taken only for the shown server, and a failure keeps what was there.
        val state = loaded()
        assertSame(state, ClaudeAccountsModel.foldSync(state, ClaudeAccountsResult.Ok(sync, OTHER_ORIGIN), ORIGIN))
        val withSync = ClaudeAccountsModel.foldSync(state, ClaudeAccountsResult.Ok(sync, ORIGIN), ORIGIN)
        assertEquals(sync, withSync.sync)
        assertEquals(sync, ClaudeAccountsModel.foldSync(withSync, ClaudeAccountsResult.Unavailable(500, ORIGIN), ORIGIN).sync)
    }

    @Test fun theSyncRowsFollowTheMode() {
        fun rows(mode: ClaudeSyncMode, primary: String?) =
            ClaudeAccountsPresentation.sync(list, ClaudeAccountsSync(ClaudeSyncConfig(mode, ClaudeSyncCategories(false, false, false, false), primary), null), time)!!
        assertEquals(listOf("Sync across accounts"), rows(ClaudeSyncMode.None, null).rows.map { it.title })
        assertNull(rows(ClaudeSyncMode.None, null).warning)
        assertEquals("Don't sync", rows(ClaudeSyncMode.None, null).rows[0].value)
        val all = rows(ClaudeSyncMode.All, null)
        assertEquals(listOf("Sync across accounts", "Primary account"), all.rows.map { it.title })
        assertEquals("Choose a primary account…", all.rows[1].value)
        assertTrue(all.warning!!.startsWith("No primary account is set"))
        assertEquals("None", rows(ClaudeSyncMode.Selected, null).rows[1].value)
        // A primary that is not a listed sync-eligible account.
        assertEquals("None", rows(ClaudeSyncMode.All, "claude-gone").rows[1].value)
        assertEquals("Unknown", rows(ClaudeSyncMode.Unknown, "claude-work").rows[0].value)
        assertEquals("Claude Code (work)", rows(ClaudeSyncMode.Unknown, "claude-work").rows[1].value)
    }

    /** settings-dialog.tsx:1229-1239 `summarizeSyncResult`. */
    @Test fun theSyncSummaryReadsAsTheWebSaysIt() {
        val s = { r: ClaudeSyncResult? -> ClaudeAccountsPresentation.summary(r, time) }
        assertEquals("Never synced yet.", s(null))
        assertEquals("Sync is off.", s(ClaudeSyncResult(5, "disabled", 0, 0, null)))
        assertEquals("Needs at least 2 accounts.", s(ClaudeSyncResult(5, "not-enough-accounts", 0, 0, null)))
        assertEquals("Choose a primary account below.", s(ClaudeSyncResult(5, "no-primary", 0, 0, null)))
        assertEquals("Last sync failed: EACCES", s(ClaudeSyncResult(5, "error", 0, 0, "EACCES")))
        assertEquals("Last sync failed: unknown error", s(ClaudeSyncResult(5, "error", 0, 0, null)))
        assertEquals("Last synced at 10:13:20 — 3 updated, 4 already current.", s(ClaudeSyncResult(5, "ok", 3, 4, null)))
        assertEquals("Last synced 0 updated, 0 already current.", s(ClaudeSyncResult(null, "ok", 0, 0, null)))
    }
}
