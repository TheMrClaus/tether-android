package com.tether.app.net

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.TetherClient
import com.tether.app.ui.fake.FakeTetherClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * ta-nl5m (C4): the default-network callback is the process's, registered once by the Application,
 * and a network change replaces the socket once (the first network a registration reports is not a change).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessNetworkWatchTest {

    private class Calls {
        var idle = 0
        var changed = 0
    }

    private fun counting(calls: Calls): TetherClient = object : TetherClient by FakeTetherClient() {
        override fun reconnectIfIdle() { calls.idle++ }
        override fun onDefaultNetworkChanged() { calls.changed++ }
    }

    private fun network(id: Int): Network = ShadowNetwork.newInstance(id)

    @Test
    fun theApplicationRegistersExactlyOneDefaultNetworkCallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertEquals(1, shadowOf(manager).networkCallbacks.size)
    }

    @Test
    fun aNetworkChangeReplacesTheSocketOnceAndTheFirstNetworkIsNotAChange() {
        val calls = Calls()
        val client = counting(calls)
        val watch = ProcessNetworkWatch { client }
        watch.callback.onAvailable(network(1))
        assertEquals("the first network: only a link check", 0, calls.changed)
        assertEquals(1, calls.idle)
        watch.callback.onAvailable(network(1))
        assertEquals(0, calls.changed)
        watch.callback.onAvailable(network(2))
        assertEquals("another default network: one replace", 1, calls.changed)
        assertEquals("and no second check on top of it", 2, calls.idle)
        watch.callback.onLost(network(2))
        watch.callback.onAvailable(network(2))
        assertEquals("the same one back after it was lost: one replace", 2, calls.changed)
    }

    @Test
    fun aNetworkSeenBeforeTheClientExistsStillCountsAsTheCurrentOne() {
        val calls = Calls()
        var client: TetherClient? = null
        val watch = ProcessNetworkWatch { client }
        watch.callback.onAvailable(network(1))
        client = counting(calls)
        watch.callback.onAvailable(network(1))
        assertEquals("no client was created for it, and it is not a change", 0, calls.changed)
        watch.callback.onAvailable(network(2))
        assertEquals(1, calls.changed)
    }
}
