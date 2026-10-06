package com.tether.app.ui.chat

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import com.tether.app.protocol.DelegateMention
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.20: the composer keeps what was typed across a configuration change, as a browser resize
 * keeps the textarea. The draft store the screen mirrors into is not involved here (no initial draft,
 * no store): the text comes back through the saved instance state alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ComposerRestorationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aRotationKeepsTheTypedDraft() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = null,
                    controls = null,
                    serverNow = { 0L },
                    onSend = { _, _ -> false },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                )
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("half a thought")
        rule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        val text = rule.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("half a thought", text)
    }

    @Test fun theDelegateMentionAndSheetViewRoundTripThroughTheirSavers() {
        val scope = SaverScope { true }
        val mention = DelegateMention("codex", "review", "gpt-5", null)
        with(DelegateMentionSaver) {
            assertEquals(mention, restore(scope.save(mention)!!))
            assertNull("nothing to keep saves nothing", scope.save(null))
        }
        with(SheetViewSaver) {
            assertEquals(SheetView.Effort, restore(scope.save(SheetView.Effort)!!))
            assertNull(restore(scope.save(null)!!))
        }
    }
}
