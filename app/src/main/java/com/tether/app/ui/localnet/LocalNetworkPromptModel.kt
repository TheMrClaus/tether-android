package com.tether.app.ui.localnet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which caller hit the block. Each source clears only its own prompt. */
enum class LocalNetworkSource { Login, Connection }

sealed interface LocalNetworkPhase {
    /** Nothing to show: not blocked, or access granted. */
    data object Idle : LocalNetworkPhase

    /** Tether's own explanation, shown once before the system dialog. */
    data object Explaining : LocalNetworkPhase

    /** The system permission dialog is up. */
    data object Requesting : LocalNetworkPhase

    /**
     * Persistent notice until access is granted. The Allow action re-requests when
     * [canRequest] is true. When it is false the system will not show the dialog
     * again, so Allow opens the app's settings page instead.
     */
    data class Denied(val canRequest: Boolean) : LocalNetworkPhase
}

/**
 * The Android 17 local-network permission flow as a plain state machine, so it
 * can be unit tested without a device:
 *
 *   blocked -> Explaining -> (Continue) Requesting -> granted: Idle + retry
 *                                                  -> denied:  Denied(canRequest)
 *           -> (Not now) Denied(canRequest)
 *   Denied -> (Allow) Requesting (or app settings) -> on resume, if granted: Idle + retry
 *
 * The flow never retries on its own while access is denied, so it cannot loop.
 * The client stays in LocalNetworkBlocked until the grant arrives, and the retry
 * runs exactly once per grant.
 */
class LocalNetworkPromptModel {
    var phase: LocalNetworkPhase by mutableStateOf(LocalNetworkPhase.Idle)
        private set

    private var source: LocalNetworkSource? = null
    private var retry: (() -> Unit)? = null
    private var explained = false

    /**
     * The client reported LocalNetworkBlocked. [retry] reruns the blocked action
     * once access is granted. [canRequest] is false when the system would refuse
     * to show its dialog.
     */
    fun onBlocked(source: LocalNetworkSource, retry: () -> Unit, canRequest: Boolean) {
        this.source = source
        this.retry = retry
        phase = when (phase) {
            LocalNetworkPhase.Explaining, LocalNetworkPhase.Requesting -> phase
            else -> if (!explained && canRequest) LocalNetworkPhase.Explaining else LocalNetworkPhase.Denied(canRequest)
        }
    }

    /** The user chose Continue on the explanation. The caller launches the system request. */
    fun onExplainContinue() {
        explained = true
        phase = LocalNetworkPhase.Requesting
    }

    /** The user chose Not now (or dismissed the explanation). */
    fun onExplainDismissed(canRequest: Boolean) {
        explained = true
        phase = LocalNetworkPhase.Denied(canRequest)
    }

    /** Allow on the notice launched the system request. */
    fun onRequestLaunched() {
        phase = LocalNetworkPhase.Requesting
    }

    fun onPermissionResult(granted: Boolean, canRequest: Boolean) {
        if (granted) onGranted() else phase = LocalNetworkPhase.Denied(canRequest)
    }

    /** Back from the background (for example the settings page). [granted] is the current state. */
    fun onResumed(granted: Boolean) {
        if (granted && phase != LocalNetworkPhase.Idle) onGranted()
    }

    /** [source] is no longer blocked (it connected, or the user moved on). */
    fun clear(source: LocalNetworkSource) {
        if (this.source != source) return
        this.source = null
        retry = null
        phase = LocalNetworkPhase.Idle
    }

    private fun onGranted() {
        val action = retry
        retry = null
        source = null
        phase = LocalNetworkPhase.Idle
        action?.invoke()
    }
}
