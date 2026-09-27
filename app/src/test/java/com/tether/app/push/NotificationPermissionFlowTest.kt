package com.tether.app.push

import android.Manifest
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Y2: the composable side of the POST_NOTIFICATIONS flow
 * ([rememberNotificationPermission]). The system dialog is replaced by an
 * [ActivityResultRegistry] that answers at once, and the "asked" flag by an
 * in-memory [NotificationPermissionAskedStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPermissionFlowTest {

    /** In memory: the app's UiPrefs DataStore is a process singleton that Robolectric cannot drive. */
    private class MemoryStore : NotificationPermissionAskedStore {
        val state = MutableStateFlow(false)
        override val asked: Flow<Boolean> = state
        override suspend fun markAsked() {
            state.value = true
        }
    }

    private val store = MemoryStore()

    /** Answers every launch with [granted] and records what was requested. */
    private class AnsweringOwner(private val granted: Boolean) : ActivityResultRegistryOwner {
        val requested = mutableListOf<Any?>()
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                requested += input
                dispatchResult(requestCode, granted)
            }
        }
    }

    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idle() }

    private fun render(owner: AnsweringOwner): () -> NotificationPermissionPrompt {
        var prompt: NotificationPermissionPrompt? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                prompt = rememberNotificationPermission(store)
            }
        }
        idle()
        return { prompt!! }
    }


    @Test
    fun aDeniedRequestIsPersistedAsAsked() {
        val owner = AnsweringOwner(granted = false)
        val prompt = render(owner)

        prompt().onAllow()
        idle()

        assertEquals(listOf<Any?>(Manifest.permission.POST_NOTIFICATIONS), owner.requested)
        assertTrue("the answer must be remembered, or the app asks again", store.state.value)
    }

    @Test
    fun theAutomaticRequestFiresOnceAndNotAfterItWasAnswered() {
        val owner = AnsweringOwner(granted = false)
        val prompt = render(owner)
        prompt().autoRequestIfDue(signedIn = true, pushEnabled = true)
        idle()
        assertTrue(store.state.value)
        prompt().autoRequestIfDue(signedIn = true, pushEnabled = true)
        idle()

        assertEquals(1, owner.requested.size)
    }
}
