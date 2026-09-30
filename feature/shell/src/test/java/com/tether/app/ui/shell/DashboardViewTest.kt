package com.tether.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T15.4: lib/dashboard-view.mjs's precedence (tests/dashboard-view.test.mjs) and the Back history. */
class DashboardViewTest {

    @Test fun precedenceIsLinkThenStoredThenExistingThenFresh() {
        assertEquals(DashboardView.Sessions to ViewSource.SessionLink, DashboardViews.resolve(true, "overview", false))
        assertEquals(DashboardView.Overview to ViewSource.Stored, DashboardViews.resolve(false, "overview", true))
        assertEquals(DashboardView.Scheduled to ViewSource.Stored, DashboardViews.resolve(false, "scheduled", false))
        assertEquals(DashboardView.Sessions to ViewSource.ExistingInstall, DashboardViews.resolve(false, null, true))
        assertEquals(DashboardView.Overview to ViewSource.FreshInstall, DashboardViews.resolve(false, null, false))
    }

    @Test fun anUnknownStoredViewIsIgnoredLikeNormalizeView() {
        // `/usage` is never a remembered view; junk falls through to the next rule.
        assertEquals(DashboardView.Overview to ViewSource.FreshInstall, DashboardViews.resolve(false, "usage", false))
        assertEquals(DashboardView.Sessions to ViewSource.ExistingInstall, DashboardViews.resolve(false, "Overview", true))
        assertNull(DashboardView.of(""))
    }

    @Test fun navigatingPushesAndBackPops() {
        val boot = ViewHistory(null).navigate(DashboardView.Overview)
        assertEquals(ViewHistory(DashboardView.Overview), boot)
        assertFalse("the boot view has nothing behind it", boot.canGoBack)
        val sessions = boot.navigate(DashboardView.Sessions)
        assertEquals(listOf(DashboardView.Overview), sessions.behind)
        assertEquals("the same view again is not a step", sessions, sessions.navigate(DashboardView.Sessions))
        val back = sessions.navigate(DashboardView.Overview).back()
        assertEquals(sessions, back)
        assertEquals(boot, back.back())
        assertEquals("nothing behind: Back is the system's", boot, boot.back())
    }

    @Test fun theHistoryIsBoundedAndSurvivesItsEncoding() {
        var h = ViewHistory(DashboardView.Overview)
        repeat(100) { h = h.navigate(if (it % 2 == 0) DashboardView.Sessions else DashboardView.Overview) }
        assertEquals(ViewHistory.MAX_BEHIND, h.behind.size)
        assertEquals(h, ViewHistory.decode(h.encode()))
        assertEquals(ViewHistory(null), ViewHistory.decode(ViewHistory(null).encode()))
        assertTrue(ViewHistory.decode("sessions,overview").canGoBack)
    }

    @Test fun usageIsADestinationButNotAView() {
        assertEquals(listOf("Overview", "Sessions", "Scheduled", "Usage"), TopBarDestination.entries.map { it.label })
        assertNull(TopBarDestination.Usage.view)
        for (view in DashboardView.entries) assertEquals(view, TopBarDestination.of(view).view)
    }
}
