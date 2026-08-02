package com.tether.app.ui.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tether.app.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tetherUiDataStore: DataStore<Preferences> by preferencesDataStore(name = "tether_ui_prefs")

/** DataStore-backed UI preferences (theme family, transcript options). */
class UiPrefs(context: Context) {
    private val store = context.applicationContext.tetherUiDataStore

    private object Keys {
        val theme = stringPreferencesKey("theme_choice")
        val showThinking = booleanPreferencesKey("show_thinking")
        val showEnded = booleanPreferencesKey("show_ended_sessions")
    }

    val themeChoice: Flow<ThemeChoice> = store.data.map { ThemeChoice.fromId(it[Keys.theme]) }

    suspend fun setThemeChoice(choice: ThemeChoice) {
        store.edit { it[Keys.theme] = choice.id }
    }

    val showThinking: Flow<Boolean> = store.data.map { it[Keys.showThinking] ?: true }

    suspend fun setShowThinking(value: Boolean) {
        store.edit { it[Keys.showThinking] = value }
    }

    val showEnded: Flow<Boolean> = store.data.map { it[Keys.showEnded] ?: false }

    suspend fun setShowEnded(value: Boolean) {
        store.edit { it[Keys.showEnded] = value }
    }
}
