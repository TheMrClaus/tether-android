package com.tether.app.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-3e7: the providers of the web's empty-state seed (tether scripts/parity-seed.mjs), ACP unconfigured. */
object WelcomeFixtures {
    val providers = listOf(
        ProviderAvailability("Claude Code", true, "claude"),
        ProviderAvailability("Codex", true, "codex"),
        ProviderAvailability("OpenCode", true, "opencode"),
        ProviderAvailability("Reasonix", true, "reasonix"),
        ProviderAvailability("Pi", true, "pi"),
        ProviderAvailability("DeepSeek Harness", true, "dsh"),
        ProviderAvailability("ACP", false, "acp"),
    )
}

/** The link state of a welcome golden. */
enum class WelcomeLink(val id: String, val connected: Boolean, val readout: LinkReadout) {
    Connected("connected", true, LinkReadout.Connected),
    Offline("offline", false, LinkReadout.Reconnecting),
}

/** The shell's hosted surfaces with the Studio welcome in its empty-stage slot (nothing hosted sends). */
private fun welcomeSlots(base: PhoneShellSlots, link: WelcomeLink) = PhoneShellSlots(
    drawer = base.drawer,
    chat = base.chat,
    inspector = base.inspector,
    gauge = base.gauge,
    dial = base.dial,
    studioWelcome = { expanded ->
        StudioWelcome(link.connected, WelcomeFixtures.providers, onNewSession = {}, onOpenWorkspace = {}, expanded = expanded)
    },
)

/** 600ms past the first frame: every transition has settled. */
private const val CaptureAtMs = 600L

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapWelcome(expanded: Boolean, link: WelcomeLink, skin: TetherSkin, path: String) {
    mainClock.autoAdvance = false
    // Seeded synchronously: the stage, its providers and the link are all fixed before the first frame.
    val stage = EmptyStage.Welcome(link.connected, WelcomeFixtures.providers)
    setContent {
        if (expanded) {
            ExpandedShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = welcomeSlots(expandedSlots(), link), link = link.readout)
        } else {
            ShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = welcomeSlots(placeholderSlots(), link), link = link.readout)
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onRoot().captureRoboImage(
        path,
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

private fun matrix(): List<Array<Any>> = WelcomeLink.entries.flatMap { l -> TetherSkin.entries.map { arrayOf<Any>(l, it) } }

/** Phone (the web's 412×915 viewport): connected and offline, Studio light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StudioWelcomePhoneScreenshotTest(private val link: WelcomeLink, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun welcome() = rule.snapWelcome(false, link, skin, "src/test/screenshots/studio-welcome-${link.id}/${skin.id}-phone.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = matrix()
    }
}

/** Expanded (the web's 1280×800 tablet viewport): connected and offline, Studio light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class StudioWelcomeTabletScreenshotTest(private val link: WelcomeLink, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun welcome() = rule.snapWelcome(true, link, skin, "src/test/screenshots/studio-welcome-${link.id}/${skin.id}-tablet.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = matrix()
    }
}

/** PLAN §4: 1.3× font scale (phone and tablet, connected, Studio light and dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class StudioWelcomePhoneFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun welcome() = rule.snapWelcome(false, WelcomeLink.Connected, skin, "src/test/screenshots/studio-welcome-font-1.3x/${skin.id}-phone.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 1.3f)
class StudioWelcomeTabletFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun welcome() = rule.snapWelcome(true, WelcomeLink.Connected, skin, "src/test/screenshots/studio-welcome-font-1.3x/${skin.id}-tablet.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
