package com.tether.app.client.sync

import com.tether.app.client.Freshness
import com.tether.app.client.LiveCopy
import com.tether.app.client.SessionSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T13.2 r2: the one rule every live control asks ([LiveCopy.isLive]). Live needs the client's live
 * set AND a Live entry; every other freshness value locks, a missing entry locks whenever the
 * client reports freshness, and only a client that reports none keeps the live-set rule alone.
 */
class LiveCopyTest {

    @Test
    fun onlyLiveIsLiveEveryOtherValueLocksEvenInTheLiveSet() {
        for (value in Freshness.entries) {
            val sync = SessionSync(value, 1L)
            assertEquals("freshness $value", value == Freshness.Live, LiveCopy.isLive("s1", setOf("s1"), sync, reportsFreshness = true))
            assertEquals("freshness $value (no report flag)", value == Freshness.Live, LiveCopy.isLive("s1", setOf("s1"), sync, reportsFreshness = false))
        }
    }

    @Test
    fun outsideTheLiveSetNothingIsLive() {
        for (value in Freshness.entries) {
            assertFalse(LiveCopy.isLive("s1", emptySet(), SessionSync(value, 1L), reportsFreshness = true))
            assertFalse(LiveCopy.isLive("s1", setOf("s2"), SessionSync(value, 1L), reportsFreshness = false))
        }
        assertFalse(LiveCopy.isLive(null, setOf("s1"), SessionSync(Freshness.Live, 1L), reportsFreshness = true))
    }

    @Test
    fun aMissingEntryLocksWheneverTheClientReportsFreshness() {
        assertFalse(LiveCopy.isLive("s1", setOf("s1"), null, reportsFreshness = true))
    }

    @Test
    fun aClientThatReportsNoneKeepsTheLiveSetRule() {
        assertTrue(LiveCopy.isLive("s1", setOf("s1"), null, reportsFreshness = false))
        assertFalse(LiveCopy.isLive("s1", emptySet(), null, reportsFreshness = false))
    }
}
