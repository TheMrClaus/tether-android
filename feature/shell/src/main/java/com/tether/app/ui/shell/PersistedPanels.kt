package com.tether.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.launch

/** The expanded shell's persisted column state and the callback that saves a change. */
class PersistedPanels(val panels: PanelPrefs, val onChange: (PanelPrefs) -> Unit)

/**
 * Binds [ExpandedShell]'s `panels` / `onPanelsChange` to the app's preference store, as the web
 * binds `sidebarWidth` / `inspectorWidth` / `sidebarCollapsed` to `usePreferences()`
 * (dashboard.tsx:443-471). Reads the stored fields (nothing stored = the theme default). Saves a
 * settled change with an atomic read-modify-write of the whole model, so other fields are kept.
 */
@Composable
fun rememberPersistedPanels(prefs: UiPrefs): PersistedPanels {
    val scope = rememberCoroutineScope()
    val preferences by prefs.preferences.collectAsStateWithLifecycle(initialValue = null)
    val panels = preferences?.let(PanelPrefs::from) ?: PanelPrefs()
    return PersistedPanels(panels) { next -> scope.launch { prefs.updatePreferences { next.applyTo(it) } } }
}
