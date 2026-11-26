package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v175: the Start frame carries the final value of every row, also when the operator never opens Options. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProposalCardStartTest {
    @get:Rule val rule = createComposeRule()

    private val final = JsonObject(mapOf("provider" to JsonPrimitive("claude"), "cwd" to JsonPrimitive("/w"), "useWorktree" to JsonPrimitive(false), "profileId" to JsonNull))

    private val host = object : ProposalOptionsHost {
        @Composable override fun Rows(request: ProposalOptionsRequest) {}
        @Composable override fun Settle(request: ProposalOptionsRequest) { LaunchedEffect(Unit) { request.onSettings(final) } }
    }

    private val view = ProposalView(
        proposalId = "p1", title = "Split the parser", prompt = "Do the work.", suggestions = JsObj.EMPTY, replaced = emptyList(),
        state = ProposalState.Pending, refusalCode = null, startedSessionId = null, startedSessionName = null,
    )

    @Test fun startWithoutOpeningOptionsSendsEveryRow() {
        var sent: JsonObject? = null
        var pressed = false
        val chosen = HashMap<String, JsonObject>()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(
                    LocalProposalActions provides ProposalActions(sessionId = "s1", optionsHost = host, chosen = chosen, cardShown = true, onStart = { _, s -> pressed = true; sent = s }),
                ) { ProposalCard(view) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-start").performClick()
        assertEquals(true, pressed)
        assertNotNull("Start sent no settings", sent)
        assertEquals(final, sent)
        assertEquals(JsonNull, sent!!["profileId"])
    }

    @Test fun aStartedCardStillOpensThePromptAndCopiesIt() {
        val started = view.copy(state = ProposalState.Started, startedSessionId = "s9", startedSessionName = "Split the parser")
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalProposalActions provides ProposalActions(sessionId = "s1", cardShown = true)) { ProposalCard(started) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-preview").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-prompt").assertIsDisplayed()
        rule.onNodeWithTag("proposal-copy").assertIsDisplayed()
    }

    @Test fun optionsReadsExpandedWhileItsPanelIsOpen() {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalProposalActions provides ProposalActions(sessionId = "s1", optionsHost = host, cardShown = true)) { ProposalCard(view) }
            }
        }
        rule.waitForIdle()
        val expanded = androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Expanded")
        rule.onNodeWithTag("proposal-options").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
        rule.onNodeWithTag("proposal-options").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-options").assert(expanded)
    }

    private val unseededHost = object : ProposalOptionsHost {
        @Composable override fun Rows(request: ProposalOptionsRequest) {}
        override fun offersOptions(proposalId: String) = false
    }

    private fun unseededCard(onStart: (JsonObject?) -> Unit) {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(
                    LocalProposalActions provides ProposalActions(sessionId = "s1", optionsHost = unseededHost, cardShown = true, onStart = { _, s -> onStart(s) }),
                ) { ProposalCard(view) }
            }
        }
        rule.waitForIdle()
    }

    @Test fun aCardWithNoSeededFormOffersNoOptionsAndStartsWithTheSuggestionsOnly() {
        var sent: JsonObject? = JsonObject(mapOf("marker" to JsonNull))
        unseededCard { sent = it }
        rule.onNodeWithTag("proposal-options").assertDoesNotExist()
        rule.onNodeWithTag("proposal-start").performClick()
        assertEquals(null, sent)
    }

    @Test fun aSeededHostStillOffersOptions() {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalProposalActions provides ProposalActions(sessionId = "s1", optionsHost = host, cardShown = true)) { ProposalCard(view) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-options").assertIsDisplayed()
    }
}
