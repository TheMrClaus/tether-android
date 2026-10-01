package com.tether.app.ui.sidebar

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.NewSessionRow
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.ui.NEW_SESSION_PENDING_TAG
import com.tether.app.ui.NEW_SESSION_ROW_TAG
import com.tether.app.ui.NewSessionPickerBody
import com.tether.app.ui.PROFILE_ID_SHOWN
import com.tether.app.ui.newSessionRowEnabled
import com.tether.app.ui.newSessionRowTags
import com.tether.app.ui.profileIdParts
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-895: the New session picker's provider and profile rows. Every account and profile is its own
 * row in the catalog's order; a row that cannot create is disabled and says why; server text is drawn
 * by the label and one-line rules. ta-abm: the rows are now the draft composer sheet's provider stage
 * (a tap picks the row; the sheet's own tests, feature:shell DraftComposerSheetBehaviourTest, cover
 * the create, its first message, the refusals and the readiness reasons).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NewSessionPickerBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun description(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString()

    private fun open(client: PickerClient, selectedKey: String? = null): MutableList<NewSessionRow> {
        val picked = mutableListOf<NewSessionRow>()
        val rows = NewSessionGuard.rows(if (client.providerCatalogLive.value) client.providerCatalog.value else null, client.providers.value)
        val providers = client.providers.value
        val pending = !client.providerCatalogLive.value
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                Column {
                    NewSessionPickerBody(
                        rows = rows,
                        providers = providers,
                        catalogPending = pending,
                        notice = null,
                        selectedKey = selectedKey,
                        pickLabel = { "Choose $it" },
                        onPick = { picked += it },
                    )
                }
            }
        }
        rule.waitForIdle()
        return picked
    }

    @Test
    fun everyAccountIsItsOwnRowAndATapPicksThatRow() {
        val client = PickerClient()
        val picked = open(client, selectedKey = "claude")
        for (key in listOf("work", "personal", "gemini-acp", "claude", "codex", "opencode", "pi")) {
            rule.onNodeWithTag(NEW_SESSION_ROW_TAG + key, useUnmergedTree = true).assertExists()
        }
        assertEquals("Claude (work), profile work, 3 models", description(NEW_SESSION_ROW_TAG + "work"))
        assertEquals("Claude, 4 models", description(NEW_SESSION_ROW_TAG + "claude"))
        // ta-abm: the draft's pick is announced selected; the others are not.
        assertEquals(true, rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertNull(rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "work", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        val action = rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "personal", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals("Choose Claude (personal)", action.label)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "personal", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { picked.isNotEmpty() }
        assertEquals(com.tether.app.client.NewSessionChoice("personal", "claude", "personal"), picked.single().choice)
        assertTrue("nothing is created by a tap", client.creates.isEmpty())
    }

    @Test
    fun anErrorRowSaysWhyAndStillTakesATap() {
        val client = PickerClient()
        val picked = open(client)
        assertTrue(description(NEW_SESSION_ROW_TAG + "codex").endsWith("Error, codex app-server exited (code 1)"))
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "codex", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { picked.isNotEmpty() }
        assertEquals("codex", picked.single().choice.key)
    }

    @Test
    fun aRowStillLoadingTakesATapAnUnavailableOneIsDisabled() {
        val client = PickerClient()
        val picked = open(client)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "opencode", useUnmergedTree = true).assertIsEnabled()
        assertTrue(description(NEW_SESSION_ROW_TAG + "opencode").endsWith("Loading…"))
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "pi", useUnmergedTree = true).assertIsNotEnabled()
        assertEquals("Pi, Unavailable", description(NEW_SESSION_ROW_TAG + "pi"))
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "pi", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun beforeThisConnectionsCatalogIsInOnlyTheBaseProvidersShowAndNoProfile() {
        // The published catalog is an earlier connection's: never drawn as profile rows.
        val client = PickerClient().apply { providerCatalogLive.value = false }
        val picked = open(client)
        rule.onNodeWithTag(NEW_SESSION_PENDING_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "work", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "acp", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { picked.isNotEmpty() }
        assertNull(picked.single().choice.profileId)
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
        assertTrue(client.creates.isEmpty())
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
