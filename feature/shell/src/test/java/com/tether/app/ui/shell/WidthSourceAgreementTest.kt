package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.components.ProvideWindowWidthDp
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.layoutClassFor
import com.tether.app.ui.components.windowWidthDp
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-09ca (W20 A1): ONE width source. MainShell picks its shell from `windowWidthDp()`, and every component reads the
 * class through `currentLayoutClass()`. A Dialog and a Popup each have their own `LocalWindowInfo`, so the width MainShell
 * measured is carried to them by a composition local; this proves the shell, a slot, a Dialog, a Popup and a default
 * parameter evaluated inside a Dialog (DraftComposerFrame's `layout`) all say the same class, at the web's 768 edge, at
 * 420 and 440 dpi (at 440 dpi, 767 dp is 2109.25 px, which truncates to 766).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w768dp-h1000dp-420dpi")
class WidthSourceAgreementTest {
    @get:Rule val rule = createComposeRule()

    private class Probes {
        var slot: TetherLayoutClass? = null
        var dialog: TetherLayoutClass? = null
        var popup: TetherLayoutClass? = null
        var dialogDefault: TetherLayoutClass? = null
    }

    /** DraftComposerFrame's shape: a `layout` default parameter, evaluated where it is called (here, inside a Dialog). */
    @androidx.compose.runtime.Composable
    private fun FrameLike(onLayout: (TetherLayoutClass) -> Unit, layout: TetherLayoutClass = currentLayoutClass()) {
        SideEffect { onLayout(layout) }
    }

    private fun agree(expected: TetherLayoutClass, override: IntSize? = null) {
        val client = ShellConsentClient()
        val session = AgentSession(id = "s1", provider = "claude", name = "s1", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1, historyId = "h-s1")
        client.show(session, freshTree())
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        val probes = Probes()
        rule.setContent {
            TetherTheme {
                val host: @androidx.compose.runtime.Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) { MainShell(vm, prefs) }
                    // The same wrapper MainShell is built on: what a slot, a Dialog and a Popup under it read.
                    ProvideWindowWidthDp {
                        val inSlot = currentLayoutClass()
                        SideEffect { probes.slot = inSlot }
                        Dialog(onDismissRequest = {}) {
                            val inDialog = currentLayoutClass()
                            SideEffect { probes.dialog = inDialog }
                            FrameLike({ probes.dialogDefault = it })
                        }
                        Popup { val inPopup = currentLayoutClass(); SideEffect { probes.popup = inPopup } }
                    }
                }
                if (override == null) host() else CompositionLocalProvider(LocalWindowInfo provides windowOf(override)) { host() }
            }
        }
        rule.waitForIdle()
        val shell = if (rule.onAllNodesWithTagCount(ShellTags.MenuKey) > 0) TetherLayoutClass.Phone else TetherLayoutClass.Expanded
        assertEquals("the shell MainShell picked", expected, shell)
        assertEquals("a slot", expected, probes.slot)
        assertEquals("a Dialog", expected, probes.dialog)
        assertEquals("a Popup", expected, probes.popup)
        assertEquals("a default parameter inside a Dialog (DraftComposerFrame layout)", expected, probes.dialogDefault)
    }

    private fun windowOf(size: IntSize) = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = size
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test @Config(qualifiers = "w767dp-h1000dp-420dpi") fun at767dp420dpiEverythingIsPhone() = agree(TetherLayoutClass.Phone)
    @Test @Config(qualifiers = "w768dp-h1000dp-420dpi") fun at768dp420dpiEverythingIsExpanded() = agree(TetherLayoutClass.Expanded)
    @Test @Config(qualifiers = "w769dp-h1000dp-420dpi") fun at769dp420dpiEverythingIsExpanded() = agree(TetherLayoutClass.Expanded)
    @Test @Config(qualifiers = "w767dp-h1000dp-440dpi") fun at767dp440dpiEverythingIsPhone() = agree(TetherLayoutClass.Phone)
    @Test @Config(qualifiers = "w768dp-h1000dp-440dpi") fun at768dp440dpiEverythingIsExpanded() = agree(TetherLayoutClass.Expanded)
    @Test @Config(qualifiers = "w769dp-h1000dp-440dpi") fun at769dp440dpiEverythingIsExpanded() = agree(TetherLayoutClass.Expanded)

    /**
     * The injected window (the ~20 MainShell tests) stops at the Dialog: its own window reports the real display (412 dp
     * here). The provided width carries the shell's measurement across, so a Dialog still follows the shell.
     */
    @Test @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun anInjectedWindowReachesADialogAndAPopupThroughTheProvidedWidth() = agree(TetherLayoutClass.Expanded, override = IntSize(800, 1000))

    @Test @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun anInjectedNarrowWindowIsPhoneInTheDialogToo() = agree(TetherLayoutClass.Phone, override = IntSize(767, 1000))

    /** Outside MainShell (setup, login) nothing is provided: the read is the window's own container size. */
    @Test @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun withoutMainShellTheWidthIsTheWindowsOwn() {
        var width = -1
        var cls: TetherLayoutClass? = null
        rule.setContent {
            CompositionLocalProvider(LocalWindowInfo provides windowOf(IntSize(900, 600))) {
                width = windowWidthDp(); cls = currentLayoutClass()
            }
        }
        rule.waitForIdle()
        assertEquals(900, width)
        assertEquals(TetherLayoutClass.Expanded, cls)
    }

    /** The pure conversion: 2015 px at 2.625 is 767.6 dp (Phone); 2016 px is exactly 768 dp (Expanded). */
    @Test fun theTruncationAtTheEdgeIsPhoneBelow768AndExpandedFrom() {
        val d = Density(2.625f)
        fun dpOf(px: Int) = with(d) { px.toDp().value.toInt() }
        assertEquals(TetherLayoutClass.Phone, layoutClassFor(dpOf(2015)))
        assertEquals(TetherLayoutClass.Expanded, layoutClassFor(dpOf(2016)))
        assertEquals(TetherLayoutClass.Phone, layoutClassFor(767))
        assertEquals(TetherLayoutClass.Expanded, layoutClassFor(768))
    }
}
