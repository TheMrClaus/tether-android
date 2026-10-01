package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.ConnectionState
import com.tether.app.client.DRAFT_LINK_DROPPED_COPY
import com.tether.app.client.NewSessionChoice
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.NewSessionResult
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.ui.NEW_SESSION_CREATING_TAG
import com.tether.app.ui.NEW_SESSION_LOADING_COPY
import com.tether.app.ui.NEW_SESSION_NOTICE_TAG
import com.tether.app.ui.NEW_SESSION_NOT_OFFERED_COPY
import com.tether.app.ui.NEW_SESSION_PENDING_TAG
import com.tether.app.ui.NEW_SESSION_ROW_TAG
import com.tether.app.ui.NewSessionDialog
import com.tether.app.ui.PROFILE_ID_SHOWN
import com.tether.app.ui.newSessionRowEnabled
import com.tether.app.ui.newSessionRowTags
import com.tether.app.ui.profileIdParts
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-895: the New session picker over the view model and a client that resolves taps as the real
 * one does. It asks for a fresh catalog each time it opens, lists every account and profile, creates
 * on the row tapped (with its profile), closes only when the create went out, and says why
 * otherwise. Server text is drawn by the label and one-line rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NewSessionPickerBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun description(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString()

    private fun open(client: PickerClient, dismissed: MutableList<Unit> = mutableListOf()): TetherViewModel {
        val vm = TetherViewModel(client)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                NewSessionDialog(vm, onDismiss = { dismissed += Unit })
            }
        }
        rule.waitForIdle()
        return vm
    }

    @Test
    fun everyOpeningAsksForAFreshCatalog() {
        val client = PickerClient()
        val vm = TetherViewModel(client)
        var shown by mutableStateOf(true)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                if (shown) NewSessionDialog(vm, onDismiss = { shown = false })
            }
        }
        rule.waitForIdle()
        assertEquals(1, client.catalogRequests)
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        assertEquals(2, client.catalogRequests)
    }

    @Test
    fun everyAccountIsItsOwnRowAndATapCreatesOnThatAccount() {
        val client = PickerClient()
        val dismissed = mutableListOf<Unit>()
        val vm = open(client, dismissed)
        for (key in listOf("work", "personal", "gemini-acp", "claude", "codex", "opencode", "pi")) {
            rule.onNodeWithTag(NEW_SESSION_ROW_TAG + key, useUnmergedTree = true).assertExists()
        }
        assertEquals("Claude (work), profile work, 3 models", description(NEW_SESSION_ROW_TAG + "work"))
        assertEquals("Claude, 4 models", description(NEW_SESSION_ROW_TAG + "claude"))
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "personal", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf(NewSessionChoice("personal", "claude", "personal")), client.choices)
        assertEquals("personal", client.creates.single().profileId)
        assertEquals("claude", client.creates.single().provider)
        // ta-8cv: the web's frame: Claude Auto, the explicit sandbox, a requestId of its own.
        assertEquals("bypassPermissions", client.creates.single().permissionMode)
        assertEquals("workspace-write", client.creates.single().sandboxPolicy)
        assertTrue(client.creates.single().requestId!!.isNotEmpty())
        // ta-8cv: it waits for THAT create's own reply, rows locked.
        assertTrue(dismissed.isEmpty())
        rule.onNodeWithTag(NEW_SESSION_CREATING_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "work", useUnmergedTree = true).assertIsNotEnabled()
        client.answer("new-personal")
        rule.waitForIdle()
        assertEquals(1, dismissed.size)
        assertEquals("new-personal", vm.selectedSessionId.value)
    }

    @Test
    fun theDefaultRowCreatesWithNoProfileAndAnErrorRowStillCreates() {
        val client = PickerClient()
        open(client)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertNull(client.creates.single().profileId)
        client.answer()
        rule.waitForIdle()
        assertTrue(description(NEW_SESSION_ROW_TAG + "codex").endsWith("Error, codex app-server exited (code 1)"))
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "codex", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("claude", "codex"), client.creates.map { it.provider })
        // ta-8cv: Codex starts on its Default permissions preset.
        assertEquals("workspace-write", client.creates.last().sandboxPolicy)
        assertNull(client.creates.last().approvalPolicy)
        assertNotEquals(client.creates[0].requestId, client.creates[1].requestId)
    }

    /** ta-8cv: the server refuses THIS create (a paired phone before ta-drm): its words, unlocked, open. */
    @Test
    fun theServersRefusalOfThisCreateIsShownAndThePickerUnlocks() {
        val client = PickerClient()
        val dismissed = mutableListOf<Unit>()
        val vm = open(client, dismissed)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // An unrelated error first: still waiting.
        client.refuse("Something else failed.", requestId = null)
        rule.waitForIdle()
        rule.onNodeWithTag(NEW_SESSION_CREATING_TAG, useUnmergedTree = true).assertExists()
        // A created for another create: not this one, nothing closes, nothing is selected.
        client.answer("someone-elses", requestId = "another-request")
        rule.waitForIdle()
        assertTrue(dismissed.isEmpty())
        assertNull(vm.selectedSessionId.value)
        client.refuse("Skipping tool approvals needs a browser sign-in, not a paired device.")
        rule.waitForIdle()
        rule.onNodeWithTag(NEW_SESSION_CREATING_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Skipping tool approvals needs a browser sign-in, not a paired device.").assertExists()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).assertIsEnabled()
        assertTrue(dismissed.isEmpty())
        // Never downgraded and resent on its own.
        assertEquals(1, client.creates.size)
        assertEquals("bypassPermissions", client.creates.single().permissionMode)
    }

    /** ta-8cv: the link drops before `created`: the picker says so and unlocks. */
    @Test
    fun aDroppedLinkWhileCreatingSaysSoAndUnlocks() {
        val client = PickerClient()
        val dismissed = mutableListOf<Unit>()
        open(client, dismissed)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        client.inner.connection.value = ConnectionState.Disconnected
        rule.waitForIdle()
        rule.onNodeWithText(DRAFT_LINK_DROPPED_COPY).assertExists()
        rule.onNodeWithTag(NEW_SESSION_CREATING_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertTrue(dismissed.isEmpty())
        // A late reply (it cannot come on another socket, but even so) closes nothing.
        client.answer()
        rule.waitForIdle()
        assertTrue(dismissed.isEmpty())
    }

    /** ta-8cv: a new opening never shows an earlier opening's refusal. */
    @Test
    fun aReopenedPickerStartsWithoutTheEarlierError() {
        val client = PickerClient()
        val vm = TetherViewModel(client)
        var shown by mutableStateOf(true)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                if (shown) NewSessionDialog(vm, onDismiss = { shown = false })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        client.refuse("Refused once.")
        rule.waitForIdle()
        rule.onNodeWithText("Refused once.").assertExists()
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        rule.onNodeWithText("Refused once.").assertDoesNotExist()
    }

    @Test
    fun aRowTheServerNoLongerOffersSaysSoStaysOpenAndAsksAgain() {
        val client = PickerClient().apply { nextResult = NewSessionResult.NotOffered }
        val dismissed = mutableListOf<Unit>()
        open(client, dismissed)
        assertEquals(1, client.catalogRequests)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "work", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(NEW_SESSION_NOTICE_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(NEW_SESSION_NOT_OFFERED_COPY).assertExists()
        assertTrue(dismissed.isEmpty())
        assertTrue(client.creates.isEmpty())
        assertEquals(2, client.catalogRequests)
    }

    @Test
    fun aRowStillLoadingSaysSoAndSendsNothingAnUnavailableOneIsDisabled() {
        val client = PickerClient()
        open(client)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "opencode", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(NEW_SESSION_LOADING_COPY).assertExists()
        assertTrue(client.choices.isEmpty())
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "pi", useUnmergedTree = true).assertIsNotEnabled()
        assertEquals("Pi, Unavailable", description(NEW_SESSION_ROW_TAG + "pi"))
    }

    @Test
    fun beforeThisConnectionsCatalogIsInOnlyTheBaseProvidersShowAndNoProfile() {
        // The published catalog is an earlier connection's: never drawn as profile rows.
        val client = PickerClient().apply { providerCatalogLive.value = false }
        open(client)
        rule.onNodeWithTag(NEW_SESSION_PENDING_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "work", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "acp", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(NewSessionChoice("claude", "claude", null), client.choices.single())
        assertNull(client.creates.single().profileId)
        // This connection's catalog lands: the accounts appear.
        client.providerCatalogLive.value = true
        rule.waitForIdle()
        rule.onNodeWithTag(NEW_SESSION_PENDING_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "work", useUnmergedTree = true).assertExists()
    }

    /** r2 (F1): two accounts with the same long nickname never look alike. */
    @Test
    fun twoAccountsWithTheSameLongNicknameAreToldApart() {
        val client = PickerClient().apply { providerCatalog.value = NewSessionFixtures.lookAlike }
        open(client)
        val id = NewSessionFixtures.LOOK_ALIKE_ID
        val first = description(NEW_SESSION_ROW_TAG + id)
        val second = description(NEW_SESSION_ROW_TAG + "$id-2")
        val label = "Claude Code (${NewSessionFixtures.NICKNAME})"
        assertTrue(first, first.startsWith("$label, tag "))
        assertTrue(second, second.startsWith("$label, tag "))
        assertTrue(second, second.contains("profile $id-2"))
        val rows = NewSessionGuard.rows(NewSessionFixtures.lookAlike, NewSessionFixtures.providers)
        val tags = newSessionRowTags(rows)
        assertEquals(3, tags.size)
        assertTrue(tags[0] != null && tags[1] != null && tags[0] != tags[1])
        assertTrue(tags[0]!!.matches(Regex("#[0-9a-f]{6}")))
        assertNull("a label of its own carries no tag", tags[2])
        assertEquals(tags[0], newSessionRowTags(rows)[0])
    }

    /** r2 (F1): an id is never cut at its end; a very long one is cut in the middle. */
    @Test
    fun aProfileIdKeepsItsEnd() {
        val id = NewSessionFixtures.LOOK_ALIKE_ID + "-2"
        assertEquals(listOf(id), profileIdParts(id))
        val long = "a".repeat(120) + "-tail-2"
        val parts = profileIdParts(long)
        assertEquals(2, parts.size)
        assertTrue(parts[1], parts[1].endsWith("-tail-2"))
        assertTrue(parts.sumOf { it.length } <= PROFILE_ID_SHOWN)
        // Never inside a surrogate pair (a flag, an emoji) at either cut.
        val emoji = "\uD83D\uDE00".repeat(80)
        val cut = profileIdParts(emoji)
        assertTrue(cut.all { p -> p.isNotEmpty() && !Character.isLowSurrogate(p.first()) && !Character.isHighSurrogate(p.last()) })
    }

    /** r2 (F2): a row the client would refuse is drawn disabled and says so. */
    @Test
    fun rowsThatCanNeverCreateAreDisabled() {
        val client = PickerClient().apply { providerCatalog.value = NewSessionFixtures.refused }
        open(client)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "odd", useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "future", useUnmergedTree = true).assertIsNotEnabled()
        rule.onAllNodesWithTag(NEW_SESSION_ROW_TAG + "dup", useUnmergedTree = true).assertCountEquals(2).assertAll(isNotEnabled())
        assertTrue(description(NEW_SESSION_ROW_TAG + "odd").endsWith("Not offered"))
        assertTrue(description(NEW_SESSION_ROW_TAG + "future").endsWith("Not offered"))
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "odd", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(client.choices.isEmpty())
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).assertIsEnabled()
        val rows = NewSessionGuard.rows(NewSessionFixtures.refused, NewSessionFixtures.providers)
        assertEquals(listOf(false, false, false, false, true), rows.map(::newSessionRowEnabled))
    }

    /** r2 (F3): an empty live catalog draws no rows and no default, as the web does. */
    @Test
    fun anEmptyLiveCatalogShowsNothingToStart() {
        val client = PickerClient().apply { providerCatalog.value = emptyList() }
        open(client)
        rule.onNodeWithText("No providers available.").assertExists()
        rule.onNodeWithTag(NEW_SESSION_PENDING_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(client.choices.isEmpty())
    }

    @Test
    fun serverTextIsDrawnByTheTextRules() {
        val client = PickerClient()
        client.providerCatalog.value = listOf(
            // A label that reorders itself and a profile id with a hidden code point.
            ProviderCatalogEntry("wo\u200Brk", "claude", "ready", emptyList(), label = "Claude \u202E(krow)\u202C", profileId = "wo\u200Brk", extends = "claude"),
            ProviderCatalogEntry("blank", "claude", "ready", emptyList(), label = "\u200B\u2066", profileId = "blank", extends = "claude"),
            ProviderCatalogEntry("claude", "claude", "error", emptyList(), label = "Claude", error = "line one\nline\u202E two"),
        )
        open(client)
        val spoofed = description(NEW_SESSION_ROW_TAG + "wo\u200Brk")
        assertFalse(spoofed, spoofed.contains('\u202E') || spoofed.contains('\u200B'))
        assertTrue(spoofed, spoofed.startsWith("Claude (krow), profile wo\\u{200B}rk"))
        // A label of nothing visible is spelled out, never drawn as nothing.
        assertTrue(description(NEW_SESSION_ROW_TAG + "blank").startsWith("\\u{200B}\\u{2066}, profile blank"))
        assertTrue(description(NEW_SESSION_ROW_TAG + "claude").endsWith("Error, line one line two"))
    }
}
