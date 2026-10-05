package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.PreferenceKeys
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

    /** ta-coik.52: the record of the server at [origin] (null: no server configured), by the fields' key names. */
    fun storedFor(origin: String? = null): Map<String, Any?> = storedRecord(store, origin)
}

/**
 * ta-coik.52: [store]'s preference record for the server at [origin] (null: no server configured; the
 * web's preferences are per origin), by the fields' key names; empty while that server has none.
 */
fun storedRecord(store: DataStore<Preferences>, origin: String? = null): Map<String, Any?> = runBlocking {
    val r = UiPrefs.on(store).preferences.first().preferencesByOrigin[origin.orEmpty()] ?: return@runBlocking emptyMap()
    mapOf(
        PreferenceKeys.THEME_MODE to r.themeMode.id,
        PreferenceKeys.LOGIN_VARIANT to r.loginVariant.id,
        PreferenceKeys.DEFAULT_WORKSPACE to r.defaultWorkspace,
        PreferenceKeys.SHOW_ENDED_SESSIONS to r.showEndedSessions,
        PreferenceKeys.CONFIRM_BEFORE_END to r.confirmBeforeEnd,
        PreferenceKeys.SHOW_THINKING to r.showThinking,
    )
}

/** ta-coik.52: no server configured (the "" record), as the frame's own default. */
private val NoServerUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

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
    providers: ProvidersBinding = ProvidersBinding.None,
    nodes: NodesBinding = NodesBinding.None,
    devices: DevicesBinding = DevicesBinding.None,
    github: GitHubBinding = GitHubBinding.None,
    /** ta-coik.52: the server the preferences are kept for (null: the frame's default, no server). */
    serverUrl: kotlinx.coroutines.flow.StateFlow<String?>? = null,
) {
    TetherTheme(mode) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            SettingsFrame(
                prefs = prefs,
                serverUrl = serverUrl ?: NoServerUrl,
                state = state,
                restartRequired = restartRequired,
                currentWorkspace = currentWorkspace,
                onClose = onClose,
                layout = layout,
                initialPreferences = initialPreferences,
                claudeAccounts = claudeAccounts,
                serverSettings = serverSettings,
                providers = providers,
                nodes = nodes,
                devices = devices,
                github = github,
            )
        }
    }
}

const val CURRENT = "/srv/work/tether"

/** ta-b72: the one node tagged [tag] is drawn and enabled now (for a waitUntil, never a single read). */
fun SemanticsNodeInteractionsProvider.isDrawnEnabled(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
