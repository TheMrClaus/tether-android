package com.tether.app.ui.usage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.FixedRouteHttp
import com.tether.app.client.UsageCall
import com.tether.app.client.UsageFailure
import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.UsageWindow
import com.tether.app.ui.inspector.Inspector
import com.tether.app.ui.inspector.InspectorBoards
import com.tether.app.ui.statusline.screenshots.ScreenSize
import com.tether.app.ui.statusline.screenshots.StateRow
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.statusline.screenshots.goldenPath
import com.tether.app.ui.statusline.screenshots.snapBoard
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.usage.UsageFixtures.ORIGIN
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T9.2 goldens at the web's device classes (phone 412×915 @2.625, tablet 1280×800 @1), both Studio
 * skins, plus 1.3× font, on the fixed clock of [UsageFixtures]:
 *
 * - `usage-page` (loaded, the viewport), `usage-page-loading` (the skeleton), `usage-page-error`
 *   (no reading yet), `usage-page-empty` (the range had no usage), and `usage-page-full` (the whole
 *   loaded page, drawn tall);
 * - `usage-accounts` (the Accounts dialog, read from the cache), `usage-codex-reset` and
 *   `usage-claude-reset` (each confirmation over it, at its first stage);
 * - `deepseek-peak` (the badge at peak and off-peak, full and compact, and the rate card);
 * - `inspector-limits-use-reset` (the telemetry Limits band's banked resets with "Use reset").
 */
enum class UsageShot(val id: String) {
    Page("usage-page"), Loading("usage-page-loading"), Error("usage-page-error"), Empty("usage-page-empty"),
    Accounts("usage-accounts"), CodexReset("usage-codex-reset"), ClaudeReset("usage-claude-reset"),
}

private fun exact() = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private fun ComposeContentTestRule.settle(ms: Long = 600) {
    mainClock.advanceTimeBy(ms)
    waitForIdle()
}

fun ComposeContentTestRule.snapUsage(shot: UsageShot, skin: TetherSkin, size: ScreenSize, name: String = shot.id) {
    mainClock.autoAdvance = false
    var accounts: UsageAccountsState? = null
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalUsageEnv provides UsageFixtures.env, LocalResetDialogsInline provides true) {
                when (shot) {
                    UsageShot.Page, UsageShot.Loading, UsageShot.Error, UsageShot.Empty -> {
                        val state = remember {
                            UsageDashboardState().apply {
                                when (shot) {
                                    UsageShot.Page -> onResult(range, UsageCall.Ok(UsageFixtures.analytics(), ORIGIN))
                                    UsageShot.Error -> onResult(range, UsageCall.Failed(UsageFailure.Http(503, null)))
                                    UsageShot.Empty -> onResult(range, UsageCall.Ok(UsageFixtures.analytics(UsageFixtures.EMPTY_JSON), ORIGIN))
                                    else -> Unit
                                }
                            }
                        }
                        UsageDashboard(state, onOpenConsole = {})
                    }
                    else -> {
                        val scope = rememberCoroutineScope()
                        val source = remember { FakeUsageSource() }
                        val a = remember { UsageAccountsState(scope, { source }, { ORIGIN }) }
                        val codex = remember { CodexResetState(scope, { source }, { ORIGIN }) }
                        val claude = remember { ClaudeResetState(scope, { source }, { ORIGIN }, clock = { UsageFixtures.NOW }) }
                        accounts = a
                        Box {
                            UsageAccountsFrame(a, onUseCredit = { codex.open(it) }, onUseGrant = { claude.open(it) })
                            CodexResetDialog(codex)
                            ClaudeResetDialog(claude)
                        }
                    }
                }
            }
        }
    }
    accounts?.open()
    settle(100)
    when (shot) {
        UsageShot.CodexReset -> onNodeWithTag(AccountsTags.UseCredit).performSemanticsAction(SemanticsActions.OnClick)
        UsageShot.ClaudeReset -> onNodeWithTag(AccountsTags.useGrant("g_1")).performSemanticsAction(SemanticsActions.OnClick)
        else -> Unit
    }
    // On the paused clock the key's state change lands in the first step and composes in the second.
    settle(100)
    settle()
    onRoot().captureRoboImage(goldenPath(name, skin, size), roborazziOptions = exact())
}

private fun shots(shots: List<UsageShot>, skins: List<TetherSkin> = TetherSkin.entries): List<Array<Any>> =
    shots.flatMap { s -> skins.map { arrayOf<Any>(s, it) } }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class UsagePhoneScreenshotTest(private val shot: UsageShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun usage() = rule.snapUsage(shot, skin, ScreenSize.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = shots(UsageShot.entries)
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class UsageTabletScreenshotTest(private val shot: UsageShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun usage() = rule.snapUsage(shot, skin, ScreenSize.Tablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = shots(UsageShot.entries)
    }
}

/** The whole loaded page, drawn tall enough to hold it (the device scrolls it). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h5000dp-420dpi")
class UsagePageFullPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun usage() = rule.snapUsage(UsageShot.Page, skin, ScreenSize.Phone, name = "usage-page-full")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h3000dp-mdpi")
class UsagePageFullTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun usage() = rule.snapUsage(UsageShot.Page, skin, ScreenSize.Tablet, name = "usage-page-full")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** PLAN §4: 1.3× font scale does not break the page, the dialog or a confirmation. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class UsageFontScaleScreenshotTest(private val shot: UsageShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun usage() = rule.snapUsage(shot, skin, ScreenSize.Phone, name = "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = shots(listOf(UsageShot.Page, UsageShot.Accounts, UsageShot.CodexReset))
    }
}

/** The DeepSeek badge (workspace-header.tsx:113) at peak and off-peak, both variants, and the rate card. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DeepSeekPeakScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    private val offPeak = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()

    @Test fun board() = rule.snapBoard("deepseek-peak", skin, ScreenSize.Phone, reducedMotion = true) {
        CompositionLocalProvider(LocalUsageEnv provides UsageFixtures.env) {
            StateRow("peak · full / compact") {
                DeepSeekPeakBadge("dsh", null, DeepSeekPeakVariant.Full)
                DeepSeekPeakBadge("dsh", null, DeepSeekPeakVariant.Compact)
            }
        }
        CompositionLocalProvider(LocalUsageEnv provides UsageFixtures.env.copy(now = { offPeak }, fixedNow = offPeak)) {
            StateRow("off-peak · full / compact") {
                DeepSeekPeakBadge("dsh", "deepseek-v4-pro", DeepSeekPeakVariant.Full)
                DeepSeekPeakBadge("dsh", "deepseek-v4-pro", DeepSeekPeakVariant.Compact)
            }
        }
        CompositionLocalProvider(LocalUsageEnv provides UsageFixtures.env) {
            DeepSeekRateCard()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** inspector.tsx:755-770: the Limits band's banked resets row with its "Use reset" key (a Codex session). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InspectorUseResetScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapBoard("inspector-limits-use-reset", skin, ScreenSize.Phone, reducedMotion = true) {
        val metrics = SessionMetrics(
            fiveHour = UsageWindow(20.0, 300, InspectorBoards.NOW + 95 * InspectorBoards.MIN),
            weekly = UsageWindow(60.0, 10_080, InspectorBoards.NOW + 50 * 60 * InspectorBoards.MIN),
            codexResetCredits = FixedRouteHttp.parseObject("""{"availableCount":2,"credits":[{"id":"cr_9","status":"available","resetType":"codexRateLimits"}]}"""),
        )
        val model = InspectorBoards.model(InspectorBoards.session(provider = "codex", metrics = metrics))
        Column(Modifier.width(380.dp)) {
            Inspector(model, null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env }, onUseCodexReset = {})
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
