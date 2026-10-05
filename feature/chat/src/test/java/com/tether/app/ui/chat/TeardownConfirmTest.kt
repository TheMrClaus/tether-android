package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.EndConfirmation
import com.tether.app.client.TeardownApproval
import com.tether.app.ui.components.ConsentTags
import com.tether.app.ui.components.HIDDEN_CHARACTERS_WARNING
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-m7ef (tether PR #241, components/setup-commands.tsx TeardownConfirmDialog 1bf4a465): ending an
 * isolated session runs its teardown only on the owner's approval, here. The dialog's words, warnings
 * and three keys, on a phone (412dp) and a tablet (1280dp).
 */
private object TeardownFixtures {
    val digest = "sha256:" + "ab".repeat(32)

    fun approval(
        commands: List<String> = listOf("pnpm run db:drop", "rm -rf .cache"),
        changed: Boolean? = false,
        hidden: Boolean = false,
        notice: String? = null,
    ) = TeardownApproval("c".repeat(40), commands, changed, hidden, notice, digest)

    fun confirmation(approval: TeardownApproval? = approval(), message: String? = null, name: String = "Fix the build") =
        EndConfirmation("s1", name, approval, message)
}

abstract class TeardownConfirmBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var run = 0
    private var skip = 0
    private var cancel = 0

    protected fun show(confirmation: EndConfirmation) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    TeardownConfirmDialog(confirmation, onRun = { run++ }, onSkip = { skip++ }, onCancel = { cancel++ })
                }
            }
        }
        rule.waitForIdle()
    }

    /** The text under [tag] (merged, so a status line's glyph and words read as one). */
    private fun shown(tag: String): String {
        val node = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            ?: rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first()
        return node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun anApprovalShowsTheCommandsAndOffersAllThreeKeys() {
        show(TeardownFixtures.confirmation())
        rule.onNodeWithTag(TeardownConfirmTags.Dialog, useUnmergedTree = true).assertExists()
        assertTrue(shown(ConsentTags.Body).contains("(commit ${"c".repeat(40)}) declares teardown commands."))
        assertTrue(shown(ConsentTags.Body).contains("the session could have changed the files these commands run"))
        assertEquals("pnpm run db:drop", shown("teardown-command:0"))
        assertEquals("rm -rf .cache", shown("teardown-command:1"))
        assertEquals(TeardownConfirmCopy.COMMANDS_LABEL, shown(ConsentTags.Label))
        // A known-unchanged checkout carries no warning.
        assertTrue(!exists(TeardownConfirmTags.Changed))
        rule.onNodeWithTag(TeardownConfirmTags.Run).assertExists()
        rule.onNodeWithTag(TeardownConfirmTags.Skip).assertExists()
        rule.onNodeWithTag(TeardownConfirmTags.Cancel).assertExists()
    }

    @Test fun theKeysCallTheirOwnHandlerOnly() {
        show(TeardownFixtures.confirmation())
        rule.onNodeWithTag(TeardownConfirmTags.Run).performClick()
        assertEquals(listOf(1, 0, 0), listOf(run, skip, cancel))
        rule.onNodeWithTag(TeardownConfirmTags.Skip).performClick()
        assertEquals(listOf(1, 1, 0), listOf(run, skip, cancel))
        rule.onNodeWithTag(TeardownConfirmTags.Cancel).performClick()
        assertEquals(listOf(1, 1, 1), listOf(run, skip, cancel))
    }

    @Test fun aChangedOrUnknownCheckoutIsWarnedAboutNeverShownAsUnchanged() {
        show(TeardownFixtures.confirmation(TeardownFixtures.approval(changed = true)))
        assertEquals(TeardownConfirmCopy.CHANGED, shown(TeardownConfirmTags.Changed))
    }

    @Test fun anUnknownCheckoutIsTreatedAsChanged() {
        show(TeardownFixtures.confirmation(TeardownFixtures.approval(changed = null)))
        assertEquals(TeardownConfirmCopy.UNKNOWN, shown(TeardownConfirmTags.Changed))
    }

    @Test fun hiddenCharactersAndStoppedSessionsAreNamedBeforeTheOwnerConfirms() {
        show(
            TeardownFixtures.confirmation(
                TeardownFixtures.approval(
                    commands = listOf("echo аpi ‮ok"), hidden = true,
                    notice = "Ending this session also stops another session that can write its checkout: Home folder.",
                ),
            ),
        )
        assertEquals(HIDDEN_CHARACTERS_WARNING, shown(ConsentTags.Hidden))
        assertEquals("Ending this session also stops another session that can write its checkout: Home folder.", shown(TeardownConfirmTags.Stops))
        assertEquals("echo U+0430pi U+202Eok", shown("teardown-command:0"))
    }

    @Test fun withNoApprovalOnlyEndWithoutTeardownAndCancelAreOffered() {
        show(TeardownFixtures.confirmation(approval = null, message = "This checkout's .git file was changed, so its teardown will not run."))
        assertEquals("This checkout's .git file was changed, so its teardown will not run.", shown(TeardownConfirmTags.Message))
        assertTrue("there is nothing to run", !exists(TeardownConfirmTags.Run))
        rule.onNodeWithTag(TeardownConfirmTags.Skip).performClick()
        assertEquals(1, skip)
        assertEquals(0, run)
        rule.onNodeWithTag(TeardownConfirmTags.Cancel).assertExists()
    }

    @Test fun noMessageFallsBackToTheWebsWords() {
        show(TeardownFixtures.confirmation(approval = null, message = null))
        assertEquals(TeardownConfirmCopy.NO_TEARDOWN, shown(TeardownConfirmTags.Message))
    }

    @Test fun theTitleNamesTheSessionOrFallsBack() {
        assertEquals("End \u2068Fix the build\u2069: run its teardown?", TeardownConfirmCopy.title("Fix the build"))
        assertEquals("End this session: run its teardown?", TeardownConfirmCopy.title(""))
        assertEquals("End this session: run its teardown?", TeardownConfirmCopy.title(null))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TeardownConfirmPhoneTest : TeardownConfirmBase()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TeardownConfirmTabletTest : TeardownConfirmBase()

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

enum class TeardownShot(val id: String, val confirmation: EndConfirmation) {
    Approval(
        "teardown-confirm",
        TeardownFixtures.confirmation(
            TeardownFixtures.approval(
                commands = listOf("pnpm run db:drop", "set -e\ndocker compose down -v\nrm -rf .cache", "echo аpi ‮ok"),
                changed = true,
                hidden = true,
                notice = "Ending this session also stops 2 other sessions that can write its checkout: Home folder, Scratch.",
            ),
        ),
    ),
    NoApproval(
        "teardown-confirm-none",
        TeardownFixtures.confirmation(approval = null, message = "This checkout's .git file was changed, so its teardown will not run."),
    ),
}

/** Both Studio skins, the dialog as the shell draws it. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TeardownConfirmPhoneScreenshotTest(private val shot: TeardownShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialog() = rule.snapTeardown(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TeardownShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TeardownConfirmTabletScreenshotTest(private val shot: TeardownShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialog() = rule.snapTeardown(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TeardownShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, ComponentActivity>.snapTeardown(shot: TeardownShot, skin: TetherSkin, size: String) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                TeardownConfirmDialog(shot.confirmation, onRun = {}, onSkip = {}, onCancel = {})
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    captureScreenRoboImage("src/test/screenshots/${shot.id}/${skin.id}-$size.png", roborazziOptions = exact)
}
