package com.tether.app.ui.share

import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.shell.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T11.2: the share chooser (a new screen; the web has no share target). `sessions` = text and two
 * files with three running sessions (the ended one is not offered); `empty` = one file and no running session.
 */
enum class ShareShot(val id: String) {
    Sessions("share-target-sessions"),
    Empty("share-target-empty"),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private fun session(id: String, name: String, cwd: String, at: Long, status: String = "running") =
    AgentSession(id = id, provider = "claude", name = name, cwd = cwd, status = status, startedAt = at, updatedAt = at)

private val fixtureSessions = listOf(
    session("s1", "Fix the login redirect", "/home/dev/tether", 3_000),
    session("s2", "Write release notes", "/home/dev/tether-android", 2_000),
    session("s3", "Old run", "/home/dev/scratch", 4_000, status = "exited"),
    session("s4", "Profile the sidebar", "/home/dev/tether", 1_000),
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ShareTargetScreenshotTest(private val shot: ShareShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialog() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TetherTheme(choiceFor(skin)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    when (shot) {
                        ShareShot.Sessions -> ShareTargetDialog(ShareTargetCopy.summary(true, 2), shareTargets(fixtureSessions), {}, {}, {})
                        ShareShot.Empty -> ShareTargetDialog(ShareTargetCopy.summary(false, 1), emptyList(), {}, {}, {})
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/${shot.id}/${skin.id}-phone.png", roborazziOptions = exact)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ShareShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
