package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.NewSessionChoice
import com.tether.app.client.NewSessionResult
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.ui.NEW_SESSION_LOADING_COPY
import com.tether.app.ui.NEW_SESSION_NOTICE_TAG
import com.tether.app.ui.NEW_SESSION_NOT_OFFERED_COPY
import com.tether.app.ui.NEW_SESSION_PENDING_TAG
import com.tether.app.ui.NEW_SESSION_ROW_TAG
import com.tether.app.ui.NewSessionDialog
import com.tether.app.ui.TetherViewModel
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
        open(client, dismissed)
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
        assertEquals(1, dismissed.size)
    }

    @Test
    fun theDefaultRowCreatesWithNoProfileAndAnErrorRowStillCreates() {
        val client = PickerClient()
        open(client)
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "claude", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertNull(client.creates.single().profileId)
        assertTrue(description(NEW_SESSION_ROW_TAG + "codex").endsWith("Error, codex app-server exited (code 1)"))
        rule.onNodeWithTag(NEW_SESSION_ROW_TAG + "codex", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("claude", "codex"), client.creates.map { it.provider })
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
