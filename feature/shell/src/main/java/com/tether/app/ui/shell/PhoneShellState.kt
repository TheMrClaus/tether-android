package com.tether.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The phone shell's own UI state — the web dashboard's `drawerOpen` / `telemetryOpen` state
 * (components/dashboard.tsx:70, :233) plus the workspace header's "Session links" popover
 * (workspace-header.tsx, `popover="auto"`). State, not dialogs: the telemetry panel collapses
 * back into its handle (the gauge), and the drawer is a transform, not a modal route.
 *
 * Android's back gesture closes the topmost of them ([handleBack]), in the order they stack on
 * screen: the links popover (top layer), then the drawer (z-modal over the scrim), then the
 * telemetry panel (in the workspace column, the web's Escape handler); T15.4's utility menu closes
 * before all of them (it opens over everything). With nothing open, back
 * is not consumed and falls through to the system.
 */
@Stable
class PhoneShellState(
    drawerOpen: Boolean = false,
    telemetryOpen: Boolean = false,
    linksOpen: Boolean = false,
    menuOpen: Boolean = false,
) {
    /** The session drawer (`.session-sidebar.is-open`). */
    var drawerOpen: Boolean by mutableStateOf(drawerOpen)
        private set

    /** The collapsible telemetry panel (`.telemetry-sheet.is-open`). */
    var telemetryOpen: Boolean by mutableStateOf(telemetryOpen)
        private set

    /** The header's "Session links" popover. */
    var linksOpen: Boolean by mutableStateOf(linksOpen)
        private set

    /** T15.4: the top bar's utility menu (topbar.tsx `menuOpen`). */
    var menuOpen: Boolean by mutableStateOf(menuOpen)
        private set

    /** The trigger toggles the menu; the light-dismiss popover closes (one light-dismiss surface at a time). */
    fun toggleMenu() {
        linksOpen = false
        menuOpen = !menuOpen
    }

    /** An outside tap, an item, or back. */
    fun closeMenu() {
        menuOpen = false
    }

    /** Topbar drawer key (`onOpenDrawer`). Opening the drawer dismisses the light-dismiss popover and menu. */
    fun openDrawer() {
        linksOpen = false
        menuOpen = false
        drawerOpen = true
    }

    /** Backdrop tap, the drawer's own close, or a selection made in it. */
    fun closeDrawer() {
        drawerOpen = false
    }

    /**
     * The gauge is the panel's handle: tap to open, tap again to collapse (dashboard.tsx:79-82).
     * On the phone layout it always toggles; the web's `min-width: 100rem` guard is the
     * expanded layout's concern (T4.2), where the inspector column owns the surface.
     */
    fun toggleTelemetry() {
        linksOpen = false
        telemetryOpen = !telemetryOpen
    }

    /** The panel's close key / back (`closeTelemetry`). */
    fun closeTelemetry() {
        telemetryOpen = false
    }

    /** `.workspace-links-trigger` toggles its popover (`popoverTarget`). */
    fun toggleLinks() {
        menuOpen = false
        linksOpen = !linksOpen
    }

    fun closeLinks() {
        linksOpen = false
    }

    /**
     * A session was picked (from the drawer or elsewhere): the web's `selectSession` closes the
     * drawer (dashboard.tsx:239); the popover belongs to the previous session's header.
     */
    fun onSessionSelected() {
        drawerOpen = false
        linksOpen = false
        menuOpen = false
    }

    /** Whether a back gesture would be consumed by the shell. */
    val canHandleBack: Boolean get() = menuOpen || linksOpen || drawerOpen || telemetryOpen

    /** Closes the topmost open surface; false when nothing was open (back falls through). */
    fun handleBack(): Boolean = when {
        menuOpen -> { menuOpen = false; true }
        linksOpen -> { linksOpen = false; true }
        drawerOpen -> { drawerOpen = false; true }
        telemetryOpen -> { telemetryOpen = false; true }
        else -> false
    }

    companion object {
        /** Survives configuration changes and process death, like the web keeps it across renders. */
        val Saver: Saver<PhoneShellState, Any> = listSaver(
            save = { listOf(it.drawerOpen, it.telemetryOpen, it.linksOpen, it.menuOpen) },
            restore = { PhoneShellState(drawerOpen = it[0], telemetryOpen = it[1], linksOpen = it[2], menuOpen = it.getOrElse(3) { false }) },
        )
    }
}

@Composable
fun rememberPhoneShellState(): PhoneShellState = rememberSaveable(saver = PhoneShellState.Saver) { PhoneShellState() }
