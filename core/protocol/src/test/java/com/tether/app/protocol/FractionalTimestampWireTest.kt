package com.tether.app.protocol

import com.tether.app.protocol.model.HistorySession
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-28ll: the server sends statSync().mtimeMs (fractional) for history/search timestamps. */
class FractionalTimestampWireTest {
    private val row =
        """{"historyId":"h1","provider":"claude","name":"n","cwd":"/w","updatedAt":1792176143294.5603,""" +
            """"createdAt":1792176000000,"lastSeenAt":1792176143406.7944}"""
    private val bad = """{"historyId":"h2","provider":"claude","name":"n","cwd":"/w","updatedAt":"soon"}"""
    private val hit = row.dropLast(1) + ""","snippet":"s","matchCount":1}"""

    @Test
    fun historiesKeepsFractionalRowsAndStillDropsBrokenOnes() {
        val m = ServerMessage.parse("""{"type":"histories","cwd":"/w","sessions":[$row,$bad]}""")
        val s = (m as ServerMessage.Histories).sessions
        assertEquals(1, s.size)
        assertEquals(1792176143294L, s[0].updatedAt)
        assertEquals(1792176000000L, s[0].createdAt)
        assertEquals(1792176143406L, s[0].lastSeenAt)
    }

    @Test
    fun searchResultsKeepFractionalHits() {
        val m = ServerMessage.parse("""{"type":"search-results","cwd":"/w","query":"q","hits":[$hit]}""")
        assertEquals(1792176143294L, (m as ServerMessage.SearchResults).hits.single().updatedAt)
    }

    @Test
    fun globalSearchResultsKeepFractionalHits() {
        val m = ServerMessage.parse("""{"type":"global-search-results","requestId":3,"query":"q","hits":[$hit]}""")
        val h = (m as ServerMessage.GlobalSearchResults).hits.single()
        assertEquals(1792176143406L, h.lastSeenAt)
    }

    @Test
    fun nullAndNonFiniteStillRejectedAndEncodeIsInteger() {
        val nullUpdated = """{"historyId":"h","provider":"p","name":"n","cwd":"/","updatedAt":null}"""
        val m = ServerMessage.parse("""{"type":"histories","cwd":"/w","sessions":[$nullUpdated]}""")
        assertTrue((m as ServerMessage.Histories).sessions.isEmpty())
        val h = TetherJson.decodeFromString(HistorySession.serializer(), row)
        val out = TetherJson.encodeToString(HistorySession.serializer(), h)
        assertTrue(out, out.contains("\"updatedAt\":1792176143294,") || out.contains("\"updatedAt\":1792176143294}"))
        assertTrue(out, out.contains("\"lastSeenAt\":1792176143406"))
        assertTrue(out, !out.contains("1792176143294."))
    }
}
