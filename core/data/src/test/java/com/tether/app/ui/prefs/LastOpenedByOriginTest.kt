package com.tether.app.ui.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * ta-coik.41 r2: the remembered chat per server origin (the web's localStorage is per origin), and
 * the one-time migration of the single value the app kept before.
 */
class LastOpenedByOriginTest {
    private val a = "https://a.example"
    private val b = "https://b.example"
    private val chatA = LastOpenedSession("/srv/a", "sa", "ha")
    private val chatB = LastOpenedSession("/srv/b", "sb", null)

    @Test
    fun eachServerRemembersItsOwnChat() {
        val prefs = TetherPreferences().rememberOpenedFor(a, chatA).rememberOpenedFor(b, chatB)
        assertEquals(chatA, prefs.lastOpenedFor(a))
        assertEquals(chatB, prefs.lastOpenedFor(b))
        assertNull(prefs.lastOpenedFor("https://c.example"))
        assertNull("no server configured is a key of its own", prefs.lastOpenedFor(null))
        assertEquals(chatA, prefs.rememberOpenedFor(null, chatA).lastOpenedFor(null))
    }

    @Test
    fun theSameChatInTheSameFolderIsNoWrite() {
        val prefs = TetherPreferences().rememberOpenedFor(a, chatA)
        assertSame(prefs, prefs.rememberOpenedFor(a, chatA.copy(historyId = "other")))
        assertEquals("/srv/moved", prefs.rememberOpenedFor(a, chatA.copy(cwd = "/srv/moved")).lastOpenedFor(a)?.cwd)
    }

    @Test
    fun theSingleValueIsMigratedToTheCurrentServerOnce() {
        val legacy = TetherPreferences(lastOpenedSession = chatA)
        assertEquals("read through until migrated", chatA, legacy.lastOpenedFor(a))
        val migrated = legacy.migrateLastOpened(a)
        assertNull(migrated.lastOpenedSession)
        assertEquals(chatA, migrated.lastOpenedFor(a))
        assertNull("another server does not inherit it", migrated.lastOpenedFor(b))
        assertSame(migrated, migrated.migrateLastOpened(b))
    }

    @Test
    fun aServersOwnChatWinsOverTheSingleValue() {
        val both = TetherPreferences(lastOpenedSession = chatA).rememberOpenedFor(b, chatB)
        assertEquals(chatB, both.migrateLastOpened(b).lastOpenedFor(b))
    }

    @Test
    fun theTabLinesOfBeforeAreStillReadFailSoft() {
        val raw = mapOf(
            PreferenceKeys.LAST_OPENED_BY_ORIGIN to "$a\tsa\tha\t/srv/a\n$b\tsb\t\t/srv/with\ttab\nbroken\n$a-x\t\tha\t/srv/x",
        )
        val parsed = TetherPreferences.parse(raw).lastOpenedByOrigin
        assertEquals(mapOf(a to chatA, b to LastOpenedSession("/srv/with\ttab", "sb", null)), parsed)
        // Rewritten as JSON, the same record reads back.
        assertEquals(parsed, TetherPreferences.parse(mapOf(PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON to TetherPreferences.joinOpened(parsed))).lastOpenedByOrigin)
    }

    /** ta-coik.46: a folder name may hold any character; the server's remembered chat is never dropped. */
    @Test
    fun anyCharacterInAnyFieldRoundTrips() {
        val odd = mapOf(
            a to LastOpenedSession("/srv/line\nbreak", "sa", "ha"),
            b to LastOpenedSession("/srv/tab\there\r\u0000\"quoted\" \\ back/ünï\uD83D\uDE00", "s\tb", null),
            "$a\n\t\"" to LastOpenedSession("", "sc", "h\nc"),
        )
        val json = TetherPreferences.joinOpened(odd)
        assertEquals(odd, TetherPreferences.parse(mapOf(PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON to json)).lastOpenedByOrigin)
    }

    @Test
    fun theJsonRecordWinsAndParsesFailSoft() {
        val json = """{"$a":{"cwd":"/srv/a","sessionId":"sa","historyId":"ha"},""" +
            """"$b":{"cwd":"/srv/b","sessionId":"sb","historyId":null},""" +
            """"no-id":{"cwd":"/x","sessionId":""},"no-cwd":{"sessionId":"s"},"wrong":{"cwd":1,"sessionId":"s"},""" +
            """"empty-history":{"cwd":"/e","sessionId":"se","historyId":""},"array":[1]}"""
        val raw = mapOf(
            PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON to json,
            PreferenceKeys.LAST_OPENED_BY_ORIGIN to "$a\tlegacy\t\t/srv/legacy",
        )
        assertEquals(
            mapOf(a to chatA, b to LastOpenedSession("/srv/b", "sb", null), "empty-history" to LastOpenedSession("/e", "se", null)),
            TetherPreferences.parse(raw).lastOpenedByOrigin,
        )
        for (junk in listOf("", "not json", "[]", "null", "{\"a\":")) {
            assertEquals(junk, emptyMap<String, LastOpenedSession>(), TetherPreferences.parse(mapOf(PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON to junk)).lastOpenedByOrigin)
        }
        assertEquals("a wrongly typed value is no record", emptyMap<String, LastOpenedSession>(), TetherPreferences.parse(mapOf(PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON to 3)).lastOpenedByOrigin)
    }
}
