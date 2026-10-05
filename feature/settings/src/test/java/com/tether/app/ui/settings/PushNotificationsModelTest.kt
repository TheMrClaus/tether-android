package com.tether.app.ui.settings

import com.tether.app.push.PushRegistrationStatus
import com.tether.app.push.PushRegistrationStatus.Failed
import com.tether.app.push.PushRegistrationStatus.Idle
import com.tether.app.push.PushRegistrationStatus.NoDevice
import com.tether.app.push.PushRegistrationStatus.ProjectChanged
import com.tether.app.push.PushRegistrationStatus.Registered
import com.tether.app.push.PushRegistrationStatus.Registering
import com.tether.app.push.PushRegistrationStatus.ServerUnconfigured
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T12.2: the web's push phases and button (tether 90fbb9f hooks/use-push-notifications.ts:121-196,
 * settings-dialog.tsx:1966-1969 and 2073-2096) over the app's registration and permission.
 */
class PushNotificationsModelTest {
    private fun derive(
        enabled: Boolean = true,
        permission: PushPermission = PushPermission.Granted,
        status: PushRegistrationStatus = Registered,
        refused: Boolean = false,
    ) = PushNotificationsModel.derive(enabled, permission, status, refused)

    @Test fun enabled() {
        val s = derive()
        assertEquals(PushRowState(PushPhase.Enabled, "On for this device, including when Tether is closed.", busy = false, subscribed = true), s)
        assertEquals(PushAction.Disable, PushNotificationsModel.action(s))
    }

    @Test fun disabledWhenTurnedOff() {
        val s = derive(enabled = false)
        assertEquals(PushRowState(PushPhase.Disabled, "Off on this device. Enable to receive approval and turn-completion alerts.", busy = false, subscribed = false), s)
        assertEquals(PushAction.Enable, PushNotificationsModel.action(s))
    }

    @Test fun disabledWhileThePermissionCanStillBeAsked() {
        val s = derive(permission = PushPermission.Askable)
        assertEquals(PushPhase.Disabled, s.phase)
        assertEquals(PushAction.Enable, PushNotificationsModel.action(s))
        assertEquals("Notification permission was not granted.", derive(permission = PushPermission.Askable, refused = true).message)
    }

    @Test fun staleWhenTheServerNamesAnotherProject() {
        val s = derive(status = ProjectChanged)
        assertEquals(PushRowState(PushPhase.Stale, "The server’s push key changed. Re-enable notifications on this device.", busy = false, subscribed = true), s)
        assertEquals(PushAction.ReEnable, PushNotificationsModel.action(s))
    }

    @Test fun errorCarriesTheFailureAndKeepsDisable() {
        val s = derive(status = Failed("Push register returned HTTP 500."))
        assertEquals(PushRowState(PushPhase.Error, "Push register returned HTTP 500.", busy = false, subscribed = true), s)
        assertEquals(PushAction.Disable, PushNotificationsModel.action(s))
        // The web's Enable on an error without a subscription (canEnablePush).
        assertEquals(PushAction.Enable, PushNotificationsModel.action(s.copy(subscribed = false)))
    }

    @Test fun deniedWhenBlocked() {
        val s = derive(permission = PushPermission.Blocked)
        assertEquals(PushRowState(PushPhase.Denied, "Notifications are blocked in this device’s settings.", busy = false, subscribed = true), s)
        assertEquals(PushAction.Disable, PushNotificationsModel.action(s))
        val off = derive(enabled = false, permission = PushPermission.Blocked)
        assertEquals(PushPhase.Denied, off.phase)
        assertNull(PushNotificationsModel.action(off))
    }

    @Test fun unconfiguredServer() {
        val s = derive(status = ServerUnconfigured)
        assertEquals(PushRowState(PushPhase.Unconfigured, "Push is not configured on this Tether server.", busy = false, subscribed = true), s)
        assertEquals(PushAction.Disable, PushNotificationsModel.action(s))
        // Unconfigured outranks the permission, as in the web's order.
        assertEquals(PushPhase.Unconfigured, derive(status = ServerUnconfigured, permission = PushPermission.Blocked).phase)
    }

    @Test fun unsupportedWithoutAPairedDevice() {
        val s = derive(status = NoDevice)
        assertEquals(PushPhase.Unsupported, s.phase)
        assertEquals(false, s.subscribed)
        assertNull(PushNotificationsModel.action(s))
        assertEquals(PushPhase.Unsupported, derive(enabled = false, status = NoDevice).phase)
    }

    @Test fun loadingWhileRegistering() {
        for (status in listOf(Idle, Registering)) {
            val s = derive(status = status)
            assertEquals(PushRowState(PushPhase.Loading, "Checking this device and server…", busy = true, subscribed = false), s)
            assertNull(PushNotificationsModel.action(s))
        }
    }

    @Test fun busyLabels() {
        assertEquals("Enabling…", PushAction.Enable.busyLabel)
        assertEquals("Enabling…", PushAction.ReEnable.busyLabel)
        assertEquals("Disabling…", PushAction.Disable.busyLabel)
    }

    @Test fun permissionFold() {
        val p = PushNotificationsModel::permission
        assertEquals(PushPermission.Granted, p(true, true, true, false))
        assertEquals(PushPermission.Blocked, p(true, false, true, false)) // turned off in system settings
        assertEquals(PushPermission.Askable, p(false, false, false, false)) // never asked
        assertEquals(PushPermission.Askable, p(false, false, true, true)) // denied once
        assertEquals(PushPermission.Blocked, p(false, false, true, false)) // denied for good
    }
}
