package com.tether.app.ui.draft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.tether.app.client.DraftComposerModel
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.ui.components.FixedKeyboardInset
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-abm r2 (F1): the keyboard never hides Send. Compose dialogs do not resize their window for the
 * keyboard (ADJUST_NOTHING from API 31), so the sheet lifts itself by the keyboard inset: on a phone it
 * docks on the keyboard (the web's bottom sheet), on an expanded window the card centres above it, and
 * the message well stays at the sheet's foot. The frame alone at several window sizes and keyboard
 * heights, the busiest draft included (an attachment and an error line).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h1000dp-mdpi")
class DraftKeyboardGeometryTest {
    @get:Rule val rule = createComposeRule()
    private val job = Job()

    @After fun stop() = job.cancel()

    private fun inputs(rich: Boolean): DraftSheetInputs {
        val model = DraftComposerModel(DraftTestClient(failOnSend = true), InMemoryDraftStore(), CoroutineScope(Dispatchers.Unconfined + job), currentWorkspace = { null })
        model.onOrigin(DraftFixtures.ORIGIN)
        model.refresh()
        model.selectProvider("work")
        model.setText("Summarize the README, then list the open issues that mention the sidebar.")
        if (rich) model.setStagedAttachments(listOf(StagedAttachment(1, Attachment(name = "sidebar-notes.md", mediaType = "text/markdown", data = "aGVsbG8="), 2_458)))
        val state = model.state.value.let { if (rich) it.copy(error = "Skipping tool approvals needs a browser sign-in, not a paired device.") else it }
        return DraftSheetInputs(
            state, draftBrowserInputs(state, DraftFixtures.catalog, DraftFixtures.providers, com.tether.app.ui.chat.IcuJsCollator.forLocale(java.util.Locale.US), 0L),
            workspaceQuickPicks(emptyList(), "", DraftFixtures.ROOT, DraftFixtures.ROOT), DraftFixtures.ROOT, model.readiness(),
        )
    }

    /** Send's drawn (clipped) bounds in a [w]×[h] window with a [keyboard]-tall keyboard. */
    private fun send(layout: TetherLayoutClass, w: Dp, h: Dp, keyboard: Dp, rich: Boolean = false): DpRect {
        val i = inputs(rich)
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalKeyboardInset provides FixedKeyboardInset(keyboard)) {
                    Box(Modifier.size(w, h)) { DraftComposerFrame(i, DraftSheetActions(), layout = layout) }
                }
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true)
        val drawn = node.getBoundsInRoot()
        val whole = node.getUnclippedBoundsInRoot()
        assertTrue("Send is drawn whole (not clipped by a scroller): $drawn vs $whole", drawn == whole)
        return drawn
    }

    private fun assertAboveKeyboard(layout: TetherLayoutClass, w: Dp, h: Dp, keyboard: Dp, rich: Boolean = false) {
        val b = send(layout, w, h, keyboard, rich)
        val top = h - keyboard
        assertTrue("$layout ${w}x$h kb=$keyboard rich=$rich: Send bottom ${b.bottom} must sit above the keyboard top $top", b.bottom <= top)
        assertTrue("Send is on screen: $b", b.top >= 0.dp && b.bottom > b.top)
    }

    @Test fun phoneReadyAbove300dpKeyboard() = assertAboveKeyboard(TetherLayoutClass.Phone, 412.dp, 915.dp, 300.dp)

    @Test fun phoneBusiestDraftAbove300dpKeyboard() = assertAboveKeyboard(TetherLayoutClass.Phone, 412.dp, 915.dp, 300.dp, rich = true)

    @Test fun smallPhoneAbove280dpKeyboard() = assertAboveKeyboard(TetherLayoutClass.Phone, 360.dp, 740.dp, 280.dp)

    @Test fun smallPhoneBusiestDraftAbove280dpKeyboard() = assertAboveKeyboard(TetherLayoutClass.Phone, 360.dp, 740.dp, 280.dp, rich = true)

    @Test fun tallKeyboardOnAShortPhone() = assertAboveKeyboard(TetherLayoutClass.Phone, 393.dp, 660.dp, 340.dp, rich = true)

    @Test fun phoneWithoutKeyboardDocksAtTheBottom() {
        val b = send(TetherLayoutClass.Phone, 412.dp, 915.dp, 0.dp)
        assertTrue("Send near the window's foot: $b", b.bottom <= 915.dp && b.bottom >= 915.dp - 120.dp)
    }

    @Test fun expandedCardKeepsSendAboveA300dpKeyboard() = assertAboveKeyboard(TetherLayoutClass.Expanded, 1280.dp, 800.dp, 300.dp, rich = true)

    @Test fun anImpossibleKeyboardDoesNotCrash() {
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalKeyboardInset provides FixedKeyboardInset(5_000.dp)) {
                    Box(Modifier.size(412.dp, 915.dp)) { DraftComposerFrame(inputs(true), DraftSheetActions(), layout = TetherLayoutClass.Phone) }
                }
            }
        }
        rule.waitForIdle()
    }
}
