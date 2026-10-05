package com.tether.app.ui.inspector

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.tether.app.client.WorktreeSkipCopy
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.inspector.InspectorBoards.obj
import com.tether.app.ui.statusline.screenshots.ScreenSize
import com.tether.app.ui.statusline.screenshots.snapBoard
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-m7ef (tether PR #241, inspector.tsx / worktree-services-card.tsx 1bf4a465): why a checkout's
 * setup / teardown did NOT run. `setupSkipped` (no matching approval at create) and `teardownSkipped`
 * (an approved teardown refused at archive: consent, tampered, checkout-changed, processes-alive,
 * sessions-changed) say so out loud in the Runtime details and on the Services card, in the web's
 * sentences; an unknown reason still says it did not run.
 */
class SkippedSetupModelTest {
    private fun worktree(setup: String? = null, teardown: String? = null) =
        WorktreeInfo(path = "/w/.t/a", branch = "tether/a", status = "active", mode = "branch-off", baseRef = "origin/main", setupStatus = "none", setupSkipped = setup, teardownSkipped = teardown)

    private fun rows(w: WorktreeInfo) = InspectorBoards.model(InspectorBoards.session(worktree = w)).runtime.rows

    @Test fun aSkippedSetupAndTeardownAreRowsWithTheWebsSentencesAsStatusNotes() {
        val rows = rows(worktree("consent-mismatch", "sessions-changed"))
        assertEquals(listOf("Worktree", "Branch", "Setup", "Teardown"), rows.map { it.label })
        val setup = rows.single { it.label == "Setup" }
        assertEquals("Not run", setup.value.plain())
        assertEquals(WorktreeSkipCopy.SETUP.getValue("consent-mismatch"), setup.notes.single().line.plain())
        assertTrue(setup.notes.single().status)
        val teardown = rows.single { it.label == "Teardown" }
        assertEquals("Not run", teardown.value.plain())
        assertEquals(
            "This project's teardown did not run: the other sessions it would have stopped were not the ones shown when it was approved.",
            teardown.notes.single().line.plain(),
        )
        assertTrue(teardown.notes.single().status)
    }

    @Test fun everyTeardownReasonHasItsOwnSentenceInTheRow() {
        for (reason in WorktreeSkipCopy.TEARDOWN.keys) {
            val note = rows(worktree(teardown = reason)).single { it.label == "Teardown" }.notes.single().line.plain()
            assertEquals(reason, WorktreeSkipCopy.TEARDOWN.getValue(reason), note)
        }
        assertEquals(
            "processes-alive reads as the web's words",
            "This project's teardown did not run: Tether could not prove that every process the session started had stopped.",
            rows(worktree(teardown = "processes-alive")).single { it.label == "Teardown" }.notes.single().line.plain(),
        )
    }

    @Test fun anUnknownReasonStillSaysItDidNotRun() {
        val rows = rows(worktree("from-the-future", "also-new"))
        assertEquals("This project's setup and teardown did not run.", rows.single { it.label == "Setup" }.notes.single().line.plain())
        assertEquals("This project's teardown did not run.", rows.single { it.label == "Teardown" }.notes.single().line.plain())
    }

    @Test fun nothingSkippedAddsNoRow() {
        assertEquals(listOf("Worktree", "Branch"), rows(worktree()).map { it.label })
    }

    @Test fun theServicesCardCarriesBothSentencesAndShowsEvenWithNothingElse() {
        val section = services(obj("""{"sessionId":"s1","scripts":[],"setupStatus":"none","setupLog":[],"configWarnings":[],"setupSkipped":"consent-missing","teardownSkipped":"checkout-changed"}"""))!!
        assertEquals(
            listOf(WorktreeSkipCopy.SETUP.getValue("consent-missing"), WorktreeSkipCopy.TEARDOWN.getValue("checkout-changed")),
            section.skipped,
        )
        assertNull("with nothing to say the card is no empty frame", services(obj("""{"sessionId":"s1","scripts":[],"setupStatus":"none","setupLog":[],"configWarnings":[]}""")))
        assertEquals(1, services(obj("""{"sessionId":"s1","scripts":[],"setupStatus":"none","setupLog":[],"configWarnings":[],"teardownSkipped":"sessions-changed"}"""))!!.skipped.size)
        assertTrue(services(obj("""{"sessionId":"s1","scripts":[],"setupStatus":"ok","setupLog":[],"configWarnings":["x"]}"""))!!.skipped.isEmpty())
    }
}

/** The card and the Runtime details drawn: both sentences are on screen. */
abstract class SkippedSetupDrawnBase {
    @get:Rule val rule = createComposeRule()

    @Test fun theSentencesAreOnScreen() {
        rule.mainClock.autoAdvance = false
        val model = SkippedSetupBoards.model()
        rule.setContent {
            com.tether.app.ui.theme.TetherTheme(com.tether.app.ui.statusline.screenshots.choiceFor(TetherSkin.Studio)) {
                androidx.compose.runtime.CompositionLocalProvider(com.tether.app.ui.theme.LocalReducedMotion provides true) {
                    androidx.compose.foundation.layout.Column {
                        Inspector(model, null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        val card = rule.onAllNodesWithTag(InspectorTags.serviceSkipped(0), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, card.size)
        assertEquals(WorktreeSkipCopy.SETUP.getValue("consent-mismatch"), card.single().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        assertNotNull(rule.onNodeWithTag(InspectorTags.serviceSkipped(1), useUnmergedTree = true).fetchSemanticsNode())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2000dp-420dpi")
class SkippedSetupPhoneDrawnTest : SkippedSetupDrawnBase()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h1400dp-mdpi")
class SkippedSetupTabletDrawnTest : SkippedSetupDrawnBase()

object SkippedSetupBoards {
    fun model(): InspectorModel = InspectorBoards.model(
        InspectorBoards.session(
            worktree = WorktreeInfo(
                path = "/w/.t/a", branch = "tether/a", status = "active", mode = "branch-off", baseRef = "origin/main", setupStatus = "none",
                setupSkipped = "consent-mismatch", teardownSkipped = "sessions-changed",
            ),
        ),
        replies = InspectorReplies(
            worktreeScripts = obj(
                """{"sessionId":"s1","worktreePath":"/w/.t/a","branch":"tether/a","setupStatus":"none","setupLog":[],"configWarnings":[],
                   "setupSkipped":"consent-mismatch","teardownSkipped":"sessions-changed","scripts":[]}""",
            ),
        ),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1400dp-420dpi")
class SkippedSetupPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapBoard("inspector-setup-skipped", skin, ScreenSize.Phone, reducedMotion = true) {
        Inspector(SkippedSetupBoards.model(), null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h1000dp-mdpi")
class SkippedSetupTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapBoard("inspector-setup-skipped", skin, ScreenSize.Tablet, reducedMotion = true) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(380.dp)) {
            Inspector(SkippedSetupBoards.model(), null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
