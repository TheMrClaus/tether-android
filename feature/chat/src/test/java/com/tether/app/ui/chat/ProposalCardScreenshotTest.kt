package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ProposalLocalState
import com.tether.app.client.ProposalRefusal
import com.tether.app.client.Proposals
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** The new-session proposal card in the transcript, one golden per state. */
enum class ProposalShot(val id: String) {
    Face("proposal-face"),
    Refused("proposal-refused"),
    Started("proposal-started"),
    Here("proposal-here"),
    Dismissed("proposal-dismissed"),
    Expired("proposal-expired"),
    Replaced("proposal-replaced"),
    Starting("proposal-starting"),
    Preview("proposal-preview"),
}

private const val PROMPT = "Split the parser into a tokenizer and a grammar module, keep the public entry point as it is, and move the fixtures next to the module that uses them. Run the unit tests after each step and report what moved."

private fun proposalFixture(shot: ProposalShot): ChatFixtures.Folded {
    val after: List<AgentEvent> = when (shot) {
        ProposalShot.Started -> listOf(evNullTurn("session_proposal_updated") { put("proposalId", "p1"); put("state", "started"); put("startedSessionId", "s9"); put("startedSessionName", "Split the parser") })
        ProposalShot.Here -> listOf(evNullTurn("session_proposal_updated") { put("proposalId", "p1"); put("state", "here") })
        ProposalShot.Dismissed -> listOf(evNullTurn("session_proposal_updated") { put("proposalId", "p1"); put("state", "dismissed") })
        ProposalShot.Expired -> listOf(evNullTurn("session_proposal_updated") { put("proposalId", "p1"); put("state", "expired") })
        else -> emptyList()
    }
    return ChatFixtures.fold(
        ev("turn_started", "t1") { put("idempotencyKey", "k") },
        ev("user_message_accepted", "t1") { put("text", "Plan the parser work") },
        ev("tool_start", "t1") { put("toolId", "tool-1"); put("name", "mcp__tether__propose_session"); put("input", buildJsonObject {}) },
        ev("session_proposal", "t1") {
            put("proposalId", "p1"); put("title", "Split the parser into a tokenizer and a grammar module"); put("prompt", PROMPT)
            put("suggestions", buildJsonObject { if (shot != ProposalShot.Replaced) put("model", "opus"); put("cwd", "/srv/ws/parity-app") })
            if (shot == ProposalShot.Replaced) {
                put("replaced", buildJsonArray {
                    add(buildJsonObject { put("field", "model"); put("code", "invalid_settings"); put("suggested", "opus-9") })
                })
            }
        },
        ev("tool_end", "t1") { put("toolId", "tool-1"); put("output", "proposed") },
        *after.toTypedArray(),
    )
}

private val stubHost = ProposalOptionsHost { }

fun ComposeContentTestRule.snapProposal(shot: ProposalShot, skin: TetherSkin, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = proposalFixture(shot)
    val local = if (shot == ProposalShot.Refused) {
        mapOf("p1" to ProposalLocalState("start", "r1", ProposalRefusal("folder_unavailable", Proposals.REFUSAL_TEXTS.getValue("folder_unavailable"))))
    } else if (shot == ProposalShot.Starting) {
        mapOf("p1" to ProposalLocalState("start", "r1"))
    } else emptyMap()
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            if (shot == ProposalShot.Preview) {
                val view = ProposalView(
                    proposalId = "p1", title = "Split the parser into a tokenizer and a grammar module", prompt = PROMPT, suggestions = com.tether.app.protocol.tree.JsObj.EMPTY,
                    replaced = emptyList(), state = ProposalState.Started, refusalCode = null, startedSessionId = "s9", startedSessionName = "Split the parser",
                )
                ProposalPreviewContent(view) {}
                return@ChatHost
            }
            CompositionLocalProvider(LocalProposalActions provides ProposalActions(sessionId = "s1", local = local, optionsHost = stubHost, cardShown = true)) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = LazyListState(),
                )
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

private val studioSkins = listOf(TetherSkin.StudioDark, TetherSkin.Studio)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProposalCardPhoneScreenshotTest(private val shot: ProposalShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun card() = rule.snapProposal(shot, skin, "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ProposalShot.entries.flatMap { s -> studioSkins.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ProposalCardTabletScreenshotTest(private val shot: ProposalShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun card() = rule.snapProposal(shot, skin, "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ProposalShot.entries.flatMap { s -> studioSkins.map { arrayOf<Any>(s, it) } }
    }
}
