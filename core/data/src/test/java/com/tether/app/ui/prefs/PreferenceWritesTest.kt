package com.tether.app.ui.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.tether.app.push.PushScope
import com.tether.app.ui.theme.ThemeMode
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A preferences store whose disk refuses every write while [refuse] (the edit itself still runs). */
class RefusingPrefsStore : DataStore<Preferences> {
    @Volatile var refuse = true
    private val disk = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = disk

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(disk.value)
        if (refuse) throw IOException("No space left on device")
        disk.value = next
        return next
    }

    fun onDisk(): Preferences = disk.value
}

/**
 * ta-8yn9: a preference write the disk refuses is lost silently, like the web's localStorage
 * write in a try/catch, and never crashes the app; the change still shows (the web's
 * `memoryCache`); cancellation is never swallowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceWritesTest {

    @Test
    fun aRefusedWriteIsSwallowedAndTheScopeLivesOn() = runTest(UnconfinedTestDispatcher()) {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var ran = false
        val job = scope.launchPreferenceWrite {
            ran = true
            throw IOException("No space left on device")
        }
        job.join()
        assertTrue(ran)
        assertFalse("the write's job completed normally", job.isCancelled)
        assertTrue(scope.isActive)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test
    fun aFailingWriteNeverThrowsToItsCaller() = runBlocking {
        bestEffortPreferenceWrite { throw IOException("No space left on device") }
        bestEffortPreferenceWrite { throw IllegalStateException("corrupt") }
    }

    @Test
    fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking { bestEffortPreferenceWrite { throw CancellationException("left") } }
        }
    }

    @Test
    fun aRefusedModelWriteThrowsButTheChangeStillShows() = runBlocking {
        val store = RefusingPrefsStore()
        val prefs = UiPrefs.on(store)
        assertThrows(IOException::class.java) { runBlocking { prefs.setThemeMode(ThemeMode.Dark) } }
        assertEquals(ThemeMode.Dark, prefs.themeMode.first())
        // Kept per store: another UiPrefs on the same file sees it too (the web's module-level cache).
        assertEquals(ThemeMode.Dark, UiPrefs.on(store).preferences.first().themeMode)
        assertTrue("nothing reached the disk", store.onDisk().asMap().isEmpty())

        // The next write that lands carries the kept change with it, and the disk is current again.
        store.refuse = false
        prefs.setShowThinking(true)
        val stored = prefs.preferences.first()
        assertEquals(ThemeMode.Dark, stored.themeMode)
        assertTrue(stored.showThinking)
        assertEquals(2, store.onDisk().asMap().count { (k, _) -> k.name == PreferenceKeys.THEME_MODE || k.name == PreferenceKeys.SHOW_THINKING })
    }

    @Test
    fun aRefusedNativeFlagStillShows() = runBlocking {
        val store = RefusingPrefsStore()
        val prefs = UiPrefs.on(store)
        assertTrue(prefs.pushEnabled.first())
        bestEffortPreferenceWrite { prefs.setPushEnabled(false) }
        bestEffortPreferenceWrite { prefs.setPushScope(PushScope.entries.last()) }
        assertFalse(prefs.pushEnabled.first())
        assertEquals(PushScope.entries.last(), prefs.pushScope.first())
    }
}
