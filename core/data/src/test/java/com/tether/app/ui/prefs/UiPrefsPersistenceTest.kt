package com.tether.app.ui.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T2.3: [UiPrefs] and [DataStoreDraftStore] on real DataStore files. "Process death" = cancel
 * the store's scope and open a fresh store on the same file.
 */
class UiPrefsPersistenceTest {
    private companion object {
        const val A = "https://a.example:443"
        const val B = "http://192.168.1.20:4173"
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefsFile get() = File(tmp.root, "tether_ui_prefs.preferences_pb")
    private val draftsFile get() = File(tmp.root, DraftStore.FILE_NAME)

    private suspend fun <T> withStore(file: File, block: suspend (DataStore<Preferences>) -> T): T {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun <T> withPrefs(block: suspend (UiPrefs) -> T): T = withStore(prefsFile) { block(UiPrefs(it)) }

    private suspend fun <T> withDrafts(block: suspend (DraftStore) -> T): T =
        withStore(draftsFile) { block(DataStoreDraftStore(it)) }

    @Test
    fun everyFieldRoundTripsAcrossARestart() = runBlocking {
        val edited = TetherPreferences(
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
            collapsedWorkspaces = listOf("/srv/a"),
            lastSeenSessions = mapOf("h1" to 1_700_000_000_000L, "h2" to 5L),
            lastOpenedSession = LastOpenedSession("/srv/a", "s1", "h1"),
            // ta-coik.41 r2: per server origin; a cwd may hold a tab, a history id may be absent.
            lastOpenedByOrigin = mapOf(
                "https://a.example" to LastOpenedSession("/srv/a", "s1", "h1"),
                "https://b.example:8443" to LastOpenedSession("/srv/with\ttab", "s2", null),
                "" to LastOpenedSession("/srv/none", "s3", "h3"),
                // ta-coik.46: and a newline (any character).
                "https://c.example" to LastOpenedSession("/srv/line\nbreak", "s4", "h\n4"),
            ),
            // ta-coik.47: per server origin, any character.
            collapsedByOrigin = mapOf(A to listOf("/srv/line\nbreak"), B to emptyList()),
            lastSeenByOrigin = mapOf(A to mapOf("h\t1" to 7L), "" to mapOf("h0" to 0L)),
            sidebarActiveOnly = true,
            sidebarUnreadOnly = true,
            sidebarHideAgentRuns = false,
            sidebarSort = SidebarSort.LastActive,
            pinnedModels = listOf("m1", "m2"),
        )
        withPrefs { prefs ->
            assertEquals(TetherPreferences.Default, prefs.preferences.first())
            prefs.updatePreferences { edited }
        }
        withPrefs { prefs ->
            assertEquals(edited, prefs.preferences.first())
            // The per-field flows read the same model.
            assertEquals(ThemeMode.Dark, prefs.themeMode.first())
            assertEquals(LoginVariant.Retro, prefs.loginVariant.first())
            assertTrue(prefs.showThinking.first())
            assertFalse(prefs.showEnded.first())
            assertEquals(listOf("/srv/b", "/srv/a"), prefs.pinnedProjects.first())
            // Clearing optional fields removes them.
            prefs.updatePreferences { it.copy(sidebarWidth = null, lastOpenedSession = null, lastOpenedByOrigin = emptyMap()) }
        }
        withPrefs { prefs ->
            val back = prefs.preferences.first()
            assertEquals(null, back.sidebarWidth)
            assertEquals(null, back.lastOpenedSession)
            assertEquals(emptyMap<String, LastOpenedSession>(), back.lastOpenedByOrigin)
            assertEquals(260, back.inspectorWidth)
        }
    }

    /** ta-coik.46: the tab-line record of ta-coik.41 r2 is read, and the next save rewrites it as JSON. */
    @Test
    fun theTabLineRecordMigratesToJsonOnTheNextSave() = runBlocking {
        withStore(prefsFile) { ds ->
            ds.edit { it[stringPreferencesKey(PreferenceKeys.LAST_OPENED_BY_ORIGIN)] = "$A\ts1\th1\t/srv/with\ttab" }
        }
        val expected = mapOf(A to LastOpenedSession("/srv/with\ttab", "s1", "h1"))
        withPrefs { prefs ->
            assertEquals(expected, prefs.preferences.first().lastOpenedByOrigin)
            prefs.setShowEnded(false)
        }
        withStore(prefsFile) { ds ->
            val stored = ds.data.first()
            assertEquals(null, stored[stringPreferencesKey(PreferenceKeys.LAST_OPENED_BY_ORIGIN)])
            assertTrue(stored[stringPreferencesKey(PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON)].orEmpty().startsWith("{"))
        }
        withPrefs { prefs -> assertEquals(expected, prefs.preferences.first().lastOpenedByOrigin) }
    }

    /**
     * Upgrade from 0.6.x and earlier: the keys shipped before T2.3 keep their stored values, and
     * the flat theme id migrates to its Studio mode (T15.5); the next save drops it.
     */
    @Test
    fun shippedKeysAreReadAndLegacyThemeIsDroppedOnSave() = runBlocking {
        withStore(prefsFile) { ds ->
            ds.edit {
                it[stringPreferencesKey("theme_choice")] = "night"
                it[booleanPreferencesKey("show_thinking")] = true
                it[stringPreferencesKey("pinned_projects")] = "/srv/x"
                it[stringPreferencesKey("push_scope")] = "pinned" // native-only, untouched
            }
        }
        withPrefs { prefs ->
            val p = prefs.preferences.first()
            assertEquals(ThemeMode.Dark, p.themeMode)
            assertTrue(p.showThinking)
            assertEquals(listOf("/srv/x"), p.pinnedProjects)
            prefs.setShowEnded(false)
            assertEquals(com.tether.app.push.PushScope.Pinned, prefs.pushScope.first())
        }
        withStore(prefsFile) { ds ->
            val raw = ds.data.first().asMap().mapKeys { it.key.name }
            assertFalse("legacy theme id consumed by the save", "theme_choice" in raw)
            assertFalse("no family is ever written", "theme_family" in raw)
            assertEquals("dark", raw["theme_mode"])
            assertEquals("pinned", raw["push_scope"])
        }
    }

    /**
     * T15.5, upgrade from every flat id up to 0.6.x and every 0.7.x-0.8.0 family x mode pair: the
     * read is the Studio mode, the first save consumes the deprecated keys and writes the mode
     * alone, and a later read (the migrated store) gives the same mode.
     */
    @Test
    fun everyStoredAppearanceShapeUpgradesToTheModeAlone() = runBlocking {
        val shapes: List<Pair<Map<String, String>, ThemeMode>> =
            listOf("system" to ThemeMode.System, "machine" to ThemeMode.Dark, "night" to ThemeMode.Dark, "quiet" to ThemeMode.Dark,
                "tactile" to ThemeMode.Light, "precision" to ThemeMode.Light)
                .map { (flat, mode) -> mapOf(PreferenceKeys.LEGACY_THEME to flat) to mode } +
                listOf("tactile", "precision", "studio").flatMap { family ->
                    ThemeMode.entries.map { mode ->
                        mapOf(PreferenceKeys.THEME_FAMILY to family, PreferenceKeys.THEME_MODE to mode.id) to mode
                    }
                } +
                listOf(mapOf(PreferenceKeys.THEME_FAMILY to "precision", PreferenceKeys.THEME_MODE to "light", PreferenceKeys.LEGACY_THEME to "machine") to ThemeMode.Light)
        for ((stored, expected) in shapes) {
            prefsFile.delete()
            withStore(prefsFile) { ds -> ds.edit { p -> stored.forEach { (k, v) -> p[stringPreferencesKey(k)] = v } } }
            withPrefs { prefs ->
                assertEquals("$stored", expected, prefs.themeMode.first())
                assertTrue("$stored predates the view record", prefs.viewBoot(null).hasExistingPreferences)
                prefs.setShowThinking(true) // any save of the model
            }
            withStore(prefsFile) { ds ->
                val raw = ds.data.first().asMap().mapKeys { it.key.name }
                assertFalse("$stored: family consumed", PreferenceKeys.THEME_FAMILY in raw)
                assertFalse("$stored: flat id consumed", PreferenceKeys.LEGACY_THEME in raw)
                assertEquals("$stored", expected.id, raw[PreferenceKeys.THEME_MODE])
            }
            withPrefs { prefs -> assertEquals("$stored after the save", expected, prefs.themeMode.first()) }
        }
    }

    /** Picking a mode stores it alone; the web's former "instrument" sign-in is saved back as "default". */
    @Test
    fun settingTheModeStoresOnlyTheModeAndRenamesInstrument() = runBlocking {
        withStore(prefsFile) { ds ->
            ds.edit {
                it[stringPreferencesKey(PreferenceKeys.THEME_FAMILY)] = "tactile"
                it[stringPreferencesKey(PreferenceKeys.THEME_MODE)] = "dark"
                it[stringPreferencesKey(PreferenceKeys.LOGIN_VARIANT)] = "instrument"
            }
        }
        withPrefs { prefs ->
            assertEquals(LoginVariant.Default, prefs.loginVariant.first())
            prefs.setThemeMode(ThemeMode.Light)
            assertEquals(ThemeMode.Light, prefs.themeMode.first())
        }
        withStore(prefsFile) { ds ->
            val raw = ds.data.first().asMap().mapKeys { it.key.name }
            assertEquals(mapOf(PreferenceKeys.THEME_MODE to "light", PreferenceKeys.LOGIN_VARIANT to "default"), raw.filterKeys { it in setOf(PreferenceKeys.THEME_MODE, PreferenceKeys.THEME_FAMILY, PreferenceKeys.LEGACY_THEME, PreferenceKeys.LOGIN_VARIANT) })
        }
    }

    /** A value stored under the wrong type (a typed get would throw ClassCastException) reads as the default. */
    @Test
    fun wronglyTypedStoredValuesNeverCrash() = runBlocking {
        withStore(prefsFile) { ds ->
            ds.edit {
                it[stringPreferencesKey(PreferenceKeys.SHOW_THINKING)] = "yes"
                it[intPreferencesKey(PreferenceKeys.LOGIN_VARIANT)] = 1
                it[stringPreferencesKey(PreferenceKeys.SIDEBAR_WIDTH)] = "wide"
            }
        }
        withPrefs { prefs ->
            assertEquals(TetherPreferences.Default, prefs.preferences.first())
            assertFalse(prefs.showThinking.first())
            prefs.setShowThinking(true) // and the next save repairs the stored type
            assertTrue(prefs.showThinking.first())
        }
    }

    @Test
    fun draftsArePerSessionAndSurviveARestart() = runBlocking {
        withDrafts { drafts ->
            assertEquals("", drafts.read(A, "s1"))
            drafts.write(A, "s1", "half a thought")
            drafts.write(A, "s2", "another")
            assertEquals("half a thought", drafts.read(A, "s1"))
            assertEquals("another", drafts.read(A, "s2"))
        }
        withDrafts { drafts ->
            assertEquals("half a thought", drafts.read(A, "s1"))
            assertEquals("another", drafts.read(A, "s2"))
            drafts.write(A, "s1", "revised")
            drafts.clear(A, "s2")
        }
        withDrafts { drafts ->
            assertEquals("revised", drafts.read(A, "s1"))
            assertEquals("", drafts.read(A, "s2"))
        }
    }

    /**
     * T7.1: drafts are per server origin, like the web's localStorage and T1.3's pending slots
     * (ta-s8q): the same session id on another server reads nothing, and clearing one origin's
     * draft leaves the other's alone.
     */
    @Test
    fun draftsArePerServerOrigin() = runBlocking {
        withDrafts { drafts ->
            drafts.write(A, "s1", "for server A")
            assertEquals("", drafts.read(B, "s1"))
            drafts.write(B, "s1", "for server B")
        }
        withDrafts { drafts ->
            assertEquals("for server A", drafts.read(A, "s1"))
            assertEquals("for server B", drafts.read(B, "s1"))
            drafts.clear(B, "s1")
            assertEquals("for server A", drafts.read(A, "s1"))
            assertEquals("", drafts.read(B, "s1"))
        }
    }

    /**
     * The web's key scheme inside the origin's namespace, and its only cleanup rule: an empty
     * draft removes the key (chat-view.tsx:1578-1583). An unscoped pre-T7.1 key is never read
     * (it belongs to no known origin) and the next write drops it.
     */
    @Test
    fun draftKeySchemeAndEmptyRemovesTheKey() = runBlocking {
        assertEquals("tether:draft:abc-123", DraftStore.webKey("abc-123"))
        assertEquals("https://a.example:443|tether:draft:abc-123", DraftStore.key(A, "abc-123"))
        assertThrows(IllegalArgumentException::class.java) { DraftStore.key("https://A.example/", "x") }
        withStore(draftsFile) { ds -> ds.edit { it[stringPreferencesKey("tether:draft:s1")] = "unscoped" } }
        withDrafts { drafts ->
            assertEquals("", drafts.read(A, "s1"))
            drafts.write(A, "s1", "text")
            drafts.write(A, "s2", "keep")
            drafts.write(A, "s1", "")
        }
        withStore(draftsFile) { ds ->
            val keys = ds.data.first().asMap().keys.map { it.name }.toSet()
            assertEquals(setOf("https://a.example:443|tether:draft:s2"), keys)
        }
    }

    /** lib/draft-preferences.mjs: `tether:draftPreferences.v1` (ta-8cv: per origin), junk or non-object reads as {}. */
    @Test
    fun draftPreferencesRoundTripAndFailSoft() = runBlocking {
        val prefs = JsObj.of("claude" to JsObj.of("model" to JsStr("opus")))
        withDrafts { drafts ->
            assertEquals(JsObj.EMPTY, drafts.readDraftPreferences(A))
            drafts.writeDraftPreferences(A, prefs)
        }
        withDrafts { drafts -> assertEquals(prefs, drafts.readDraftPreferences(A)) }
        for (junk in listOf("{not json", "[1,2]", "\"str\"")) {
            withStore(draftsFile) { ds -> ds.edit { it[stringPreferencesKey("$A|tether:draftPreferences.v1")] = junk } }
            withDrafts { drafts -> assertEquals(junk, JsObj.EMPTY, drafts.readDraftPreferences(A)) }
        }
    }

    /**
     * ta-8cv: the draft preferences are per server origin (catalog keys are a server's own), and the
     * unscoped record an earlier build kept belongs to no server: never read, dropped on the next write.
     */
    @Test
    fun draftPreferencesArePerOriginAndTheUnscopedRecordIsDropped() = runBlocking {
        assertEquals("https://a.example:443|tether:draftPreferences.v1", DraftStore.prefsKey(A))
        assertThrows(IllegalArgumentException::class.java) { DraftStore.prefsKey("https://A.example/") }
        val legacy = JsObj.of("providerPreferences" to JsObj.of("work" to JsObj.of("mode" to JsStr("bypassPermissions"))))
        withStore(draftsFile) { ds -> ds.edit { it[stringPreferencesKey("tether:draftPreferences.v1")] = legacy.toString() } }
        val onA = JsObj.of("providerPreferences" to JsObj.of("work" to JsObj.of("mode" to JsStr("default"))))
        withDrafts { drafts ->
            assertEquals("the unscoped record is never read as A's", JsObj.EMPTY, drafts.readDraftPreferences(A))
            assertEquals("nor as B's", JsObj.EMPTY, drafts.readDraftPreferences(B))
            drafts.writeDraftPreferences(A, onA)
            assertEquals(onA, drafts.readDraftPreferences(A))
            assertEquals("A's picks never show on B", JsObj.EMPTY, drafts.readDraftPreferences(B))
        }
        withStore(draftsFile) { ds ->
            val keys = ds.data.first().asMap().keys.map { it.name }.toSet()
            assertEquals(setOf("$A|tether:draftPreferences.v1"), keys)
        }
        // A draft write drops it as well.
        withStore(draftsFile) { ds -> ds.edit { it[stringPreferencesKey("tether:draftPreferences.v1")] = legacy.toString() } }
        withDrafts { drafts -> drafts.write(B, "s1", "x") }
        withStore(draftsFile) { ds ->
            assertFalse(ds.data.first().asMap().keys.any { it.name == "tether:draftPreferences.v1" })
        }
        // The in-memory store keeps origins apart too.
        val memory = InMemoryDraftStore()
        memory.writeDraftPreferences(A, onA)
        assertEquals(onA, memory.readDraftPreferences(A))
        assertEquals(JsObj.EMPTY, memory.readDraftPreferences(B))
    }

    /**
     * T15.4 (dashboard.tsx bootView): a fresh install has no remembered view and no preferences;
     * any save of the model makes it an existing one; the last view survives process death.
     */
    @Test
    fun viewBootReadsTheRememberedViewAndWhetherPreferencesExist() = runBlocking {
        withPrefs { prefs -> assertEquals(ViewBoot(storedView = null, hasExistingPreferences = false), prefs.viewBoot(null)) }
        withPrefs { prefs -> prefs.updatePreferences { it.copy(showThinking = true) } }
        withPrefs { prefs -> assertEquals(ViewBoot(storedView = null, hasExistingPreferences = true), prefs.viewBoot(null)) }
        withPrefs { prefs -> prefs.setLastView(null, "overview") }
        // ta-coik.47: once a server has a view record, a server without one is a fresh origin.
        withPrefs { prefs -> assertEquals(ViewBoot(storedView = "overview", hasExistingPreferences = false), prefs.viewBoot(null)) }
        // Only a remembered view (no model saved yet): still not an "existing" install.
        prefsFile.delete()
        withPrefs { prefs -> prefs.setLastView(null, "sessions") }
        withPrefs { prefs -> assertEquals(ViewBoot(storedView = "sessions", hasExistingPreferences = false), prefs.viewBoot(null)) }
        // A legacy flat theme alone marks an install that predates the view record.
        prefsFile.delete()
        withStore(prefsFile) { ds -> ds.edit { it[stringPreferencesKey(PreferenceKeys.LEGACY_THEME)] = "night" } }
        withPrefs { prefs -> assertTrue(prefs.viewBoot(null).hasExistingPreferences) }
    }

    /** ta-coik.47: each server remembers its own last view (the web's `tether:lastView` is per origin). */
    @Test
    fun theLastViewIsPerServer() = runBlocking {
        withPrefs { prefs ->
            prefs.updatePreferences { it.copy(showThinking = true) }
            prefs.setLastView(A, "scheduled")
            prefs.setLastView(B, "overview")
        }
        withPrefs { prefs ->
            assertEquals("scheduled", prefs.viewBoot(A).storedView)
            assertEquals("overview", prefs.viewBoot(B).storedView)
            // A server with no record is a fresh origin, though this install kept preferences.
            assertEquals(ViewBoot(storedView = null, hasExistingPreferences = false), prefs.viewBoot("https://c.example:443"))
            assertEquals(ViewBoot(storedView = null, hasExistingPreferences = false), prefs.viewBoot(null))
            prefs.setLastView(A, "sessions")
        }
        withPrefs { prefs ->
            assertEquals("sessions", prefs.viewBoot(A).storedView)
            assertEquals("overview", prefs.viewBoot(B).storedView)
        }
    }

    /** ta-coik.47: the device-wide view of before is this boot's server's; its first write moves it there. */
    @Test
    fun theDeviceWideLastViewIsMigratedToTheCurrentServerOnce() = runBlocking {
        withStore(prefsFile) { ds ->
            ds.edit {
                it[stringPreferencesKey("last_view")] = "scheduled"
                it[stringPreferencesKey(PreferenceKeys.THEME_MODE)] = "dark"
            }
        }
        withPrefs { prefs ->
            assertEquals(ViewBoot(storedView = "scheduled", hasExistingPreferences = true), prefs.viewBoot(A))
            prefs.setLastView(A, "scheduled")
        }
        withStore(prefsFile) { ds -> assertEquals(null, ds.data.first()[stringPreferencesKey("last_view")]) }
        withPrefs { prefs ->
            assertEquals("scheduled", prefs.viewBoot(A).storedView)
            assertEquals("another server does not inherit it", ViewBoot(storedView = null, hasExistingPreferences = false), prefs.viewBoot(B))
        }
        // An install of before the view record (preferences, no view): the first server boots Sessions.
        prefsFile.delete()
        withStore(prefsFile) { ds -> ds.edit { it[stringPreferencesKey(PreferenceKeys.THEME_MODE)] = "dark" } }
        withPrefs { prefs ->
            assertEquals(ViewBoot(storedView = null, hasExistingPreferences = true), prefs.viewBoot(A))
            prefs.setLastView(A, "sessions")
            assertEquals(ViewBoot(storedView = null, hasExistingPreferences = false), prefs.viewBoot(B))
        }
    }

    @Test
    fun aCorruptViewRecordReadsAsNone() = runBlocking {
        for (garbage in listOf("not json", "[\"overview\"]", "{\"$A\":3}", "{")) {
            prefsFile.delete()
            withStore(prefsFile) { ds -> ds.edit { it[stringPreferencesKey(PreferenceKeys.LAST_VIEW_BY_ORIGIN_JSON)] = garbage } }
            withPrefs { prefs ->
                assertEquals(garbage, ViewBoot(storedView = null, hasExistingPreferences = false), prefs.viewBoot(A))
                prefs.setLastView(B, "overview")
                assertEquals(garbage, "overview", prefs.viewBoot(B).storedView)
            }
        }
        // An origin key of any characters round-trips.
        val odd = "\n\t\"\\😀"
        withPrefs { prefs -> prefs.setLastView(odd, "scheduled") }
        withPrefs { prefs -> assertEquals("scheduled", prefs.viewBoot(odd).storedView) }
    }

    /**
     * ta-coik.47: the folded blocks and seen stamps are stored per server as JSON; the device-wide keys
     * of before are read until the migration moves them, and are then gone from the file.
     */
    @Test
    fun theFoldedBlocksAndSeenStampsArePerServerOnDisk() = runBlocking {
        withStore(prefsFile) { ds ->
            ds.edit {
                it[stringPreferencesKey(PreferenceKeys.COLLAPSED_WORKSPACES)] = "/srv/x\n/srv/y"
                it[stringPreferencesKey(PreferenceKeys.LAST_SEEN_SESSIONS)] = "h1\t5"
            }
        }
        withPrefs { prefs ->
            assertEquals(listOf("/srv/x", "/srv/y"), prefs.preferences.first().forServer(A).collapsedWorkspaces)
            prefs.updatePreferences { it.migrateToServer(A) }
        }
        withStore(prefsFile) { ds ->
            val stored = ds.data.first()
            assertEquals(null, stored[stringPreferencesKey(PreferenceKeys.COLLAPSED_WORKSPACES)])
            assertEquals(null, stored[stringPreferencesKey(PreferenceKeys.LAST_SEEN_SESSIONS)])
            assertTrue(stored[stringPreferencesKey(PreferenceKeys.COLLAPSED_BY_ORIGIN_JSON)].orEmpty().startsWith("{"))
            assertTrue(stored[stringPreferencesKey(PreferenceKeys.LAST_SEEN_BY_ORIGIN_JSON)].orEmpty().startsWith("{"))
        }
        withPrefs { prefs ->
            prefs.updatePreferencesFor(B) { it.copy(collapsedWorkspaces = listOf("/srv/new\nline"), lastSeenSessions = mapOf("h\t2" to 9L)) }
        }
        withPrefs { prefs ->
            val stored = prefs.preferences.first()
            assertEquals(listOf("/srv/x", "/srv/y"), stored.forServer(A).collapsedWorkspaces)
            assertEquals(mapOf("h1" to 5L), stored.forServer(A).lastSeenSessions)
            assertEquals(listOf("/srv/new\nline"), stored.forServer(B).collapsedWorkspaces)
            assertEquals(mapOf("h\t2" to 9L), stored.forServer(B).lastSeenSessions)
            // A server's view by its URL (the canonical origin).
            assertEquals(listOf("/srv/x", "/srv/y"), prefs.preferencesFor(kotlinx.coroutines.flow.flowOf("https://A.example/")).first().collapsedWorkspaces)
        }
    }
}
