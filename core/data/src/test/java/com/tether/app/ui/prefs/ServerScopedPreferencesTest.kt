package com.tether.app.ui.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.47: the web keeps `collapsedWorkspaces` and `lastSeenSessions` in its per-origin
 * localStorage, so each server has its own; the device-wide values the app kept before move to
 * the current server once.
 */
class ServerScopedPreferencesTest {
    private val a = "https://a.example:443"
    private val b = "https://b.example:443"

    @Test
    fun eachServerHasItsOwnFoldedBlocksAndSeenStamps() {
        val prefs = TetherPreferences()
            .updateForServer(a) { it.copy(collapsedWorkspaces = listOf("/srv/a"), lastSeenSessions = mapOf("h1" to 10L)) }
            .updateForServer(b) { it.copy(collapsedWorkspaces = listOf("/srv/b"), lastSeenSessions = it.lastSeenSessions + ("h2" to 20L)) }
        assertEquals(listOf("/srv/a"), prefs.forServer(a).collapsedWorkspaces)
        assertEquals(mapOf("h1" to 10L), prefs.forServer(a).lastSeenSessions)
        assertEquals(listOf("/srv/b"), prefs.forServer(b).collapsedWorkspaces)
        assertEquals("server A's stamp is not B's", mapOf("h2" to 20L), prefs.forServer(b).lastSeenSessions)
        assertTrue("a server never seen has none", prefs.forServer("https://c.example:443").collapsedWorkspaces.isEmpty())
        assertTrue(prefs.forServer("https://c.example:443").lastSeenSessions.isEmpty())
        assertTrue("no server configured is a key of its own", prefs.forServer(null).lastSeenSessions.isEmpty())
        // The stored model keeps no device-wide copy.
        assertTrue(prefs.collapsedWorkspaces.isEmpty())
        assertTrue(prefs.lastSeenSessions.isEmpty())
        // Device-wide fields written through a server's view stay device-wide.
        val sorted = prefs.updateForServer(a) { it.copy(sidebarSort = SidebarSort.LastActive) }
        assertEquals(SidebarSort.LastActive, sorted.forServer(b).sidebarSort)
        assertEquals(listOf("/srv/b"), sorted.forServer(b).collapsedWorkspaces)
    }

    @Test
    fun theRememberedChatIsTheServersOwnInItsView() {
        val chat = LastOpenedSession("/srv/a", "sa", "ha")
        val prefs = TetherPreferences().rememberOpenedFor(a, chat)
        assertEquals(chat, prefs.forServer(a).lastOpenedSession)
        assertNull(prefs.forServer(b).lastOpenedSession)
        // A transform that leaves it alone writes nothing for it; one that changes it writes the server's.
        assertEquals(prefs.lastOpenedByOrigin, prefs.updateForServer(b) { it.copy(showThinking = true) }.lastOpenedByOrigin)
        val moved = LastOpenedSession("/srv/b", "sb", null)
        val next = prefs.updateForServer(b) { it.copy(lastOpenedSession = moved) }
        assertEquals(moved, next.lastOpenedFor(b))
        assertEquals(chat, next.lastOpenedFor(a))
        assertNull(next.lastOpenedSession)
    }

    @Test
    fun theDeviceWideValuesMoveToTheCurrentServerOnce() {
        val chat = LastOpenedSession("/srv/a", "sa", "ha")
        val legacy = TetherPreferences(collapsedWorkspaces = listOf("/srv/x"), lastSeenSessions = mapOf("h" to 5L), lastOpenedSession = chat)
        assertTrue(legacy.hasDeviceWideServerRecords)
        assertEquals("read through until migrated", listOf("/srv/x"), legacy.forServer(a).collapsedWorkspaces)
        val migrated = legacy.migrateToServer(a)
        assertFalse(migrated.hasDeviceWideServerRecords)
        assertTrue(migrated.collapsedWorkspaces.isEmpty())
        assertTrue(migrated.lastSeenSessions.isEmpty())
        assertNull(migrated.lastOpenedSession)
        assertEquals(listOf("/srv/x"), migrated.forServer(a).collapsedWorkspaces)
        assertEquals(mapOf("h" to 5L), migrated.forServer(a).lastSeenSessions)
        assertEquals(chat, migrated.forServer(a).lastOpenedSession)
        assertTrue("another server does not inherit it", migrated.forServer(b).collapsedWorkspaces.isEmpty())
        assertTrue(migrated.forServer(b).lastSeenSessions.isEmpty())
        assertSame("once", migrated, migrated.migrateToServer(b))
    }

    @Test
    fun aServersOwnRecordWinsOverTheDeviceWideValue() {
        val own = TetherPreferences().updateForServer(a) { it.copy(collapsedWorkspaces = listOf("/own"), lastSeenSessions = mapOf("own" to 1L)) }
        val both = own.copy(collapsedWorkspaces = listOf("/legacy"), lastSeenSessions = mapOf("legacy" to 2L))
        val migrated = both.migrateToServer(a)
        assertEquals(listOf("/own"), migrated.forServer(a).collapsedWorkspaces)
        assertEquals(mapOf("own" to 1L), migrated.forServer(a).lastSeenSessions)
        assertFalse(migrated.hasDeviceWideServerRecords)
    }

    @Test
    fun aWriteThroughAServersViewMigratesFirst() {
        val legacy = TetherPreferences(collapsedWorkspaces = listOf("/srv/x"), lastSeenSessions = mapOf("h" to 5L))
        val next = legacy.updateForServer(a) { it.copy(lastSeenSessions = it.lastSeenSessions + ("h2" to 6L)) }
        assertEquals(mapOf("h" to 5L, "h2" to 6L), next.forServer(a).lastSeenSessions)
        assertEquals(listOf("/srv/x"), next.forServer(a).collapsedWorkspaces)
        assertTrue(next.forServer(b).lastSeenSessions.isEmpty())
        assertFalse(next.hasDeviceWideServerRecords)
        // With no server configured nothing is migrated: the value waits for a real server.
        val unconfigured = legacy.updateForServer(null) { it.copy(showThinking = true) }
        assertTrue(unconfigured.hasDeviceWideServerRecords)
    }

    /** Any character in a folder, an id or an origin round-trips (JSON, as the web's localStorage). */
    @Test
    fun anyCharacterRoundTrips() {
        val odd = "/srv/line\nbreak\ttab\r\u0000\"quoted\" \\ back/ünï😀"
        val collapsed = mapOf(a to listOf(odd, "/plain"), "$b\n\t\"" to listOf("/x"), "" to emptyList())
        val seen = mapOf(a to mapOf("h\n1" to 1_700_000_000_000L, "h\t\"2\"" to 0L, odd to Long.MAX_VALUE), b to emptyMap())
        val parsed = TetherPreferences.parse(
            mapOf(
                PreferenceKeys.COLLAPSED_BY_ORIGIN_JSON to TetherPreferences.joinCollapsed(collapsed),
                PreferenceKeys.LAST_SEEN_BY_ORIGIN_JSON to TetherPreferences.joinSeenByOrigin(seen),
            ),
        )
        assertEquals(collapsed, parsed.collapsedByOrigin)
        assertEquals(seen, parsed.lastSeenByOrigin)
    }

    @Test
    fun corruptRecordsReadAsDefaults() {
        for (garbage in listOf("", "not json", "[1,2]", "\"str\"", "{", "null", "{\"a\":")) {
            val parsed = TetherPreferences.parse(
                mapOf(PreferenceKeys.COLLAPSED_BY_ORIGIN_JSON to garbage, PreferenceKeys.LAST_SEEN_BY_ORIGIN_JSON to garbage),
            )
            assertEquals(garbage, emptyMap<String, List<String>>(), parsed.collapsedByOrigin)
            assertEquals(garbage, emptyMap<String, Map<String, Long>>(), parsed.lastSeenByOrigin)
        }
        val parsed = TetherPreferences.parse(
            mapOf(
                PreferenceKeys.COLLAPSED_BY_ORIGIN_JSON to """{"$a":["/ok",1,null,"",{"x":1}],"$b":"not-a-list","c":{}}""",
                PreferenceKeys.LAST_SEEN_BY_ORIGIN_JSON to """{"$a":{"ok":5,"str":"6","neg":-1,"bool":true,"nul":null,"frac":7.9,"":3},"$b":[1],"c":"x"}""",
            ),
        )
        assertEquals(mapOf(a to listOf("/ok")), parsed.collapsedByOrigin)
        assertEquals(mapOf(a to mapOf("ok" to 5L, "frac" to 7L)), parsed.lastSeenByOrigin)
        // Wrongly typed stored values never crash either.
        val wrong = TetherPreferences.parse(mapOf(PreferenceKeys.COLLAPSED_BY_ORIGIN_JSON to 3, PreferenceKeys.LAST_SEEN_BY_ORIGIN_JSON to true))
        assertTrue(wrong.collapsedByOrigin.isEmpty())
        assertTrue(wrong.lastSeenByOrigin.isEmpty())
    }
}
