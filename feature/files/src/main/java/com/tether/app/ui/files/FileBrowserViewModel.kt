package com.tether.app.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tether.app.client.ConnectionState
import com.tether.app.client.TetherClient
import com.tether.app.client.WorkspaceFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns the file browser for as long as the same identity is signed in, and outlives a
 * configuration change (a rotation recreates the activity; the browser stays open in its folder
 * and a save or upload in flight keeps going).
 *
 * The teardown is keyed to [identity], never to composition: when it changes from the identity
 * the browser was made for — sign-out (null), a rejected credential (null), another server — every
 * job is cancelled, the state is replaced, and the whole scratch cache is swept, shared copies
 * included. Leaving the activity for good ([onCleared]) only sweeps what is past its window.
 */
class FileBrowserViewModel(
    private val files: WorkspaceFiles,
    private val platform: BrowserPlatform,
    identity: Flow<String?>,
) : ViewModel() {
    var state by mutableStateOf(newState())
        private set

    private var boundIdentity: String? = null

    init {
        viewModelScope.launch {
            identity.distinctUntilChanged().collect(::onIdentity)
        }
    }

    private fun newState() = FileBrowserState(files, platform, viewModelScope)

    private fun onIdentity(identity: String?) {
        val bound = boundIdentity
        boundIdentity = identity
        if (bound == null || bound == identity) return
        // The session the browser's content belonged to is over.
        state.dispose()
        platform.sweep(SweepMode.All)
        state = newState()
    }

    override fun onCleared() {
        state.dispose()
        platform.sweep(SweepMode.Expired)
    }

    companion object {
        /** The server a signed-in client talks to; null while nobody is signed in there. */
        fun identityOf(client: TetherClient): Flow<String?> =
            combine(client.configured, client.connection, client.serverUrl) { configured, connection, url ->
                if (configured && connection !is ConnectionState.AuthRequired) url else null
            }
    }
}
