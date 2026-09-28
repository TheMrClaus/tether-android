package com.tether.app.push

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round 3 item 2: the real token calls never start on the main thread, and
 * their wait is cancellable. Before, logout called `Tasks.await` on Main, which
 * throws there; the catch swallowed it, so the token was never deleted.
 * Robolectric's test thread is the main looper thread, as in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayServicesTokenProviderTest {

    private val onMain = CopyOnWriteArrayList<Boolean>()

    private fun <T> recording(task: () -> Task<T>): () -> Task<T> = {
        onMain += Looper.myLooper() == Looper.getMainLooper()
        task()
    }

    private fun provider(
        getToken: () -> Task<String> = { Tasks.forResult("fake-fcm-token") },
        deleteToken: () -> Task<Void> = { Tasks.forResult(null) },
        timeoutMs: Long = 10_000,
    ) = PlayServicesTokenProvider(recording(getToken), recording(deleteToken), timeoutMs = timeoutMs)

    @Test
    fun deleteCalledFromMainRunsOffMain() = runBlocking {
        assertTrue("precondition: the test runs on the main thread", Looper.myLooper() == Looper.getMainLooper())
        provider().delete()
        assertEquals(listOf(false), onMain)
    }

    @Test
    fun tokenCalledFromMainRunsOffMainAndReturnsTheToken() = runBlocking {
        assertEquals("fake-fcm-token", provider().token())
        assertEquals(listOf(false), onMain)
    }

    @Test
    fun aFailedTaskIsNullNotAnException() = runBlocking {
        val failing = provider(
            getToken = { Tasks.forException(IllegalStateException("no Play services")) },
            deleteToken = { Tasks.forException(IllegalStateException("no Play services")) },
        )
        assertNull(failing.token())
        failing.delete() // returns normally
    }

    @Test
    fun aHungTaskIsCutShortByTheTimeout() {
        val hungDelete = TaskCompletionSource<Void>()
        val hungToken = TaskCompletionSource<String>()
        val hung = provider(deleteToken = { hungDelete.task }, getToken = { hungToken.task }, timeoutMs = 100)
        val token = AtomicReference<String?>("not returned")
        val failure = AtomicReference<Throwable?>()
        // The calls run on a thread of their own, joined with a timeout, so a
        // lost internal timeout fails this test fast (M2b). A coroutine timeout
        // cannot: it cannot interrupt a thread that blocks inside the call.
        val caller = thread(isDaemon = true, name = "hung-task-caller") {
            try {
                runBlocking {
                    hung.delete()
                    token.set(hung.token())
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        try {
            caller.join(5_000)
            assertFalse("the provider's own timeout did not cut the hung calls short", caller.isAlive)
            assertNull(failure.get())
            assertNull(token.get())
        } finally {
            // Release a caller that is still blocked, so nothing outlives the test.
            hungDelete.trySetResult(null)
            hungToken.trySetResult("late-fake-token")
        }
    }

    @Test
    fun theProvidersOwnTimeoutIsNullAtItsBound() = runTest {
        val hung = PlayServicesTokenProvider(
            getToken = { TaskCompletionSource<String>().task },
            deleteToken = { TaskCompletionSource<Void>().task },
            io = StandardTestDispatcher(testScheduler),
            timeoutMs = 10_000,
        )
        assertNull(hung.token())
        assertEquals(10_000L, currentTime)
        hung.delete()
        assertEquals(20_000L, currentTime)
    }

    @Test
    fun aCallersShorterTimeoutIsHonouredNotSwallowed() = runTest {
        // The logout path bounds the hook at 5 s (RealTetherClient); the
        // provider's own bound is 10 s. The caller's timeout must end the
        // caller's block, not be mistaken for the provider's own and answered
        // with null (which let the block carry on).
        val hung = PlayServicesTokenProvider(
            getToken = { TaskCompletionSource<String>().task },
            deleteToken = { TaskCompletionSource<Void>().task },
            io = StandardTestDispatcher(testScheduler),
            timeoutMs = 10_000,
        )
        var carriedOnAfterDelete = false
        assertNull(withTimeoutOrNull(5_000) { hung.delete(); carriedOnAfterDelete = true })
        assertFalse(carriedOnAfterDelete)
        assertEquals(5_000L, currentTime)

        var carriedOnAfterToken = false
        assertNull(withTimeoutOrNull(5_000) { hung.token(); carriedOnAfterToken = true })
        assertFalse(carriedOnAfterToken)
        assertEquals(10_000L, currentTime)
    }

    @Test
    fun aHungTaskIsCancellable() = runBlocking {
        val hung = provider(deleteToken = { TaskCompletionSource<Void>().task })
        val job = CoroutineScope(Dispatchers.Default).launch { hung.delete() }
        while (onMain.isEmpty()) Thread.sleep(5)
        val started = System.nanoTime()
        job.cancelAndJoin()
        assertTrue(System.nanoTime() - started < 3_000_000_000)
    }

    @Test
    fun theLogoutPathFromTheUiDeletesTheTokenOffMain() = runBlocking {
        // TetherViewModel.logout runs on Main; RealTetherClient calls the onLogout
        // hook on the same thread. Drive the real hook from the main thread.
        val tether = MockWebServer().apply { enqueue(MockResponse().setBody("""{"ok":true}""")); start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = PushController(
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
                tokenProvider = provider(),
                firebase = FirebaseInitializer.AlreadyInitialised,
            )
            controller.unregisterAfterLogout(tether.url("/").toString(), Credential.DeviceToken("fake-device-token"))
            assertEquals("DELETE", tether.takeRequest().method)
            assertEquals("deleteToken ran exactly once, off main", listOf(false), onMain)
        } finally {
            scope.cancel()
            tether.shutdown()
        }
    }
}
