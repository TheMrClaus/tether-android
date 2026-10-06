package com.tether.app.ui.prefs

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tether.app.ui.theme.ThemeMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ta-341b: a preferences read that fails falls back to the defaults, as the web's `readStored`
 * (hooks/use-preferences.ts) returns null when its storage throws and the snapshot falls to the defaults.
 */
class PreferenceReadFailureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val a = "https://a.example:443"

    /** A store whose disk read fails with [failure] (the write is never reached). */
    private class UnreadableStore(private val failure: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
    }

    @Test
    fun anIoFailureOnReadReadsAsTheDefaults() = runBlocking {
        val prefs = UiPrefs.on(UnreadableStore(IOException("disk read failed")))
        val read = prefs.preferences.first()
        assertEquals(TetherPreferences.Default, read)
        assertEquals(ThemeMode.System, prefs.themeMode(flowOf(a)).first())
        assertTrue(prefs.pushEnabled.first())
    }

    @Test
    fun aFailureThatIsNotTheDisksIsNotHidden() {
        val prefs = UiPrefs.on(UnreadableStore(IllegalStateException("a bug")))
        assertThrows(IllegalStateException::class.java) { runBlocking { prefs.preferences.first() } }
    }

    @Test
    fun aCorruptFileIsReplacedByTheDefaultsAndWritesWorkAgain() = runBlocking {
        val file = File(tmp.root, "tether_ui_prefs.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55, 0x66, 0x77))
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = uiPrefsCorruptionHandler,
            scope = CoroutineScope(Dispatchers.IO + job),
        ) { file }
        try {
            val prefs = UiPrefs.on(store)
            assertEquals(TetherPreferences.Default, prefs.preferences.first())
            prefs.setThemeMode(a, ThemeMode.Dark)
            assertEquals(ThemeMode.Dark, prefs.themeMode(flowOf(a)).first())
            store.edit { it[stringPreferencesKey("x")] = "y" }
            Unit
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun aCorruptionReadWithoutTheHandlerIsStillTheDefaultsToTheReader() = runBlocking {
        val prefs = UiPrefs.on(UnreadableStore(CorruptionException("unreadable")))
        assertEquals(TetherPreferences.Default, prefs.preferences.first())
    }
}
