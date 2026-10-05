package com.tether.app.ui.settings

import com.tether.app.push.PushRegistrationStatus

/**
 * T12.2: the web's push phases (tether 90fbb9f hooks/use-push-notifications.ts:5-14). `insecure`
 * (a browser's https rule) has no counterpart on a device.
 */
internal enum class PushPhase { Loading, Unsupported, Unconfigured, Denied, Disabled, Stale, Enabled, Error }

/** The web's `PushNotificationState` (use-push-notifications.ts:16-21). */
internal data class PushRowState(
    val phase: PushPhase,
    val message: String,
    val busy: Boolean,
    val subscribed: Boolean,
)

/**
 * Android's POST_NOTIFICATIONS grant, folded to what the web's `Notification.permission` says:
 * [Granted]; [Askable] (never asked, or denied once: the system dialog shows again, the web's
 * "default"); [Blocked] (denied for good, or the app's notifications turned off in system settings:
 * the web's "denied").
 */
internal enum class PushPermission { Granted, Askable, Blocked }

/** The row's one button (settings-dialog.tsx:2073-2096). */
internal enum class PushAction(val label: String) {
    ReEnable("Re-enable"),
    Disable("Disable"),
    Enable("Enable"),
    ;

    /** The busy label (`Enabling…` / `Disabling…`). */
    val busyLabel: String get() = if (this == Disable) "Disabling…" else "Enabling…"
}

internal object PushCopy {
    const val TITLE = "Push notifications"
    const val TIP = "Push notifications for approvals, agent questions, and completed turns. Notifications are private and generic — they never reveal session content."

    // use-push-notifications.ts, each line's device counterpart.
    const val LOADING = "Checking this device and server…" // :31
    const val NO_DEVICE = "Push needs a paired device. This sign-in is not one, so the server cannot register it." // :106, unsupported
    const val UNCONFIGURED = "Push is not configured on this Tether server." // :145
    const val DENIED = "Notifications are blocked in this device’s settings." // :152
    const val NOT_GRANTED = "Notification permission was not granted." // :220
    const val DISABLED = "Off on this device. Enable to receive approval and turn-completion alerts." // :161
    const val STALE = "The server’s push key changed. Re-enable notifications on this device." // :173
    const val ENABLED = "On for this device, including when Tether is closed." // :182
}

internal object PushNotificationsModel {
    /**
     * The web's `refresh()` order (use-push-notifications.ts:121-196) over the app's registration:
     * nothing to register with, then the server's configuration, the permission, off, stale,
     * and the registration's outcome. [subscribed] is the stored `pushEnabled` (the device's
     * standing registration, the web's browser subscription). [permissionRefused]: the last
     * Enable's system dialog was answered without a grant (the web's message for a "default"
     * answer, :220).
     */
    fun derive(
        pushEnabled: Boolean,
        permission: PushPermission,
        status: PushRegistrationStatus,
        permissionRefused: Boolean = false,
    ): PushRowState = when {
        status == PushRegistrationStatus.NoDevice ->
            PushRowState(PushPhase.Unsupported, PushCopy.NO_DEVICE, busy = false, subscribed = false)
        pushEnabled && status == PushRegistrationStatus.ServerUnconfigured ->
            PushRowState(PushPhase.Unconfigured, PushCopy.UNCONFIGURED, busy = false, subscribed = true)
        permission == PushPermission.Blocked ->
            PushRowState(PushPhase.Denied, PushCopy.DENIED, busy = false, subscribed = pushEnabled)
        !pushEnabled || permission == PushPermission.Askable ->
            PushRowState(
                PushPhase.Disabled,
                if (permissionRefused) PushCopy.NOT_GRANTED else PushCopy.DISABLED,
                busy = false,
                subscribed = false,
            )
        else -> when (status) {
            PushRegistrationStatus.ProjectChanged -> PushRowState(PushPhase.Stale, PushCopy.STALE, busy = false, subscribed = true)
            PushRegistrationStatus.Registered -> PushRowState(PushPhase.Enabled, PushCopy.ENABLED, busy = false, subscribed = true)
            is PushRegistrationStatus.Failed -> PushRowState(PushPhase.Error, status.message, busy = false, subscribed = true)
            // Idle / Registering (NoDevice and ServerUnconfigured are handled above).
            else -> PushRowState(PushPhase.Loading, PushCopy.LOADING, busy = true, subscribed = false)
        }
    }

    /** settings-dialog.tsx:2073-2096 with `canEnablePush` (:1966-1969). */
    fun action(state: PushRowState): PushAction? = when {
        state.phase == PushPhase.Stale -> PushAction.ReEnable
        state.subscribed -> PushAction.Disable
        state.phase == PushPhase.Disabled || state.phase == PushPhase.Error -> PushAction.Enable
        else -> null
    }

    /** The grant, from what the platform reports; see [PushPermission]. */
    fun permission(granted: Boolean, notificationsOn: Boolean, asked: Boolean, shouldShowRationale: Boolean): PushPermission = when {
        granted -> if (notificationsOn) PushPermission.Granted else PushPermission.Blocked
        asked && !shouldShowRationale -> PushPermission.Blocked
        else -> PushPermission.Askable
    }
}
