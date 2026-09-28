package com.tether.app.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.TaskCompletionSource
import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-ouu item 3: logout forgets the accepted Firebase project BEFORE the
 * network token delete, so a hung delete (cut by the 5 s logout bound) or a
 * failed one never leaves the old binding in place. Virtual time; the server
 * DELETE is faked, since only the order after it is under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushLogoutForgetFirstTest {

    private val events = CopyOnWriteArrayList<String>()

    private val recordingFirebase = object : FirebaseInitializer {
        override suspend fun ensure(serverConfig: FirebaseClientConfig?, origin: String) = FirebaseSetup.Ready
        override suspend fun forget() {
            events += "forget"
        }
    }

    private fun controller(scope: CoroutineScope, tokenProvider: FirebaseTokenProvider) = PushController(
        app = ApplicationProvider.getApplicationContext<Application>(),
        settings = InMemorySettings(),
        prefs = object : PushPrefs {
            override val pushEnabled = MutableStateFlow(true)
            override val pushScope = MutableStateFlow(PushScope.All)
            override val attachedSessions = MutableStateFlow(emptyList<String>())
            override val pinnedSessions = MutableStateFlow(emptyList<String>())
        },
        httpClient = OkHttpClient(),
        scope = scope,
        tokenProvider = tokenProvider,
        firebase = recordingFirebase,
        registrarFactory = {
            object : PushRegistrar(InMemorySettings(), OkHttpClient(), FirebaseTokenProvider { null }) {
                override suspend fun unregister(baseUrl: String, credential: Credential): PushRegistrarResult {
                    events += "server-delete"
                    return PushRegistrarResult.Success
                }
            }
        },
    )

    /** What RealTetherClient.logout does with the hook: bound it at 5 s. */
    private suspend fun logoutHook(controller: PushController): Boolean {
        var finished = false
        withTimeoutOrNull(LOGOUT_BOUND_MS) {
            controller.unregisterAfterLogout("https://tether.invalid", Credential.DeviceToken("fake-device-token"))
            finished = true
        }
        return finished
    }

    @Test
    fun aHungTokenDeleteStillLeavesTheProjectForgotten() = runTest {
        val hungDelete = object : FirebaseTokenProvider {
            override suspend fun token(): String? = null
            override suspend fun delete() {
                events += "token-delete"
                awaitCancellation()
            }
        }
        assertFalse(logoutHook(controller(backgroundScope, hungDelete)))
        assertEquals(listOf("server-delete", "forget", "token-delete"), events)
        assertEquals(LOGOUT_BOUND_MS, currentTime)
    }

    @Test
    fun aFailedTokenDeleteStillLeavesTheProjectForgotten() = runTest {
        val failingDelete = object : FirebaseTokenProvider {
            override suspend fun token(): String? = null
            override suspend fun delete() {
                events += "token-delete"
                throw IllegalStateException("fake Firebase failure")
            }
        }
        assertTrue("a failed delete is best-effort", logoutHook(controller(backgroundScope, failingDelete)))
        assertEquals(listOf("server-delete", "forget", "token-delete"), events)
    }

    @Test
    fun theRealProviderHungPastTheLogoutBoundEndsTheHookAfterForgetting() = runTest {
        // The production provider (10 s own bound) under the 5 s logout bound:
        // the bound wins and propagates, and the project was already forgotten.
        val real = PlayServicesTokenProvider(
            getToken = { TaskCompletionSource<String>().task },
            deleteToken = { events += "token-delete"; TaskCompletionSource<Void>().task },
            io = StandardTestDispatcher(testScheduler),
            timeoutMs = 10_000,
        )
        assertFalse(logoutHook(controller(backgroundScope, real)))
        assertEquals(listOf("server-delete", "forget", "token-delete"), events)
        assertEquals(LOGOUT_BOUND_MS, currentTime)
    }

    private companion object {
        const val LOGOUT_BOUND_MS = 5_000L
    }
}
