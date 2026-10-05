package com.tether.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where this device's FCM registration with the server stands (T12.2). The app's push pipeline
 * (`PushController` / `PushSyncCoordinator`) publishes it; the Settings push row derives the web's
 * `PushNotificationPhase` from it (tether hooks/use-push-notifications.ts:5-14).
 */
sealed interface PushRegistrationStatus {
    /** Nothing attempted for the current server and prefs yet (or push is turned off). */
    data object Idle : PushRegistrationStatus

    /** No paired-device credential (a password sign-in): the server's fcm-register needs a device. */
    data object NoDevice : PushRegistrationStatus

    /** A full registration (fcm-config, token, fcm-register) is in flight. */
    data object Registering : PushRegistrationStatus

    /** The server holds this device's row. */
    data object Registered : PushRegistrationStatus

    /** The server has no FCM credentials (fcm-config `configured: false`, or a 503). */
    data object ServerUnconfigured : PushRegistrationStatus

    /**
     * The server now names another Firebase project than the one this device accepted: the app's
     * counterpart of the web's changed push key. Nothing was registered.
     */
    data object ProjectChanged : PushRegistrationStatus

    /** The registration failed; [message] says why. */
    data class Failed(val message: String) : PushRegistrationStatus
}

/**
 * The push registration as the Settings row sees it: its [status], and the two actions the web's
 * hook has besides enable/disable (which are the stored `pushEnabled` pref here).
 */
interface PushRegistration {
    val status: StateFlow<PushRegistrationStatus>

    /**
     * The web's `refresh()` on the dialog's mount (use-push-notifications.ts:121-196): re-run the
     * full registration, so a row the server lost (restart, restore) is repaired and the status is
     * current. Does nothing while push is off.
     */
    fun refresh()

    /**
     * The web's `enable()` on a stale subscription (use-push-notifications.ts:240-255): drop the
     * old binding and its token, then register afresh with the server's current project.
     */
    fun reEnable()

    companion object {
        /** No pipeline in this process (previews, module tests): idle, and the actions do nothing. */
        val None: PushRegistration = object : PushRegistration {
            override val status: StateFlow<PushRegistrationStatus> = MutableStateFlow(PushRegistrationStatus.Idle).asStateFlow()
            override fun refresh() = Unit
            override fun reEnable() = Unit
        }

        /** The process's pipeline; `PushController.start` installs itself here. */
        @Volatile
        var current: PushRegistration = None
    }
}
