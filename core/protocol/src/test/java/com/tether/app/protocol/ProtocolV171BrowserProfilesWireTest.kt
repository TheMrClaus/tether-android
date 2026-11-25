package com.tether.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v171: named browser profiles. The `browser-profiles` request (exact key set per action) and the tolerant `browser_profiles` reply. */
class ProtocolV171BrowserProfilesWireTest {
    private fun keys(m: ClientMessage.BrowserProfiles) = m.toJsonObject().keys

    @Test
    fun eachActionWritesOnlyItsKeys() {
        assertEquals(setOf("type", "action", "requestId"), keys(ClientMessage.BrowserProfiles("list", "r1")))
        assertEquals(setOf("type", "action", "requestId", "name"), keys(ClientMessage.BrowserProfiles("create", "r1", name = "Work")))
        assertEquals(setOf("type", "action", "requestId", "profileId", "name"), keys(ClientMessage.BrowserProfiles("rename", "r1", name = "Work", profileId = "p1")))
        assertEquals(setOf("type", "action", "requestId", "profileId"), keys(ClientMessage.BrowserProfiles("delete", "r1", profileId = "p1")))
        for (m in listOf(
            ClientMessage.BrowserProfiles("list", "r1"),
            ClientMessage.BrowserProfiles("rename", "r1", name = "Work", profileId = "p1"),
        )) assertEquals(m, ClientMessage.decode(m.toJsonObject()).getOrThrow())
    }

    @Test
    fun theReplyDecodesRowsAndHeldBy() {
        val m = ServerMessage.parse(ServerFixtures.BROWSER_PROFILES) as ServerMessage.BrowserProfiles
        assertEquals("r1", m.requestId)
        assertTrue(m.ok)
        assertEquals(listOf(
            BrowserProfileInfo("p1", "Work", 1, 2, "Fix login"),
            BrowserProfileInfo("p2", "Personal", 3, 4, null),
        ), m.profiles)
    }

    @Test
    fun aRefusalKeepsTheServersWordsAndATolerantListDropsBadRows() {
        val m = ServerMessage.parse(
            """{"type":"browser_profiles","requestId":"r2","ok":false,"code":"name_taken","error":"Taken.","profiles":[{"id":"p1","name":"A"},{"id":3,"name":"B"},"x",{"name":"C"}]}""",
        ) as ServerMessage.BrowserProfiles
        assertEquals(false, m.ok)
        assertEquals("name_taken", m.code)
        assertEquals("Taken.", m.error)
        assertEquals(listOf(BrowserProfileInfo("p1", "A")), m.profiles)
        val bare = ServerMessage.parse("""{"type":"browser_profiles","requestId":"r3","ok":"true"}""") as ServerMessage.BrowserProfiles
        assertEquals(false, bare.ok)
        assertEquals(emptyList<BrowserProfileInfo>(), bare.profiles)
    }
}
