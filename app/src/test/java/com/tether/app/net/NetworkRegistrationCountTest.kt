package com.tether.app.net

import android.app.Application
import android.net.ConnectivityManager
import android.os.Handler
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager

/** Counts every registerDefaultNetworkCallback CALL (the stock shadow keeps a set, so a repeat vanishes). */
@Implements(ConnectivityManager::class)
class CountingConnectivityManagerShadow : ShadowConnectivityManager() {
    @Implementation
    override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
        calls += Thread.currentThread().stackTrace.map { it.className }.firstOrNull { it.startsWith("com.tether.app.") && !it.contains("Counting") } ?: "?"
        super.registerDefaultNetworkCallback(callback)
    }

    @Implementation
    override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback, handler: Handler) {
        calls += Thread.currentThread().stackTrace.map { it.className }.firstOrNull { it.startsWith("com.tether.app.") && !it.contains("Counting") } ?: "?"
        super.registerDefaultNetworkCallback(callback, handler)
    }

    companion object {
        /** The class that made each call, in order (a sandbox of its own: only this class's tests add to it). */
        val calls = CopyOnWriteArrayList<String>()
    }
}

/**
 * ta-nl5m (C4): the process registers the default-network callback exactly once (ProcessNetworkWatch, started by the Application),
 * and the screen registers none (a second one replaces the socket twice per change).
 * Counts CALLS, not callback objects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [CountingConnectivityManagerShadow::class])
class NetworkRegistrationCountTest {

    @After
    fun clear() = CountingConnectivityManagerShadow.calls.clear()

    @Test
    fun theApplicationMakesExactlyOneRegistrationCall() {
        ApplicationProvider.getApplicationContext<Application>()
        assertEquals(listOf("com.tether.app.net.ProcessNetworkWatch"), CountingConnectivityManagerShadow.calls.toList())
    }

    /**
     * Composing the real UiRoot here would leave state behind that breaks other suites in the same
     * JVM (the preferences store is a process singleton), so the screen is held to the source: no
     * file of the app but [ProcessNetworkWatch] asks the platform for a network callback.
     */
    @Test
    fun noFileButTheProcessWatchAsksForANetworkCallback() {
        val root = java.io.File("src/main/java")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val askers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { "registerDefaultNetworkCallback" in it || "registerNetworkCallback" in it } }
            .map { it.name }.toList()
        assertEquals(listOf("ProcessNetworkWatch.kt"), askers)
    }
}
