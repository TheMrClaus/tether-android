package com.tether.app.ui.chat

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.5 slice c states (both Studio skins, phone): `mention` = the `@` picker with the Agents and the
 * "Sessions on this project" (two locked rows: running, waiting); `loading` = the takeover draft
 * waiting for its brief; `draft` = the brief landed (the editable instruction, the folded summary,
 * Cancel / Take over). chat-view.tsx 90fbb9f :3931-4078, globals.css 7204-7282, 7346-7465.
 */
enum class TakeoverShot(val id: String) {
    Mention("mention"),
    Loading("loading"),
    Draft("draft"),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TakeoverPhoneScreenshotTest(private val shot: TakeoverShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun takeover() {
        val rec = TakeoverFixtures.Recorder()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ComposerHost(skin) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = ComposerFixtures.idle.projection,
                    controls = SessionControlFixtures.claudeIdleControls,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    initialDraft = "Continue @",
                    tree = ComposerFixtures.idle.tree,
                    controlActions = SessionControlFixtures.Recorder().actions(),
                    runActions = CommandFixtures.Recorder().actions(agents = CommandFixtures.catalog),
                    takeover = rec.takeover(),
                )
            }
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        if (shot != TakeoverShot.Mention) {
            rule.mainClock.autoAdvance = true
            rule.onNodeWithTag(TakeoverTags.session(TakeoverFixtures.older.id), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            if (shot == TakeoverShot.Draft) {
                rec.briefs = mapOf(TakeoverFixtures.older.id to TakeoverFixtures.brief())
                rule.waitForIdle()
            }
            rule.mainClock.autoAdvance = false
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
        }
        rule.onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/takeover-${shot.id}/${skin.id}-phone.png", roborazziOptions = exact)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TakeoverShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
