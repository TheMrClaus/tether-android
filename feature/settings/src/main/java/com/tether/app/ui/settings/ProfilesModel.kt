package com.tether.app.ui.settings

import com.tether.app.client.LabelText
import com.tether.app.client.Profile
import com.tether.app.client.ProfileEdit
import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersPatch
import com.tether.app.client.ProvidersWrite
import com.tether.app.client.ProvidersBuild
import com.tether.app.client.ProvidersWriteStatus
import com.tether.app.client.ProvidersRefusal

/**
 * ta-q6p: where the Custom providers editor sends its writes. The app's is the client
 * ([com.tether.app.client.TetherClient.setProviders]), which sends a write only on a socket opened
 * for [origin], built from a list that came on that socket, with no other write in flight, and
 * only when it passes [ProvidersPatch.refusal] against its newest list. Null: sent.
 */
interface ProvidersWriter {
    fun setProviders(write: ProvidersWrite, origin: String): ProvidersRefusal?

    /**
     * r4: what became of the last write (the client's own in-flight guard, the one source of
     * truth: waiting, overdue, saved or not saved).
     */
    fun status(): ProvidersWriteStatus = ProvidersWriteStatus.Idle

    /** No client (previews, a signed-out frame): nothing is ever sent. */
    object None : ProvidersWriter {
        override fun setProviders(write: ProvidersWrite, origin: String) = ProvidersRefusal.NotConnected
    }
}

/** ta-q6p r2: what became of an editor action. */
sealed interface ProvidersSend {
    data object Sent : ProvidersSend
    data object NoChange : ProvidersSend
    data class Refused(val reason: ProvidersRefusal) : ProvidersSend
}

/**
 * ta-q6p: what the Custom providers editor draws and where its writes go, for ONE server
 * ([origin]). [list] is the last `providers` frame (null until the server replies). Nothing here
 * is persisted; the env values it holds live in the client's last frame, which the client drops on
 * a server switch, a sign-out and an auth-required state, and the editor draws nothing without an
 * [origin].
 *
 * Every write is built when it is sent, from [latest]: the client's NEWEST list ([fresh]), not the
 * composed one (which may be a frame behind), so an edit made while another client's broadcast
 * landed applies to that broadcast and never undoes it. r4: a write while the last one still waits
 * for its broadcast is refused by the client alone ([ProvidersWriter.status] says what became of
 * it), so there is one in-flight guard and it recovers from a refused write.
 */
data class ProvidersBinding(
    val list: ProvidersList?,
    val origin: String?,
    val writer: ProvidersWriter = ProvidersWriter.None,
    /** The client's newest list, read when a write is built. Null: [list] is the newest (tests, previews). */
    val fresh: (() -> ProvidersList?)? = null,
) {
    /** The newest list of this binding's server, or null without one. */
    fun latest(): ProvidersList? = if (origin == null) null else fresh?.invoke() ?: list

    /** A plain edit, applied to the newest list and sent to this binding's server. */
    fun send(edit: ProfileEdit): ProvidersSend {
        val newest = latest()
        return deliver(ProvidersPatch.build(newest, edit), newest)
    }

    private fun deliver(build: ProvidersBuild, newest: ProvidersList?): ProvidersSend {
        val o = origin ?: return ProvidersSend.Refused(ProvidersRefusal.NotConnected)
        val write = when (build) {
            is ProvidersBuild.Ready -> build.write
            ProvidersBuild.NoChange -> return ProvidersSend.NoChange
            is ProvidersBuild.Refused -> return ProvidersSend.Refused(build.reason)
        }
        ProvidersPatch.refusal(write, newest)?.let { return ProvidersSend.Refused(it) }
        writer.setProviders(write, o)?.let { return ProvidersSend.Refused(it) }
        return ProvidersSend.Sent
    }

    companion object {
        val None = ProvidersBinding(null, null)
    }
}

/** The Custom providers section (settings-dialog.tsx 887c222 :621-1000 `ProfilesEditor`), the web's words verbatim. */
object ProfileRows {
    const val TITLE = "Custom providers"

    // :640 the caption, with its code spans.
    const val CAPTION_1 = "Declarative profiles extending the built-in engines — different credentials, binaries, or (later) model lists and tool allowlists per profile. Every entry needs an "
    const val CAPTION_2 = " (lowercase, hyphens), an "
    const val CAPTION_3 = " engine, and a label. "
    const val CAPTION_4 = " values are stored in plaintext in "
    const val CAPTION_5 = " (0600); prefer a dedicated home dir for credential isolation where the CLI reads its own config. Changes apply to future launches."

    const val EMPTY = "No custom providers configured — sessions run on the built-in engines."
    const val ADD = "Add profile"
    const val REMOVE = "Remove"
    const val LOADING = "Loading the custom providers…"

    const val LABEL = "Label"
    const val LABEL_CAPTION = "Display name"
    const val ID = "ID"
    const val ID_CAPTION = "Stable registry key (lowercase letters, digits, hyphens)"
    const val EXTENDS = "Extends"
    const val EXTENDS_CAPTION = "Which built-in engine this profile runs on"
    const val COMMAND = "Command"
    const val COMMAND_CAPTION_1 = "Binary + arguments (space-separated), e.g. "
    const val COMMAND_EXAMPLE = "gemini --acp"
    const val COMMAND_CAPTION_2 = ". Claude uses the SDK-bundled CLI path — no command."
    const val HOME = "Home"
    const val HOME_ACP = "Required — the profile's dedicated credential home"
    const val HOME_OPTIONAL = "Optional — defaults to the engine's dedicated home"
    const val HOME_PLACEHOLDER = "/path/to/home"
    const val ENV = "Env"
    const val ENV_CAPTION = "Key/value overrides applied to the child environment (stored in plaintext in providers.json; prefer a dedicated home for credentials)"
    const val ENV_NAME_PLACEHOLDER = "NAME"
    const val ENV_VALUE_PLACEHOLDER = "value"
    const val ENV_NAME = "Environment variable name"
    const val ENV_NEW_NAME = "New environment variable name"
    const val ENV_NEW_VALUE = "New environment variable value"
    const val ADD_ENV = "Add"
    const val DROP_ENV = "Drop env prefixes"
    const val DROP_ENV_CAPTION_1 = "Comma-separated variable-name prefixes removed from the inherited environment (e.g. "
    const val DROP_ENV_EXAMPLE = "ANTHROPIC"
    const val DROP_ENV_CAPTION_2 = " strips your shell's ANTHROPIC_API_KEY)"
    const val TOOLS = "Disallowed tools"
    const val TOOLS_PLACEHOLDER = "WebSearch, Task"
    const val ORDER = "Order"
    const val ORDER_CAPTION = "New-session picker sorts ascending; empty sorts last"
    const val VERIFIED = "Verified through"
    const val VERIFIED_CAPTION_1 = "Approval-gate watermark — the build "
    const val VERIFIED_CAPTION_CODE = "npm run gate:profile-approval"
    const val VERIFIED_CAPTION_2 = " last passed at"
    const val VERIFIED_PLACEHOLDER = "0.43.0"
    const val MODELS = "Models"
    const val MODELS_CAPTION = "Replaces the engine's live-discovered list entirely for this profile. Empty = inherit live discovery + legacy catalog."
    const val ADDITIONAL = "Additional models"
    const val ADDITIONAL_CAPTION = "Merges with the live list by id — a matching id relabels the discovered model, a new id appends. Empty = no merge."
    const val MODEL_ID = "Model id"
    const val MODEL_LABEL_PLACEHOLDER = "Display name (defaults to the id)"
    const val MODEL_NEW_LABEL = "Display name for the new model"
    const val MODEL_DISCARD = "Discard the new model row"
    const val MODEL_NEW_DEFAULT = "New model is default (name the row first)"
    const val DEFAULT = "Default"
    const val ADD_MODEL = "Add model"

    /** :794 the disallowed tools' caption, by engine. */
    fun toolsCaption(extends: String) = "Comma-separated tool names denied before you are asked. " +
        if (extends == "claude") "UX policy, not a sandbox — the model could still act via another tool or path." else "Not yet supported on this engine — the field is disabled for $extends profiles."

    /** The card's name (:651 `profile.label || profile.id`), by the label rule. */
    fun name(p: Profile): String = LabelText.label(p.label.ifEmpty { p.id }).ifEmpty { LabelText.visibleValue(p.id) }

    /** A field's accessible name (the web's `aria-label`, `${profile.id} label`). */
    fun field(p: Profile, what: String) = "${LabelText.visibleValue(p.id)} $what"

    fun switchLabel(p: Profile) = "Enable ${name(p)}"
    fun valueLabel(key: String) = "Value for ${LabelText.visibleValue(key)}"
    fun removeLabel(what: String) = "Remove ${LabelText.visibleValue(what)}"
    fun defaultLabel(rowId: String) = "${LabelText.visibleValue(rowId)} is default"
    fun labelFor(rowId: String) = "Label for ${LabelText.visibleValue(rowId)}"

    /** `.provider-glyph`'s letter (:645). */
    fun glyph(extends: String) = extends.take(1).uppercase()

    /** A command row's field text (:708): the parts joined by a space. */
    fun commandText(p: Profile) = (p.command ?: emptyList()).joinToString(" ")

    // ---- r2 (security F4): refused writes are never silent --------------------------------------

    const val NOT_SAVED_CHANGED = "Not saved: the list changed. Try again."
    const val NOT_SAVED_IN_FLIGHT = "Not saved: the last change is still being saved. Try again in a moment."
    const val NOT_SAVED_OFFLINE = "Not saved: not connected to the server. Try again."
    const val UNCONFIRMED = "The server hasn't confirmed the last change. Check the list before you edit again."
    const val LAST_NOT_SAVED = "The last change wasn't saved: the server didn't take it. Check the list and try again."

    fun notSaved(reason: ProvidersRefusal): String = when (reason) {
        ProvidersRefusal.InFlight -> NOT_SAVED_IN_FLIGHT
        ProvidersRefusal.NotConnected -> NOT_SAVED_OFFLINE
        ProvidersRefusal.NoList, ProvidersRefusal.Gone, ProvidersRefusal.Stale -> NOT_SAVED_CHANGED
    }

    /** A field's outcome for an editor action. */
    fun outcome(send: ProvidersSend): CommitOutcome = when (send) {
        ProvidersSend.Sent -> CommitOutcome.Sent
        ProvidersSend.NoChange -> CommitOutcome.Nothing
        is ProvidersSend.Refused -> CommitOutcome.Refused(notSaved(send.reason))
    }
}
