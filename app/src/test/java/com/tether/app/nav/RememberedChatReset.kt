package com.tether.app.nav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.runBlocking

/**
 * ta-coik.41: the app's preferences DataStore is a process singleton, so the chat one test had on
 * screen (`lastOpenedSession`, dashboard.tsx 90fbb9f :829-835) would be restored at the next test's
 * cold start. A test that boots the real root starts from no remembered chat.
 */
internal fun forgetRememberedChat() = runBlocking {
    UiPrefs(ApplicationProvider.getApplicationContext<Context>()).updatePreferences { it.copy(lastOpenedSession = null, lastOpenedByOrigin = emptyMap()) }
}
