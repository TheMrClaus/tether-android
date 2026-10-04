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
    fun storedLinesParseFailSoft() {
        val raw = mapOf(
            PreferenceKeys.LAST_OPENED_BY_ORIGIN to "$a\tsa\tha\t/srv/a\n$b\tsb\t\t/srv/with\ttab\nbroken\n$a-x\t\tha\t/srv/x",
        )
        val parsed = TetherPreferences.parse(raw).lastOpenedByOrigin
        assertEquals(mapOf(a to chatA, b to LastOpenedSession("/srv/with\ttab", "sb", null)), parsed)
        assertEquals(parsed, TetherPreferences.parse(mapOf(PreferenceKeys.LAST_OPENED_BY_ORIGIN to TetherPreferences.joinOpened(parsed))).lastOpenedByOrigin)
    }
}
