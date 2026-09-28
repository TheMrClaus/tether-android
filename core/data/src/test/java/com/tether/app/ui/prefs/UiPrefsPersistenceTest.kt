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
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeFamily
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
            theme = ThemeChoice(ThemeFamily.Studio, ThemeMode.Dark),
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
            assertEquals(edited.theme, prefs.themeChoice.first())
            assertEquals(LoginVariant.Retro, prefs.loginVariant.first())
            assertTrue(prefs.showThinking.first())
            assertFalse(prefs.showEnded.first())
            assertEquals(listOf("/srv/b", "/srv/a"), prefs.pinnedProjects.first())
            // Clearing optional fields removes them.
            prefs.updatePreferences { it.copy(sidebarWidth = null, lastOpenedSession = null) }
        }
        withPrefs { prefs ->
            val back = prefs.preferences.first()
            assertEquals(null, back.sidebarWidth)
            assertEquals(null, back.lastOpenedSession)
            assertEquals(260, back.inspectorWidth)
        }
    }

    /** The keys shipped before T2.3 keep their stored values, and a legacy flat theme migrates. */
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
            assertEquals(ThemeChoice(ThemeFamily.Tactile, ThemeMode.Dark), p.theme)
            assertTrue(p.showThinking)
            assertEquals(listOf("/srv/x"), p.pinnedProjects)
            prefs.setShowEnded(false)
            assertEquals(com.tether.app.push.PushScope.Pinned, prefs.pushScope.first())
        }
        withStore(prefsFile) { ds ->
            val raw = ds.data.first().asMap().mapKeys { it.key.name }
            assertFalse("legacy theme id dropped once the pair is stored", "theme_choice" in raw)
            assertEquals("tactile", raw["theme_family"])
            assertEquals("dark", raw["theme_mode"])
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

    /** lib/draft-preferences.mjs: `tether:draftPreferences.v1`, junk or non-object reads as {}. */
    @Test
    fun draftPreferencesRoundTripAndFailSoft() = runBlocking {
        val prefs = JsObj.of("claude" to JsObj.of("model" to JsStr("opus")))
        withDrafts { drafts ->
            assertEquals(JsObj.EMPTY, drafts.readDraftPreferences())
            drafts.writeDraftPreferences(prefs)
        }
        withDrafts { drafts -> assertEquals(prefs, drafts.readDraftPreferences()) }
        for (junk in listOf("{not json", "[1,2]", "\"str\"")) {
            withStore(draftsFile) { ds -> ds.edit { it[stringPreferencesKey("tether:draftPreferences.v1")] = junk } }
            withDrafts { drafts -> assertEquals(junk, JsObj.EMPTY, drafts.readDraftPreferences()) }
        }
    }
}
