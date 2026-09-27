package com.tether.app.push

import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.Flow

/**
 * The push preferences [PushController] observes. Production reads them from
 * [UiPrefs] ([fromUiPrefs]). Tests pass their own flows, because UiPrefs can
 * only be built over the app's single DataStore.
 */
interface PushPrefs {
    val pushEnabled: Flow<Boolean>
    val pushScope: Flow<PushScope>
    val attachedSessions: Flow<List<String>>
    val pinnedSessions: Flow<List<String>>

    companion object {
        fun fromUiPrefs(prefs: UiPrefs): PushPrefs = object : PushPrefs {
            override val pushEnabled = prefs.pushEnabled
            override val pushScope = prefs.pushScope
            override val attachedSessions = prefs.attachedSessions
            override val pinnedSessions = prefs.pinnedSessions
        }
    }
}
