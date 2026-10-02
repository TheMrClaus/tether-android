package com.tether.app.ui.draft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.client.SetupConfirmation
import com.tether.app.client.WorktreeModes
import com.tether.app.ui.settings.CONFIRM_ARM_MS
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-q9l: the setup confirmation's key is armed for what it shows. Its host keys the dialog on the
 * confirmation's id; the dialog keys its key on the confirmation too, so a confirmation replaced in
 * the same composition (any host) re-arms: the whole window again. An equal one recomposed keeps it
 * armed (the host's single-confirmation flow is unchanged).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class WorktreeConfirmArmTest {
    @get:Rule val rule = createComposeRule()

    private var confirmed = 0
    private val first = SetupConfirmation(WorktreeModes.BRANCH_OFF, "Base", "main", "feature/x", "/srv/repo", certain = false)
    private var confirmation by mutableStateOf(first)

    private fun frame(ms: Long = 32) = rule.mainClock.advanceTimeBy(ms)

    private fun tap() {
        rule.onNodeWithTag(WorktreeTags.ConfirmKey, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        frame()
    }

    private fun replace(next: SetupConfirmation) {
        rule.runOnUiThread {
            confirmation = next
            Snapshot.sendApplyNotifications()
        }
        frame()
    }

    private fun host() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    WorktreeSetupConfirmDialog(confirmation, onConfirm = { confirmed++ }, onCancel = {})
                }
            }
        }
        frame(CONFIRM_ARM_MS + 50)
    }

    @Test fun aConfirmationReplacedWhileOpenReArms() {
        host()
        tap()
        assertEquals("armed (the positive control)", 1, confirmed)
        replace(first.copy(ref = "release", certain = true))
        tap()
        frame(CONFIRM_ARM_MS - 150)
        tap()
        assertEquals("inside the window again", 1, confirmed)
        frame(200)
        tap()
        assertEquals("re-armed", 2, confirmed)
    }

    @Test fun anEqualConfirmationRecomposedStaysArmed() {
        host()
        replace(first.copy())
        tap()
        assertEquals(1, confirmed)
    }
}
