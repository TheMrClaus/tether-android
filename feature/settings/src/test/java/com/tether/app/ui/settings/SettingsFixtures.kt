package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

/** A preference store of the test's own (never the app's process-wide one), seeded with raw keys. */
class PrefsStore(private val tmp: TemporaryFolder) : ExternalResource() {
    private val job = Job()
    val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") }
    }
    val prefs: UiPrefs by lazy { UiPrefs.on(store) }

    override fun after() = runBlocking { job.cancel() }

    fun seed(vararg raw: Pair<String, Any>) = runBlocking {
        store.edit { p ->
            raw.forEach { (k, v) ->
                when (v) {
                    is Boolean -> p[booleanPreferencesKey(k)] = v
                    else -> p[stringPreferencesKey(k)] = v.toString()
                }
            }
        }
    }

    fun stored(): Map<String, Any> = runBlocking { store.data.first().asMap().mapKeys { it.key.name } }
}

/** The dialog in place, in [mode], at [layout], motion reduced so a capture is settled. */
@Composable
fun SettingsUnderTest(
    prefs: UiPrefs,
    state: SettingsDialogState,
    mode: ThemeMode = ThemeMode.Light,
    layout: TetherLayoutClass = TetherLayoutClass.Phone,
    restartRequired: Boolean = false,
    currentWorkspace: String = CURRENT,
    onClose: () -> Unit = {},
    initialPreferences: com.tether.app.ui.prefs.TetherPreferences? = null,
    claudeAccounts: ClaudeAccountsBinding = ClaudeAccountsBinding.None,
    serverSettings: ServerSettingsBinding = ServerSettingsBinding.None,
) {
    TetherTheme(mode) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            SettingsFrame(
                prefs = prefs,
                state = state,
                restartRequired = restartRequired,
                currentWorkspace = currentWorkspace,
                onClose = onClose,
                layout = layout,
                initialPreferences = initialPreferences,
                claudeAccounts = claudeAccounts,
                serverSettings = serverSettings,
            )
        }
    }
}

const val CURRENT = "/srv/work/tether"
