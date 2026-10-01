package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.model.ProviderInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-895: the New session picker's rules (lib/provider-catalog.mjs buildDescriptors,
 * model-browser.tsx ProviderRow, use-draft-composer.ts readiness + create): which rows it draws, and
 * the one `create` a tap may produce. A profile id goes out only from the live catalog, exactly as
 * drawn; anything else is refused, never rerouted to the default profile.
 */
class NewSessionGuardTest {

    private fun entry(
        key: String,
        provider: String = key,
        status: String = "ready",
        profileId: String? = null,
        extends: String? = null,
        label: String? = null,
        error: String? = null,
    ) = ProviderCatalogEntry(key, provider, status, emptyList(), label = label, profileId = profileId, error = error, extends = extends)

    private fun profile(id: String, extends: String = "claude", status: String = "ready", label: String? = id) =
        entry(id, extends, status, profileId = id, extends = extends, label = label)

    private val providers = listOf(
        ProviderInfo("claude", "Claude", "C", true),
        ProviderInfo("codex", "Codex", "X", true),
        ProviderInfo("acp", "ACP", "A", true),
        ProviderInfo("pi", "Pi", "P", false),
    )

    /** The server's order: enabled profiles first, then one default row per non-acp engine. */
    private val catalog = listOf(
        profile("work", label = "Claude (work)"),
        profile("personal", label = "Claude (personal)"),
        profile("gemini-acp", extends = "acp", label = "Gemini via ACP"),
        entry("claude", label = "Claude"),
        entry("codex", label = "Codex"),
        entry("pi", status = "unavailable", label = "Pi"),
    )

    private fun choice(e: ProviderCatalogEntry) = NewSessionChoice(e.key, e.provider, e.profileId)

    // --- rows -----------------------------------------------------------------------------------

    @Test
    fun theLiveCatalogIsListedInTheServersOrderEveryProfileItsOwnRow() {
        val rows = NewSessionGuard.rows(catalog, providers)
        assertEquals(listOf("work", "personal", "gemini-acp", "claude", "codex", "pi"), rows.map { it.choice.key })
        assertEquals(listOf("work", "personal", "gemini-acp", null, null, null), rows.map { it.choice.profileId })
        assertEquals(listOf(true, true, true, true, true, false), rows.map { it.creatable })
        assertEquals("acp", rows[2].choice.provider)
    }

    @Test
    fun aLoadingOrUnavailableRowIsDrawnButNeverCreatesAnErrorRowDoes() {
        val rows = NewSessionGuard.rows(listOf(profile("a", status = "loading"), profile("b", status = "unavailable"), profile("c", status = "error"), profile("d", status = "bogus")), providers)
        assertEquals(listOf(false, false, true, false), rows.map { it.creatable })
    }

    @Test
    fun aMalformedOrDuplicatedRowIsDrawnButNeverCreates() {
        val bad = listOf(
            entry("work", "claude", profileId = "other"),                 // key != profileId
            entry("empty", "claude", profileId = ""),                     // an empty profile id
            entry("x".repeat(129), "claude", profileId = "x".repeat(129)), // over LIMITS.ID_LENGTH
            profile("mismatch").copy(extends = "codex"),                  // extends != engine
            entry("claude-alias", "claude"),                              // default row key != engine
            entry("acp"),                                                 // acp has no default row
            profile("dup"), profile("dup").copy(label = "Spoof"),         // two rows, one key
        )
        val rows = NewSessionGuard.rows(bad, providers)
        assertEquals(bad.size, rows.size)
        assertTrue(rows.toString(), rows.none { it.creatable })
    }

    @Test
    fun withoutALiveCatalogTheBaseProvidersStandInAsDefaultRowsAcpLeftOut() {
        for (live in listOf(null, emptyList<ProviderCatalogEntry>())) {
            val rows = NewSessionGuard.rows(live, providers)
            assertEquals(listOf("claude", "codex", "pi"), rows.map { it.choice.key })
            assertTrue(rows.all { it.choice.profileId == null && it.entry == null })
            assertEquals(listOf(true, true, false), rows.map { it.creatable })
            assertEquals(listOf("ready", "ready", "unavailable"), rows.map { it.status })
        }
    }

    // --- resolve: the create a tap may produce --------------------------------------------------

    @Test
    fun aProfileRowCreatesOnThatProfileAndADefaultRowOnNone() {
        assertEquals(
            ClientMessage.Create(provider = "claude", cwd = "/w", profileId = "work"),
            NewSessionGuard.resolve(choice(catalog[0]), catalog, providers, "/w"),
        )
        assertEquals(
            ClientMessage.Create(provider = "acp", cwd = "/w", profileId = "gemini-acp"),
            NewSessionGuard.resolve(choice(catalog[2]), catalog, providers, "/w"),
        )
        val default = NewSessionGuard.resolve(choice(catalog[3]), catalog, providers, "/w")
        assertEquals(ClientMessage.Create(provider = "claude", cwd = "/w"), default)
        assertNull(default!!.profileId)
    }

    @Test
    fun aRowGoneFromTheRefreshedCatalogIsRefusedNotCreatedOnTheDefault() {
        val drawn = choice(catalog[0])
        val refreshed = catalog.filter { it.key != "work" }
        assertNull(NewSessionGuard.resolve(drawn, refreshed, providers, "/w"))
    }

    @Test
    fun aRowWhoseKeyNowNamesAnotherProfileOrEngineIsRefused() {
        val drawn = choice(catalog[0])
        val swapped = listOf(
            entry("work", "claude", profileId = "work-2", extends = "claude"),
            entry("work", "codex", profileId = "work", extends = "codex"),
            entry("work", "claude"), // the profile went and its key is now an (odd) default row
        )
        for (row in swapped) assertNull("$row", NewSessionGuard.resolve(drawn, listOf(row) + catalog.drop(1), providers, "/w"))
        // A default row the operator drew never picks up a profile that took its key.
        assertNull(NewSessionGuard.resolve(choice(catalog[3]), listOf(profile("claude")), providers, "/w"))
    }

    @Test
    fun aRowThatTurnedUnavailableOrLoadingIsRefusedAnErrorOneStillCreates() {
        val drawn = choice(catalog[0])
        assertNull(NewSessionGuard.resolve(drawn, listOf(profile("work", status = "unavailable")), providers, "/w"))
        assertNull(NewSessionGuard.resolve(drawn, listOf(profile("work", status = "loading")), providers, "/w"))
        assertEquals("work", NewSessionGuard.resolve(drawn, listOf(profile("work", status = "error")), providers, "/w")?.profileId)
    }

    @Test
    fun anAmbiguousKeyIsRefused() {
        val drawn = choice(catalog[0])
        assertNull(NewSessionGuard.resolve(drawn, listOf(profile("work"), profile("work").copy(label = "Spoof")), providers, "/w"))
    }

    @Test
    fun withoutALiveCatalogNoProfileIsEverSentOnlyAnAvailableDefault() {
        // A row drawn from an earlier connection's (or another server's) catalog.
        assertNull(NewSessionGuard.resolve(choice(catalog[0]), null, providers, "/w"))
        assertNull(NewSessionGuard.resolve(choice(catalog[0]), emptyList(), providers, "/w"))
        assertNull(NewSessionGuard.resolve(NewSessionChoice("gemini-acp", "acp", "gemini-acp"), null, providers, "/w"))
        assertNull("acp has no default row", NewSessionGuard.resolve(NewSessionChoice("acp", "acp", null), null, providers, "/w"))
        assertNull("unavailable", NewSessionGuard.resolve(NewSessionChoice("pi", "pi", null), null, providers, "/w"))
        assertNull("not listed", NewSessionGuard.resolve(NewSessionChoice("opencode", "opencode", null), null, providers, "/w"))
        assertNull("key != engine", NewSessionGuard.resolve(NewSessionChoice("work", "claude", null), null, providers, "/w"))
        assertEquals(ClientMessage.Create(provider = "codex", cwd = "/w"), NewSessionGuard.resolve(NewSessionChoice("codex", "codex", null), null, providers, "/w"))
    }

    @Test
    fun theCreateFrameIsTheWebsShapeWithTheProfileOnlyWhenThereIsOne() {
        val withProfile = NewSessionGuard.resolve(choice(catalog[0]), catalog, providers, "/w")!!.toJsonObject()
        assertEquals(setOf("type", "provider", "cwd", "profileId"), withProfile.keys)
        val without = NewSessionGuard.resolve(choice(catalog[3]), catalog, providers, "/w")!!.toJsonObject()
        assertEquals(setOf("type", "provider", "cwd"), without.keys)
    }

    // --- the catalog fields the picker reads ----------------------------------------------------

    @Test
    fun theCatalogDecodesErrorAndExtends() {
        val o: JsonObject = buildJsonObject {
            put("key", "work"); put("provider", "claude"); put("status", "error"); put("profileId", "work")
            put("extends", "claude"); put("label", "Claude (work)"); put("error", "Not logged in")
        }
        val parsed = ProviderCatalogEntry.parse(listOf(o)).single()
        assertEquals("claude", parsed.extends)
        assertEquals("Not logged in", parsed.error)
        assertEquals("work", parsed.profileId)
        assertFalse(ProviderCatalogEntry.parse(listOf(buildJsonObject { put("key", "x"); put("provider", "claude") })).isNotEmpty())
    }
}
