package com.tether.app.ui.shell

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneShellStateTest {

    @Test fun startsWithEverythingClosed() {
        val s = PhoneShellState()
        assertFalse(s.drawerOpen)
        assertFalse(s.telemetryOpen)
        assertFalse(s.linksOpen)
        assertFalse(s.canHandleBack)
        assertFalse("back falls through to the system", s.handleBack())
    }

    @Test fun drawerOpensAndCloses() {
        val s = PhoneShellState()
        s.openDrawer()
        assertTrue(s.drawerOpen)
        assertTrue(s.canHandleBack)
        s.closeDrawer()
        assertFalse(s.drawerOpen)
    }

    @Test fun selectingASessionClosesTheDrawerAndThePopover() {
        val s = PhoneShellState(drawerOpen = true, linksOpen = true)
        s.onSessionSelected()
        assertFalse(s.drawerOpen)
        assertFalse(s.linksOpen)
    }

    @Test fun selectingASessionKeepsTheTelemetryPanel() {
        // dashboard.tsx never resets telemetryOpen on selectSession.
        val s = PhoneShellState(telemetryOpen = true)
        s.onSessionSelected()
        assertTrue(s.telemetryOpen)
    }

    @Test fun gaugeIsTheTelemetryHandle() {
        val s = PhoneShellState()
        s.toggleTelemetry()
        assertTrue(s.telemetryOpen)
        s.toggleTelemetry()
        assertFalse("tap again collapses it back", s.telemetryOpen)
        s.toggleTelemetry()
        s.closeTelemetry()
        assertFalse(s.telemetryOpen)
    }

    @Test fun openingAnotherSurfaceDismissesTheLightDismissPopover() {
        val s = PhoneShellState(linksOpen = true)
        s.toggleTelemetry()
        assertFalse(s.linksOpen)
        s.toggleLinks()
        assertTrue(s.linksOpen)
        s.openDrawer()
        assertFalse(s.linksOpen)
    }

    @Test fun linksToggle() {
        val s = PhoneShellState()
        s.toggleLinks()
        assertTrue(s.linksOpen)
        s.toggleLinks()
        assertFalse(s.linksOpen)
        s.toggleLinks()
        s.closeLinks()
        assertFalse(s.linksOpen)
    }

    @Test fun backClosesTheTopmostSurfaceFirst() {
        val s = PhoneShellState(drawerOpen = true, telemetryOpen = true, linksOpen = true)
        assertTrue(s.handleBack())
        assertEquals(Triple(true, true, false), Triple(s.drawerOpen, s.telemetryOpen, s.linksOpen))
        assertTrue(s.handleBack())
        assertEquals(Triple(false, true, false), Triple(s.drawerOpen, s.telemetryOpen, s.linksOpen))
        assertTrue(s.handleBack())
        assertEquals(Triple(false, false, false), Triple(s.drawerOpen, s.telemetryOpen, s.linksOpen))
        assertFalse(s.canHandleBack)
        assertFalse(s.handleBack())
    }

    @Test fun saverRoundTrips() {
        val scope = SaverScope { true }
        val original = PhoneShellState(drawerOpen = true, telemetryOpen = false, linksOpen = true)
        val saved = with(PhoneShellState.Saver) { scope.save(original) }!!
        val restored = PhoneShellState.Saver.restore(saved)!!
        assertEquals(original.drawerOpen, restored.drawerOpen)
        assertEquals(original.telemetryOpen, restored.telemetryOpen)
        assertEquals(original.linksOpen, restored.linksOpen)
    }

    @Test fun phoneShellBelowTheExpandedCutoff() {
        assertEquals(com.tether.app.ui.components.TetherLayoutClass.Phone, shellLayoutFor(412))
        assertEquals(com.tether.app.ui.components.TetherLayoutClass.Phone, shellLayoutFor(839))
        assertEquals(com.tether.app.ui.components.TetherLayoutClass.Expanded, shellLayoutFor(840))
        assertEquals(com.tether.app.ui.components.TetherLayoutClass.Expanded, shellLayoutFor(1280))
    }

    @Test fun drawerWidthFollowsTheWebClamp() {
        // Studio: min(21rem, 92vw) (studio.css 443).
        assertEquals(336f, drawerWidth(viewportWidth = androidx.compose.ui.unit.Dp(412f)).value, 0.01f)
        assertEquals(331.2f, drawerWidth(viewportWidth = androidx.compose.ui.unit.Dp(360f)).value, 0.01f)
    }
}
