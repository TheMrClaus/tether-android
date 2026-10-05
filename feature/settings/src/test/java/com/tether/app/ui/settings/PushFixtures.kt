package com.tether.app.ui.settings

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tether.app.push.PushRegistration
import com.tether.app.push.PushRegistrationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows

/** T12.2: a push registration of the test's own; it records the row's calls and holds [status]. */
class FakePushRegistration(initial: PushRegistrationStatus = PushRegistrationStatus.Registered) : PushRegistration {
    override val status = MutableStateFlow(initial)
    @Volatile var refreshes = 0
    @Volatile var reEnables = 0
    override fun refresh() { refreshes++ }
    override fun reEnable() { reEnables++ }
}

/** POST_NOTIFICATIONS granted to the test app (Robolectric starts with it denied). */
fun grantNotificationPermission() {
    Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
}
