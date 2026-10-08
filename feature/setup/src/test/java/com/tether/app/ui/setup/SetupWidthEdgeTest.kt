package com.tether.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.tether.app.client.SetupCall
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-09ca E2: the setup wizard's phone form is studio.css:957/1031's `(max-width: 640px)` block, so it holds at 640 dp and
 * is the desktop form from 641, not at the shell's 768. The phone form stacks the welcome and centres its copy in a
 * 20 dp-sided page; the desktop form sets the copy in the left column of a row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h1000dp-mdpi")
class SetupWidthEdgeTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun stop() = scope.cancel()

    private fun beginCentreX(): Float {
        val api = FakeSetupApi(state = SetupCall.Ok(FakeSetupApi.sampleState()))
        val model = SetupWizardModel(api, scope, restartPollMs = 3_600_000, accountTiming = SetupAccountTiming(initialMs = 0, pollMs = 3_600_000, retryMs = 3_600_000))
        model.load()
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalReducedMotion provides true) { SetupWizardScreen(model) } } }
        rule.waitForIdle()
        val b = rule.onNodeWithTag(SetupTags.Begin, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return (b.left + b.right) / 2f
    }

    @Test fun at640ThePhoneFormCentresTheWelcomeInTheWindow() {
        val x = beginCentreX()
        assertEquals("phone form: Begin is centred in the 640 dp window", 320f, x, 2f)
    }

    @Test @Config(qualifiers = "w641dp-h1000dp-mdpi")
    fun at641TheDesktopFormSetsTheWelcomeInItsLeftColumn() {
        val x = beginCentreX()
        assertTrue("desktop form: Begin sits in the left column, left of the 641 dp window's centre: $x", x < 641f / 2f - 20f)
    }

    @Test @Config(qualifiers = "w768dp-h1000dp-mdpi")
    fun atTheShellsOwnEdgeItIsStillTheDesktopForm() {
        val x = beginCentreX()
        assertTrue("desktop form at 768: $x", x < 768f / 2f - 20f)
    }
}
