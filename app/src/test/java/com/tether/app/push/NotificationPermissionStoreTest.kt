package com.tether.app.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R8b: the production adapter ([NotificationPermissionAskedStore.of]) really
 * persists the answer in the app's [UiPrefs].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPermissionStoreTest {

    @Test
    fun markAskedIsPersistedInUiPrefs() = runBlocking {
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext<Context>())
        val store = NotificationPermissionAskedStore.of(prefs)
        withTimeout(10_000) {
            // The DataStore is shared across tests in this JVM: start from false,
            // so only markAsked() can make it true.
            prefs.setPushPermissionAsked(false)
            assertFalse(store.asked.first())
            store.markAsked()
            assertTrue(store.asked.first())
        }
    }
}
