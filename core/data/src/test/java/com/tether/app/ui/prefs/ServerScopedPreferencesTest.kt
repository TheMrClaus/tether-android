package com.tether.app.ui.prefs

import com.tether.app.ui.theme.ThemeMode
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
        // ta-coik.52: every other preference written through a server's view is that server's too.
        val sorted = prefs.updateForServer(a) { it.copy(sidebarSort = SidebarSort.LastActive) }
        assertEquals(SidebarSort.LastActive, sorted.forServer(a).sidebarSort)
        assertEquals(SidebarSort.Created, sorted.forServer(b).sidebarSort)
        assertEquals(SidebarSort.Created, sorted.sidebarSort)
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

    // ── ta-coik.52: every other preference ──────────────────────────────────

    /** One non-default value in every [ServerPreferences] field. */
    private val edited = ServerPreferences(
        themeMode = ThemeMode.Dark,
        loginVariant = LoginVariant.Retro,
        defaultWorkspace = "/srv/work",
        showEndedSessions = false,
        confirmBeforeEnd = false,
        showThinking = true,
        sidebarCollapsed = true,
        sidebarWidth = 300,
        inspectorWidth = 260,
        pinnedProjects = listOf("/srv/b", "/srv/a"),
        sidebarActiveOnly = true,
        sidebarUnreadOnly = true,
        sidebarHideAgentRuns = false,
        sidebarSort = SidebarSort.LastActive,
        pinnedModels = listOf("m1", "m2"),
    )

    /** Each field on its own: written through A's view, A has it, B and the stored model do not. */
    private val oneFieldEach: List<(TetherPreferences) -> TetherPreferences> = listOf(
        { it.copy(themeMode = edited.themeMode) },
        { it.copy(loginVariant = edited.loginVariant) },
        { it.copy(defaultWorkspace = edited.defaultWorkspace) },
        { it.copy(showEndedSessions = edited.showEndedSessions) },
        { it.copy(confirmBeforeEnd = edited.confirmBeforeEnd) },
        { it.copy(showThinking = edited.showThinking) },
        { it.copy(sidebarCollapsed = edited.sidebarCollapsed) },
        { it.copy(sidebarWidth = edited.sidebarWidth) },
        { it.copy(inspectorWidth = edited.inspectorWidth) },
        { it.copy(pinnedProjects = edited.pinnedProjects) },
        { it.copy(sidebarActiveOnly = edited.sidebarActiveOnly) },
        { it.copy(sidebarUnreadOnly = edited.sidebarUnreadOnly) },
        { it.copy(sidebarHideAgentRuns = edited.sidebarHideAgentRuns) },
        { it.copy(sidebarSort = edited.sidebarSort) },
        { it.copy(pinnedModels = edited.pinnedModels) },
    )

    @Test
    fun everyPreferenceIsTheServersOwn() {
        assertEquals(ServerPreferences.of(TetherPreferences()), ServerPreferences.Default)
        for ((i, change) in oneFieldEach.withIndex()) {
            val prefs = TetherPreferences().updateForServer(a, change)
            val onA = ServerPreferences.of(prefs.forServer(a))
            assertEquals("field $i", ServerPreferences.of(change(TetherPreferences())), onA)
            assertTrue("field $i differs from the default", onA != ServerPreferences.Default)
            assertEquals("field $i: B has its own", ServerPreferences.Default, ServerPreferences.of(prefs.forServer(b)))
            assertEquals("field $i: no server configured is a key of its own", ServerPreferences.Default, ServerPreferences.of(prefs.forServer(null)))
            assertEquals("field $i: no device-wide copy", ServerPreferences.Default, ServerPreferences.of(prefs))
            assertFalse("field $i", prefs.hasDeviceWideServerRecords)
        }
        val both = TetherPreferences().updateForServer(a) { edited.applyTo(it) }.updateForServer(b) { it.copy(themeMode = ThemeMode.Light) }
        assertEquals(edited, ServerPreferences.of(both.forServer(a)))
        assertEquals(ServerPreferences(themeMode = ThemeMode.Light), ServerPreferences.of(both.forServer(b)))
        assertTrue(both.hasRecordFor(a))
        assertTrue(both.hasRecordFor(b))
        assertFalse(both.hasRecordFor("https://c.example:443"))
        assertFalse(both.hasRecordFor(null))
        // Any record of its own counts (the web's one object holds them all).
        assertTrue(TetherPreferences(preferencesByOrigin = mapOf(a to ServerPreferences.Default)).hasRecordFor(a))
        assertTrue(TetherPreferences(collapsedByOrigin = mapOf(a to emptyList())).hasRecordFor(a))
        assertTrue(TetherPreferences(lastSeenByOrigin = mapOf(a to emptyMap())).hasRecordFor(a))
        assertTrue(TetherPreferences(lastOpenedByOrigin = mapOf(a to LastOpenedSession("/w", "s", null))).hasRecordFor(a))
        assertFalse(TetherPreferences(preferencesByOrigin = mapOf(b to ServerPreferences.Default)).hasRecordFor(a))
    }

    @Test
    fun theDeviceWidePreferencesMoveToTheCurrentServerOnce() {
        val legacy = edited.applyTo(TetherPreferences())
        assertTrue("a device-wide value waits for a server", legacy.hasDeviceWideServerRecords)
        assertEquals("read through until migrated", edited, ServerPreferences.of(legacy.forServer(a)))
        assertEquals(edited, ServerPreferences.of(legacy.forServer(null)))
        val migrated = legacy.migrateToServer(a)
        assertFalse(migrated.hasDeviceWideServerRecords)
        assertEquals("the device-wide values are gone", ServerPreferences.Default, ServerPreferences.of(migrated))
        assertEquals(edited, ServerPreferences.of(migrated.forServer(a)))
        assertEquals("another server does not inherit them", ServerPreferences.Default, ServerPreferences.of(migrated.forServer(b)))
        assertSame("once", migrated, migrated.migrateToServer(b))
        // Each field alone is a device-wide value to migrate.
        for ((i, change) in oneFieldEach.withIndex()) {
            val one = change(TetherPreferences())
            assertTrue("field $i", one.hasDeviceWideServerRecords)
            assertEquals("field $i", ServerPreferences.of(one), ServerPreferences.of(one.migrateToServer(a).forServer(a)))
        }
        // A write through a server's view migrates first.
        val written = legacy.updateForServer(b) { it.copy(showThinking = false) }
        assertEquals(edited.copy(showThinking = false), ServerPreferences.of(written.forServer(b)))
        assertEquals(ServerPreferences.Default, ServerPreferences.of(written.forServer(a)))
        assertEquals(ServerPreferences.Default, ServerPreferences.of(written))
    }

    @Test
    fun aServersOwnPreferencesWinOverTheDeviceWideOnes() {
        val own = TetherPreferences().updateForServer(a) { it.copy(themeMode = ThemeMode.Light) }
        val both = edited.applyTo(own)
        assertEquals("its own record, not the device-wide one", ThemeMode.Light, both.forServer(a).themeMode)
        assertEquals("a server without one reads the device-wide value until it is migrated", ThemeMode.Dark, both.forServer(b).themeMode)
        val migrated = both.migrateToServer(a)
        assertEquals(ServerPreferences(themeMode = ThemeMode.Light), ServerPreferences.of(migrated.forServer(a)))
        assertFalse(migrated.hasDeviceWideServerRecords)
    }

    /** With no server configured the "" record is written; the device-wide value waits for a real server. */
    @Test
    fun withNoServerTheEmptyKeyIsARecordOfItsOwn() {
        val legacy = edited.applyTo(TetherPreferences())
        val unconfigured = legacy.updateForServer(null) { it.copy(themeMode = ThemeMode.Light) }
        assertEquals(edited.copy(themeMode = ThemeMode.Light), ServerPreferences.of(unconfigured.forServer(null)))
        assertTrue(unconfigured.hasDeviceWideServerRecords)
        assertEquals("still the device-wide value", edited, ServerPreferences.of(unconfigured.forServer(a)))
        assertTrue(unconfigured.hasRecordFor(null))
        assertEquals(edited, ServerPreferences.of(unconfigured.migrateToServer(a).forServer(a)))
    }

    /** ta-coik.47 gap: an install whose only device-wide record is seen stamps migrates them too. */
    @Test
    fun seenStampsAloneAreMigrated() {
        val legacy = TetherPreferences(lastSeenSessions = mapOf("h" to 5L))
        assertTrue(legacy.hasDeviceWideServerRecords)
        val migrated = legacy.migrateToServer(a)
        assertTrue(migrated.lastSeenSessions.isEmpty())
        assertEquals(mapOf("h" to 5L), migrated.forServer(a).lastSeenSessions)
        assertTrue(migrated.forServer(b).lastSeenSessions.isEmpty())
        assertFalse(migrated.hasDeviceWideServerRecords)
    }

    /** ta-coik.47 gap: until migrated, a server without stamps of its own reads the device-wide ones. */
    @Test
    fun theDeviceWideSeenStampsAreReadUntilMigrated() {
        val legacy = TetherPreferences(lastSeenSessions = mapOf("h" to 5L))
        assertEquals(mapOf("h" to 5L), legacy.forServer(a).lastSeenSessions)
        assertEquals(mapOf("h" to 5L), legacy.forServer(null).lastSeenSessions)
        val own = legacy.copy(lastSeenByOrigin = mapOf(b to mapOf("own" to 1L)))
        assertEquals(mapOf("own" to 1L), own.forServer(b).lastSeenSessions)
    }

    @Test
    fun anyCharacterInAPreferenceRoundTrips() {
        val odd = "/srv/line\nbreak\ttab\r\u0000\"quoted\" \\ back/ünï😀"
        val records = mapOf(
            a to edited.copy(defaultWorkspace = odd, pinnedProjects = listOf(odd, "/plain"), pinnedModels = listOf("m\n1", odd)),
            "$b\n\t\"" to ServerPreferences(defaultWorkspace = "\u2028"),
            "" to ServerPreferences.Default,
        )
        val parsed = TetherPreferences.parse(mapOf(PreferenceKeys.PREFERENCES_BY_ORIGIN_JSON to TetherPreferences.joinPreferencesByOrigin(records)))
        assertEquals(records, parsed.preferencesByOrigin)
        assertEquals(odd, parsed.forServer(a).defaultWorkspace)
    }

    @Test
    fun corruptPreferencesReadAsDefaults() {
        for (garbage in listOf("", "not json", "[1,2]", "\"str\"", "{", "null", "{\"a\":")) {
            val parsed = TetherPreferences.parse(mapOf(PreferenceKeys.PREFERENCES_BY_ORIGIN_JSON to garbage))
            assertEquals(garbage, emptyMap<String, ServerPreferences>(), parsed.preferencesByOrigin)
        }
        val junk = """{"themeMode":"neon","loginVariant":"instrument","defaultWorkspace":7,"showEndedSessions":"no",""" +
            """"confirmBeforeEnd":null,"showThinking":1,"sidebarCollapsed":"true","sidebarWidth":"wide","inspectorWidth":{},""" +
            """"pinnedProjects":"/srv/x","sidebarActiveOnly":[],"sidebarUnreadOnly":{},"sidebarHideAgentRuns":"false",""" +
            """"sidebarSort":"name","pinnedModels":[1,null,"",{"m":1}]}"""
        val parsed = TetherPreferences.parse(
            mapOf(PreferenceKeys.PREFERENCES_BY_ORIGIN_JSON to """{"$a":$junk,"$b":"not-an-object","c":[1],"d":{}}"""),
        )
        assertEquals("every unusable field is its default; non-objects are no record", mapOf(a to ServerPreferences.Default, "d" to ServerPreferences.Default), parsed.preferencesByOrigin)
        // The web's own repairs: a numeric-string width is honoured (lib/panel-widths.mjs), retired modes are not.
        val repaired = TetherPreferences.parse(
            mapOf(PreferenceKeys.PREFERENCES_BY_ORIGIN_JSON to """{"$a":{"themeMode":"light","sidebarWidth":" 0x138 ","inspectorWidth":299.6,"pinnedProjects":["/ok",3]}}"""),
        ).preferencesByOrigin.getValue(a)
        assertEquals(ServerPreferences(themeMode = ThemeMode.Light, sidebarWidth = 312, inspectorWidth = 300, pinnedProjects = listOf("/ok")), repaired)
        assertTrue(TetherPreferences.parse(mapOf(PreferenceKeys.PREFERENCES_BY_ORIGIN_JSON to 3)).preferencesByOrigin.isEmpty())
    }

    /** overview.tsx 90fbb9f :43-55 readChoice. */
    @Test
    fun overviewFiltersReadFailSoft() {
        val f = OverviewFilters("/srv/\n😀", "claude", "waiting")
        assertEquals(f, OverviewFilters.fromJson(f.toJson()))
        assertEquals(OverviewFilters(), OverviewFilters.fromJson(OverviewFilters().toJson()))
        for (garbage in listOf(null, kotlinx.serialization.json.JsonPrimitive("x"), kotlinx.serialization.json.JsonArray(emptyList()))) {
            assertEquals(OverviewFilters(), OverviewFilters.fromJson(garbage))
        }
        val junk = kotlinx.serialization.json.Json.parseToJsonElement("""{"workspace":"","provider":3,"status":true}""")
        assertEquals(OverviewFilters(), OverviewFilters.fromJson(junk))
    }
}
