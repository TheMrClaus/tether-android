package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.ui.MainShell
import com.tether.app.ui.overview.OverviewTags
import com.tether.app.ui.overview.StatusTab
import com.tether.app.ui.prefs.OverviewFilters
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.52 (overview.tsx 90fbb9f :24, :43-62, :96-102): the Overview's filter choice is kept in the
 * per-origin `tether:overviewFilters`, read when the Overview opens (before it asks for the feed) and
 * written on every change, so each server has its own and a relaunch or a sign-out keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class MainShellOverviewFiltersTest : NavigationBase(1200, 1000) {
    private val a = "https://a.example:443"
    private val b = "https://b.example:443"
    private var generation by mutableIntStateOf(0)
    private var signedIn by mutableStateOf(true)

    private fun statuses() = client.subscriptions.map { it.filters?.statuses }

    private fun show() = rule.setContent {
        key(generation) {
            if (signedIn) TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
    }

    private fun stored(origin: String?) = runBlocking { prefs.overviewFilters(origin) }

    private fun awaitSubscribed(statuses: List<String>?) =
        rule.waitUntil(5_000) { client.subscriptions.isNotEmpty() && statuses().last() == statuses }

    @Test fun theOverviewFiltersAreEachServersOwnAndSurviveARelaunchAndASignOut() {
        client.server.value = "https://A.example/"
        show()
        onOverview()
        awaitSubscribed(null)
        assertEquals("a fresh origin asks for the default choice (Active)", listOf<List<String>?>(null), statuses())

        rule.onNodeWithTag(OverviewTags.tab(StatusTab.Waiting)).performClick()
        rule.waitUntil(5_000) { stored(a).status == "waiting" }
        assertEquals(OverviewFilters(status = "waiting"), stored(a))
        awaitSubscribed(listOf("waiting"))

        // A relaunch (a fresh composition): the stored choice is read before the feed is asked for.
        client.subscriptions.clear()
        rule.runOnIdle { generation++ }
        onOverview()
        awaitSubscribed(listOf("waiting"))
        assertTrue("never the default first: ${statuses()}", statuses().all { it == listOf("waiting") })

        // Another server has its own (none yet: the default), and does not touch A's.
        rule.runOnIdle { client.server.value = "https://b.example" }
        awaitSubscribed(null)
        rule.onNodeWithTag(OverviewTags.tab(StatusTab.Ready)).performClick()
        rule.waitUntil(5_000) { stored(b).status == "ready" }
        assertEquals(OverviewFilters(status = "waiting"), stored(a))

        // Back on A: A's choice again, read before the feed is asked for (never the default first).
        rule.runOnIdle {
            client.subscriptions.clear()
            client.server.value = "https://a.example"
        }
        awaitSubscribed(listOf("waiting"))
        assertTrue("never the default first: ${statuses()}", statuses().all { it == listOf("waiting") })

        // A sign-out keeps every server's choice.
        vm.logout()
        rule.waitUntil(5_000) { client.logoutCalls.get() == 1 }
        rule.runOnIdle { signedIn = false }
        rule.waitForIdle()
        assertEquals(OverviewFilters(status = "waiting"), stored(a))
        assertEquals(OverviewFilters(status = "ready"), stored(b))
        assertEquals("no server configured has its own", OverviewFilters(), stored(null))
        client.subscriptions.clear()
        rule.runOnIdle { signedIn = true }
        onOverview()
        awaitSubscribed(listOf("waiting"))
        assertTrue(statuses().toString(), statuses().all { it == listOf("waiting") })
    }
}
