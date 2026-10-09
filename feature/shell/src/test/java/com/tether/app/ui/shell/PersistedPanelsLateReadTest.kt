package com.tether.app.ui.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.tether.app.testsupport.runPrefsWrite
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-azst: the interleaving the flaky resize test was suspected of, made deterministic. The preference read (a
 * DataStore read, off the main thread) lands AFTER the user's drag; the first thing it carries is what the
 * store held BEFORE the drag's write. The drag must survive: on the disk, on screen, and once the read is in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1680dp-h1050dp-mdpi")
class PersistedPanelsLateReadTest : PersistedPanelsBase() {
    private val release = CompletableDeferred<Unit>()
    private val readLanded = CompletableDeferred<Unit>()
    private val disk = MutableStateFlow(emptyPreferences())

    /** A store whose read is held until [release], then reports the pre-drag snapshot, then the live disk. */
    private val store = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            val before = disk.value
            release.await()
            emit(before)
            readLanded.complete(Unit)
            emitAll(disk.onEach { })
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(disk.value)
            disk.value = next
            return next
        }
    }

    @Before fun lateStore() {
        prefs = UiPrefs.on(store)
    }

    private fun onDisk(): PanelPrefs {
        val reader = UiPrefs.on(object : DataStore<Preferences> {
            override val data: Flow<Preferences> = disk
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = disk.value
        })
        return runBlocking { PanelPrefs.from(reader.preferences.first().forServer(null)) }
    }

    @Test fun aDragBeforeTheReadLandsIsKeptAndSavedAndSurvivesTheRead() {
        runPrefsWrite { prefs.updatePreferences { PanelPrefs(inspectorWidth = 300).applyTo(it) } }
        show()
        awaitWidth(ShellTags.InspectorColumn, 288f) // the theme default: the read has not landed
        assertTrue("the read is still held", !readLanded.isCompleted)
        rule.onNodeWithTag(ShellTags.InspectorHandle).performTouchInput {
            down(center)
            moveBy(Offset(-dpPx(48f), 0f))
            up()
        }
        rule.waitUntil(5_000) { onDisk() == PanelPrefs(inspectorWidth = 336) }
        awaitWidth(ShellTags.InspectorColumn, 336f)
        release.complete(Unit)
        rule.waitUntil(5_000) { readLanded.isCompleted }
        assertEquals(PanelPrefs(inspectorWidth = 336), onDisk())
        awaitWidth(ShellTags.InspectorColumn, 336f)
        recompose()
        awaitWidth(ShellTags.InspectorColumn, 336f)
    }
}
