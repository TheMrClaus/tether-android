package com.tether.app.ui.draft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tether.app.client.DraftComposerModel
import com.tether.app.ui.components.FixedKeyboardInset
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.min

/**
 * ta-ny88: `.draft-dialog` has two breakpoints. The frame (docked, top border, no subtitle) is keyed at 40rem
 * (globals.css:8884, 640 dp and under); the inner metrics at 48rem (:11008, under 768). The card above 640 is
 * `min(736, W - 38)` (the UA's `dialog:modal` cap). The frame alone with its defaults, one class per width, in a
 * window of that width (web captures ny88-<W>x1024: 603 / 662 / 729 at 641 / 700 / 767, 730 at 768).
 */
abstract class DraftDialogBandBase(private val width: Int) {
    @get:Rule val rule = createComposeRule()
    private val job = Job()

    @After fun stop() = job.cancel()

    @Test fun theFrameFollowsTheTwoBreakpoints() {
        rule.setContent { TetherTheme { Box(Modifier.fillMaxSize()) { DraftComposerFrame(draftInputs(job), DraftSheetActions()) } } }
        rule.waitForIdle()
        val sheet = rule.onNodeWithTag(DraftComposerTags.Sheet).getBoundsInRoot()
        val root = rule.onRoot().getBoundsInRoot()
        if (width <= 640) {
            assertEquals("docked: the window's foot", root.bottom.value, sheet.bottom.value, 0.5f)
            assertEquals(0f, sheet.left.value, 0.5f)
            assertEquals("full width", width.toFloat(), sheet.width.value, 0.5f)
            rule.onNodeWithText(DRAFT_SUBTITLE).assertDoesNotExist()
        } else {
            val card = min(736, width - 38).toFloat()
            assertEquals("min(736, W - 38)", card, sheet.width.value, 0.5f)
            assertEquals("centred", (width - card) / 2f, sheet.left.value, 0.5f)
            assert(sheet.bottom.value < root.bottom.value - 1f) { "a centred card does not touch the window's foot: $sheet in $root" }
            rule.onNodeWithText(DRAFT_SUBTITLE).assertExists()
        }
        // The title's inset from the sheet's edge: the inner metrics, keyed at 48rem.
        val title = rule.onAllNodesWithText(DRAFT_TITLE)[0].getBoundsInRoot()
        assertEquals("title inset", if (width < 768) 16f else 24f, title.left.value - sheet.left.value, 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w639dp-h1024dp-mdpi") class DraftDialogBandW639Test : DraftDialogBandBase(639)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w640dp-h1024dp-mdpi") class DraftDialogBandW640Test : DraftDialogBandBase(640)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w641dp-h1024dp-mdpi") class DraftDialogBandW641Test : DraftDialogBandBase(641)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w700dp-h1024dp-mdpi") class DraftDialogBandW700Test : DraftDialogBandBase(700)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w767dp-h1024dp-mdpi") class DraftDialogBandW767Test : DraftDialogBandBase(767)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w768dp-h1024dp-mdpi") class DraftDialogBandW768Test : DraftDialogBandBase(768)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w773dp-h1024dp-mdpi") class DraftDialogBandW773Test : DraftDialogBandBase(773)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w774dp-h1024dp-mdpi") class DraftDialogBandW774Test : DraftDialogBandBase(774)

/**
 * The docked sheet's keyboard and nav-bar handling is the docked sheet's: with the keyboard up (and a 3-button
 * nav bar) the card above 640 keeps the web's 12 dp composer padding and 96 dp message well, and the sheet at
 * 640 keeps its docked values (space-sm over the keyboard, a 44 dp well, max(12, nav bar) without one).
 */
abstract class DraftDockInsetBase(private val keyboard: Dp, private val nav: Dp) {
    @get:Rule val rule = createComposeRule()
    private val job = Job()

    @After fun stop() = job.cancel()

    /** The composer's bottom padding and the well's height, with the keyboard and the nav bar injected. */
    protected fun measure(): Pair<Float, Float> {
        var view: android.view.View? = null
        rule.setContent {
            view = LocalView.current
            TetherTheme {
                CompositionLocalProvider(LocalKeyboardInset provides FixedKeyboardInset(keyboard)) {
                    Box(Modifier.fillMaxSize()) { DraftComposerFrame(draftInputs(job), DraftSheetActions()) }
                }
            }
        }
        rule.waitForIdle()
        // After the first composition: Compose installs its insets listener on the view then.
        val px = (nav.value * view!!.resources.displayMetrics.density).toInt()
        val bar = androidx.core.graphics.Insets.of(0, 0, 0, px)
        rule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                view!!,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), bar)
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), bar)
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build(),
            )
        }
        rule.waitForIdle()
        val sheet = rule.onNodeWithTag(DraftComposerTags.Sheet).getBoundsInRoot()
        val composer = rule.onNodeWithTag(DraftComposerTags.Composer).getBoundsInRoot()
        val well = rule.onNodeWithTag(DraftComposerTags.Input).getBoundsInRoot()
        return (sheet.bottom - composer.bottom).value to well.height.value
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w700dp-h1024dp-mdpi")
class DraftDockInsets700Test : DraftDockInsetBase(keyboard = 300.dp, nav = 48.dp) {
    @Test fun theCentredCardKeepsTheWebsPaddingAndWell() {
        val (bottomPadding, well) = measure()
        assertEquals("composer bottom padding (space-md)", 12f, bottomPadding, 0.5f)
        assertEquals("message well min-height", 96f, well, 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h1024dp-mdpi")
class DraftDockInsets640Test : DraftDockInsetBase(keyboard = 300.dp, nav = 48.dp) {
    @Test fun theDockedSheetKeepsItsDockedValues() {
        val (bottomPadding, well) = measure()
        assertEquals("space-sm over the keyboard", 8f, bottomPadding, 0.5f)
        assertEquals("a 44 dp well with the keyboard up", 44f, well, 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h1024dp-mdpi")
class DraftDockNavInset640Test : DraftDockInsetBase(keyboard = 0.dp, nav = 48.dp) {
    /** Proves the injection works: the docked sheet clears the nav bar, 48 dp, where max(12, 0) would be 12. */
    @Test fun theDockedSheetClearsTheNavBar() {
        val (bottomPadding, well) = measure()
        assertEquals("max(space-md, nav bar)", 48f, bottomPadding, 0.5f)
        assertEquals(96f, well, 0.5f)
    }
}

private fun draftInputs(job: Job): DraftSheetInputs {
    val model = DraftComposerModel(DraftTestClient(failOnSend = true), InMemoryDraftStore(), CoroutineScope(Dispatchers.Unconfined + job), currentWorkspace = { null })
    model.onOrigin(DraftFixtures.ORIGIN)
    model.refresh()
    model.selectProvider("work")
    model.setText("Summarize the README.")
    val state = model.state.value
    return DraftSheetInputs(
        state, draftBrowserInputs(state, DraftFixtures.catalog, DraftFixtures.providers, com.tether.app.ui.chat.IcuJsCollator.forLocale(java.util.Locale.US), 0L),
        workspaceQuickPicks(emptyList(), "", DraftFixtures.ROOT, DraftFixtures.ROOT), DraftFixtures.ROOT, model.readiness(),
    )
}
