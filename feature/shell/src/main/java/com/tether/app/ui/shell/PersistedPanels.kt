package com.tether.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.serverOrigin
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.StateFlow
import com.tether.app.ui.prefs.launchPreferenceWrite

/** The expanded shell's persisted column state and the callback that saves a change. */
class PersistedPanels(val panels: PanelPrefs, val onChange: (PanelPrefs) -> Unit)

/**
 * Binds [ExpandedShell]'s `panels` / `onPanelsChange` to the app's preference store, as the web
 * binds `sidebarWidth` / `inspectorWidth` / `sidebarCollapsed` to `usePreferences()`
 * (dashboard.tsx:443-471). Reads the stored fields (nothing stored = the theme default). Saves a
 * settled change with an atomic read-modify-write of the whole model, so other fields are kept.
 */
@Composable
fun rememberPersistedPanels(prefs: UiPrefs, serverUrl: StateFlow<String?>): PersistedPanels {
    val scope = rememberCoroutineScope()
    // ta-coik.52: the server's own columns (the web's preferences are per origin).
    val preferences by remember(prefs, serverUrl) { prefs.preferencesFor(serverUrl) }.collectAsStateWithLifecycle(initialValue = null)
    val panels = preferences?.let(PanelPrefs::from) ?: PanelPrefs()
    return PersistedPanels(panels) { next -> scope.launchPreferenceWrite { prefs.updatePreferencesFor(serverOrigin(serverUrl.value)) { next.applyTo(it) } } }
}
