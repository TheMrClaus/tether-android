package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.ServerSecretOutcome
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsPatch
import com.tether.app.client.ServerSettingsView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * v144 (components/server-secret-row.tsx 7cc0f38c): the two console credentials as WRITE-ONLY rows. The words are the
 * web's, verbatim.
 */
object ServerSecretCopy {
    const val SECTION_CAPTION = "Credentials are write-only: Tether shows whether one is set, never its value."
    const val PASSWORD_TIP = "Login password for the Tether web console. Applies immediately; every other signed-in session is signed out."
    const val PROXY_TOKEN_TIP = "A pre-shared token from a reverse proxy such as Authelia. When the proxy sends this token in its auth header, the password login page is bypassed. Requires restart."

    const val TAG_ENV = "Set by environment"
    const val TAG_CONFIGURED = "Configured"
    const val TAG_NOT_SET = "Not set"

    const val OPEN_PASSWORD = "Set new password…"
    const val OPEN_TOKEN = "Set new token…"
    const val CLEAR = "Clear"

    fun label(s: ServerSetting) = if (s == ServerSetting.Password) "Password" else "Proxy token"
    fun envVar(s: ServerSetting) = if (s == ServerSetting.Password) "TETHER_PASSWORD" else "TETHER_PROXY_TOKEN"

    /** The status caption, by precedence forced > (a token saved, not cleared) > configured > not set. */
    fun caption(s: ServerSetting, forced: Boolean, configured: Boolean, saved: SecretSaved?): String = when {
        forced -> "Set by ${envVar(s)} in the environment — change it there and restart."
        s == ServerSetting.ProxyToken && saved == SecretSaved.TokenSaved -> "Saved — takes effect after a restart."
        s == ServerSetting.Password && configured -> "Login password for the web console. Changing it signs out every other session."
        s == ServerSetting.Password -> "No login password is set. Set TETHER_PASSWORD and restart to manage it here."
        configured -> "SSO-header sign-in is on. Changes apply after a restart."
        else -> "SSO-header sign-in is off."
    }

    fun tag(forced: Boolean, configured: Boolean) = if (forced) TAG_ENV else if (configured) TAG_CONFIGURED else TAG_NOT_SET

    fun savedLine(saved: SecretSaved) = when (saved) {
        SecretSaved.PasswordChanged -> "Password changed. Every other session was signed out."
        SecretSaved.TokenSaved -> "Proxy token saved. It takes effect after a restart."
        SecretSaved.TokenCleared -> "Proxy token cleared. SSO-header sign-in turns off after a restart."
    }

    const val CURRENT = "Current password"
    const val NEW_PASSWORD = "New password"
    const val NEW_PASSWORD_NOTE = "At least 12 characters."
    const val CONFIRM = "Confirm new password"
    const val MISMATCH = "Passwords do not match yet."
    const val NEW_TOKEN = "New token"
    const val NEW_TOKEN_NOTE = "At least 32 characters."
    const val SAVING = "Saving…"
}

/** The three outcomes a write leaves under its row (server-secret-row.tsx:108-114). */
enum class SecretSaved { PasswordChanged, TokenSaved, TokenCleared }

/** What a credential dialog is doing (the web's `kind` + `mode`). */
enum class SecretMode(val setting: ServerSetting, val clear: Boolean) {
    PasswordSet(ServerSetting.Password, false),
    TokenSet(ServerSetting.ProxyToken, false),
    TokenClear(ServerSetting.ProxyToken, true),
    ;

    val title: String get() = when (this) {
        PasswordSet -> "Set a new password"
        TokenSet -> "Set a new proxy token"
        TokenClear -> "Clear the proxy token?"
    }
    val body: String get() = when (this) {
        PasswordSet -> "Every other signed-in browser and device is signed out when you save. This one stays signed in."
        TokenSet -> "Your reverse proxy must send this exact value in its auth header. Takes effect after a restart."
        TokenClear -> "SSO-header sign-in turns off after the next restart. Password and passkey sign-in are unaffected."
    }
    val submitLabel: String get() = when (this) {
        PasswordSet -> "Save password"
        TokenSet -> "Save token"
        TokenClear -> "Clear token"
    }
    val newLabel: String? get() = when (this) {
        PasswordSet -> ServerSecretCopy.NEW_PASSWORD
        TokenSet -> ServerSecretCopy.NEW_TOKEN
        TokenClear -> null
    }
    val newNote: String? get() = when (this) {
        PasswordSet -> ServerSecretCopy.NEW_PASSWORD_NOTE
        TokenSet -> ServerSecretCopy.NEW_TOKEN_NOTE
        TokenClear -> null
    }
    val confirms: Boolean get() = this == PasswordSet
    val savedKind: SecretSaved get() = when (this) {
        PasswordSet -> SecretSaved.PasswordChanged
        TokenSet -> SecretSaved.TokenSaved
        TokenClear -> SecretSaved.TokenCleared
    }
}

enum class SecretField { Current, New, Confirm }

/** A request to focus [field]; [nonce] makes two in a row distinct. */
data class SecretFocus(val field: SecretField, val nonce: Int)

/**
 * One open credential dialog (the web's per-row state: current / next / confirm / saving / error). Memory only: it rides
 * the retained store, never the saved-instance Bundle. Closing drops the object, so what was typed is gone.
 */
class SecretDialogState(val mode: SecretMode) {
    var current by mutableStateOf("")
    var next by mutableStateOf("")
    var confirm by mutableStateOf("")
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var focus by mutableStateOf<SecretFocus?>(null)

    /** `passwordMismatch` (server-secret-row.tsx:82): shown while a confirmation is typed that differs. */
    val mismatch: Boolean get() = mode.confirms && confirm.isNotEmpty() && confirm != next

    /** `canSubmit` (:83-86), exactly: no client-side length, whitespace or strength check (the server judges). */
    val canSubmit: Boolean
        get() = !sending && current.isNotEmpty() && when (mode) {
            SecretMode.TokenClear -> true
            SecretMode.PasswordSet -> next.isNotEmpty() && confirm.isNotEmpty() && next == confirm
            SecretMode.TokenSet -> next.isNotEmpty()
        }

    internal fun requestFocus(field: SecretField) {
        focus = SecretFocus(field, (focus?.nonce ?: 0) + 1)
    }

    internal fun wipe() {
        current = ""
        next = ""
        confirm = ""
        error = null
        sending = false
    }
}

/**
 * v144: what the Authentication rows keep above the tab level (the web keeps them mounted): the open dialog and the
 * "saved" lines. Wiped when Settings closes or the server changes (the retained store releases it then). A write
 * runs on the Settings-level scope, not the dialog's, so a result that lands after Back closed the dialog still
 * applies its success (the saved line, the sessions refresh) and drops a refusal, as the web does.
 */
class ServerSecretsState {
    val saved = mutableStateMapOf<ServerSetting, SecretSaved>()
    var dialog by mutableStateOf<SecretDialogState?>(null)
        private set

    fun open(mode: SecretMode) {
        dialog?.wipe()
        dialog = SecretDialogState(mode)
        dialog?.requestFocus(SecretField.Current)
    }

    fun close() {
        dialog?.wipe()
        dialog = null
    }

    /**
     * Send the open dialog's write ([change]: the client's `changeServerSecret`, bound to the server) when
     * [SecretDialogState.canSubmit]. A new submit clears the previous error first (the web's `setError(null)`).
     */
    fun submit(
        scope: CoroutineScope,
        view: ServerSettingsView,
        change: suspend (JsonObject, String) -> ServerSecretOutcome,
        onPasswordChanged: () -> Unit = {},
    ) {
        val d = dialog ?: return
        if (!d.canSubmit) return
        val setting = d.mode.setting
        val patch = ServerSettingsPatch.secret(view, setting, if (d.mode.clear) "" else d.next) ?: return
        val currentPassword = d.current
        d.error = null
        d.sending = true
        scope.launch {
            val outcome = try {
                change(patch, currentPassword)
            } catch (e: CancellationException) {
                // The scope went away mid-send (a rotation): the write may or may not have landed; say so, honestly.
                if (d.sending) {
                    d.sending = false
                    if (dialog === d) d.error = ServerSecretOutcome.NO_REPLY_MESSAGE
                }
                throw e
            }
            d.sending = false
            when (outcome) {
                ServerSecretOutcome.Saved -> {
                    saved[setting] = d.mode.savedKind
                    if (dialog === d) close()
                    if (d.mode == SecretMode.PasswordSet) onPasswordChanged()
                }
                is ServerSecretOutcome.Refused -> if (dialog === d) {
                    d.error = outcome.message
                    when (outcome.reason) {
                        "bad_current_password" -> {
                            d.current = ""
                            d.requestFocus(SecretField.Current)
                        }
                        "weak" -> d.requestFocus(SecretField.New)
                    }
                }
                ServerSecretOutcome.NoReply -> if (dialog === d) d.error = ServerSecretOutcome.NO_REPLY_MESSAGE
            }
        }
    }
}

/** The status row's facts (server-secret-row.tsx:143-145), from the last `server-settings` frame. */
data class SecretRowFacts(
    val setting: ServerSetting,
    val forced: Boolean,
    val configured: Boolean,
    val passwordConfigured: Boolean,
) {
    /** `canAct`: not forced, and the password configured (for both rows: the write is confirmed against it). */
    val canAct: Boolean get() = !forced && passwordConfigured

    companion object {
        fun of(view: ServerSettingsView, setting: ServerSetting) =
            SecretRowFacts(setting, view.forced(setting), view.secretSet(setting), view.secretSet(ServerSetting.Password))
    }
}
