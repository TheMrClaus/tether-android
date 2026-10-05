package com.tether.app.ui.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.55: the model picker's pin key writes `pinnedModels` exactly as chat-view.tsx (90fbb9f
 * :2281-2286) toggleModelPin does — a pinned id is removed, any other appended — and, like every
 * web preference (per-origin localStorage, ta-coik.52), on the server's own record only.
 */
class ModelPinPreferenceTest {
    private val a = "https://a.example:443"
    private val b = "https://b.example:443"

    private fun store() = RefusingPrefsStore().apply { refuse = false }

    @Test
    fun aPinIsAppendedAndAnUnpinRemovesOnlyThatId() = runBlocking {
        val prefs = UiPrefs.on(store())
        prefs.toggleModelPin(a, "claude-opus-4-1")
        prefs.toggleModelPin(a, "claude-sonnet-4")
        assertEquals(listOf("claude-opus-4-1", "claude-sonnet-4"), prefs.preferencesFor(flowOf(a)).first().pinnedModels)
        prefs.toggleModelPin(a, "claude-opus-4-1")
        assertEquals(listOf("claude-sonnet-4"), prefs.preferencesFor(flowOf(a)).first().pinnedModels)
        prefs.toggleModelPin(a, "claude-opus-4-1")
        assertEquals("a re-pin goes to the end", listOf("claude-sonnet-4", "claude-opus-4-1"), prefs.preferencesFor(flowOf(a)).first().pinnedModels)
    }

    @Test
    fun aPinOnOneServerNeverChangesAnother() = runBlocking {
        val store = store()
        val prefs = UiPrefs.on(store)
        prefs.toggleModelPin(b, "gpt-4.1")
        prefs.toggleModelPin(a, "claude-opus-4-1")
        assertEquals(listOf("claude-opus-4-1"), prefs.preferencesFor(flowOf(a)).first().pinnedModels)
        assertEquals(listOf("gpt-4.1"), prefs.preferencesFor(flowOf(b)).first().pinnedModels)
        prefs.toggleModelPin(a, "claude-opus-4-1")
        assertTrue(prefs.preferencesFor(flowOf(a)).first().pinnedModels.isEmpty())
        assertEquals(listOf("gpt-4.1"), prefs.preferencesFor(flowOf(b)).first().pinnedModels)
        assertTrue("no device-wide copy", prefs.preferences.first().pinnedModels.isEmpty())
        // It is on disk: a fresh reader of the same store sees B's pin as B's.
        assertEquals(listOf("gpt-4.1"), UiPrefs.on(store).preferencesFor(flowOf(b)).first().pinnedModels)
    }
}
