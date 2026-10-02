package com.tether.app.client

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-2uq: the custom model id rule and the refresh-providers throttle, as pure checks. */
class ModelBrowserRulesTest {

    @Test
    fun aCustomIdIsOneBoundedWordOfVisibleCharacters() {
        for (ok in listOf("claude-opus-4-5[1m]", "anthropic/claude-sonnet-4", "gpt-5.1-codex", "模型-1", "  trimmed  ", "é".repeat(100))) {
            assertNull("accepted: $ok", CustomModelId.problem(ok))
        }
        assertEquals("trimmed", CustomModelId.normalize("  trimmed \n"))
        val cases = mapOf(
            "" to CustomModelId.Problem.Empty,
            "   " to CustomModelId.Problem.Empty,
            "é".repeat(100) + "x" to CustomModelId.Problem.TooLong,
            "a b" to CustomModelId.Problem.NotOneWord,
            "a\tb" to CustomModelId.Problem.NotOneWord,
            "a\nb" to CustomModelId.Problem.NotOneWord,
            "x\u202Ey" to CustomModelId.Problem.NotOneWord,
            "x\u2066y" to CustomModelId.Problem.NotOneWord,
            "\u200Bx" to CustomModelId.Problem.NotOneWord,
            "x\uFEFFy" to CustomModelId.Problem.NotOneWord,
            "x\u3164y" to CustomModelId.Problem.NotOneWord,
            "x\u0007y" to CustomModelId.Problem.NotOneWord,
            "x\uD800y" to CustomModelId.Problem.NotOneWord,
            "x\u00A0y" to CustomModelId.Problem.NotOneWord,
            "x\u3000y" to CustomModelId.Problem.NotOneWord,
            LEGACY_GROUP_VALUE to CustomModelId.Problem.Reserved,
        )
        for ((raw, problem) in cases) assertEquals("for ${raw.map { it.code.toString(16) }}", problem, CustomModelId.problem(raw))
    }

    @Test
    fun storedCustomIdsAreCleanedOnRead() {
        val raw = JsObj.of(
            "claude" to JsArr.of(JsStr(" a "), JsStr("a"), JsStr("b c"), JsStr("ok")),
            "" to JsArr.of(JsStr("x")),
            "codex" to JsArr.of(JsStr("\u202E")),
        )
        assertEquals(mapOf("claude" to listOf("a", "ok")), CustomModelId.asMap(raw))
        assertNull(CustomModelId.sanitize(JsStr("nope")))
        assertNull(CustomModelId.sanitize(JsObj.of("codex" to JsArr.of(JsStr("bad id")))))
        val prefs = JsObj.of("providerKey" to JsStr("claude"), "customModels" to JsObj.of("codex" to JsArr.of(JsStr("bad id"))))
        assertEquals(JsObj.of("providerKey" to JsStr("claude")), CustomModelId.cleanPreferences(prefs))
        val many = JsObj.of("claude" to JsArr.of((1..500).map { JsStr("m$it") }))
        assertEquals(CustomModelId.MAX_PER_ENTRY, CustomModelId.asMap(many).getValue("claude").size)
    }

    private fun entry(fetchedAt: Long?, key: String = "codex") = ProviderCatalogEntry(key, "codex", "error", emptyList(), fetchedAt = fetchedAt)

    @Test
    fun oneRefreshInFlightPerRowThenTapsAreDebounced() {
        val t = ProviderRefreshThrottle(debounceMs = 2_000, inFlightTimeoutMs = 30_000)
        assertTrue(t.admit("codex", 1, 0, entry(100)))
        assertFalse("a double tap", t.admit("codex", 1, 10, entry(100)))
        assertTrue("another row is its own", t.admit("claude", 1, 10, entry(100, "claude")))
        // A push that did not settle the row (same stamp) keeps it in flight, past the debounce.
        t.onCatalog(1, listOf(entry(100)))
        assertFalse(t.admit("codex", 1, 5_000, entry(100)))
        assertTrue(t.inFlight("codex", 1, 5_000))
        // Settled (a new stamp): the next tap goes, once.
        t.onCatalog(1, listOf(entry(200)))
        assertFalse(t.inFlight("codex", 1, 5_000))
        assertTrue(t.admit("codex", 1, 5_000, entry(200)))
        // Settled at once, but tapped again inside the debounce: ignored.
        t.onCatalog(1, listOf(entry(300)))
        assertFalse(t.admit("codex", 1, 6_000, entry(300)))
        assertTrue(t.admit("codex", 1, 7_001, entry(300)))
    }

    @Test
    fun aSilentRefreshIsLetGoAfterItsTimeoutAndASocketChangeDropsIt() {
        val t = ProviderRefreshThrottle(debounceMs = 2_000, inFlightTimeoutMs = 30_000)
        assertTrue(t.admit("pi", 1, 0, entry(null, "pi")))
        assertFalse(t.admit("pi", 1, 29_999, entry(null, "pi")))
        assertTrue(t.admit("pi", 1, 30_000, entry(null, "pi")))
        // A row the push no longer lists ends its flight.
        t.onCatalog(1, emptyList())
        assertFalse(t.inFlight("pi", 1, 30_001))
        // On a new socket an old flight never blocks; clear() forgets them all.
        assertTrue(t.admit("codex", 1, 40_000, entry(1)))
        assertTrue("another socket", t.admit("codex", 2, 40_001, entry(1)))
        t.clear()
        assertFalse(t.inFlight("codex", 2, 40_002))
        assertTrue(t.admit("codex", 2, 40_002, entry(1)))
    }
}
