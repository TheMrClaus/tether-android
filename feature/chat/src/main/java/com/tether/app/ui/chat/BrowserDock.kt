package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.client.BrowserSocketOpener
import com.tether.app.client.BrowserProfilesReply
import com.tether.app.client.BrowserProfilesRequest
import com.tether.app.client.BrowserPick

/**
 * What the chat asks the shell to show as the pane's column (dashboard.tsx `is-browser-open`:
 * from 80rem the pane is a grid column beside the workspace, not an overlay).
 */
class BrowserDockRequest(
    val opener: BrowserSocketOpener,
    val sessionId: String,
    val onClose: () -> Unit,
    val onPicked: (BrowserPick, String) -> Unit,
    val browserProfiles: (suspend (BrowserProfilesRequest) -> BrowserProfilesReply)?,
)

/**
 * The shell's pane column: the shell provides one through [LocalBrowserDock] only while its window is
 * wide enough to dock (1280dp and up); the chat publishes its open pane here instead of drawing the
 * overlay. No provider (phones, narrower windows) = the overlay and the phone sheet, unchanged.
 */
@Stable
class BrowserDock {
    var request by mutableStateOf<BrowserDockRequest?>(null)
}

val LocalBrowserDock = staticCompositionLocalOf<BrowserDock?> { null }
