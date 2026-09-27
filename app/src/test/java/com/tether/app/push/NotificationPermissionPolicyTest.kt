package com.tether.app.push

import com.tether.app.push.NotificationPermissionStatus.Denied
import com.tether.app.push.NotificationPermissionStatus.Granted
import com.tether.app.push.NotificationPermissionStatus.NotAsked
import com.tether.app.push.NotificationPermissionStatus.Rationale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android 13+ POST_NOTIFICATIONS flow, every branch. */
class NotificationPermissionPolicyTest {

    @Test
    fun statusFromTheThreeSignals() {
        assertEquals(Granted, NotificationPermissionPolicy.status(granted = true, asked = false, shouldShowRationale = false))
        assertEquals(Granted, NotificationPermissionPolicy.status(granted = true, asked = true, shouldShowRationale = true))
        assertEquals(NotAsked, NotificationPermissionPolicy.status(granted = false, asked = false, shouldShowRationale = false))
        assertEquals(Rationale, NotificationPermissionPolicy.status(granted = false, asked = true, shouldShowRationale = true))
        // "Don't ask again" and "never asked" look alike to the platform; the flag splits them.
        assertEquals(Denied, NotificationPermissionPolicy.status(granted = false, asked = true, shouldShowRationale = false))
    }

    @Test
    fun theAutomaticRequestHappensOnceAfterSignInWithPushOn() {
        assertTrue(NotificationPermissionPolicy.shouldAutoRequest(signedIn = true, pushEnabled = true, granted = false, asked = false))
    }

    @Test
    fun theAutomaticRequestNeverNags() {
        // Already asked (either answer), not signed in, push off, or granted.
        assertFalse(NotificationPermissionPolicy.shouldAutoRequest(signedIn = true, pushEnabled = true, granted = false, asked = true))
        assertFalse(NotificationPermissionPolicy.shouldAutoRequest(signedIn = false, pushEnabled = true, granted = false, asked = false))
        assertFalse(NotificationPermissionPolicy.shouldAutoRequest(signedIn = true, pushEnabled = false, granted = false, asked = false))
        assertFalse(NotificationPermissionPolicy.shouldAutoRequest(signedIn = true, pushEnabled = true, granted = true, asked = false))
    }

    @Test
    fun nothingIsRequestedBeforeTheStoredFlagLoads() {
        assertFalse(NotificationPermissionPolicy.shouldAutoRequest(signedIn = true, pushEnabled = true, granted = false, asked = null))
    }

    @Test
    fun aUserTapRequestsWhileThePlatformStillAsksAndOpensSettingsAfter() {
        assertEquals(NotificationPermissionAction.None, NotificationPermissionPolicy.actionFor(Granted))
        assertEquals(NotificationPermissionAction.Request, NotificationPermissionPolicy.actionFor(NotAsked))
        assertEquals(NotificationPermissionAction.Request, NotificationPermissionPolicy.actionFor(Rationale))
        assertEquals(NotificationPermissionAction.OpenSettings, NotificationPermissionPolicy.actionFor(Denied))
    }
}
